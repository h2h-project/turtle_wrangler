package org.hopeturtles.wrangler.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hopeturtles.wrangler.Notice
import org.hopeturtles.wrangler.ble.Op
import org.hopeturtles.wrangler.ble.Status
import org.hopeturtles.wrangler.ble.TelemetryMode
import org.hopeturtles.wrangler.ble.TurtleTelemetry
import org.hopeturtles.wrangler.data.StampEntry
import org.hopeturtles.wrangler.ui.theme.Wrangler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class GpsAsk { TO_MANUAL, MODE_OFF, GPS_OFF }

/** How many log entries the tab shows; the store keeps more. */
private const val LOG_SHOWN = 50

/**
 * GPS tab (app plan Phase 4): manual position stamps for field work,
 * replacing the OLED's hold-flow GPS logger. One tap = one GPS_STAMP; the
 * phone keeps a log of every stamp, grouped by journey.
 */
@Composable
fun GpsTab(
    tel: TurtleTelemetry,
    live: Boolean,
    canCommand: Boolean,
    busy: String?,
    notice: Notice?,
    log: List<StampEntry>,
    run: RunCommand,
    onStamp: () -> Unit,
    onSetClock: () -> Unit,
) {
    var ask by remember { mutableStateOf<GpsAsk?>(null) }
    val enabled = canCommand && busy == null
    val st = tel.status

    fun setMode(m: TelemetryMode, label: String) =
        run(label, Op.TELEMETRY_SET_MODE, byteArrayOf(m.ordinal.toByte())) {}

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NoticeCard(notice, busy)
        if (!live) Muted("Not live — values are the last ones received.")
        StampCard(tel, enabled, onStamp, onToManual = { ask = GpsAsk.TO_MANUAL }, onSetClock)
        ShoreCard(tel)
        LogCard(log)
        ModeCard(st, enabled, onMode = { m ->
            when (m) {
                TelemetryMode.OFF -> ask = GpsAsk.MODE_OFF
                TelemetryMode.AUTO -> setMode(m, "Logging: auto")
                else -> setMode(m, "Logging: manual")
            }
        })
        GpsModuleCard(st, enabled, onGps = { on ->
            if (on) run("GPS on", Op.GPS_SET_ENABLED, byteArrayOf(1)) {} else ask = GpsAsk.GPS_OFF
        })
        if (!canCommand) Muted("Commands are unavailable on this link (see Diagnostics).")
    }

    when (ask) {
        GpsAsk.TO_MANUAL -> ConfirmDialog(
            title = "Switch to manual logging?",
            body = "The turtle stops logging on its own timer and records a reading only when you stamp. " +
                "You can switch back to auto here.",
            confirm = "Switch to manual",
            onConfirm = { setMode(TelemetryMode.MANUAL, "Logging: manual") },
            onDismiss = { ask = null },
        )
        GpsAsk.MODE_OFF -> ConfirmDialog(
            title = "Turn logging off?",
            body = "The turtle stops recording readings, and stamps are refused, until logging is turned back on.",
            confirm = "Turn off",
            onConfirm = { setMode(TelemetryMode.OFF, "Logging: off") },
            onDismiss = { ask = null },
        )
        GpsAsk.GPS_OFF -> ConfirmDialog(
            title = "Turn the turtle's GPS off?",
            body = "The turtle steers by its GPS: with it off it can't navigate to its target, and stamps " +
                "carry no position.",
            confirm = "Turn GPS off",
            onConfirm = { run("GPS off", Op.GPS_SET_ENABLED, byteArrayOf(0)) {} },
            onDismiss = { ask = null },
        )
        null -> {}
    }
}

// ------------------------------------------------------------------- stamp

@Composable
private fun StampCard(
    tel: TurtleTelemetry, enabled: Boolean, onStamp: () -> Unit, onToManual: () -> Unit, onSetClock: () -> Unit,
) {
    val p = tel.position
    val st = tel.status
    val stale = Format.fixStale(p)
    WCard {
        CardLabel("Stamp the turtle's position")
        Text(
            Format.latLon(p?.lat, p?.lon) ?: "No fix",
            color = if (stale) Wrangler.TextMuted else Wrangler.Dark,
            fontWeight = FontWeight.Bold, fontSize = 18.sp,
        )
        Field("Satellites", p?.sats?.toString())
        Field("Fix age", p?.fixAgeS?.let { "$it s" })
        Muted("The turtle's own GPS — never this phone's location.")
        Spacer(Modifier.height(12.dp))
        if (st != null && !st.rtcSynced) {
            // A stamp needs a timestamp: without a set clock the turtle refuses it.
            Text("The turtle's clock isn't set, so it will refuse stamps.", color = Wrangler.PinkDark,
                fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            SetClockButton(enabled, onSetClock)
            Spacer(Modifier.height(12.dp))
        }
        when {
            st == null -> Muted("Waiting for the turtle's status…")
            st.telemetryMode == TelemetryMode.MANUAL -> {
                Button(
                    onClick = onStamp, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Wrangler.Primary, contentColor = Color.White,
                        disabledContainerColor = Wrangler.Light, disabledContentColor = Wrangler.Dark,
                    ),
                ) {
                    Text("Stamp position", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                }
                Spacer(Modifier.height(6.dp))
                Muted(when {
                    !st.gpsEnabled -> "GPS is off — stamps record sensor values and time only."
                    p?.hasFix != true -> "No fix — a stamp records sensor values and time only."
                    else -> "One tap, one stamp."
                })
                Field("Logged this boot", st.stampsSession?.toString())
            }
            else -> {
                Muted("The turtle is in ${modeWord(st.telemetryMode)} logging mode. Stamps need manual mode.")
                Spacer(Modifier.height(6.dp))
                GreenOutlineButton("Switch to manual to take stamps", onToManual, enabled = enabled)
            }
        }
    }
}

private fun modeWord(m: TelemetryMode) = when (m) {
    TelemetryMode.OFF -> "off"
    TelemetryMode.AUTO -> "auto"
    TelemetryMode.MANUAL -> "manual"
    TelemetryMode.UNKNOWN -> "an unknown"
}

// ------------------------------------------------------------------- shore

@Composable
private fun ShoreCard(tel: TurtleTelemetry) {
    val st = tel.status
    val q = st?.queueCount
    WCard {
        CardLabel("Shore delivery")
        Text(
            when (q) {
                null -> "—"
                0 -> "All sent"
                1 -> "1 reading waiting to send"
                else -> "$q readings waiting to send"
            },
            color = if (q == 0) Wrangler.Primary else Wrangler.Dark, fontWeight = FontWeight.Bold, fontSize = 16.sp,
        )
        Field("Last sent to hopeturtles.org", tel.shore?.lastShoreSync?.let { stampTime(it * 1000, true) }
            ?: if (tel.shore != null) "Never" else null)
        Field("WiFi", st?.let {
            when {
                it.wifiConnected -> "Connected"
                it.wifiEnabled -> "On, not connected"
                else -> "Off"
            }
        })
        Muted("Stamps wait on the turtle until it reaches WiFi, then show as Sent below.")
    }
}

// --------------------------------------------------------------------- log

@Composable
private fun LogCard(log: List<StampEntry>) {
    WCard {
        CardLabel("Stamp log")
        if (log.isEmpty()) {
            Muted("No stamps from this phone yet.")
            return@WCard
        }
        val shown = log.takeLast(LOG_SHOWN).reversed()
        var journey: Long? = -1
        shown.forEach { e ->
            if (e.journeyId != journey) {
                journey = e.journeyId
                Spacer(Modifier.height(8.dp))
                Text(
                    e.journeyId?.let { "Journey from ${stampTime(it * 1000, true)}" } ?: "No journey open",
                    color = Wrangler.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
            }
            StampRow(e)
        }
        if (log.size > LOG_SHOWN) Muted("Showing the latest $LOG_SHOWN of ${log.size}.")
    }
}

@Composable
private fun StampRow(e: StampEntry) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stampTime(e.atMs, false), color = Wrangler.TextMuted, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    !e.ok -> "Not stamped"
                    !e.hasPosition -> "Stamped — no position"
                    else -> "Stamped"
                } + (e.stampsSession?.let { " #$it" } ?: ""),
                color = if (e.ok) Wrangler.Dark else Wrangler.PinkDark,
                fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f),
            )
            if (e.ok) Text(
                if (e.sent) "Sent" else "Waiting",
                color = if (e.sent) Wrangler.Primary else Wrangler.TextMuted,
                fontWeight = FontWeight.Bold, fontSize = 12.sp,
            )
        }
        Muted(when {
            !e.ok -> e.reason ?: "—"
            e.lat != null && e.lon != null ->
                Format.latLon(e.lat, e.lon)!! + (e.sats?.let { " · $it sats" } ?: "")
            else -> "Sensor values and time only"
        })
    }
}

private fun stampTime(ms: Long, withDate: Boolean): String =
    SimpleDateFormat(if (withDate) "d MMM, HH:mm" else "HH:mm:ss", Locale.getDefault()).format(Date(ms))

// -------------------------------------------------------------------- mode

@Composable
private fun ModeCard(st: Status?, enabled: Boolean, onMode: (TelemetryMode) -> Unit) {
    WCard {
        CardLabel("Logging mode")
        if (st == null) {
            Muted("Waiting for the turtle's status…")
            return@WCard
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(TelemetryMode.OFF to "Off", TelemetryMode.AUTO to "Auto", TelemetryMode.MANUAL to "Manual")
                .forEach { (m, label) ->
                    val on = st.telemetryMode == m
                    OutlinedButton(
                        onClick = { if (!on) onMode(m) }, enabled = enabled || on,
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, if (on) Wrangler.Primary else Wrangler.Light),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (on) Wrangler.Primary else Color.Transparent,
                            disabledContainerColor = if (on) Wrangler.Primary else Color.Transparent,
                        ),
                    ) {
                        Text(label, color = if (on) Color.White else Wrangler.Dark, fontWeight = FontWeight.Bold)
                    }
                }
        }
        Spacer(Modifier.height(6.dp))
        Muted(when (st.telemetryMode) {
            TelemetryMode.OFF -> "The turtle records nothing."
            TelemetryMode.AUTO -> "The turtle logs a reading " + (st.intervalS?.let { "every ${interval(it)}" } ?: "on a timer") + "."
            TelemetryMode.MANUAL -> "The turtle records a reading only when you stamp."
            TelemetryMode.UNKNOWN -> "The turtle reports a mode this app doesn't know."
        })
    }
}

private fun interval(s: Int) = when {
    s % 3600 == 0 -> "${s / 3600} h"
    s % 60 == 0 -> "${s / 60} min"
    else -> "$s s"
}

// --------------------------------------------------------------------- gps

@Composable
private fun GpsModuleCard(st: Status?, enabled: Boolean, onGps: (Boolean) -> Unit) {
    WCard {
        CardLabel("GPS module")
        if (st == null) {
            Muted("Waiting for the turtle's status…")
            return@WCard
        }
        if (!st.gpsHwPresent) {
            Muted("No GPS module detected on the turtle.")
            return@WCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (st.gpsEnabled) "GPS on" else "GPS off", fontWeight = FontWeight.Bold,
                    color = Wrangler.Dark, fontSize = 15.sp)
                Muted(if (st.gpsFixed) "Has a fix" else "No fix")
            }
            Switch(
                checked = st.gpsEnabled, onCheckedChange = onGps, enabled = enabled,
                colors = SwitchDefaults.colors(checkedTrackColor = Wrangler.Primary),
            )
        }
    }
}
