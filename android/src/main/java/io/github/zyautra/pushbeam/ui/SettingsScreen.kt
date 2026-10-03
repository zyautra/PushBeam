package io.github.zyautra.pushbeam.ui

import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.zyautra.pushbeam.App
import io.github.zyautra.pushbeam.BuildConfig
import io.github.zyautra.pushbeam.data.NotificationPrefs
import io.github.zyautra.pushbeam.data.RegistrationStore
import io.github.zyautra.pushbeam.shared.api.QuietHoursDto
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

@Composable
fun SettingsScreen(viewModel: AppViewModel, gate: Gate.Ready) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = gate.me
    val prefs by App.graph.notificationPrefs.value.collectAsState(initial = NotificationPrefs.Value())
    var notificationsOn by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var batteryOk by remember { mutableStateOf(isIgnoringBattery(context)) }
    // 시스템 설정에서 돌아오면 다시 확인한다.
    LifecycleResumeEffect(Unit) {
        notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
        batteryOk = isIgnoringBattery(context)
        onPauseOrDispose { }
    }
    var accountDialog by remember { mutableStateOf(false) }
    var editQuiet by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text("설정", Modifier.padding(start = 4.dp, top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        SectionLabel("계정", start = 4.dp)
        AppCard {
            SettingRow(Icons.Outlined.Person, me?.email ?: App.graph.session.email ?: "-") { accountDialog = true }
        }

        SectionLabel("알림 설정", start = 4.dp)
        AppCard {
            SwitchRow(Icons.Outlined.Notifications, "알림 허용", notificationsOn) { openNotificationSettings(context) }
            RowDivider()
            SwitchRow(Icons.Outlined.Vibration, "진동", prefs.vibrate, enabled = notificationsOn) { on ->
                scope.launch { App.graph.notificationPrefs.setVibrate(on) }
            }
            RowDivider()
            SwitchRow(Icons.AutoMirrored.Outlined.VolumeUp, "소리", prefs.sound, enabled = notificationsOn) { on ->
                scope.launch { App.graph.notificationPrefs.setSound(on) }
            }
            RowDivider()
            SettingRow(Icons.Outlined.Bedtime, "방해 금지 시간", quietSummary(me?.quietHours)) {
                if (me != null && !gate.offline) editQuiet = true
            }
            RowDivider()
            SettingRow(Icons.Outlined.MusicNote, "중요도별 알림 소리", "시스템 설정") { openNotificationSettings(context) }
        }
        Text(
            "긴급 알림은 진동·소리 설정과 관계없이 울려요.",
            Modifier.padding(start = 4.dp, top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionLabel("기기", start = 4.dp)
        AppCard {
            SettingRow(Icons.Outlined.BatteryAlert, "배터리 최적화", if (batteryOk) "제한 없음" else "알림이 늦을 수 있어요") {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        SectionLabel("앱 정보", start = 4.dp)
        AppCard {
            SettingRow(Icons.Outlined.Info, "버전", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", showArrow = false)
            RowDivider()
            SettingRow(Icons.Outlined.BugReport, "진단 정보") { showDiagnostics = true }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (accountDialog) {
        AlertDialog(
            onDismissRequest = { accountDialog = false },
            title = { Text("로그아웃할까요?") },
            text = { Text("이 기기로 더 이상 알림이 오지 않아요.\n받은 알림 기록은 남아 있어요.") },
            confirmButton = { TextButton(onClick = { accountDialog = false; viewModel.signOut() }) { Text("로그아웃") } },
            dismissButton = { TextButton(onClick = { accountDialog = false }) { Text("취소") } },
        )
    }
    if (editQuiet) QuietHoursDialog(me?.quietHours, viewModel) { editQuiet = false }
    if (showDiagnostics) DiagnosticsDialog { showDiagnostics = false }
}

@Composable
private fun QuietHoursDialog(current: QuietHoursDto?, viewModel: AppViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(current?.enabled ?: true) }
    var start by remember { mutableStateOf(current?.start ?: "23:00") }
    var end by remember { mutableStateOf(current?.end ?: "07:00") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun pick(value: String, set: (String) -> Unit) {
        val (h, m) = value.split(":").map(String::toInt)
        TimePickerDialog(context, { _, hh, mm -> set("%02d:%02d".format(hh, mm)) }, h, m, true).show()
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("방해 금지 시간") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("사용", Modifier.weight(1f))
                    AppSwitch(checked = enabled) { enabled = it }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { pick(start) { start = it } }) { Text("시작  $start") }
                    TextButton(onClick = { pick(end) { end = it } }) { Text("종료  $end" + if (end < start) " (다음 날)" else "") }
                }
                Spacer(Modifier.height(8.dp))
                Text("이 시간에 온 알림은 소리 없이 받아요.\n필수 채널의 긴급 알림은 소리가 나요.", style = MaterialTheme.typography.bodySmall)
                Text("시간대: ${ZoneId.systemDefault().id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                if (enabled && start == end) { error = "시작과 종료가 같을 수 없어요"; return@TextButton }
                saving = true
                viewModel.setQuietHours(QuietHoursDto(enabled, start, end)) { ok ->
                    saving = false
                    if (ok) onClose() else error = "저장하지 못했어요. 다시 시도해 주세요"
                }
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("취소") } },
    )
}

@Composable
private fun DiagnosticsDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val reg by App.graph.registration.state.collectAsState(initial = null)
    val text = diagnosticsText(context, reg)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("진단 정보") },
        text = { Text(text, style = MaterialTheme.typography.bodySmall) },
        confirmButton = {
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PushBeam 진단 정보", text))
            }) { Text("복사") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("닫기") } },
    )
}

/** 토큰 원문과 알림 내용은 넣지 않는다 (docs/06 7절). */
private fun diagnosticsText(context: Context, reg: RegistrationStore.State?): String {
    val installation = reg?.installationId?.let { it.take(4) + "…" + it.takeLast(2) } ?: "-"
    val lastRegistered = reg?.lastRegisteredAt?.let { formatTime(Instant.ofEpochMilli(it)) } ?: "-"
    return listOf(
        "앱 버전" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        "Installation" to installation,
        "로그인 계정" to (App.graph.session.email ?: "-"),
        "마지막 기기 등록" to lastRegistered,
        "등록 필요" to if (reg?.needsRegistration == true) "예" else "아니오",
        "알림 권한" to if (NotificationManagerCompat.from(context).areNotificationsEnabled()) "허용" else "꺼짐",
        "배터리 최적화 제외" to if (isIgnoringBattery(context)) "예" else "아니오",
        "Server" to BuildConfig.PUSHBEAM_URL,
    ).joinToString("\n") { (k, v) -> "$k: $v" }
}

private fun isIgnoringBattery(context: Context) =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

private fun quietSummary(q: QuietHoursDto?): String =
    if (q == null || !q.enabled) "설정 안 함" else "${q.start} ~ ${q.end}" + if (q.end < q.start) " (다음 날)" else ""

private fun openNotificationSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
}
