package org.hopeturtles.wrangler.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hopeturtles.wrangler.ui.theme.Wrangler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/*
 * Shared time-series chart for the full-screen graphs (battery, bottle).
 * One series per chart, one y-axis — never two scales on one chart; use two
 * charts on the same time axis instead. 2 px line, recessive grid, labels in
 * text colours, a crosshair for the touched sample.
 */

/**
 * Tap or drag across a chart to select the nearest sample; [times] are the
 * samples' timestamps (sorted). Shared by every chart on a screen so their
 * crosshairs move together.
 */
fun Modifier.chartScrub(times: List<Long>, onSelect: (Int) -> Unit): Modifier {
    if (times.isEmpty()) return this
    val t0 = times.first()
    val t1 = times.last()
    fun pick(x: Float, width: Int) {
        val plotW = width - AXIS_W
        val f = ((x - AXIS_W) / plotW).coerceIn(0f, 1f)
        val target = t0 + (f * (t1 - t0)).toLong()
        onSelect(times.indices.minBy { abs(times[it] - target) })
    }
    return this
        .pointerInput(times.size) { detectTapGestures { pick(it.x, size.width) } }
        .pointerInput(times.size + 1) {
            detectDragGestures(onDragStart = { pick(it.x, size.width) }) { change, _ ->
                pick(change.position.x, size.width)
            }
        }
}

internal const val AXIS_W = 110f   // px reserved left of the plot for y labels
internal const val AXIS_H = 34f    // px reserved below the plot for time labels

/**
 * One single-series line chart: 2 px line, recessive grid, y labels left,
 * time labels below, optional zero baseline, crosshair at [selT].
 */
@Composable
fun LineChart(
    data: List<Pair<Long, Double?>>, t0: Long, t1: Long, selT: Long?,
    yLabel: (Double) -> String, zeroLine: Boolean, modifier: Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Wrangler.TextMuted, fontSize = 11.sp)
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val vals = data.mapNotNull { it.second }
    if (vals.isEmpty()) {
        Muted("No readings")
        return
    }
    var lo = vals.min(); var hi = vals.max()
    if (zeroLine) { lo = minOf(lo, 0.0); hi = maxOf(hi, 0.0) }
    if (hi - lo < 1e-6) { hi += 1.0; lo -= 1.0 }
    val pad = (hi - lo) * 0.08
    lo -= pad; hi += pad
    val span = (t1 - t0).coerceAtLeast(1L)

    Canvas(modifier.fillMaxWidth().height(170.dp).padding(top = 8.dp)) {
        val plotW = size.width - AXIS_W
        val plotH = size.height - AXIS_H
        fun x(t: Long) = AXIS_W + (t - t0).toFloat() / span * plotW
        fun y(v: Double) = ((hi - v) / (hi - lo) * plotH).toFloat()

        // Recessive grid + y labels (3 lines)
        for (k in 0..2) {
            val v = lo + (hi - lo) * k / 2
            val yy = y(v)
            drawLine(Wrangler.CardBorder, Offset(AXIS_W, yy), Offset(size.width, yy), 1f)
            label(measurer, yLabel(v), labelStyle, Offset(0f, yy - 16f))
        }
        if (zeroLine && lo < 0 && hi > 0) {
            drawLine(Wrangler.TextMuted, Offset(AXIS_W, y(0.0)), Offset(size.width, y(0.0)), 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
        // Time labels: start, middle, end
        listOf(t0, t0 + span / 2, t1).forEachIndexed { k, t ->
            val txt = fmt.format(Date(t))
            val w = measurer.measure(txt, labelStyle).size.width
            val xx = when (k) { 0 -> AXIS_W; 2 -> size.width - w; else -> x(t) - w / 2 }
            label(measurer, txt, labelStyle, Offset(xx, plotH + 8f))
        }
        // The series: 2 px line; breaks over missing readings.
        val path = Path()
        var pen = false
        data.forEach { (t, v) ->
            if (v == null) { pen = false; return@forEach }
            if (!pen) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v))
            pen = true
        }
        drawPath(path, Wrangler.Primary, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        // Crosshair
        selT?.let { st ->
            val xx = x(st)
            drawLine(Wrangler.Dark, Offset(xx, 0f), Offset(xx, plotH), 2f)
            data.firstOrNull { it.first == st }?.second?.let { v ->
                drawCircle(Wrangler.Surface, 7f, Offset(xx, y(v)))
                drawCircle(Wrangler.Primary, 5f, Offset(xx, y(v)))
            }
        }
    }
}

private fun DrawScope.label(
    m: androidx.compose.ui.text.TextMeasurer, text: String, style: TextStyle, at: Offset,
) {
    drawText(m.measure(text, style), topLeft = at)
}
