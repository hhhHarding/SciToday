package com.rssai.push.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// 靖蓝品牌色：主色 #2563EB / 强调 #60A5FA。亮、暗两套都基于此色生成。

// ── 亮色 ──
private val LightPrimary = Color(0xFF2563EB)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFFDBEAFE)
private val LightOnPrimaryContainer = Color(0xFF1E3A8A)
private val LightSecondary = Color(0xFF475569)
private val LightOnSecondary = Color(0xFFFFFFFF)
private val LightSecondaryContainer = Color(0xFFE2E8F0)
private val LightOnSecondaryContainer = Color(0xFF334155)
private val LightTertiary = Color(0xFF059669)
private val LightOnTertiary = Color(0xFFFFFFFF)
private val LightTertiaryContainer = Color(0xFFD1FAE5)
private val LightOnTertiaryContainer = Color(0xFF065F46)
private val LightBackground = Color(0xFFF6F7F9)
private val LightOnBackground = Color(0xFF15171A)
private val LightSurface = Color(0xFFFFFFFF)
private val LightOnSurface = Color(0xFF15171A)
private val LightSurfaceVariant = Color(0xFFEDF0F4)
private val LightOnSurfaceVariant = Color(0xFF5F6B7A)
private val LightOutline = Color(0xFFCBD5E1)
private val LightError = Color(0xFFDC2626)
private val LightOnError = Color(0xFFFFFFFF)
private val LightErrorContainer = Color(0xFFFEE2E2)
private val LightOnErrorContainer = Color(0xFF991B1B)

// ── 暗色（沿用现有 slate 深色系） ──
private val DarkPrimary = Color(0xFF60A5FA)
private val DarkOnPrimary = Color(0xFF0F172A)
private val DarkPrimaryContainer = Color(0xFF1E3A5F)
private val DarkOnPrimaryContainer = Color(0xFF93C5FD)
private val DarkSecondary = Color(0xFF94A3B8)
private val DarkOnSecondary = Color(0xFF0F172A)
private val DarkSecondaryContainer = Color(0xFF334155)
private val DarkOnSecondaryContainer = Color(0xFFCBD5E1)
private val DarkTertiary = Color(0xFF34D399)
private val DarkOnTertiary = Color(0xFF0F172A)
private val DarkTertiaryContainer = Color(0xFF065F46)
private val DarkOnTertiaryContainer = Color(0xFFA7F3D0)
private val DarkBackground = Color(0xFF0F172A)
private val DarkOnBackground = Color(0xFFF1F5F9)
private val DarkSurface = Color(0xFF1E293B)
private val DarkOnSurface = Color(0xFFF1F5F9)
private val DarkSurfaceVariant = Color(0xFF334155)
private val DarkOnSurfaceVariant = Color(0xFF94A3B8)
private val DarkOutline = Color(0xFF475569)
private val DarkError = Color(0xFFEF4444)
private val DarkOnError = Color(0xFF0F172A)
private val DarkErrorContainer = Color(0xFF7F1D1D)
private val DarkOnErrorContainer = Color(0xFFFECACA)

val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
)

val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
)
