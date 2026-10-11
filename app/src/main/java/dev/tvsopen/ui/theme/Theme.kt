package dev.tvsopen.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Palette.
 *
 * Two deliberate constraints, both about not misrepresenting provenance:
 *
 *  1. Not TVS's racing red/black. This is an unaffiliated project; borrowing
 *     their brand palette would imply endorsement.
 *  2. No TVS or Jupiter marks, logos or model wordmarks anywhere in the UI. The
 *     layout is adapted from `overclock98/JupiterRideCompanion` (CC BY-NC 4.0),
 *     whose header carries the TVS logo and the Jupiter name. We keep the
 *     *structure* — name, connection pill, status dot — and substitute our own
 *     identifiers. See `research/SOURCES.md`.
 *
 * The soft pastel tile scheme is likewise from that app, reused under the same
 * licence.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF2196F3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE3FB),
    onPrimaryContainer = Color(0xFF0B1B2B),
    secondary = Color(0xFF5B6B7A),
    secondaryContainer = Color(0xFFEDF1F6),
    onSecondaryContainer = Color(0xFF1A2430),
    tertiary = Color(0xFF7E57C2),
    tertiaryContainer = Color(0xFFEEDCFA),
    onTertiaryContainer = Color(0xFF2A1B3D),
    error = Color(0xFFD32F2F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14181C),
    surfaceVariant = Color(0xFFECECEE),
    onSurfaceVariant = Color(0xFF5A5F66),
    outline = Color(0xFFD3D7DC),
)

/**
 * Tile backgrounds, kept out of the [androidx.compose.material3.ColorScheme]
 * because they are decorative and have no foreground/background pairing role.
 *
 * Soft pastels only; text on top of every one of these is the near-black
 * `onSurface`, which clears WCAG AA on all of them.
 */
@Immutable
data class TilePalette(
    val largeLeft: Color,
    val largeRight: Color,
    val small1: Color,
    val small2: Color,
    val small3: Color,
    val neutral: Color,
    val accent: Color,
    val connected: Color,
)

private val LightTiles = TilePalette(
    largeLeft = Color(0xFFDCE9FB),   // fuel
    largeRight = Color(0xFFF6DCF4),  // odometer
    small1 = Color(0xFFCFE3FB),      // range
    small2 = Color(0xFFE3EAFB),      // economy
    small3 = Color(0xFFEEDCFA),      // service
    neutral = Color(0xFFECECEE),     // last parked, ride mode
    accent = Color(0xFFCFE3FB),      // find my scooter
    connected = Color(0xFF4CAF50),   // status dot
)

private val DarkTiles = TilePalette(
    largeLeft = Color(0xFF1E2A38),
    largeRight = Color(0xFF33223A),
    small1 = Color(0xFF22303F),
    small2 = Color(0xFF1B2733),
    small3 = Color(0xFF2C2440),
    neutral = Color(0xFF232629),
    accent = Color(0xFF1F3247),
    connected = Color(0xFF66BB6A),
)

val LocalTilePalette = staticCompositionLocalOf { LightTiles }

/** Background for the n-th small telemetry tile. */
@Composable
fun smallTile(index: Int): Color {
    val p = LocalTilePalette.current
    return when (index) {
        0 -> p.small1
        1 -> p.small2
        else -> p.small3
    }
}

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
)

/**
 * The app renders on the light palette by default rather than following the
 * system, because the adapted design is light-only. Pass `darkTheme = true` for
 * the dark scheme; both are tuned for legibility on a glance.
 */
@Composable
fun TVSRideTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalTilePalette provides if (darkTheme) DarkTiles else LightTiles,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) LightColors.copy(
                surface = Color(0xFF12161A),
                onSurface = Color(0xFFECEFF2),
                surfaceVariant = Color(0xFF1E2226),
                onSurfaceVariant = Color(0xFFB4BAC1),
                outline = Color(0xFF3A4046),
            ) else LightColors,
            typography = AppTypography,
            content = content,
        )
    }
}