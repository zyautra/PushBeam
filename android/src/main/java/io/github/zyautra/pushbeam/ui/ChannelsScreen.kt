package io.github.zyautra.pushbeam.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.zyautra.pushbeam.shared.Severity
import io.github.zyautra.pushbeam.shared.api.MemberChannel
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class ChannelFilter { ALL, SUBSCRIBED, UNSUBSCRIBED }

@Composable
fun ChannelsScreen(viewModel: AppViewModel, openSlug: String? = null, onOpened: () -> Unit = {}) {
    val state by viewModel.channels.collectAsState()
    var sheetSlug by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf(ChannelFilter.ALL) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var confirmUnsubscribe by remember { mutableStateOf<MemberChannel?>(null) }

    LaunchedEffect(Unit) { viewModel.loadChannels() }
    LaunchedEffect(openSlug) { if (openSlug != null) { sheetSlug = openSlug; onOpened() } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (searching) {
                TextField(
                    value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("채널 검색") },
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.Filled.Close, "검색 닫기") }
            } else {
                Text("채널 관리", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "검색") }
                Box {
                    var open by remember { mutableStateOf(false) }
                    IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "메뉴") }
                    DropdownMenu(open, onDismissRequest = { open = false }) {
                        DropdownMenuItem(text = { Text("새로 고침") }, onClick = { open = false; viewModel.loadChannels() })
                    }
                }
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        if (state.offline) Banner("연결되면 바꿀 수 있어요", "다시 시도") { viewModel.loadChannels() }
        state.error?.let { Banner(it) }

        val subscribed = state.channels.count { it.subscribed }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillChip("전체", filter == ChannelFilter.ALL, count = state.channels.size) { filter = ChannelFilter.ALL }
            PillChip("구독 중", filter == ChannelFilter.SUBSCRIBED, count = subscribed) { filter = ChannelFilter.SUBSCRIBED }
            PillChip("미구독", filter == ChannelFilter.UNSUBSCRIBED, count = state.channels.size - subscribed) { filter = ChannelFilter.UNSUBSCRIBED }
        }

        val q = query.trim()
        val shown = state.channels.filter {
            when (filter) {
                ChannelFilter.ALL -> true
                ChannelFilter.SUBSCRIBED -> it.subscribed
                ChannelFilter.UNSUBSCRIBED -> !it.subscribed
            } && (q.isEmpty() || it.name.contains(q, true) || it.slug.contains(q, true) || it.description?.contains(q, true) == true)
        }
        if (!state.loading && shown.isEmpty()) {
            CenteredColumn { Text(if (state.channels.isEmpty()) "아직 채널이 없어요." else "조건에 맞는 채널이 없어요.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(shown, key = { it.slug }) { ch ->
                    ChannelCard(
                        ch, busy = state.updating == ch.slug, readOnly = state.offline,
                        onClick = { sheetSlug = ch.slug },
                        onToggle = { on -> if (on) viewModel.setSubscribed(ch.slug, true) else confirmUnsubscribe = ch },
                    )
                }
            }
        }
    }

    state.channels.firstOrNull { it.slug == sheetSlug }?.let { ch ->
        ChannelSheet(ch, busy = state.updating == ch.slug, readOnly = state.offline, viewModel = viewModel,
            onUnsubscribe = { confirmUnsubscribe = ch }, onClose = { sheetSlug = null })
    }
    confirmUnsubscribe?.let { ch ->
        AlertDialog(
            onDismissRequest = { confirmUnsubscribe = null },
            title = { Text("${ch.name} 채널 구독을 해제할까요?") },
            text = { Text("이 채널의 알림을 더 이상 받지 않아요.") },
            confirmButton = { TextButton(onClick = { viewModel.setSubscribed(ch.slug, false); confirmUnsubscribe = null }) { Text("해제") } },
            dismissButton = { TextButton(onClick = { confirmUnsubscribe = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun ChannelCard(ch: MemberChannel, busy: Boolean, readOnly: Boolean, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val look = channelLook(ch.slug, ch.name)
    AppCard(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), onClick = onClick) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(look.icon, look.color, size = 46.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ch.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (ch.required) Icon(Icons.Filled.Lock, "필수 채널", Modifier.padding(start = 6.dp).size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ch.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.People, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(" ${ch.subscribers}명", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (ch.subscribed) {
                    Text(receivingSummary(ch), style = MaterialTheme.typography.labelMedium,
                        color = if (ch.muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                }
            }
            AppSwitch(checked = ch.subscribed, enabled = !busy && !readOnly && !ch.required, onChange = onToggle)
        }
    }
}

/** 받는 범위를 결과로 설명한다 (docs/06 2절). */
fun receivingSummary(ch: MemberChannel): String {
    if (!ch.subscribed) return "구독하지 않음"
    val parts = mutableListOf<String>()
    if (ch.muted) parts += ch.mutedUntil?.let { "🔕 ${untilText(it)}까지" } ?: "🔕 음소거"
    parts += when (ch.minSeverity ?: Severity.LOW) {
        Severity.LOW -> "모두 울림"
        Severity.NORMAL -> "일반 이상 울림"
        Severity.HIGH -> "높음 이상 울림"
        Severity.CRITICAL -> "긴급만 울림"
    }
    return parts.joinToString(" · ")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ChannelSheet(ch: MemberChannel, busy: Boolean, readOnly: Boolean, viewModel: AppViewModel, onUnsubscribe: () -> Unit, onClose: () -> Unit) {
    val enabled = !busy && !readOnly
    val look = channelLook(ch.slug, ch.name)
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(look.icon, look.color, size = 48.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(ch.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    ch.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            Spacer(Modifier.height(16.dp))
            AppCard {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("구독", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    AppSwitch(checked = ch.subscribed, enabled = enabled && !ch.required) { on ->
                        if (on) viewModel.setSubscribed(ch.slug, true) else onUnsubscribe()
                    }
                }
            }
            if (ch.required) {
                Text("운영자가 정한 필수 채널이에요. 긴급 알림은 항상 울려요.", Modifier.padding(top = 8.dp, start = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (ch.subscribed) {
                SectionLabel("울릴 알림", start = 4.dp)
                AppCard {
                    listOf(
                        Severity.LOW to "모두", Severity.NORMAL to "일반 이상",
                        Severity.HIGH to "높음 이상", Severity.CRITICAL to "긴급만",
                    ).forEach { (sev, label) ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = ch.minSeverity == sev, enabled = enabled) { viewModel.setMinSeverity(ch.slug, sev) }
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = ch.minSeverity == sev, onClick = null, enabled = enabled)
                            Text(label, Modifier.padding(start = 8.dp))
                        }
                    }
                }
                Text(minSeverityExplanation(ch.minSeverity ?: Severity.LOW), Modifier.padding(top = 8.dp, start = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                SectionLabel("음소거", start = 4.dp)
                if (ch.muted) {
                    AppCard {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(ch.mutedUntil?.let { "${untilText(it)}까지 음소거 중" } ?: "직접 끌 때까지 음소거 중", Modifier.weight(1f))
                            TextButton(onClick = { viewModel.unmute(ch.slug) }, enabled = enabled) { Text("해제") }
                        }
                    }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("1시간" to Duration.ofHours(1), "8시간" to Duration.ofHours(8), "직접 끌 때까지" to null).forEach { (label, d) ->
                            OutlinedButton(
                                onClick = { viewModel.mute(ch.slug, d?.let { Instant.now().plus(it) }) }, enabled = enabled,
                                shape = RoundedCornerShape(50),
                            ) { Text(label) }
                        }
                    }
                }
                Text(
                    if (ch.required) "음소거하면 알림 없이 목록에만 남아요. 긴급 알림은 그래도 울려요." else "음소거하면 알림 없이 목록에만 남아요.",
                    Modifier.padding(top = 8.dp, start = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun minSeverityExplanation(min: Severity): String = when (min) {
    Severity.LOW -> "이 채널의 모든 알림이 울려요."
    Severity.NORMAL -> "낮음 알림은 울리지 않고 목록에만 남아요."
    Severity.HIGH -> "일반·낮음 알림은 울리지 않고 목록에만 남아요."
    Severity.CRITICAL -> "긴급 알림만 울리고 나머지는 목록에만 남아요."
}

private val untilFormat = DateTimeFormatter.ofPattern("M월 d일 a h:mm", java.util.Locale.KOREAN)

private val untilTimeFormat = DateTimeFormatter.ofPattern("a h:mm", java.util.Locale.KOREAN)

private fun untilText(iso: String): String = runCatching {
    val t = Instant.parse(iso).atZone(ZoneId.systemDefault())
    if (t.toLocalDate() == java.time.LocalDate.now()) t.format(untilTimeFormat) else t.format(untilFormat)
}.getOrDefault(iso)
