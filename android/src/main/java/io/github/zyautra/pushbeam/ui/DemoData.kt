package io.github.zyautra.pushbeam.ui

import io.github.zyautra.pushbeam.data.inbox.InboxDao
import io.github.zyautra.pushbeam.data.inbox.InboxEntry
import io.github.zyautra.pushbeam.shared.Severity
import io.github.zyautra.pushbeam.shared.api.MeResponse
import io.github.zyautra.pushbeam.shared.api.MemberChannel
import io.github.zyautra.pushbeam.shared.api.QuietHoursDto
import java.time.Instant
import java.time.temporal.ChronoUnit

/** debug 빌드에서 화면 확인용 예시 데이터 (adb shell am start ... --ez demo true). */
object DemoData {
    val me = MeResponse("user@example.com", "데모", "ACTIVE", QuietHoursDto(false, "23:00", "07:00"))

    val channels = listOf(
        MemberChannel("test", "test", "테스트용 알림 채널", required = true, subscribed = true, minSeverity = Severity.LOW, subscribers = 42),
        MemberChannel("notice", "공지사항", "서비스 공지 및 중요 안내", required = false, subscribed = true, minSeverity = Severity.LOW, subscribers = 128),
        MemberChannel("dev", "개발팀", "개발 관련 알림", required = false, subscribed = true, minSeverity = Severity.HIGH, subscribers = 36),
        MemberChannel("team", "팀 채널", "팀 공지 및 소통", required = false, subscribed = false, subscribers = 24),
        MemberChannel("server-alerts", "시스템", "시스템 알림 및 점검 안내", required = false, subscribed = true, minSeverity = Severity.LOW,
            muted = true, mutedUntil = Instant.now().plus(1, ChronoUnit.HOURS).toString(), subscribers = 56),
        MemberChannel("deploy", "배포", "배포 결과 및 상태 알림", required = false, subscribed = false, subscribers = 18),
    )

    fun install(inbox: InboxDao) {
        val now = Instant.now()
        fun at(minutesAgo: Long) = now.minus(minutesAgo, ChronoUnit.MINUTES)
        listOf(
            entry("demo-1", "2단계 high", "높음 알림이라 와야 해요", "high", "test", at(10), read = false),
            entry("demo-2", "시스템 점검 안내", "이번 주 토요일 새벽 2시부터 4시까지 서버 점검이 있어요.", "normal", "notice", at(60), read = false),
            entry("demo-3", "배포 완료", "v1.2.0 이 프로덕션에 배포되었습니다.", "normal", "dev", at(180), read = true, display = "inbox", reason = "below_min"),
            entry("demo-4", "주간 회의 자료", "이번 주 회의 자료를 공유합니다.", "low", "team", at(300), read = true),
            entry("demo-5", "디스크 사용량 경고", "nas-01 /var 사용량 92%", "critical", "server-alerts", at(60 * 26), read = true),
            entry("demo-6", "백업 완료", "야간 백업이 끝났어요.", "normal", "server-alerts", at(60 * 30), read = true, display = "inbox", reason = "muted"),
        ).forEach { inbox.insertIfAbsent(it) }
    }

    private fun entry(id: String, title: String, body: String, severity: String, channel: String, time: Instant,
                      read: Boolean, display: String = "normal", reason: String? = null) = InboxEntry(
        messageId = id, title = title, body = body, severity = severity, channel = channel, quiet = false,
        data = if (id == "demo-5") """{"host":"nas-01","eventId":"10234"}""" else null,
        sentAt = time.toString(), receivedAt = time.toEpochMilli(), readAt = if (read) time.toEpochMilli() else null,
        display = display, reason = reason,
    )
}
