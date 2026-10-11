package dev.tvsopen.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Palette.
 *
 * Deliberately *not* TVS's racing red/black. This is an unaffiliated project,
 * and borrowing their brand palette would imply endorsement. Blue/teal is also
 * far more legible on a dashboard glance than red-on-black.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF00658F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC7E7FF),
    onPrimaryContainer = Color(0xFF001E2E),
    secondary = Color(0xFF4F616E),
    secondaryContainer = Color(0xFFD2E5F5),
    onSecondaryContainer = Color(0xFF0B1D29),
    tertiary = Color(0xFF00696E),
    tertiaryContainer = Color(0xFF9CF1F6),
    onTertiaryContainer = Color(0xFF002021),
    error = Color(0xFFBA1A1A),
    surfaceVariant = Color(0xFFDEE3EB),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF84CFFF),
    onPrimary = Color(0xFF00344C),
    primaryContainer = Color(0xFF004C6C),
    onPrimaryContainer = Color(0xFFC7E7FF),
    secondary = Color(0xFFB6C9D8),
    secondaryContainer = Color(0xFF374955),
    onSecondaryContainer = Color(0xFFD2E5F5),
    tertiary = Color(0xFF80D4DA),
    tertiaryContainer = Color(0xFF004F53),
    onTertiaryContainer = Color(0xFF9CF1F6),
    surfaceVariant = Color(0xFF40484F),
)

@Composable
fun TVSRideTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}