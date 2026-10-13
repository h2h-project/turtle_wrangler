package org.hopeturtles.wrangler.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
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
    onMap: () -> Unit,
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
            Banner("SAFE mode — " + (Format.fault(nav?.fault) ?: "turtle stopped steering"),
                Wrangler.PinkDark, Wrangler.PinkTint)
        }

        Column(Modifier.alpha(if (live) 1f else 0.5f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HeadingCard(tel)
            // Where the turtle is (its own GPS, never the phone's). Tap → Navigate.
            WCard {
                Muted("Map")
                Spacer(Modifier.height(6.dp))
                MapFrame(turtleScene(tel), grey = false, onOpen = onMap, Modifier.height(200.dp))
            }
            // Tap → full-screen charging graph.
            Tile("Battery", tel.power?.socPct?.let { "$it%" }, Modifier.clickable(onClick = onBattery)) {
                ChargeLine(tel.power?.currentMa)
                Muted(listOfNotNull(Format.volts(tel.power?.voltageMv), Format.current(tel.power?.currentMa))
                    .joinToString(" · ").ifEmpty { "—" })
                val mv = battHistory.mapNotNull { it.mv }
                if (mv.size >= 2) Sparkline(mv)
                Muted("Tap for graph ›")
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
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("Sail angle", Format.degrees(tel.sail?.sailDeg), Modifier.weight(1f).fillMaxHeight()) {
                    Muted(tel.sail?.windDeg?.let { "wind ${Format.degrees(it)}" +
                        (tel.sail?.confidencePct?.let { c -> " · $c% sure" } ?: "") } ?: "no wind yet")
                }
                WCard(Modifier.weight(1f).fillMaxHeight().alpha(if (Format.fixStale(tel.position)) 0.6f else 1f)) {
                    Muted("Position")
                    // Half width: one coordinate per line.
                    val ll = Format.latLon(tel.position?.lat, tel.position?.lon)?.split(", ")
                    Text(ll?.joinToString("\n") ?: "—", color = Wrangler.Text, fontSize = 15.sp,
                        fontWeight = FontWeight.Medium)
                    tel.position?.fixAgeS?.let { Muted("fix ${it}s old") }
                }
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

/**
 * Full-width compass dial with the heading and target line underneath: a
 * heavy ring with fine ticks round the outside (2°, longer every 10° and
 * 30°), a green needle for the heading, a grey bar through the centre for
 * the sail, and a triangle on the ring pointing in at the target bearing.
 * The triangle is pink, outline over tint.
 */
@Composable
private fun HeadingCard(tel: TurtleTelemetry) {
    val heading = tel.nav?.headingDeg
    val bearing = tel.nav?.bearingToTargetDeg
    val sail = tel.sail?.sailDeg
    val labels = rememberTextMeasurer()
    WCard {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val ringW = 10.dp.toPx()
            val tickOuter = size.minDimension / 2f - 2.dp.toPx()
            val tickGap = 3.dp.toPx()
            val r = tickOuter - 14.dp.toPx() - tickGap - ringW / 2f   // ring centreline
            fun at(deg: Double, len: Float): Offset {
                val a = Math.toRadians(deg - 90.0)
                return Offset(c.x + (len * cos(a)).toFloat(), c.y + (len * sin(a)).toFloat())
            }

            // Fine ticks outside the ring.
            for (d in 0 until 360 step 2) {
                val (len, w, ink) = when {
                    d % 30 == 0 -> Triple(14.dp, 2.5.dp, Wrangler.Dark)
                    d % 10 == 0 -> Triple(9.dp, 1.5.dp, Wrangler.Dark)
                    else -> Triple(5.dp, 1.dp, Wrangler.TextMuted)
                }
                drawLine(ink, at(d.toDouble(), tickOuter - len.toPx()), at(d.toDouble(), tickOuter),
                    w.toPx(), cap = StrokeCap.Butt)
            }
            drawCircle(Wrangler.Dark, r, c, style = Stroke(ringW))

            // Cardinal letters just inside the ring.
            val inside = r - ringW / 2f - 14.dp.toPx()
            listOf(0 to "N", 90 to "E", 180 to "S", 270 to "W").forEach { (d, s) ->
                val m = labels.measure(s, TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = if (d == 0) Wrangler.Dark else Wrangler.TextMuted))
                val p = at(d.toDouble(), inside)
                drawText(m, topLeft = Offset(p.x - m.size.width / 2f, p.y - m.size.height / 2f))
            }

            // Sail: a grey bar straddling the centre, at heading + sail angle.
            // Assumes encoder 0° is the sail along the hull (the contract
            // gives the AS5600 angle relative to the boat, zero unstated).
            if (heading != null && sail != null) {
                val len = r * 0.62f
                drawLine(Wrangler.TextMuted.copy(alpha = 0.75f), at(heading + sail, len),
                    at(heading + sail + 180.0, len), 6.dp.toPx(), cap = StrokeCap.Round)
            }

            heading?.let {
                drawLine(Wrangler.Primary, c, at(it, r - ringW / 2f - 30.dp.toPx()), 7.dp.toPx(),
                    cap = StrokeCap.Round)
                drawCircle(Wrangler.Primary, 8.dp.toPx(), c)
                drawCircle(Wrangler.Surface, 3.dp.toPx(), c)
            }

            // Target bearing: a triangle riding the ring, point inward.
            bearing?.let {
                val half = 11.dp.toPx()
                val a = Math.toRadians(it - 90.0)
                val side = Offset(-sin(a).toFloat(), cos(a).toFloat())   // along the ring
                val base = at(it, r + ringW / 2f + 2.dp.toPx())
                val tri = Path().apply {
                    val tip = at(it, r - ringW / 2f - 12.dp.toPx())
                    moveTo(tip.x, tip.y)
                    lineTo(base.x + side.x * half, base.y + side.y * half)
                    lineTo(base.x - side.x * half, base.y - side.y * half)
                    close()
                }
                drawPath(tri, Wrangler.PinkTint)
                drawPath(tri, Wrangler.PinkDark, style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round))
            }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Format.heading(heading) ?: "—", fontSize = 24.sp,
                fontWeight = FontWeight.Bold, color = Wrangler.Dark)
            Muted(bearing?.let { "target is ${Format.degrees(it)} · " +
                (Format.turnText(Format.turn(heading, it)) ?: "") } ?: "no target bearing")
        }
    }
}

/** Charge (battery) or temperature (bottle) trend for this connection, in the fuchsia accent. */
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
        drawPath(path, Wrangler.Fuchsia, style = Stroke(3f, cap = StrokeCap.Round))
    }
}
