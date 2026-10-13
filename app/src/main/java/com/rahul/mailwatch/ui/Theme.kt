package com.rahul.mailwatch.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Postal blue for everything calm, signal amber only for alarms.
val Amber = Color(0xFFF2A93B)
val AmberDeep = Color(0xFF2A1A00)

private val Light = lightColorScheme(
    primary = Color(0xFF2B4FD8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE4FF),
    onPrimaryContainer = Color(0xFF0E1C5C),
    secondary = Amber,
    onSecondary = AmberDeep,
    secondaryContainer = Color(0xFFFFE9C7),
    onSecondaryContainer = Color(0xFF4A2E00),
    background = Color(0xFFF4F6FA),
    onBackground = Color(0xFF1A2333),
    surface = Color(0xFFF4F6FA),
    onSurface = Color(0xFF1A2333),
    surfaceVariant = Color(0xFFE4E8F0),
    onSurfaceVariant = Color(0xFF5B6579),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFBFD),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEDF0F6),
    surfaceContainerHighest = Color(0xFFE4E8F0),
    outline = Color(0xFFC3CAD7),
    outlineVariant = Color(0xFFDDE2EA),
    error = Color(0xFFC23A3A),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8AA3FF),
    onPrimary = Color(0xFF0E1424),
    primaryContainer = Color(0xFF24346F),
    onPrimaryContainer = Color(0xFFDDE4FF),
    secondary = Color(0xFFF5B65A),
    onSecondary = AmberDeep,
    secondaryContainer = Color(0xFF4A3410),
    onSecondaryContainer = Color(0xFFFFDDA6),
    background = Color(0xFF121826),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF121826),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF242D40),
    onSurfaceVariant = Color(0xFFA3ADC0),
    surfaceContainerLowest = Color(0xFF0D1220),
    surfaceContainerLow = Color(0xFF161D2C),
    surfaceContainer = Color(0xFF1B2334),
    surfaceContainerHigh = Color(0xFF222B3D),
    surfaceContainerHighest = Color(0xFF2A3447),
    outline = Color(0xFF3A4560),
    outlineVariant = Color(0xFF2C3650),
    error = Color(0xFFFF8A8A),
)

@Composable
fun MailWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
