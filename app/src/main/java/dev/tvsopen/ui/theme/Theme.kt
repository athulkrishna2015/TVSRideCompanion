package dev.tvsopen.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Palette, mapped from the token set in `DESIGN.md` (Coinbase-inspired
 * system: single blue accent, white canvas, near-black dark canvas).
 *
 * Token mapping (light / dark):
 * - primary #0052FF both; CTAs stay blue on either canvas per the system.
 * - primaryContainer is the neutral card fill (surface-strong #EEF0F3 /
 *   surface-dark-elevated #16181C), not a blue tint: blue is scarce.
 * - tertiary is the darker/faded blue step (primary-active #003ECC /
 *   primary-disabled #A8B8CC, readable on black). It signals WARN-level
 *   and unverified-provenance rows, never brand.
 * - error is semantic-down #CF202F, text/fill per the trading-semantics rule.
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
 * licence. Tiles keep their hues: they are functional surfaces (adjacent tiles
 * must stay distinguishable), not brand accents.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF0052FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEEF0F3),
    onPrimaryContainer = Color(0xFF0A0B0D),
    secondary = Color(0xFF5B616E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEF0F3),
    onSecondaryContainer = Color(0xFF0A0B0D),
    tertiary = Color(0xFF003ECC),
    tertiaryContainer = Color(0xFFEEF0F3),
    onTertiaryContainer = Color(0xFF0A0B0D),
    error = Color(0xFFCF202F),
    onError = Color.White,
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0A0B0D),
    surfaceVariant = Color(0xFFF7F7F7),
    onSurfaceVariant = Color(0xFF5B616E),
    outline = Color(0xFFDEE1E6),
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
    largeLeft = Color(0xFF1B2735),
    largeRight = Color(0xFF2A1F30),
    small1 = Color(0xFF1F3040),
    small2 = Color(0xFF1A2430),
    small3 = Color(0xFF241E33),
    neutral = Color(0xFF1C2024),
    accent = Color(0xFF17293A),
    connected = Color(0xFF66BB6A),
)

val LocalTilePalette = staticCompositionLocalOf { LightTiles }

/**
 * Background for the n-th small telemetry tile.
 *
 * Cycles rather than clamping. A `when { else -> small3 }` made every tile past
 * the third reuse one colour, which is invisible until a sixth value is added
 * and two adjacent tiles become indistinguishable.
 */
@Composable
fun smallTile(index: Int): Color {
    val p = LocalTilePalette.current
    return when (((index % 3) + 3) % 3) {
        0 -> p.small1
        1 -> p.small2
        else -> p.small3
    }
}

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Normal),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
)

/**
 * Tabular numerals, per `DESIGN.md` (`number-display`, weight 500, mono).
 * System monospace: the licensed brand mono has an Inter/JetBrains substitute
 * on the web, but this app ships no fonts and requests no network, so the
 * platform face is the honest stand-in. Applied to telemetry readouts only,
 * never to prose.
 */
val NumberStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
)

/**
 * Dark scheme: near-black canvas #0A0B0D with #16181C elevated cards, per
 * `DESIGN.md` (`surface-dark`, `surface-dark-elevated`). Blue stays #0052FF —
 * CTAs are blue pills on either canvas. Error stays semantic-down #CF202F,
 * which is the system's own dark-hero price-cell treatment.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF0052FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF16181C),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFFA8ACB3),
    secondaryContainer = Color(0xFF16181C),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFFA8B8CC),
    tertiaryContainer = Color(0xFF16181C),
    onTertiaryContainer = Color(0xFFFFFFFF),
    error = Color(0xFFCF202F),
    onError = Color.White,
    background = Color(0xFF0A0B0D),
    surface = Color(0xFF0A0B0D),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF16181C),
    onSurfaceVariant = Color(0xFFA8ACB3),
    outline = Color(0xFF5B616E),
)

/**
 * Applies the palette for [mode].
 *
 * [ThemeMode.AUTO] follows the system setting, which is why this takes a
 * `darkTheme` boolean computed by the caller rather than calling
 * `isSystemInDarkTheme()` internally — the caller already needs to read it to
 * keep the two in sync.
 */
@Composable
fun TVSRideTheme(
    mode: ThemeMode = ThemeMode.AUTO,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val useDark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.AUTO -> darkTheme
    }
    CompositionLocalProvider(
        LocalTilePalette provides if (useDark) DarkTiles else LightTiles,
    ) {
        MaterialTheme(
            colorScheme = if (useDark) DarkColors else LightColors,
            typography = AppTypography,
            content = content,
        )
    }
}