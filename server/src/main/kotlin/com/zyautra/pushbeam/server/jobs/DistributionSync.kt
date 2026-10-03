package com.zyautra.pushbeam.server.jobs

import com.zyautra.pushbeam.server.gateway.DistributionGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 허용 목록과 App Distribution 그룹을 맞춘다 (docs/01 5.6).
 * 허용·취소할 때와 6시간마다 그룹 전체를 비교해 빠진 사람은 넣고 남은 사람은 뺀다.
 */
class DistributionSync(
    private val gateway: DistributionGateway,
    private val allowedEmails: suspend () -> Set<String>,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(DistributionSync::class.java)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile var lastSuccessAt: Instant? = null; private set
    @Volatile var lastError: String? = null; private set

    fun request() {
        wake.trySend(Unit)
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            val ok = runOnce()
            // 실패하면 1분 뒤 다시, 성공하면 다음 변경이나 6시간까지 기다린다.
            withTimeoutOrNull(if (ok) INTERVAL.toMillis() else RETRY.toMillis()) { wake.receive() }
        }
    }

    suspend fun runOnce(): Boolean = try {
        val allowed = allowedEmails()
        val current = gateway.groupMembers()
        val toJoin = allowed - current
        val toLeave = current - allowed
        gateway.join(toJoin)
        gateway.leave(toLeave)
        lastSuccessAt = clock.instant()
        lastError = null
        if (toJoin.isNotEmpty() || toLeave.isNotEmpty()) {
            log.info("distribution_synced joined={} left={}", toJoin.size, toLeave.size)
        }
        true
    } catch (e: Exception) {
        lastError = e.message ?: e::class.simpleName
        log.warn("distribution_failed error={}", lastError)
        false
    }

    private companion object {
        val INTERVAL: Duration = Duration.ofHours(6)
        val RETRY: Duration = Duration.ofMinutes(1)
    }
}
