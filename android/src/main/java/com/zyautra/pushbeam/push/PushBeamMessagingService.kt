package com.zyautra.pushbeam.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.zyautra.pushbeam.App
import com.zyautra.pushbeam.data.inbox.InboxEntry
import com.zyautra.pushbeam.shared.Display
import com.zyautra.pushbeam.shared.PushPayload
import kotlinx.serialization.json.Json

class PushBeamMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        App.graph.requestRegistration()
    }

    /** docs/01 6.4: 중복 확인 → Inbox 저장 → 알림. 이 메서드 안에서 동기로 끝낸다. */
    override fun onMessageReceived(message: RemoteMessage) {
        val graph = App.graph
        val payload = PushPayload.fromFcmData(message.data)
        if (payload == null) {
            val title = message.data[PushPayload.Keys.TITLE] ?: message.notification?.title ?: return
            graph.notifications.showPlain(title, message.data[PushPayload.Keys.BODY] ?: message.notification?.body.orEmpty())
            return
        }
        val entry = InboxEntry(
            messageId = payload.id,
            title = payload.title,
            body = payload.body,
            severity = payload.severity.wire,
            channel = payload.channel,
            quiet = payload.quiet,
            display = payload.display.wire,
            reason = payload.reason,
            data = payload.data.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) },
            sentAt = payload.sentAt,
            receivedAt = System.currentTimeMillis(),
        )
        val inserted = graph.inbox.insertIfAbsent(entry) != -1L
        // 음소거·최소 중요도 미만은 알림 없이 목록에만 남긴다.
        if (inserted && payload.display != Display.INBOX) graph.notifications.show(entry)
    }
}
