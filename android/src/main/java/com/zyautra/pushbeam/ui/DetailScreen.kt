package com.zyautra.pushbeam.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import java.time.Instant

/** 알림 상세 . onOpenChannel이 null이면 채널 설정 버튼을 숨긴다. */
@Composable
fun DetailScreen(viewModel: AppViewModel, messageId: String, onBack: () -> Unit, onOpenChannel: ((String) -> Unit)?) {
    val entries by viewModel.inbox.collectAsState()
    val channels by viewModel.channels.collectAsState()
    val e = entries.firstOrNull { it.messageId == messageId }
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(messageId) { viewModel.markRead(messageId) }
    if (e == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val sev = severityLook(e.severity)
    val channel = channels.channels.firstOrNull { it.slug == e.channel }
    val ch = channelLook(e.channel, channel?.name)
    val data = e.data?.let { runCatching { Json.decodeFromString<Map<String, String>>(it) }.getOrNull() }.orEmpty()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
            Spacer(Modifier.weight(1f))
            androidx.compose.foundation.layout.Box {
                var open by remember { mutableStateOf(false) }
                IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "메뉴") }
                DropdownMenu(open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text("지우기") }, onClick = { open = false; confirmDelete = true })
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(sev.icon, null, tint = sev.color, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text(sev.label, color = sev.color, fontWeight = FontWeight.SemiBold)
                Text(" · ${ch.label}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            Text(e.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(formatTime(runCatching { Instant.parse(e.sentAt) }.getOrNull()), color = MaterialTheme.colorScheme.onSurfaceVariant)
            displayExplanation(e)?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            AppCard {
                SelectionContainer { Text(e.body, Modifier.padding(18.dp), style = MaterialTheme.typography.bodyLarge) }
            }
            if (data.isNotEmpty()) {
                SectionLabel("추가 정보", start = 4.dp)
                AppCard {
                    SelectionContainer {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            data.forEach { (k, v) ->
                                Row { Text(k, Modifier.width(96.dp), color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v) }
                            }
                        }
                    }
                }
            }
            if (e.channel != null) {
                SectionLabel("채널 정보", start = 4.dp)
                AppCard {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoundIcon(ch.icon, ch.color, size = 42.dp)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(ch.label, style = MaterialTheme.typography.titleMedium)
                            channel?.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DetailButton("공유", Icons.Outlined.Share, Modifier.weight(1f)) {
                    val text = "${e.title}\n${e.body}"
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "공유"))
                }
                if (onOpenChannel != null && e.channel != null) {
                    DetailButton("채널 설정", Icons.Outlined.Settings, Modifier.weight(1f)) { onOpenChannel(e.channel) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("이 알림을 지울까요?") },
            text = { Text("이 기기에서만 사라져요.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete(e.messageId); onBack() }) { Text("지우기") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun DetailButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, modifier = modifier.height(52.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}
