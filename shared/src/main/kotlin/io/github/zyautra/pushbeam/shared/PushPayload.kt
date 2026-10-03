package io.github.zyautra.pushbeam.shared

import kotlinx.serialization.json.Json

/** 앱이 알림을 어떻게 보여 줄지 (docs/00 7.5). */
enum class Display(val wire: String) {
    /** 중요도에 맞는 소리로 알림 */
    NORMAL("normal"),
    /** 방해 금지 시간: 소리 없는 알림 */
    QUIET("quiet"),
    /** 음소거·최소 중요도 미만: 알림 없이 앱 목록에만 저장 */
    INBOX("inbox");

    companion object {
        fun fromWire(value: String?): Display? = entries.firstOrNull { it.wire == value }
    }
}

/** 서버가 기기마다 보내는 FCM data-only 메시지의 내용 (docs/03 7절). */
data class PushPayload(
    val id: String,
    val title: String,
    val body: String,
    val severity: Severity,
    val channel: String?,
    val display: Display,
    val sentAt: String,
    val data: Map<String, String> = emptyMap(),
    /** display가 INBOX인 이유: "muted" 또는 "below_min". */
    val reason: String? = null,
) {
    val quiet: Boolean get() = display == Display.QUIET

    fun toFcmData(): Map<String, String> = buildMap {
        put(Keys.VERSION, VERSION)
        put(Keys.ID, id)
        put(Keys.TITLE, title)
        put(Keys.BODY, body)
        put(Keys.SEVERITY, severity.wire)
        channel?.let { put(Keys.CHANNEL, it) }
        put(Keys.DISPLAY, display.wire)
        put(Keys.QUIET, quiet.toString())
        reason?.let { put(Keys.REASON, it) }
        put(Keys.SENT_AT, sentAt)
        if (data.isNotEmpty()) put(Keys.DATA, Json.encodeToString(data.toSortedMap() as Map<String, String>))
    }

    object Keys {
        const val VERSION = "pb.v"
        const val ID = "pb.id"
        const val TITLE = "pb.title"
        const val BODY = "pb.body"
        const val SEVERITY = "pb.severity"
        const val CHANNEL = "pb.channel"
        const val DISPLAY = "pb.display"
        /** pb.display가 생기기 전 형식. 이전 앱을 위해 계속 보낸다. */
        const val QUIET = "pb.quiet"
        const val REASON = "pb.reason"
        const val SENT_AT = "pb.sentAt"
        const val DATA = "pb.data"
    }

    companion object {
        const val VERSION = "1"

        /** 형식 버전이 다르거나 필수 key가 없으면 null. */
        fun fromFcmData(fcm: Map<String, String>): PushPayload? {
            if (fcm[Keys.VERSION] != VERSION) return null
            return PushPayload(
                id = fcm[Keys.ID] ?: return null,
                title = fcm[Keys.TITLE] ?: return null,
                body = fcm[Keys.BODY] ?: return null,
                severity = fcm[Keys.SEVERITY]?.let(Severity::fromWire) ?: return null,
                channel = fcm[Keys.CHANNEL],
                display = Display.fromWire(fcm[Keys.DISPLAY])
                    ?: if (fcm[Keys.QUIET] == "true") Display.QUIET else Display.NORMAL,
                sentAt = fcm[Keys.SENT_AT] ?: return null,
                data = fcm[Keys.DATA]
                    ?.let { runCatching { Json.decodeFromString<Map<String, String>>(it) }.getOrNull() }
                    ?: emptyMap(),
                reason = fcm[Keys.REASON],
            )
        }
    }
}
