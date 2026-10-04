package org.hopeturtles.wrangler.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hopeturtles.wrangler.BattSample
import org.hopeturtles.wrangler.EnvSample
import org.hopeturtles.wrangler.ble.NavState
import org.hopeturtles.wrangler.ble.TurtleTelemetry
import org.hopeturtles.wrangler.ui.theme.Wrangler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * App Phase 2 Dashboard. [live] = connected and updated recently; otherwise
 * everything is dimmed under a "last seen" banner — live and cached data
 * must never look the same (app plan Phase 2).
 */
@Composable
fun DashboardTab(
    tel: TurtleTelemetry,
    live: Boolean,
    battHistory: List<BattSample>,
    onBattery: () -> Unit,
    envHistory: List<EnvSample>,
    onBottle: () -> Unit,
) {
    val nav = tel.nav
    val safe = nav?.state == NavState.SAFE
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!live) {
            val at = if (tel.lastUpdateMs > 0)
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(tel.lastUpdateMs)) else "—"
            Banner("Last seen $at — not live", Wrangler.TextMuted, Wrangler.CardBorder.copy(alpha = 0.6f))
        }
        if (safe) {
            // SAFE takes the screen's one pink slot (tint, not a solid fill).
            Banner("SAFE mode — " + (Format.fault(nav?.fault) ?: "turtle stopped steering"),
                Wrangler.PinkDark, Wrangler.PinkTint)
        }

        Column(Modifier.alpha(if (live) 1f else 0.5f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HeadingCard(tel, live && !safe)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Tap → full-screen charging graph.
                Tile("Battery", tel.power?.socPct?.let { "$it%" },
                    Modifier.weight(1f).clickable(onClick = onBattery)) {
                    ChargeLine(tel.power?.currentMa)
                    Muted(listOfNotNull(Format.volts(tel.power?.voltageMv), Format.current(tel.power?.currentMa))
                        .joinToString(" · ").ifEmpty { "—" })
                    val mv = battHistory.mapNotNull { it.mv }
                    if (mv.size >= 2) Sparkline(mv)
                    Muted("Tap for graph ›")
                }
                Tile("Sail angle", Format.degrees(tel.sail?.sailDeg), Modifier.weight(1f)) {
                    Muted(tel.sail?.windDeg?.let { "wind ${Format.degrees(it)}" +
                        (tel.sail?.confidencePct?.let { c -> " · $c% sure" } ?: "") } ?: "no wind yet")
                }
            }
            // Control-bottle conditions — tap for the full-screen graphs.
            WCard(Modifier.clickable(onClick = onBottle)) {
                val env = tel.environment
                Muted("Bottle")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Format.celsius(env?.airTempC) ?: "—", fontSize = 22.sp,
                        fontWeight = FontWeight.Bold, color = Wrangler.Dark)
                    Spacer(Modifier.size(12.dp))
                    Muted(listOfNotNull(env?.humidityPct?.let { "%.0f%% RH".format(it) },
                        env?.pressureHpa?.let { "%.0f hPa".format(it) }).joinToString(" · ")
                        .ifEmpty { if (env == null) "not reported by this firmware" else "—" })
                }
                val temps = envHistory.mapNotNull { it.airTempC?.let { t -> (t * 10).toInt() } }
                if (temps.size >= 2) Sparkline(temps)
                Muted("Tap for graph ›")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("GPS", Format.fixSummary(tel.position), Modifier.weight(1f).alpha(
                    if (Format.fixStale(tel.position)) 0.6f else 1f)) {}
                Tile("To target", Format.distance(nav?.distToTargetM), Modifier.weight(1f)) {
                    Muted(Format.navState(nav?.state) ?: "—")
                }
            }
            WCard(Modifier.alpha(if (Format.fixStale(tel.position)) 0.6f else 1f)) {
                CardLabel("Position")
                Text(Format.latLon(tel.position?.lat, tel.position?.lon) ?: "—",
                    color = Wrangler.Text, fontSize = 16.sp)
                tel.position?.fixAgeS?.let { Muted("fix ${it}s old") }
            }
        }
    }
}

@Composable
private fun Banner(text: String, fg: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color) {
    Text(
        text, color = fg, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().background(bg, RoundedCornerShape(10.dp)).padding(12.dp),
    )
}

@Composable
private fun Tile(label: String, value: String?, modifier: Modifier, extra: @Composable () -> Unit) {
    WCard(modifier) {
        Muted(label)
        Text(value ?: "—", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Wrangler.Dark)
        extra()
    }
}

/** Big heading with a compass dial: green needle = heading, dark dot = target bearing. */
@Composable
private fun HeadingCard(tel: TurtleTelemetry, liveBadgePink: Boolean) {
    val heading = tel.nav?.headingDeg
    val bearing = tel.nav?.bearingToTargetDeg
    WCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(84.dp)) {
                val r = size.minDimension / 2f - 4f
                val c = Offset(size.width / 2f, size.height / 2f)
                drawCircle(Wrangler.TextMuted, r, c, style = Stroke(2f))
                // North tick
                drawLine(Wrangler.Dark, Offset(c.x, c.y - r), Offset(c.x, c.y - r + 10f), 3f)
                fun at(deg: Double, len: Float): Offset {
                    val a = Math.toRadians(deg - 90.0)
                    return Offset(c.x + (len * cos(a)).toFloat(), c.y + (len * sin(a)).toFloat())
                }
                bearing?.let { drawCircle(Wrangler.Dark, 6f, at(it, r)) }
                heading?.let {
                    drawLine(Wrangler.Primary, c, at(it, r - 6f), 6f, cap = StrokeCap.Round)
                    drawCircle(Wrangler.Primary, 5f, c)
                }
            }
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(Format.heading(heading) ?: "—", fontSize = 34.sp,
                    fontWeight = FontWeight.Bold, color = Wrangler.Dark)
                Muted(bearing?.let { "target is ${Format.degrees(it)} · " +
                    (Format.turnText(Format.turn(heading, it)) ?: "") } ?: "no target bearing")
                Spacer(Modifier.height(6.dp))
                Text(
                    "● live",
                    color = if (liveBadgePink) Wrangler.PinkDark else Wrangler.Primary,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(if (liveBadgePink) Wrangler.PinkTint else Wrangler.Background,
                            RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** Battery charge trend for this connection. */
@Composable
private fun Sparkline(values: List<Int>) {
    Canvas(Modifier.fillMaxWidth().height(18.dp).padding(top = 4.dp)) {
        val lo = values.min().toFloat()
        val hi = maxOf(values.max().toFloat(), lo + 1f)
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = size.height - (v - lo) / (hi - lo) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Wrangler.Primary, style = Stroke(3f, cap = StrokeCap.Round))
    }
}
