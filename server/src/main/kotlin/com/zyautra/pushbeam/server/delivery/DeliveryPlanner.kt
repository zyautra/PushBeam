package com.zyautra.pushbeam.server.delivery

import com.zyautra.pushbeam.shared.Display
import com.zyautra.pushbeam.shared.Severity
import java.time.Instant
import java.time.ZoneId

/** Member 한 명에 대한 결정 (docs/02 message_recipients.result). */
enum class RecipientResult { DELIVER, QUIET, MUTED, BELOW_MIN, NOT_ACTIVE, NO_DEVICE }

enum class TargetType { CHANNEL, USERS, ALL }

data class QuietHours(val enabled: Boolean, val startMin: Int, val endMin: Int) {
    /** 시작은 포함, 종료는 포함하지 않는다. 시작과 종료가 같으면 꺼진 것으로 본다. */
    fun isQuietAt(now: Instant, zone: ZoneId): Boolean {
        if (!enabled || startMin == endMin) return false
        val local = now.atZone(zone)
        val m = local.hour * 60 + local.minute
        return if (startMin < endMin) m in startMin until endMin else m >= startMin || m < endMin
    }
}

data class Subscription(val minSeverity: Severity, val muted: Boolean, val mutedUntil: Instant?) {
    fun isMutedAt(now: Instant): Boolean = muted && (mutedUntil == null || now < mutedUntil)
}

data class Candidate(
    val memberId: String,
    val active: Boolean,
    val zone: ZoneId,
    val quietHours: QuietHours?,
    /** 채널 알림일 때만 있다. */
    val subscription: Subscription?,
    val activeDeviceIds: List<String>,
)

data class PlannedDelivery(val memberId: String, val deviceId: String, val display: Display, val reason: String? = null)

data class Plan(val results: Map<String, RecipientResult>, val deliveries: List<PlannedDelivery>)

/** docs/00 7.5 수신 규칙. DB와 시계에 접근하지 않는 순수 함수다. */
object DeliveryPlanner {
    fun plan(
        target: TargetType,
        requiredChannel: Boolean,
        severity: Severity,
        now: Instant,
        candidates: List<Candidate>,
    ): Plan {
        val results = linkedMapOf<String, RecipientResult>()
        val deliveries = mutableListOf<PlannedDelivery>()
        for (c in candidates) {
            var result = decide(target, requiredChannel, severity, now, c)
            // 음소거·최소 중요도 미만도 알림 없이 목록에는 남기므로 기기로 보낸다.
            val display = when (result) {
                RecipientResult.DELIVER -> Display.NORMAL
                RecipientResult.QUIET -> Display.QUIET
                RecipientResult.MUTED, RecipientResult.BELOW_MIN -> Display.INBOX
                else -> null
            }
            if (display != null && c.activeDeviceIds.isEmpty()) result = RecipientResult.NO_DEVICE
            results[c.memberId] = result
            if (display != null && result != RecipientResult.NO_DEVICE) {
                val reason = when (result) {
                    RecipientResult.MUTED -> "muted"
                    RecipientResult.BELOW_MIN -> "below_min"
                    else -> null
                }
                c.activeDeviceIds.mapTo(deliveries) { PlannedDelivery(c.memberId, it, display, reason) }
            }
        }
        return Plan(results, deliveries)
    }

    private fun decide(
        target: TargetType,
        requiredChannel: Boolean,
        severity: Severity,
        now: Instant,
        c: Candidate,
    ): RecipientResult {
        if (!c.active) return RecipientResult.NOT_ACTIVE
        val critical = severity == Severity.CRITICAL
        if (target == TargetType.CHANNEL) {
            if (requiredChannel && critical) return RecipientResult.DELIVER
            val sub = requireNotNull(c.subscription) { "channel candidate without subscription: ${c.memberId}" }
            if (sub.isMutedAt(now)) return RecipientResult.MUTED
            if (severity < sub.minSeverity) return RecipientResult.BELOW_MIN
        }
        if (!critical && c.quietHours?.isQuietAt(now, c.zone) == true) return RecipientResult.QUIET
        return RecipientResult.DELIVER
    }
}
