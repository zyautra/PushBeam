package com.zyautra.pushbeam.server.delivery

import com.zyautra.pushbeam.server.db.Database
import com.zyautra.pushbeam.server.db.bool
import com.zyautra.pushbeam.server.db.instant
import com.zyautra.pushbeam.server.db.query
import com.zyautra.pushbeam.server.db.str
import com.zyautra.pushbeam.server.db.strOrNull
import com.zyautra.pushbeam.server.db.update
import com.zyautra.pushbeam.server.gateway.FcmGateway
import com.zyautra.pushbeam.server.gateway.FcmResult
import com.zyautra.pushbeam.shared.Display
import com.zyautra.pushbeam.shared.PushPayload
import com.zyautra.pushbeam.shared.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** PENDING 전송을 FCM으로 보내고 결과를 기록한다 (docs/01 5.2~5.5). 서버에 하나만 실행한다. */
class Dispatcher(
    private val db: Database,
    private val fcm: FcmGateway,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(Dispatcher::class.java)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun wakeUp() {
        wake.trySend(Unit)
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            try {
                while (runOnce() == BATCH_SIZE) { /* 남은 것이 있으면 바로 이어서 */ }
            } catch (e: Exception) {
                log.error("dispatcher_error", e)
            }
            withTimeoutOrNull(IDLE_WAIT.toMillis()) { wake.receive() }
        }
    }

    /** 시도할 때가 된 전송을 최대 BATCH_SIZE개 처리하고 처리한 수를 돌려준다. */
    suspend fun runOnce(): Int {
        val now = clock.instant()
        val due = db.read { c ->
            c.query(
                """SELECT d.id, d.display, d.reason, d.attempts, v.fcm_token, v.active AS device_active, m.status AS member_status,
                          g.id AS message_id, g.title, g.body, g.severity, g.target_channel, g.data, g.created_at
                   FROM deliveries d
                   JOIN devices v ON v.id = d.device_id
                   JOIN members m ON m.id = v.member_id
                   JOIN messages g ON g.id = d.message_id
                   WHERE d.status = 'PENDING' AND d.next_attempt_at <= ?
                   ORDER BY d.next_attempt_at LIMIT ?""",
                now, BATCH_SIZE,
            ) { rs ->
                Due(
                    id = rs.str("id"), attempts = rs.getInt("attempts"), token = rs.str("fcm_token"),
                    sendable = rs.bool("device_active") && rs.str("member_status") == "ACTIVE",
                    createdAt = rs.instant("created_at"),
                    payload = PushPayload(
                        id = rs.str("message_id"), title = rs.str("title"), body = rs.str("body"),
                        severity = Severity.valueOf(rs.str("severity")), channel = rs.strOrNull("target_channel"),
                        display = Display.valueOf(rs.str("display")), reason = rs.strOrNull("reason"), sentAt = rs.instant("created_at").toString(),
                        data = rs.strOrNull("data")?.let { Json.decodeFromString<Map<String, String>>(it) } ?: emptyMap(),
                    ),
                )
            }
        }
        due.chunked(PARALLELISM).forEach { chunk ->
            coroutineScope { chunk.map { async { process(it, now) } }.awaitAll() }
        }
        return due.size
    }

    private suspend fun process(d: Due, now: Instant) {
        when {
            !d.sendable -> finish(d.id, "CANCELLED", "member or device inactive")
            Duration.between(d.createdAt, now) > MAX_AGE -> finish(d.id, "FAILED", "expired after 24h")
            else -> {
                // 목록에만 남길 알림은 기기를 급하게 깨울 필요가 없다.
                val high = d.payload.severity >= Severity.HIGH && d.payload.display != Display.INBOX
                val result = try {
                    fcm.send(d.token, d.payload, high)
                } catch (e: Exception) {
                    FcmResult.Retryable(e.message ?: e::class.simpleName ?: "error")
                }
                record(d, result)
            }
        }
    }

    private suspend fun record(d: Due, result: FcmResult) {
        val now = clock.instant()
        val attempts = d.attempts + 1
        when (result) {
            is FcmResult.Sent -> db.write { c ->
                c.update(
                    "UPDATE deliveries SET status = 'SENT', attempts = ?, last_error = NULL, updated_at = ? WHERE id = ? AND status = 'PENDING'",
                    attempts, now, d.id,
                )
            }
            is FcmResult.InvalidToken -> db.write { c ->
                c.update(
                    "UPDATE deliveries SET status = 'INVALID_TOKEN', attempts = ?, last_error = ?, updated_at = ? WHERE id = ? AND status = 'PENDING'",
                    attempts, result.reason, now, d.id,
                )
                val deviceId = c.query("SELECT device_id FROM deliveries WHERE id = ?", d.id) { it.str("device_id") }.first()
                c.update("UPDATE devices SET active = 0 WHERE id = ?", deviceId)
                c.update(
                    "UPDATE deliveries SET status = 'CANCELLED', last_error = 'device token invalid', updated_at = ? WHERE device_id = ? AND status = 'PENDING'",
                    now, deviceId,
                )
                log.info("device_invalidated deviceId={} reason={}", deviceId, result.reason)
            }
            is FcmResult.Retryable -> {
                val delay = RETRY_DELAYS.getOrNull(attempts - 1)
                if (delay == null) {
                    finish(d.id, "FAILED", result.reason, attempts)
                } else {
                    val wait = maxOf(delay, result.retryAfter ?: Duration.ZERO)
                    db.write { c ->
                        c.update(
                            "UPDATE deliveries SET attempts = ?, next_attempt_at = ?, last_error = ?, updated_at = ? WHERE id = ? AND status = 'PENDING'",
                            attempts, now.plus(wait), result.reason, now, d.id,
                        )
                    }
                }
            }
            is FcmResult.Failed -> finish(d.id, "FAILED", result.reason, attempts)
        }
    }

    private suspend fun finish(id: String, status: String, error: String, attempts: Int? = null) {
        val now = clock.instant()
        db.write { c ->
            c.update(
                "UPDATE deliveries SET status = ?, attempts = COALESCE(?, attempts), last_error = ?, updated_at = ? WHERE id = ? AND status = 'PENDING'",
                status, attempts, error, now, id,
            )
        }
    }

    private data class Due(
        val id: String,
        val attempts: Int,
        val token: String,
        val sendable: Boolean,
        val createdAt: Instant,
        val payload: PushPayload,
    )

    companion object {
        const val BATCH_SIZE = 100
        private const val PARALLELISM = 10
        private val IDLE_WAIT: Duration = Duration.ofSeconds(5)
        val MAX_AGE: Duration = Duration.ofHours(24)
        /** n번째 실패 후 기다리는 시간. 5번째 실패면 FAILED (docs/01 5.3). */
        val RETRY_DELAYS: List<Duration> = listOf(
            Duration.ofSeconds(15), Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
        )
    }
}
