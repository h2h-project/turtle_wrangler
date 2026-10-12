package org.hopeturtles.wrangler.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Palette from hopeturtles.org (public/css/main.css :root) — see the turtleOS
// repo's docs/wrangler/README.md "Visual identity". Green is the base;
// pink and fuchsia are the app's accent colours.
object Wrangler {
    val Primary = Color(0xFF017919)
    val Accent = Color(0xFF23B053)
    val Dark = Color(0xFF1F3B22)
    val Light = Color(0xFFC0E3CB)
    val Background = Color(0xFFF2F9F3)
    val Surface = Color(0xFFFFFFFF)
    val Text = Color(0xFF1F2521)
    val TextMuted = Color(0xFF6B7280)
    val Pink = Color(0xFFEC8FC0)
    val PinkDark = Color(0xFFB8508E)
    val PinkTint = Color(0xFFFDF1F8)
    /** The site's standout call-to-action colour (main.css, commission
     *  rollover). An accent: the "● In range" dot, the sparklines. */
    val Fuchsia = Color(0xFFFF00FF)
    val CardBorder = Color(0xFFDBE6DD)
    /** Settings page: Primary at 60%, and its cards at 10%, mixed with white. */
    val SettingsBackground = Color(0xFF67AF75)
    val SettingsCard = Color(0xFFE6F2E8)
}

private val Scheme = lightColorScheme(
    primary = Wrangler.Primary,
    onPrimary = Color.White,
    secondary = Wrangler.Accent,
    onSecondary = Color.White,
    tertiary = Wrangler.PinkDark,
    background = Wrangler.Background,
    onBackground = Wrangler.Text,
    surface = Wrangler.Surface,
    onSurface = Wrangler.Text,
    onSurfaceVariant = Wrangler.TextMuted,
    outline = Wrangler.CardBorder,
)

@Composable
fun WranglerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
