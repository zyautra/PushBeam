package com.zyautra.pushbeam.shared

import kotlinx.serialization.json.Json

/** 서버가 기기마다 보내는 FCM data-only 메시지의 내용 (docs/03 7절). */
data class PushPayload(
    val id: String,
    val title: String,
    val body: String,
    val severity: Severity,
    val channel: String?,
    val quiet: Boolean,
    val sentAt: String,
    val data: Map<String, String> = emptyMap(),
) {
    fun toFcmData(): Map<String, String> = buildMap {
        put(Keys.VERSION, VERSION)
        put(Keys.ID, id)
        put(Keys.TITLE, title)
        put(Keys.BODY, body)
        put(Keys.SEVERITY, severity.wire)
        channel?.let { put(Keys.CHANNEL, it) }
        put(Keys.QUIET, quiet.toString())
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
        const val QUIET = "pb.quiet"
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
                quiet = fcm[Keys.QUIET] == "true",
                sentAt = fcm[Keys.SENT_AT] ?: return null,
                data = fcm[Keys.DATA]
                    ?.let { runCatching { Json.decodeFromString<Map<String, String>>(it) }.getOrNull() }
                    ?: emptyMap(),
            )
        }
    }
}
