package io.github.zyautra.pushbeam.server.delivery

import io.github.zyautra.pushbeam.server.delivery.RecipientResult.BELOW_MIN
import io.github.zyautra.pushbeam.server.delivery.RecipientResult.DELIVER
import io.github.zyautra.pushbeam.server.delivery.RecipientResult.MUTED
import io.github.zyautra.pushbeam.server.delivery.RecipientResult.NOT_ACTIVE
import io.github.zyautra.pushbeam.server.delivery.RecipientResult.NO_DEVICE
import io.github.zyautra.pushbeam.server.delivery.RecipientResult.QUIET
import io.github.zyautra.pushbeam.shared.Display
import io.github.zyautra.pushbeam.shared.Severity
import io.github.zyautra.pushbeam.shared.Severity.CRITICAL
import io.github.zyautra.pushbeam.shared.Severity.HIGH
import io.github.zyautra.pushbeam.shared.Severity.LOW
import io.github.zyautra.pushbeam.shared.Severity.NORMAL
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class DeliveryPlannerTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val noonSeoul = Instant.parse("2026-10-02T03:00:00Z")    // 12:00 KST
    private val midnightSeoul = Instant.parse("2026-10-02T15:30:00Z") // 00:30 KST
    private val night = QuietHours(enabled = true, startMin = 23 * 60, endMin = 7 * 60)

    private fun candidate(
        active: Boolean = true,
        sub: Subscription? = Subscription(LOW, muted = false, mutedUntil = null),
        devices: List<String> = listOf("dev_1"),
    ) = Candidate("mbr_1", active, seoul, night, sub, devices)

    private fun channel(
        severity: Severity,
        c: Candidate = candidate(),
        required: Boolean = false,
        now: Instant = noonSeoul,
    ) = DeliveryPlanner.plan(TargetType.CHANNEL, required, severity, now, listOf(c)).results.getValue("mbr_1")

    @Test fun `기본은 보낸다`() = assertEquals(DELIVER, channel(NORMAL))

    @Test fun `음소거면 보내지 않는다`() =
        assertEquals(MUTED, channel(NORMAL, candidate(sub = Subscription(LOW, muted = true, mutedUntil = null))))

    @Test fun `음소거 기한이 지나면 보낸다`() =
        assertEquals(DELIVER, channel(NORMAL, candidate(sub = Subscription(LOW, true, noonSeoul.minusSeconds(1)))))

    @Test fun `음소거 기한 전이면 보내지 않는다`() =
        assertEquals(MUTED, channel(NORMAL, candidate(sub = Subscription(LOW, true, noonSeoul.plusSeconds(60)))))

    @Test fun `최소 중요도보다 낮으면 보내지 않는다`() =
        assertEquals(BELOW_MIN, channel(NORMAL, candidate(sub = Subscription(HIGH, false, null))))

    @Test fun `최소 중요도와 같으면 보낸다`() =
        assertEquals(DELIVER, channel(HIGH, candidate(sub = Subscription(HIGH, false, null))))

    @Test fun `방해 금지 시간에는 무음으로 보낸다`() =
        assertEquals(QUIET, channel(NORMAL, now = midnightSeoul))

    @Test fun `필수가 아닌 채널의 critical도 음소거는 따른다`() =
        assertEquals(MUTED, channel(CRITICAL, candidate(sub = Subscription(LOW, true, null))))

    @Test fun `critical은 방해 금지 시간에도 소리로 보낸다`() =
        assertEquals(DELIVER, channel(CRITICAL, now = midnightSeoul))

    @Test fun `필수 채널의 critical은 음소거와 방해 금지를 무시한다`() =
        assertEquals(DELIVER, channel(CRITICAL, candidate(sub = Subscription(CRITICAL, true, null)), required = true, now = midnightSeoul))

    @Test fun `필수 채널이어도 critical이 아니면 음소거를 따른다`() =
        assertEquals(MUTED, channel(HIGH, candidate(sub = Subscription(LOW, true, null)), required = true))

    @Test fun `로그인하지 않은 Member에게는 보내지 않는다`() =
        assertEquals(NOT_ACTIVE, channel(NORMAL, candidate(active = false)))

    @Test fun `활성 기기가 없으면 NO_DEVICE`() =
        assertEquals(NO_DEVICE, channel(NORMAL, candidate(devices = emptyList())))

    @Test fun `사용자 지정 발송은 구독 설정을 보지 않고 방해 금지만 적용한다`() {
        val c = candidate(sub = null)
        assertEquals(DELIVER, DeliveryPlanner.plan(TargetType.USERS, false, LOW, noonSeoul, listOf(c)).results["mbr_1"])
        assertEquals(QUIET, DeliveryPlanner.plan(TargetType.ALL, false, LOW, midnightSeoul, listOf(c)).results["mbr_1"])
    }

    @Test fun `보낼 때는 활성 기기마다 전송을 만든다`() {
        val plan = DeliveryPlanner.plan(
            TargetType.CHANNEL, false, NORMAL, midnightSeoul,
            listOf(candidate(devices = listOf("dev_1", "dev_2"))),
        )
        assertEquals(
            listOf(PlannedDelivery("mbr_1", "dev_1", Display.QUIET), PlannedDelivery("mbr_1", "dev_2", Display.QUIET)),
            plan.deliveries,
        )
    }

    @Test fun `음소거와 최소 중요도 미만은 알림 없이 목록에만 보낸다`() {
        val muted = DeliveryPlanner.plan(TargetType.CHANNEL, false, NORMAL, noonSeoul, listOf(candidate(sub = Subscription(LOW, true, null))))
        assertEquals(listOf(PlannedDelivery("mbr_1", "dev_1", Display.INBOX, "muted")), muted.deliveries)
        val below = DeliveryPlanner.plan(TargetType.CHANNEL, false, NORMAL, noonSeoul, listOf(candidate(sub = Subscription(HIGH, false, null))))
        assertEquals(listOf(PlannedDelivery("mbr_1", "dev_1", Display.INBOX, "below_min")), below.deliveries)
    }

    @Test fun `로그인하지 않았거나 기기가 없으면 전송을 만들지 않는다`() {
        assertEquals(emptyList(), DeliveryPlanner.plan(TargetType.CHANNEL, false, NORMAL, noonSeoul, listOf(candidate(active = false))).deliveries)
        val noDevice = DeliveryPlanner.plan(TargetType.CHANNEL, false, NORMAL, noonSeoul, listOf(candidate(sub = Subscription(LOW, true, null), devices = emptyList())))
        assertEquals(NO_DEVICE, noDevice.results["mbr_1"])
        assertEquals(emptyList(), noDevice.deliveries)
    }
}
