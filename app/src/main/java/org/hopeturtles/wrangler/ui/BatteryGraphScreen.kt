package org.hopeturtles.wrangler.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hopeturtles.wrangler.BattSample
import org.hopeturtles.wrangler.ui.theme.Wrangler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val FULL_MV = 4_200          // pack voltage treated as full (matches the firmware's SoC map)
private const val RATE_WINDOW_MS = 10 * 60_000L

/**
 * Full-screen charging graph (Dashboard → tap Battery). Two single-series
 * charts sharing one time axis — voltage, and current into the pack — never
 * one chart with two y-scales. Drag across either chart to read a sample.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryGraphScreen(name: String?, samples: List<BattSample>, live: Boolean, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var sel by remember { mutableStateOf<Int?>(null) }
    val fmtT = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Battery · ${name ?: "Turtle"}", fontWeight = FontWeight.Bold, color = Wrangler.Dark) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to dashboard",
                            tint = Wrangler.Dark)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Wrangler.Surface),
            )
        },
        containerColor = Wrangler.Background,
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Summary(samples, live)

            val pts = samples.filter { it.mv != null || it.ma != null }
            if (pts.size < 2) {
                WCard {
                    CardLabel("Collecting…")
                    Muted("A reading arrives about every 5 s while connected. The graph starts with the second one.")
                }
                return@Column
            }

            // Readout for the touched sample (or the latest).
            val i = (sel ?: pts.lastIndex).coerceIn(0, pts.lastIndex)
            val s = pts[i]
            WCard {
                Muted(if (sel == null) "Latest reading" else "Reading at ${fmtT.format(Date(s.tMs))}")
                Text(
                    listOfNotNull(Format.volts(s.mv), Format.chargeWord(Format.charge(s.ma)),
                        Format.current(s.ma), s.soc?.let { "$it%" }).joinToString(" · "),
                    fontWeight = FontWeight.Bold, color = Wrangler.Dark, fontSize = 16.sp,
                )
            }

            val t0 = pts.first().tMs
            val t1 = pts.last().tMs
            val touch = Modifier.chartScrub(pts.map { it.tMs }) { sel = it }

            WCard {
                CardLabel("Battery voltage (V)")
                LineChart(
                    pts.map { it.tMs to it.mv?.div(1000.0) }, t0, t1, sel?.let { pts[it].tMs },
                    yLabel = { "%.2f".format(it) }, zeroLine = false, modifier = touch,
                )
            }
            WCard {
                CardLabel("Current into the battery (mA)")
                Muted("Above zero = charging, below = discharging")
                LineChart(
                    // Contract: raw INA219 sign, negative = charging. Flip so charging plots upward.
                    pts.map { it.tMs to it.ma?.let { ma -> -ma.toDouble() } }, t0, t1, sel?.let { pts[it].tMs },
                    yLabel = { "${it.roundToInt()}" }, zeroLine = true, modifier = touch,
                )
            }
            Muted("${fmtT.format(Date(t0))} – ${fmtT.format(Date(t1))} · ${pts.size} readings · drag to inspect")
        }
    }
}

@Composable
private fun Summary(samples: List<BattSample>, live: Boolean) {
    val last = samples.lastOrNull()
    WCard {
        ChargeLine(last?.ma)
        Field("Charge", last?.soc?.let { "$it%" })
        Field("Voltage", Format.volts(last?.mv))
        Field("Current", Format.current(last?.ma))
        if (samples.size >= 2) {
            val first = samples.first()
            val mins = (samples.last().tMs - first.tMs) / 60_000.0
            Field("Watching for", "%.0f min".format(mins))
            if (first.mv != null && last?.mv != null) {
                Field("Change", "%+.3f V".format((last.mv - first.mv) / 1000.0))
            }
            // Rate over the last 10 minutes (or the whole session if shorter).
            val windowStart = samples.last().tMs - RATE_WINDOW_MS
            val w = samples.filter { it.tMs >= windowStart && it.mv != null }
            if (w.size >= 2) {
                val dtMin = (w.last().tMs - w.first().tMs) / 60_000.0
                if (dtMin > 0.5) {
                    val rate = (w.last().mv!! - w.first().mv!!) / dtMin      // mV per minute
                    Field("Rate (last 10 min)", "%+.1f mV/min".format(rate))
                    if (Format.charge(last?.ma) == Format.Charge.CHARGING && rate > 0.1 && last?.mv != null && last.mv < FULL_MV) {
                        val eta = (FULL_MV - last.mv) / rate
                        Field("To 4.20 V at this rate", if (eta > 120) "%.1f h".format(eta / 60) else "%.0f min".format(eta))
                    }
                }
            }
        }
        if (!live) Muted("Not live — showing what was recorded while connected.")
    }
}
