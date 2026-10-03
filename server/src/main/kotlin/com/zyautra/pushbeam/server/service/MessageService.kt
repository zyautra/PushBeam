package com.zyautra.pushbeam.server.service

import com.zyautra.pushbeam.server.Ids
import com.zyautra.pushbeam.server.api.ApiException
import com.zyautra.pushbeam.server.auth.Principal
import com.zyautra.pushbeam.server.auth.forbidden
import com.zyautra.pushbeam.server.db.Database
import com.zyautra.pushbeam.server.db.bool
import com.zyautra.pushbeam.server.db.count
import com.zyautra.pushbeam.server.db.instant
import com.zyautra.pushbeam.server.db.instantOrNull
import com.zyautra.pushbeam.server.db.query
import com.zyautra.pushbeam.server.db.queryOne
import com.zyautra.pushbeam.server.db.str
import com.zyautra.pushbeam.server.db.strOrNull
import com.zyautra.pushbeam.server.db.update
import com.zyautra.pushbeam.server.delivery.Candidate
import com.zyautra.pushbeam.server.delivery.DeliveryPlanner
import com.zyautra.pushbeam.server.delivery.QuietHours
import com.zyautra.pushbeam.server.delivery.RecipientResult
import com.zyautra.pushbeam.server.delivery.Subscription
import com.zyautra.pushbeam.server.delivery.TargetType
import com.zyautra.pushbeam.shared.Display
import com.zyautra.pushbeam.shared.ErrorCodes
import com.zyautra.pushbeam.shared.PushPayload
import com.zyautra.pushbeam.shared.Severity
import com.zyautra.pushbeam.shared.Validation
import com.zyautra.pushbeam.shared.api.DeliveryCounts
import com.zyautra.pushbeam.shared.api.DeliveryDetail
import com.zyautra.pushbeam.shared.api.MessageDetail
import com.zyautra.pushbeam.shared.api.MessageStatusResponse
import com.zyautra.pushbeam.shared.api.MessageSummary
import com.zyautra.pushbeam.shared.api.MessageTarget
import com.zyautra.pushbeam.shared.api.Page
import com.zyautra.pushbeam.shared.api.RecipientCounts
import com.zyautra.pushbeam.shared.api.RecipientDetail
import com.zyautra.pushbeam.shared.api.SendMessageRequest
import com.zyautra.pushbeam.shared.api.SendMessageResponse
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class MessageService(
    private val db: Database,
    private val clock: Clock,
    private val defaultZone: ZoneId,
    private val onAccepted: () -> Unit = {},
) {
    /** docs/01 5.2: 검증 → 받는 사람 결정 → 하나의 transaction으로 기록. */
    suspend fun send(sender: Principal, req: SendMessageRequest): SendMessageResponse {
        val target = validateTarget(sender, req.target)
        validateContent(req)
        val now = clock.instant()
        val messageId = Ids.message()

        val response = db.write { c ->
            val (required, candidates) = when (target) {
                is Target.Channel -> {
                    val required = c.queryOne(
                        "SELECT required FROM channels WHERE slug = ? AND archived_at IS NULL", target.slug,
                    ) { it.bool("required") } ?: throw ApiException.notFound("Channel not found: ${target.slug}")
                    required to c.channelCandidates(target.slug)
                }
                is Target.Users -> false to c.userCandidates(target.emails)
                Target.All -> false to c.allCandidates()
            }
            val plan = DeliveryPlanner.plan(target.type, required, req.severity, now, candidates)

            c.update(
                """INSERT INTO messages (id, sender_id, target_type, target_channel, target_emails, title, body, severity, data, created_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                messageId, (sender as? Principal.Sender)?.id, target.type, (target as? Target.Channel)?.slug,
                (target as? Target.Users)?.let { Json.encodeToString(it.emails) },
                req.title, req.body, req.severity, req.data?.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it.toSortedMap() as Map<String, String>) },
                now,
            )
            plan.results.forEach { (memberId, result) ->
                c.update("INSERT INTO message_recipients (message_id, member_id, result) VALUES (?, ?, ?)", messageId, memberId, result)
            }
            plan.deliveries.forEach { d ->
                c.update(
                    """INSERT INTO deliveries (id, message_id, device_id, quiet, display, reason, status, next_attempt_at, updated_at)
                       VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)""",
                    Ids.delivery(), messageId, d.deviceId, d.display == Display.QUIET, d.display, d.reason, now, now,
                )
            }
            val results = plan.results.values
            SendMessageResponse(
                messageId = messageId,
                recipients = RecipientCounts(
                    deliver = results.count { it == RecipientResult.DELIVER },
                    quiet = results.count { it == RecipientResult.QUIET },
                    skipped = results.count { it == RecipientResult.NOT_ACTIVE || it == RecipientResult.NO_DEVICE },
                    inboxOnly = results.count { it == RecipientResult.MUTED || it == RecipientResult.BELOW_MIN },
                ),
                deliveries = plan.deliveries.size,
            )
        }
        onAccepted()
        return response
    }

    suspend fun status(sender: Principal, messageId: String): MessageStatusResponse = db.read { c ->
        val row = c.queryOne("SELECT sender_id, created_at FROM messages WHERE id = ?", messageId) {
            it.strOrNull("sender_id") to it.instant("created_at")
        }
        if (row == null || (sender is Principal.Sender && row.first != sender.id)) throw ApiException.notFound("Message not found: $messageId")
        MessageStatusResponse(messageId, row.second.toString(), c.deliveryCounts(messageId))
    }

    suspend fun list(limit: Int, before: String?): Page<MessageSummary> = db.read { c ->
        val size = limit.coerceIn(1, 200)
        val rows = c.query(
            """SELECT m.*, s.name AS sender_name FROM messages m LEFT JOIN senders s ON s.id = m.sender_id
               WHERE (? IS NULL OR m.id < ?) ORDER BY m.id DESC LIMIT ?""",
            before, before, size + 1,
        ) { it.toMessageRow() }
        val page = rows.take(size)
        Page(
            items = page.map { m ->
                MessageSummary(m.id, m.createdAt.toString(), m.sender, m.target, m.title, m.severity, c.deliveryCounts(m.id))
            },
            nextCursor = if (rows.size > size) page.last().id else null,
        )
    }

    suspend fun detail(messageId: String): MessageDetail = db.read { c ->
        val m = c.queryOne(
            "SELECT m.*, s.name AS sender_name FROM messages m LEFT JOIN senders s ON s.id = m.sender_id WHERE m.id = ?",
            messageId,
        ) { it.toMessageRow() } ?: throw ApiException.notFound("Message not found: $messageId")
        val deliveries = c.query(
            """SELECT d.*, v.member_id, v.model FROM deliveries d JOIN devices v ON v.id = d.device_id
               WHERE d.message_id = ? ORDER BY d.id""",
            messageId,
        ) {
            it.str("member_id") to DeliveryDetail(
                deviceId = it.str("device_id"), deviceModel = it.strOrNull("model"), quiet = it.bool("quiet"),
                display = it.str("display").lowercase(),
                status = it.str("status"), attempts = it.getInt("attempts"), lastError = it.strOrNull("last_error"),
                updatedAt = it.instant("updated_at").toString(),
            )
        }.groupBy({ it.first }, { it.second })
        val recipients = c.query(
            """SELECT r.member_id, r.result, mb.email FROM message_recipients r JOIN members mb ON mb.id = r.member_id
               WHERE r.message_id = ? ORDER BY mb.email""",
            messageId,
        ) { RecipientDetail(it.str("email"), it.str("result"), deliveries[it.str("member_id")].orEmpty()) }
        MessageDetail(m.id, m.createdAt.toString(), m.sender, m.target, m.title, m.body, m.severity, m.data, recipients)
    }

    // ---- 검증 ----

    private sealed interface Target {
        val type: TargetType
        data class Channel(val slug: String) : Target { override val type = TargetType.CHANNEL }
        data class Users(val emails: List<String>) : Target { override val type = TargetType.USERS }
        data object All : Target { override val type = TargetType.ALL }
    }

    private fun validateTarget(sender: Principal, t: MessageTarget): Target {
        val given = listOfNotNull(t.channel, t.users, t.all?.takeIf { it })
        if (given.size != 1) throw ApiException.invalid("target must have exactly one of channel, users, all")
        val channel = t.channel
        val users = t.users
        val target = when {
            channel != null -> Target.Channel(channel)
            users != null -> {
                val emails = users.map(Validation::normalizeEmail).distinct()
                if (emails.isEmpty() || emails.size > 50) throw ApiException.invalid("users must have 1 to 50 emails")
                Target.Users(emails)
            }
            else -> Target.All
        }
        if (sender is Principal.Sender) {
            if (target !is Target.Channel) throw forbidden(ErrorCodes.FORBIDDEN, "Senders can only send to channels")
            if (target.slug !in sender.allowedChannels) throw forbidden(ErrorCodes.FORBIDDEN, "Sender is not allowed to send to ${target.slug}")
        }
        return target
    }

    private fun validateContent(req: SendMessageRequest) {
        if (req.title.isBlank() || req.title.length > Validation.TITLE_MAX) throw ApiException.invalid("title must be 1..${Validation.TITLE_MAX} characters")
        if (req.body.isBlank() || req.body.length > Validation.BODY_MAX) throw ApiException.invalid("body must be 1..${Validation.BODY_MAX} characters")
        val data = req.data.orEmpty()
        if (data.size > Validation.DATA_MAX_ENTRIES) throw ApiException.invalid("data must have at most ${Validation.DATA_MAX_ENTRIES} entries")
        data.forEach { (k, v) ->
            if (!Validation.isDataKey(k)) throw ApiException.invalid("Invalid data key: $k")
            if (v.length > Validation.DATA_VALUE_MAX) throw ApiException.invalid("data value too long: $k")
        }
        // 실제로 보낼 FCM data 크기로 확인한다 (FCM 제한 4KB).
        val sample = PushPayload(
            id = "msg_" + "0".repeat(26), title = req.title, body = req.body, severity = req.severity,
            channel = req.target.channel, display = Display.INBOX, sentAt = Instant.EPOCH.toString(), data = data,
            reason = "below_min", // 가장 긴 경우로 계산한다
        ).toFcmData()
        val size = sample.entries.sumOf { (k, v) -> k.toByteArray().size + v.toByteArray().size }
        if (size > MAX_FCM_DATA_BYTES) throw ApiException(HttpStatusCode.PayloadTooLarge, ErrorCodes.PAYLOAD_TOO_LARGE, "Message is too large ($size bytes)")
    }

    // ---- 후보 조회 ----

    private fun Connection.channelCandidates(slug: String): List<Candidate> = query(
        """SELECT m.id, m.status, m.time_zone, s.min_severity, s.muted, s.muted_until
           FROM subscriptions s JOIN members m ON m.id = s.member_id WHERE s.channel = ?""",
        slug,
    ) { rs -> rs.toCandidateBase(Subscription(Severity.valueOf(rs.str("min_severity")), rs.bool("muted"), rs.instantOrNull("muted_until"))) }
        .map { complete(it) }

    private fun Connection.userCandidates(emails: List<String>): List<Candidate> {
        val found = emails.associateWith { email ->
            queryOne("SELECT id, status, time_zone FROM members WHERE email = ?", email) { it.toCandidateBase(null) }
        }
        val unknown = found.filter { (_, c) -> c == null || c.status == "REVOKED" }.keys
        if (unknown.isNotEmpty()) {
            throw ApiException(HttpStatusCode.UnprocessableEntity, ErrorCodes.UNKNOWN_RECIPIENT, "Not on the allowlist: ${unknown.joinToString()}")
        }
        return found.values.map { complete(it!!) }
    }

    private fun Connection.allCandidates(): List<Candidate> =
        query("SELECT id, status, time_zone FROM members WHERE status = 'ACTIVE'") { it.toCandidateBase(null) }.map { complete(it) }

    private data class CandidateBase(val id: String, val status: String, val zone: ZoneId, val subscription: Subscription?)

    private fun ResultSet.toCandidateBase(sub: Subscription?) = CandidateBase(
        id = str("id"), status = str("status"),
        zone = strOrNull("time_zone")?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: defaultZone,
        subscription = sub,
    )

    private fun Connection.complete(b: CandidateBase): Candidate {
        val quiet = queryOne("SELECT * FROM quiet_hours WHERE member_id = ?", b.id) {
            QuietHours(it.bool("enabled"), it.getInt("start_min"), it.getInt("end_min"))
        }
        val devices = query("SELECT id FROM devices WHERE member_id = ? AND active = 1 ORDER BY id", b.id) { it.str("id") }
        return Candidate(b.id, b.status == "ACTIVE", b.zone, quiet, b.subscription, devices)
    }

    // ---- 기록 조회 ----

    private data class MessageRow(
        val id: String, val sender: String, val target: MessageTarget, val title: String, val body: String,
        val severity: Severity, val data: Map<String, String>?, val createdAt: Instant,
    )

    private fun ResultSet.toMessageRow(): MessageRow {
        val target = when (str("target_type")) {
            "CHANNEL" -> MessageTarget(channel = str("target_channel"))
            "USERS" -> MessageTarget(users = strOrNull("target_emails")?.let { Json.decodeFromString<List<String>>(it) } ?: emptyList())
            else -> MessageTarget(all = true)
        }
        return MessageRow(
            id = str("id"), sender = strOrNull("sender_name") ?: "operator", target = target,
            title = str("title"), body = str("body"), severity = Severity.valueOf(str("severity")),
            data = strOrNull("data")?.let { Json.decodeFromString<Map<String, String>>(it) },
            createdAt = instant("created_at"),
        )
    }

    private fun Connection.deliveryCounts(messageId: String): DeliveryCounts {
        fun n(status: String) = count("SELECT COUNT(*) FROM deliveries WHERE message_id = ? AND status = ?", messageId, status)
        return DeliveryCounts(n("PENDING"), n("SENT"), n("FAILED"), n("INVALID_TOKEN"), n("CANCELLED"))
    }

    private companion object {
        const val MAX_FCM_DATA_BYTES = 3900
    }
}
