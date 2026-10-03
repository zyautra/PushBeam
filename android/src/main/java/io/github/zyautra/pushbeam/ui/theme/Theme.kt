package io.github.zyautra.pushbeam.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val Light = lightColorScheme(
    primary = Indigo,
    onPrimary = Surface,
    primaryContainer = IndigoSoft,
    onPrimaryContainer = Indigo,
    secondaryContainer = IndigoSoft,
    onSecondaryContainer = Indigo,
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = Background,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = Surface,
    surfaceContainerLow = Surface,
    surfaceContainerHigh = Surface,
    outlineVariant = Divider,
    error = Critical,
)

private val Dark = darkColorScheme(
    primary = Indigo,
    onPrimary = Surface,
    primaryContainer = Indigo.copy(alpha = 0.25f),
    onPrimaryContainer = IndigoSoft,
    secondaryContainer = Indigo.copy(alpha = 0.25f),
    onSecondaryContainer = IndigoSoft,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkBackground,
    onSurfaceVariant = DarkTextSecondary,
    surfaceContainer = DarkSurface,
    surfaceContainerLow = DarkSurface,
    surfaceContainerHigh = DarkSurface,
    outlineVariant = DarkSurface,
    error = Critical,
)

/** 기기 배경화면 색(dynamic color) 대신 가이드라인의 고정 색을 쓴다. */
@Composable
fun PushbeamTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, typography = Typography, content = content)
}
