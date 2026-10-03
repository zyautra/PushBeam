package com.zyautra.pushbeam.server.gateway

import com.zyautra.pushbeam.shared.PushPayload
import java.time.Duration

/** FCM 전송 결과를 docs/01 5.3의 분류로 바꾼 것. */
sealed interface FcmResult {
    data class Sent(val name: String) : FcmResult
    data class InvalidToken(val reason: String) : FcmResult
    data class Retryable(val reason: String, val retryAfter: Duration? = null) : FcmResult
    data class Failed(val reason: String) : FcmResult
}

fun interface FcmGateway {
    suspend fun send(token: String, payload: PushPayload, highPriority: Boolean): FcmResult
}

/** Firebase App Distribution 테스터 그룹. */
interface DistributionGateway {
    suspend fun groupMembers(): Set<String>
    suspend fun join(emails: Set<String>)
    suspend fun leave(emails: Set<String>)
}
