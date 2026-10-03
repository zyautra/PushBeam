package com.zyautra.pushbeam.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.zyautra.pushbeam.R
import com.zyautra.pushbeam.data.inbox.InboxEntry
import com.zyautra.pushbeam.shared.Severity
import com.zyautra.pushbeam.ui.MainActivity

/** 중요도별 Android 알림 채널과 알림 표시 (docs/01 6.4). */
class NotificationPresenter(private val context: Context, private val prefs: com.zyautra.pushbeam.data.NotificationPrefs) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    /** 채널은 처음 한 번 만들고, 이후 사용자가 바꾼 설정은 덮어쓰지 않는다. */
    fun createChannels() {
        listOf(
            NotificationChannel(CRITICAL, "긴급", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) },
            NotificationChannel(HIGH, "높음", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) },
            NotificationChannel(NORMAL, "보통", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(LOW, "낮음", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(QUIET, "조용히 받은 알림", NotificationManager.IMPORTANCE_LOW).apply {
                description = "방해 금지 시간에 받은 알림"
            },
            // 설정의 진동·소리 스위치 조합용 (긴급 제외)
            NotificationChannel(VIBRATE_ONLY, "진동만", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null); enableVibration(true)
            },
            NotificationChannel(SOUND_ONLY, "소리만", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(false); vibrationPattern = longArrayOf(0)
            },
            NotificationChannel(SILENT, "무음", NotificationManager.IMPORTANCE_LOW),
        ).forEach(manager::createNotificationChannel)
    }

    fun show(entry: InboxEntry) {
        val severity = Severity.fromWire(entry.severity) ?: Severity.NORMAL
        val p = kotlinx.coroutines.runBlocking { prefs.current() }
        val channelId = when {
            severity == Severity.CRITICAL -> CRITICAL
            entry.quiet -> QUIET
            severity == Severity.LOW -> LOW
            !p.sound && !p.vibrate -> SILENT
            !p.sound -> VIBRATE_ONLY
            !p.vibrate -> SOUND_ONLY
            severity == Severity.HIGH -> HIGH
            else -> NORMAL
        }
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_MESSAGE_ID, entry.messageId)
        val pending = PendingIntent.getActivity(
            context, notificationId(entry.messageId), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(entry.title)
            .setContentText(entry.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(entry.body))
            .setSubText(entry.channel)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setCategory(if (severity >= Severity.HIGH) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
            .build()
        manager.notify(notificationId(entry.messageId), notification)
    }

    /** 제목과 본문만 있는 알림 (모르는 형식 버전). */
    fun showPlain(title: String, body: String) {
        val n = NotificationCompat.Builder(context, NORMAL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        manager.notify((title + body).hashCode(), n)
    }

    fun cancel(messageId: String) = manager.cancel(notificationId(messageId))

    private fun notificationId(messageId: String) = messageId.hashCode()

    companion object {
        const val CRITICAL = "pb_critical"
        const val HIGH = "pb_high"
        const val NORMAL = "pb_normal"
        const val LOW = "pb_low"
        const val QUIET = "pb_quiet"
        const val VIBRATE_ONLY = "pb_vibrate_only"
        const val SOUND_ONLY = "pb_sound_only"
        const val SILENT = "pb_silent"
    }
}
