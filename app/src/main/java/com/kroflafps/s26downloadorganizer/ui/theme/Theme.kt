package com.kroflafps.s26downloadorganizer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF0F295E),
    secondary = Color(0xFF0D9488),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF0B3B36),
    tertiary = Color(0xFF6366F1),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE7EDF6),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    error = Color(0xFFDC2626),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF071933),
    primaryContainer = Color(0xFF172E54),
    onPrimaryContainer = Color(0xFFD6E8FF),
    secondary = Color(0xFF2DD4BF),
    onSecondary = Color(0xFF042622),
    secondaryContainer = Color(0xFF0F3835),
    onSecondaryContainer = Color(0xFFBAF7EE),
    tertiary = Color(0xFFA78BFA),
    onTertiary = Color(0xFF1E133D),
    background = Color(0xFF0A0E14),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF121822),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1B2433),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF2B364A),
    error = Color(0xFFF87171),
    errorContainer = Color(0xFF3E1619),
    onErrorContainer = Color(0xFFFECACA),
)

@Composable
fun S26DownloadOrganizerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
