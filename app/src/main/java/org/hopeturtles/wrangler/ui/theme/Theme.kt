package org.hopeturtles.wrangler.ui.theme

import android.app.UiModeManager
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Palette from hopeturtles.org (public/css/main.css :root and
// [data-theme="dark"]) — see the turtleOS repo's docs/wrangler/README.md
// "Visual identity". Green is the base; pink and fuchsia are the app's
// accent colours, fuchsia for the priority ones (live bullets, sparklines,
// confirm buttons); red is for the danger zone.

/** One theme's colours. Read them through [Wrangler], which follows the theme. */
@Immutable
class WranglerPalette(
    val primary: Color,
    val accent: Color,
    val ink: Color,
    val light: Color,
    val positive: Color,
    val background: Color,
    val surface: Color,
    val text: Color,
    val textMuted: Color,
    val pink: Color,
    val pinkDark: Color,
    val pinkTint: Color,
    val fuchsia: Color,
    val danger: Color,
    val cardBorder: Color,
    val settingsBackground: Color,
    val settingsCard: Color,
)

/** Fixed brand colours, the same in both themes: the map (its tiles are
 *  always light) and what's drawn on it, and the light palette below. */
object Brand {
    val Primary = Color(0xFF017919)
    val Dark = Color(0xFF1F3B22)
    val Pink = Color(0xFFEC8FC0)
    val PinkDark = Color(0xFFB8508E)
    val TextMuted = Color(0xFF6B7280)
    /** The site's standout call-to-action colour (main.css, commission rollover). */
    val Fuchsia = Color(0xFFFF00FF)
}

val LightPalette = WranglerPalette(
    primary = Brand.Primary,
    accent = Color(0xFF23B053),
    ink = Brand.Dark,
    light = Color(0xFFC0E3CB),
    positive = Brand.Primary,
    background = Color(0xFFF2F9F3),
    surface = Color(0xFFFFFFFF),
    text = Color(0xFF1F2521),
    textMuted = Brand.TextMuted,
    pink = Brand.Pink,
    pinkDark = Brand.PinkDark,
    pinkTint = Color(0xFFFDF1F8),
    fuchsia = Brand.Fuchsia,
    danger = Color(0xFFB91C1C),               // the site's .button.danger
    cardBorder = Color(0xFFDBE6DD),
    settingsBackground = Brand.Primary,       // the main green, as the top bar
    // Primary at 10%, mixed with white.
    settingsCard = Color(0xFFE6F2E8),
)

/** The site's dark neutrals with a deep green for the chrome (splash, top
 *  bar, Settings). */
val DarkPalette = WranglerPalette(
    primary = Color(0xFF006900),
    accent = Color(0xFF23B053),
    ink = Color(0xFFD4EAD9),                  // headings: a green-tinted --color-text
    light = Color(0xFF1D4A32),                // accent green at 30% on the surface
    positive = Color(0xFF6CE699),             // the site's dark status-pill green
    background = Color(0xFF121417),
    surface = Color(0xFF1B1F24),
    text = Color(0xFFE7ECF0),
    textMuted = Color(0xFF94A3B8),
    pink = Brand.Pink,
    pinkDark = Color(0xFFF0A6CF),             // pink text: lighter, not darker, on dark
    pinkTint = Color(0xFF3A2131),
    fuchsia = Brand.Fuchsia,
    danger = Color(0xFFF87171),               // the site's dark-mode red
    cardBorder = Color(0xFF1E5234),           // rgba(35,176,83,.35) on the surface
    settingsBackground = Color(0xFF006900),   // the main green, as the top bar
    settingsCard = Color(0xFF18261C),
)

val LocalWranglerPalette = staticCompositionLocalOf { LightPalette }

/** The current theme's colours. Composable reads: in a Canvas, read the
 *  colour into a local first. */
object Wrangler {
    val Primary: Color @Composable @ReadOnlyComposable get() = LocalWranglerPalette.current.primary
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalWranglerPalette.current.accent
    /** Headings and dial/chart ink: the site's dark green in light mode, near-white in dark. */
    val Ink: Color @Composable @ReadOnlyComposable get() = LocalWranglerPalette.current.ink
    /** Pale green tints (pills, outlines); a dark green wash in dark mode. */
    val Light: Color @Composable @ReadOnlyComposable get() = LocalWranglerPalette.current.light
    /** Green text on a [Light] tint. */
    val Positive: Color @Composable @ReadOnlyComposable get() = LocalWranglerPalette.current.positive
    val Background: Color @Composable @ReadOnlyComposable get() = LocalWranglerPalette.current.background
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalWranglerPalette.current.surface
    val Text: Color @Composable @ReadOnlyComposable get() = LocalWranglerPalette.current.text
    val TextMuted: Color @Composable @ReadOnlyComposable get() = LocalWranglerPalette.current.textMuted
    val Pink: Color @Composable @ReadOnlyComposable get() = LocalWranglerPalette.current.pink
    /** Pink text (and pink-on-tint); lighter than [Pink] in dark mode. */
    val PinkDark: Color @Composable @ReadOnlyComposable get() = LocalWranglerPalette.current.pinkDark
    val PinkTint: Color @Composable @ReadOnlyComposable get() = LocalWranglerPalette.current.pinkTint
    /** Priority accent: live bullets, sparklines, confirm buttons, "● In range". */
    val Fuchsia: Color @Composable @ReadOnlyComposable get() = LocalWranglerPalette.current.fuchsia
    /** Danger zone: actions that end the connection. */
    val Danger: Color @Composable @ReadOnlyComposable get() = LocalWranglerPalette.current.danger
    val CardBorder: Color @Composable @ReadOnlyComposable get() = LocalWranglerPalette.current.cardBorder
    val SettingsBackground: Color @Composable @ReadOnlyComposable get() = LocalWranglerPalette.current.settingsBackground
    val SettingsCard: Color @Composable @ReadOnlyComposable get() = LocalWranglerPalette.current.settingsCard
}

private fun scheme(p: WranglerPalette, dark: Boolean) = (if (dark) darkColorScheme() else lightColorScheme()).copy(
    primary = p.primary,
    onPrimary = Color.White,
    secondary = p.accent,
    onSecondary = Color.White,
    tertiary = p.pinkDark,
    error = p.danger,
    background = p.background,
    onBackground = p.text,
    surface = p.surface,
    onSurface = p.text,
    onSurfaceVariant = p.textMuted,
    outline = p.cardBorder,
)

private val LightScheme = scheme(LightPalette, dark = false)
private val DarkScheme = scheme(DarkPalette, dark = true)

/**
 * Light or dark follows the app's night mode, which is the phone's until
 * the Settings switch picks one ([setDarkMode]).
 */
@Composable
fun WranglerTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalWranglerPalette provides if (dark) DarkPalette else LightPalette) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}

/**
 * The Settings sun/moon switch. The system keeps the choice for this app
 * (across restarts), and the window theme (values-night) follows it too.
 * MainActivity handles the uiMode change itself, so nothing restarts.
 */
fun setDarkMode(context: Context, dark: Boolean) {
    context.getSystemService(UiModeManager::class.java).setApplicationNightMode(
        if (dark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO)
}
