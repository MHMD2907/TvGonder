package com.onur.tvgonder.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF0D1B2A)
val Panel = Color(0xFF15283D)
val PanelHi = Color(0xFF1E3650)
val Accent = Color(0xFF58D5C9)
val TextMain = Color(0xFFE8EEF5)
val TextMuted = Color(0xFF8FA3B8)
val Danger = Color(0xFFFF7A7A)

private val scheme = darkColorScheme(
    primary = Accent, onPrimary = Ink,
    background = Ink, onBackground = TextMain,
    surface = Panel, onSurface = TextMain,
    surfaceVariant = PanelHi, onSurfaceVariant = TextMuted,
    error = Danger, outline = TextMuted,
)

private val type = Typography(
    headlineLarge = TextStyle(fontSize = 44.sp, lineHeight = 50.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
