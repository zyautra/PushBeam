package com.zyautra.pushbeam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyautra.pushbeam.ui.theme.Critical
import com.zyautra.pushbeam.ui.theme.High
import com.zyautra.pushbeam.ui.theme.Low
import com.zyautra.pushbeam.ui.theme.Normal

// ---- 중요도와 채널 표현  ----

data class Look(val label: String, val color: Color, val icon: ImageVector)

/** 중요도는 색만이 아니라 글자와 아이콘을 함께 쓴다 (docs/06 2절). */
fun severityLook(wire: String?): Look = when (wire) {
    "critical" -> Look("긴급", Critical, Icons.Filled.Error)
    "high" -> Look("높음", High, Icons.Filled.Warning)
    "low" -> Look("낮음", Low, Icons.Filled.Notifications)
    else -> Look("일반", Normal, Icons.Filled.Notifications)
}

private val palette = listOf(
    Color(0xFF3B82F6), Color(0xFF10B981), Color(0xFF8B5CF6), Color(0xFFF59E0B),
    Color(0xFF06B6D4), Color(0xFFEC4899), Color(0xFF6366F1),
)

/** 서버에 아이콘 정보가 없으므로 채널 이름·slug의 단어로 고르고, 없으면 slug로 색을 정한다. */
fun channelLook(slug: String?, name: String? = null): Look {
    val key = "${slug.orEmpty()} ${name.orEmpty()}".lowercase()
    fun has(vararg words: String) = words.any { it in key }
    val icon = when {
        slug == null -> Icons.Filled.ChatBubble
        has("notice", "공지", "announce") -> Icons.Filled.Campaign
        has("dev", "개발", "code") -> Icons.Filled.Code
        has("team", "팀") -> Icons.Filled.Groups
        has("deploy", "배포", "release") -> Icons.Filled.Storage
        has("server", "서버", "system", "시스템", "alert") -> Icons.Filled.Dns
        has("backup", "백업") -> Icons.Filled.Backup
        has("flight", "항공", "air") -> Icons.Filled.Flight
        has("security", "보안") -> Icons.Filled.Security
        has("test", "테스트") -> Icons.Filled.Science
        else -> Icons.Filled.Notifications
    }
    val color = palette[Math.floorMod((slug ?: "direct").hashCode(), palette.size)]
    return Look(name ?: slug ?: "직접 받은 알림", color, icon)
}

// ---- 공통 컴포넌트 ----

@Composable
fun RoundIcon(icon: ImageVector, color: Color, size: Dp = 48.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(size * 0.5f)) }
}

@Composable
fun AppCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        content = content,
    )
}

/** 가이드라인의 알약 모양 필터 칩. 선택되면 인디고로 채운다. */
@Composable
fun PillChip(text: String, selected: Boolean, count: Int? = null, badge: Int? = null, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(if (selected) primary else MaterialTheme.colorScheme.surface)
            .border(1.dp, if (selected) primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        Text(text, color = fg, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(count.toString(), color = fg, fontSize = 14.sp)
        }
        if (badge != null && badge > 0) {
            Spacer(Modifier.width(6.dp))
            Box(Modifier.clip(CircleShape).background(Critical).padding(horizontal = 6.dp, vertical = 1.dp)) {
                Text(if (badge > 99) "99+" else badge.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SectionLabel(text: String, start: Dp = 20.dp) {
    Text(
        text,
        Modifier.padding(start = start, top = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 설정 카드 안의 한 줄. 아이콘, 제목, 오른쪽 값 또는 화살표. */
@Composable
fun SettingRow(icon: ImageVector, title: String, value: String? = null, showArrow: Boolean = true, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        value?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (showArrow && onClick != null) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SwitchRow(icon: ImageVector, title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        AppSwitch(checked, enabled, onChange)
    }
}

@Composable
fun AppSwitch(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked, enabled = enabled, onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant,
            uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            uncheckedThumbColor = Color.White,
            // 필수 채널처럼 켜진 채 바꿀 수 없는 경우도 켜져 있다는 게 보이게 한다.
            disabledCheckedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
            disabledCheckedThumbColor = Color.White,
            disabledCheckedBorderColor = Color.Transparent,
        ),
    )
}

@Composable
fun RowDivider() = HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant)
