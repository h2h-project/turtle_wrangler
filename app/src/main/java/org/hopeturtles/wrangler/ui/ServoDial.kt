package org.hopeturtles.wrangler.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hopeturtles.wrangler.ble.Sail
import org.hopeturtles.wrangler.ble.SweepState
import org.hopeturtles.wrangler.ui.theme.Wrangler
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** After a touch, show the finger's angle this long before trusting telemetry again. */
private const val LOCAL_MS = 3_000L

/**
 * Servo dial (top of Navigate). Slide a finger along the semicircle to
 * move the sail servo from one end stop (0°, left) to the other (180°,
 * right) — SERVO_SET_ANGLE, streamed latest-wins by the ViewModel. Only
 * touches on the arc band are taken, so the page still scrolls elsewhere.
 */
@Composable
fun ServoDial(sail: Sail?, servoPresent: Boolean?, enabled: Boolean, onSet: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    var local by remember { mutableStateOf<Int?>(null) }
    var localAt by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    val sweeping = sail?.sweep == SweepState.NAV_SWEEP || sail?.sweep == SweepState.BENCH_SWEEP
    val usable = enabled && servoPresent != false && !sweeping
    val canTouch by rememberUpdatedState(usable)
    val send by rememberUpdatedState(onSet)

    val recent = dragging || System.currentTimeMillis() - localAt < LOCAL_MS
    val shown: Double? = if (recent && local != null) local!!.toDouble() else sail?.servoPosDeg ?: local?.toDouble()

    WCard {
        CardLabel("Sail servo")
        Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.BottomCenter) {
            Canvas(
                Modifier.fillMaxWidth().aspectRatio(2.1f).pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val g = geometry(size.width.toFloat(), size.height.toFloat(), density)
                        if (!canTouch || !g.onBand(down.position)) return@awaitEachGesture
                        down.consume()
                        dragging = true
                        fun take(p: Offset) {
                            val d = g.angleAt(p)
                            if (d != local) {
                                if (local != null && d / 10 != local!! / 10) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                local = d
                                send(d)
                            }
                            localAt = System.currentTimeMillis()
                        }
                        take(down.position)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            c.consume()
                            take(c.position)
                        }
                        localAt = System.currentTimeMillis()
                        dragging = false
                    }
                },
            ) {
                val g = geometry(size.width, size.height, density)
                val stroke = g.band
                val arcTopLeft = Offset(g.cx - g.r, g.cy - g.r)
                val arcSize = Size(g.r * 2, g.r * 2)
                // Track, then the filled part from the 0° end to the position.
                drawArc(Wrangler.CardBorder, 180f, 180f, false, arcTopLeft, arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round))
                val v = shown?.toFloat()?.coerceIn(0f, 180f)
                val ink = if (usable) Wrangler.Primary else Wrangler.TextMuted
                if (v != null && v > 0f) {
                    drawArc(ink.copy(alpha = 0.35f), 180f, v, false, arcTopLeft, arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round))
                }
                // Ticks every 45°, just inside the track.
                for (t in 0..180 step 45) {
                    val a = Math.toRadians(180.0 + t)
                    val r1 = g.r - stroke / 2 - 4.dp.toPx()
                    val r2 = r1 - 6.dp.toPx()
                    drawLine(Wrangler.TextMuted,
                        Offset(g.cx + r1 * cos(a).toFloat(), g.cy + r1 * sin(a).toFloat()),
                        Offset(g.cx + r2 * cos(a).toFloat(), g.cy + r2 * sin(a).toFloat()), 1.5.dp.toPx())
                }
                // Knob.
                if (v != null) {
                    val a = Math.toRadians(180.0 + v)
                    val k = Offset(g.cx + g.r * cos(a).toFloat(), g.cy + g.r * sin(a).toFloat())
                    drawCircle(ink, stroke * 0.95f, k)
                    drawCircle(Wrangler.Surface, stroke * 0.95f, k, style = Stroke(2.5.dp.toPx()))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 2.dp)) {
                Text(shown?.let { "${it.roundToInt()}°" } ?: "—", fontSize = 34.sp, fontWeight = FontWeight.Bold,
                    color = Wrangler.Dark)
                Muted("of full travel")
            }
        }
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
            Muted("0°")
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Muted("180°")
        }
        Muted(when {
            servoPresent == false -> "This turtle has no servo fitted (servo_present is off)."
            sweeping -> "A luff sweep is moving the servo — wait for it to finish."
            !enabled -> "Commands are unavailable on this link."
            (sail?.manualHoldS ?: 0) > 0 -> "Hand control — the autopilot takes the sail back in ${sail!!.manualHoldS} s."
            sail?.servoPosDeg != null -> "The autopilot has the sail. Slide along the arc to take it by hand for 60 s."
            else -> "Slide along the arc to move the servo from one end stop to the other."
        })
        sail?.sailDeg?.let { Field("Sail angle (encoder)", Format.degrees(it)) }
    }
}

/** Arc geometry shared by drawing and touch: centre on the bottom edge. */
private class DialGeometry(val cx: Float, val cy: Float, val r: Float, val band: Float, val slop: Float) {
    /** Touches within the band (plus finger slop) and not far below the baseline. */
    fun onBand(p: Offset): Boolean {
        val d = hypot(p.x - cx, p.y - cy)
        return d in (r - band / 2 - slop)..(r + band / 2 + slop) && p.y <= cy + slop
    }

    /** 0 at the left end, 90 at the top, 180 at the right; below the baseline clamps to the nearer end. */
    fun angleAt(p: Offset): Int {
        val dy = p.y - cy
        val dx = p.x - cx
        if (dy >= 0) return if (dx < 0) 0 else 180
        val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 180.0
        return deg.roundToInt().coerceIn(0, 180)
    }
}

private fun geometry(w: Float, h: Float, density: Float): DialGeometry {
    val band = 14f * density
    val pad = band + 6f * density          // room for the knob at both ends and at the top
    val cy = h - pad
    val r = minOf(w / 2 - pad, cy - pad)
    return DialGeometry(w / 2, cy, r, band, 22f * density)
}
