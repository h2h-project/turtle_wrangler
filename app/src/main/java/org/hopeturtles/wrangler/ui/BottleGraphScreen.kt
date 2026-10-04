package org.hopeturtles.wrangler.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hopeturtles.wrangler.EnvSample
import org.hopeturtles.wrangler.ble.Environment
import org.hopeturtles.wrangler.ui.theme.Wrangler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen control-bottle conditions (Dashboard → tap Bottle). The AHT21
 * air temperature is the headline; humidity is the leak early-warning.
 * Three single-series charts on one time axis, shared drag crosshair.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottleGraphScreen(
    name: String?, current: Environment?, samples: List<EnvSample>, live: Boolean, onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var sel by remember { mutableStateOf<Int?>(null) }
    val fmtT = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bottle · ${name ?: "Turtle"}", fontWeight = FontWeight.Bold, color = Wrangler.Dark) },
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
            if (current == null && samples.isEmpty()) {
                WCard {
                    CardLabel("No bottle readings")
                    Muted("This turtle's firmware doesn't report bottle conditions yet. " +
                        "Sync turtleOS 2.5 or newer (Environment characteristic 0118).")
                }
                return@Column
            }

            Summary(current, samples, live)

            if (samples.size < 2) {
                WCard {
                    CardLabel("Collecting…")
                    Muted("A reading arrives about every 5 s while connected. The graphs start with the second one.")
                }
                return@Column
            }

            val i = (sel ?: samples.lastIndex).coerceIn(0, samples.lastIndex)
            val s = samples[i]
            WCard {
                Muted(if (sel == null) "Latest reading" else "Reading at ${fmtT.format(Date(s.tMs))}")
                Text(
                    listOfNotNull(Format.celsius(s.airTempC), s.humidityPct?.let { "%.0f%% RH".format(it) },
                        s.pressureHpa?.let { "%.1f hPa".format(it) }).joinToString(" · ").ifEmpty { "—" },
                    fontWeight = FontWeight.Bold, color = Wrangler.Dark, fontSize = 16.sp,
                )
            }

            val t0 = samples.first().tMs
            val t1 = samples.last().tMs
            val selT = sel?.let { samples[it].tMs }
            val touch = Modifier.chartScrub(samples.map { it.tMs }) { sel = it }

            WCard {
                CardLabel("Air temperature (°C)")
                LineChart(samples.map { it.tMs to it.airTempC }, t0, t1, selT,
                    yLabel = { "%.1f".format(it) }, zeroLine = false, modifier = touch)
            }
            WCard {
                CardLabel("Humidity (% RH)")
                Muted("A steady rise in a sealed bottle can mean a leak")
                LineChart(samples.map { it.tMs to it.humidityPct }, t0, t1, selT,
                    yLabel = { "%.0f".format(it) }, zeroLine = false, modifier = touch)
            }
            WCard {
                CardLabel("Pressure (hPa)")
                LineChart(samples.map { it.tMs to it.pressureHpa }, t0, t1, selT,
                    yLabel = { "%.1f".format(it) }, zeroLine = false, modifier = touch)
            }
            Muted("${fmtT.format(Date(t0))} – ${fmtT.format(Date(t1))} · ${samples.size} readings · drag to inspect")
        }
    }
}

@Composable
private fun Summary(e: Environment?, samples: List<EnvSample>, live: Boolean) {
    WCard {
        Text(Format.celsius(e?.airTempC) ?: "—", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Wrangler.Dark)
        Muted("Air inside the bottle (AHT21)")
        Field("Humidity", e?.humidityPct?.let { "%.1f%% RH".format(it) })
        Field("Pressure", e?.pressureHpa?.let { "%.1f hPa".format(it) })
        Field("Barometer temperature", Format.celsius(e?.baroTempC))
        Field("Board temperature (RTC)", Format.celsius(e?.boardTempC))
        val temps = samples.mapNotNull { it.airTempC }
        if (temps.size >= 2) {
            Field("Change since connecting", "%+.1f °C".format(temps.last() - temps.first()))
            Field("Min / max", "${Format.celsius(temps.min())} / ${Format.celsius(temps.max())}")
        }
        val rh = samples.mapNotNull { it.humidityPct }
        if (rh.size >= 2) Field("Humidity change", "%+.1f%% RH".format(rh.last() - rh.first()))
        if (!live) Muted("Not live — showing what was recorded while connected.")
    }
}
