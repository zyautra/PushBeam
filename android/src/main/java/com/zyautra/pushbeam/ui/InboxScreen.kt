package com.zyautra.pushbeam.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.zyautra.pushbeam.data.inbox.InboxEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private enum class InboxFilter { ALL, UNREAD, IMPORTANT, CHANNEL }

@Composable
fun InboxScreen(
    viewModel: AppViewModel,
    openMessageId: String?,
    onMessageOpened: () -> Unit,
    onOpen: (String) -> Unit,
    offline: Boolean = false,
    readOnlyNotice: String? = null,
) {
    val entries by viewModel.inbox.collectAsState()
    val channels by viewModel.channels.collectAsState()
    var filter by rememberSaveable { mutableStateOf(InboxFilter.ALL) }
    var channel by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var channelMenu by remember { mutableStateOf(false) }

    LaunchedEffect(openMessageId) {
        if (openMessageId != null) { onOpen(openMessageId); onMessageOpened() }
    }
    LaunchedEffect(Unit) { if (readOnlyNotice == null && channels.channels.isEmpty()) viewModel.loadChannels() }
    val channelNames = channels.channels.associate { it.slug to it.name }

    Column(Modifier.fillMaxSize()) {
        // 상단: 제목 / 검색 / 메뉴
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (searching) {
                TextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("제목, 내용 검색") },
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                )
                IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.Filled.Close, "검색 닫기") }
            } else {
                Text("PushBeam", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "검색") }
                Box {
                    var open by remember { mutableStateOf(false) }
                    IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "메뉴") }
                    DropdownMenu(open, onDismissRequest = { open = false }) {
                        DropdownMenuItem(text = { Text("모두 읽음으로 표시") }, onClick = { open = false; viewModel.markAllRead() })
                        DropdownMenuItem(text = { Text("모두 지우기") }, onClick = { open = false; confirmDeleteAll = true })
                    }
                }
            }
        }

        readOnlyNotice?.let { Banner(it) }
        if (offline) Banner("인터넷에 연결되어 있지 않아요")
        if (readOnlyNotice == null) NotificationPermissionBanner()

        // 필터 칩
        val unread = entries.count { it.readAt == null }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillChip("전체", filter == InboxFilter.ALL) { filter = InboxFilter.ALL; channel = null }
            PillChip("안 읽음", filter == InboxFilter.UNREAD, badge = unread) { filter = InboxFilter.UNREAD; channel = null }
            PillChip("중요", filter == InboxFilter.IMPORTANT) { filter = InboxFilter.IMPORTANT; channel = null }
            Box {
                val label = channel?.let { channelNames[it] ?: it } ?: "채널"
                PillChip(label, filter == InboxFilter.CHANNEL) { channelMenu = true }
                DropdownMenu(channelMenu, onDismissRequest = { channelMenu = false }) {
                    entries.mapNotNull { it.channel }.distinct().sorted().forEach { slug ->
                        DropdownMenuItem(text = { Text(channelNames[slug] ?: slug) }, onClick = {
                            channel = slug; filter = InboxFilter.CHANNEL; channelMenu = false
                        })
                    }
                }
            }
        }

        val q = query.trim()
        val shown = entries.filter { e ->
            when (filter) {
                InboxFilter.ALL -> true
                InboxFilter.UNREAD -> e.readAt == null
                InboxFilter.IMPORTANT -> e.severity == "high" || e.severity == "critical"
                InboxFilter.CHANNEL -> e.channel == channel
            } && (q.isEmpty() || e.title.contains(q, true) || e.body.contains(q, true))
        }
        if (shown.isEmpty()) {
            CenteredColumn {
                Text(
                    if (entries.isEmpty()) "아직 받은 알림이 없어요." else "조건에 맞는 알림이 없어요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val zone = ZoneId.systemDefault()
            val groups = shown.groupBy { Instant.ofEpochMilli(it.receivedAt).atZone(zone).toLocalDate() }
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                groups.forEach { (day, items) ->
                    item(key = "day-$day") { SectionLabel(dayLabel(day)) }
                    items(items, key = { it.messageId }) { e ->
                        InboxCard(e, channelNames[e.channel]) { onOpen(e.messageId) }
                    }
                }
            }
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("받은 알림을 모두 지울까요?") },
            text = { Text("이 기기에서만 사라져요.") },
            confirmButton = { TextButton(onClick = { viewModel.deleteAll(); confirmDeleteAll = false }) { Text("지우기") } },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun InboxCard(e: InboxEntry, channelName: String?, onClick: () -> Unit) {
    val sev = severityLook(e.severity)
    val ch = channelLook(e.channel, channelName)
    // 높음·긴급은 중요도 아이콘, 그 외는 채널 아이콘 (가이드라인)
    val look = if (e.severity == "high" || e.severity == "critical") sev else ch
    val unread = e.readAt == null
    AppCard(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), onClick = onClick) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            RoundIcon(look.icon, look.color, size = 46.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(sev.label, color = sev.color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        val rest = " · ${ch.label}" + (displayNote(e)?.let { " · $it" } ?: "")
                        Text(rest, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(relativeTime(e.receivedAt), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        e.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (unread) Box(Modifier.padding(start = 8.dp).size(8.dp).clip(CircleShape).background(if (e.severity == "high" || e.severity == "critical") sev.color else MaterialTheme.colorScheme.primary))
                }
                Spacer(Modifier.height(2.dp))
                Text(e.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun NotificationPermissionBanner() {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!granted && !asked) { asked = true; launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
    if (!granted) {
        Banner("알림 권한이 꺼져 있어요. 알림이 와도 화면에 뜨지 않아요.", "설정 열기") {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
    }
}

/** 목록에 붙이는 짧은 표시 (docs/06 5절). */
fun displayNote(e: InboxEntry): String? = when {
    e.display == "inbox" && e.reason == "below_min" -> "🔕 중요도 낮음"
    e.display == "inbox" -> "🔕 음소거됨"
    e.quiet && e.severity != "critical" -> "🌙 조용히 받음"
    else -> null
}

fun displayExplanation(e: InboxEntry): String? = when {
    e.display == "inbox" && e.reason == "below_min" -> "이 채널에서 받기로 한 중요도보다 낮아 알림 없이 목록에만 남겼어요."
    e.display == "inbox" -> "채널을 음소거해서 알림 없이 목록에만 남겼어요."
    e.quiet && e.severity != "critical" -> "방해 금지 시간이라 소리 없이 받았어요."
    else -> null
}

private val dayFormat = DateTimeFormatter.ofPattern("M월 d일 (E)", java.util.Locale.KOREAN)
private val fullFormat = DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm")
private val shortFormat = DateTimeFormatter.ofPattern("M월 d일 HH:mm")

private fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "오늘"
        today.minusDays(1) -> "어제"
        else -> day.format(dayFormat)
    }
}

fun formatTime(instant: Instant?): String = instant?.atZone(ZoneId.systemDefault())?.format(fullFormat) ?: "-"

private fun relativeTime(epochMillis: Long): String {
    val then = Instant.ofEpochMilli(epochMillis)
    val minutes = ChronoUnit.MINUTES.between(then, Instant.now())
    val zone = ZoneId.systemDefault()
    val day = then.atZone(zone).toLocalDate()
    return when {
        minutes < 1 -> "방금"
        minutes < 60 -> "${minutes}분 전"
        day == LocalDate.now(zone) -> "${minutes / 60}시간 전"
        day == LocalDate.now(zone).minusDays(1) -> "어제 " + then.atZone(zone).toLocalTime().withSecond(0).withNano(0)
        else -> then.atZone(zone).format(shortFormat)
    }
}
