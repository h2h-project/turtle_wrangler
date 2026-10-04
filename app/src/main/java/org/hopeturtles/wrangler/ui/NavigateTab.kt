package org.hopeturtles.wrangler.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hopeturtles.wrangler.Notice
import org.hopeturtles.wrangler.ble.CommandResult
import org.hopeturtles.wrangler.ble.LatLon
import org.hopeturtles.wrangler.ble.Op
import org.hopeturtles.wrangler.ble.Payloads
import org.hopeturtles.wrangler.ble.ResultCode
import org.hopeturtles.wrangler.ble.TargetSource
import org.hopeturtles.wrangler.ble.TurtleTelemetry
import org.hopeturtles.wrangler.ui.theme.Wrangler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Sends a command; the callback sees the result after the generic notice is set. */
typealias RunCommand = (label: String, opcode: Int, payload: ByteArray, onResult: (CommandResult) -> Unit) -> Unit

private enum class Ask { SET_HERE, USE_MISSION, CLEAR, START, END, OFFER_JOURNEY }

/**
 * Navigate tab (app plan Phase 3): where the turtle is going, the operator
 * journey, and how far off the line it is. Every action is a contract v1
 * command — the same code path as the OLED's Destination and Journey screens.
 */
@Composable
fun NavigateTab(
    tel: TurtleTelemetry,
    live: Boolean,
    canCommand: Boolean,
    busy: String?,
    notice: Notice?,
    run: RunCommand,
    say: (String, Boolean) -> Unit,
    onPickOnMap: () -> Unit,
    onServo: (Int) -> Unit,
) {
    var ask by remember { mutableStateOf<Ask?>(null) }
    val enabled = canCommand && busy == null
    val here = tel.position?.takeIf { it.hasFix }?.let { LatLon(it.lat!!, it.lon!!) }
    val journeyOpen = tel.status?.journeyActive == true

    fun startJourney() = run("Start journey", Op.JOURNEY_START, ByteArray(0)) { r ->
        when {
            r.code.ok -> say("Journey started" +
                (Payloads.journeyId(r.payload)?.let { " at " + clock(it) } ?: "") +
                ". Every reading is tagged until you end it.", true)
            r.code == ResultCode.WRONG_STATE -> say("A journey is already open on the turtle.", false)
            else -> say("Couldn't start the journey: ${r.message}", false)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ServoDial(tel.sail, tel.status?.servoPresent, canCommand, onServo)
        NoticeCard(notice, busy)
        if (!live) Muted("Not live — values are the last ones received.")
        CourseCard(tel)
        DestinationCard(tel, here, enabled, onPickOnMap, onAsk = { ask = it })
        JourneyCard(tel, journeyOpen, enabled, onAsk = { ask = it })
        if (!canCommand) Muted("Commands are unavailable on this link (see Diagnostics).")
    }

    when (ask) {
        Ask.SET_HERE -> {
            val p = tel.position
            val fix = when {
                p == null || !p.hasFix -> "The turtle has no GPS fix right now, so this will fail. " +
                    "Take it somewhere with open sky first."
                Format.fixStale(p) -> "Its last fix is ${p.fixAgeS} s old — it will use a fresh one if it can."
                else -> "It is at ${Format.latLon(p.lat, p.lon)} (${p.sats ?: "?"} satellites)."
            }
            ConfirmDialog(
                title = "Target = the turtle's position?",
                body = "This sets the destination to where the TURTLE is, using the turtle's own GPS — " +
                    "not this phone's location. $fix",
                confirm = "Set target here",
                onConfirm = {
                    run("Set target to turtle's position", Op.DEST_SET_HERE, ByteArray(0)) { r ->
                        if (r.code.ok) {
                            val at = Payloads.latLon(r.payload)
                            say("Target set to the turtle's position" +
                                (at?.let { " (${Format.latLon(it.lat, it.lon)})" } ?: "") + ".", true)
                            if (!journeyOpen) ask = Ask.OFFER_JOURNEY
                        }
                    }
                },
                onDismiss = { ask = null },
            )
        }
        Ask.OFFER_JOURNEY -> ConfirmDialog(
            title = "Start journey now?",
            body = "Stamp the turtle's departure point and tag every reading until you end the journey " +
                "— the same hand-off the turtle's Destination screen makes.",
            confirm = "Start journey",
            dismiss = "Not now",
            onConfirm = ::startJourney,
            onDismiss = { ask = null },
        )
        Ask.USE_MISSION -> ConfirmDialog(
            title = "Use the mission target?",
            body = "The turtle steers for the mission target " +
                "(${Format.latLon(tel.targets?.mission?.lat, tel.targets?.mission?.lon) ?: "—"}). " +
                "Your own target is replaced.",
            confirm = "Use mission target",
            onConfirm = { run("Use mission target", Op.DEST_SET_MISSION, ByteArray(0)) {} },
            onDismiss = { ask = null },
        )
        Ask.CLEAR -> ConfirmDialog(
            title = "Clear your target?",
            body = "The turtle forgets the target you set and falls back to its mission target, if it has one.",
            confirm = "Clear",
            onConfirm = { run("Clear target", Op.DEST_CLEAR, ByteArray(0)) {} },
            onDismiss = { ask = null },
        )
        Ask.START -> ConfirmDialog(
            title = "Start a journey?",
            body = "The turtle stamps its departure point from its GPS and tags every reading " +
                "(auto, manual and queued) with this journey until you end it.",
            confirm = "Start journey",
            onConfirm = ::startJourney,
            onDismiss = { ask = null },
        )
        Ask.END -> ConfirmDialog(
            title = "End the journey?",
            body = "The turtle stamps its arrival point from its GPS. Without a fix the journey still ends, " +
                "but no arrival point is recorded.",
            confirm = "End journey",
            onConfirm = {
                run("End journey", Op.JOURNEY_END, ByteArray(0)) { r ->
                    when {
                        r.code.ok && Payloads.arrivalStamped(r.payload) == false ->
                            say("Journey ended — arrival point not recorded (no GPS).", true)
                        r.code.ok -> say("Journey ended. Arrival point recorded.", true)
                        r.code == ResultCode.WRONG_STATE -> say("No journey is open on the turtle.", false)
                        else -> say("Couldn't end the journey: ${r.message}", false)
                    }
                }
            },
            onDismiss = { ask = null },
        )
        null -> {}
    }
}

private fun clock(unixS: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(unixS * 1000))

// ------------------------------------------------------------------ course

@Composable
private fun CourseCard(tel: TurtleTelemetry) {
    val nav = tel.nav
    WCard {
        CardLabel("Course")
        Text(Format.distance(nav?.distToTargetM) ?: "—", fontSize = 30.sp, fontWeight = FontWeight.Bold,
            color = Wrangler.Dark)
        Muted(if (nav?.distToTargetM != null) "to the active target" else "No distance — no target or no fix")
        Field("Bearing to target", Format.heading(nav?.bearingToTargetDeg))
        Field("Heading", Format.heading(nav?.headingDeg))
        Field("Turn", Format.turnText(Format.turn(nav?.headingDeg, nav?.bearingToTargetDeg)))
        Field("Navigation", Format.navState(nav?.state)?.let { s ->
            Format.fault(nav?.fault)?.let { "$s — $it" } ?: s })
        Spacer8()
        Text("Cross-track", color = Wrangler.TextMuted, fontSize = 13.sp)
        XteGauge(nav?.xteM)
        Text(
            Format.xte(nav?.xteM) ?: "— (the turtle doesn't compute cross-track yet)",
            color = if (nav?.xteM != null) Wrangler.Text else Wrangler.TextMuted, fontSize = 14.sp,
        )
    }
}

@Composable
private fun Spacer8() = androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))

/**
 * Left/right of the planned line, centred on zero. The scale grows with
 * the error (never below ±25 m) so a small drift doesn't pin the marker.
 */
@Composable
private fun XteGauge(xteM: Int?) {
    val range = max(25, niceCeil((abs(xteM ?: 0) * 1.25).toInt()))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("L", color = Wrangler.TextMuted, fontSize = 12.sp)
        Canvas(Modifier.weight(1f).height(28.dp).padding(horizontal = 8.dp)) {
            val y = size.height / 2
            drawLine(Wrangler.CardBorder, Offset(0f, y), Offset(size.width, y), 4.dp.toPx(), StrokeCap.Round)
            val cx = size.width / 2
            drawLine(Wrangler.TextMuted, Offset(cx, y - 8.dp.toPx()), Offset(cx, y + 8.dp.toPx()), 2.dp.toPx())
            if (xteM != null) {
                val x = cx + (xteM.toFloat() / range).coerceIn(-1f, 1f) * (size.width / 2)
                drawCircle(Wrangler.Primary, 7.dp.toPx(), Offset(x, y))
                drawCircle(Wrangler.Surface, 7.dp.toPx(), Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            }
        }
        Text("R", color = Wrangler.TextMuted, fontSize = 12.sp)
    }
    if (xteM != null) Muted("Scale ±$range m")
}

/** Smallest of 25, 50, 100, 250, 500, 1000, … that is ≥ [v]. */
private fun niceCeil(v: Int): Int {
    var base = 25
    while (true) {
        for (m in intArrayOf(1, 2, 4)) if (base * m >= v) return base * m
        base *= 10
    }
}

// ------------------------------------------------------------- destination

@Composable
private fun DestinationCard(
    tel: TurtleTelemetry, here: LatLon?, enabled: Boolean, onPickOnMap: () -> Unit, onAsk: (Ask) -> Unit,
) {
    val t = tel.targets
    val src = t?.activeSource
    WCard {
        CardLabel("Destination")
        if (t == null) {
            Muted("Waiting for the turtle's targets…")
        } else {
            TargetRow("Your target", t.set, here,
                active = src == TargetSource.SET_DESTINATION || src == TargetSource.SET_WAYPOINTS,
                none = "Not set")
            TargetRow("Mission target", t.mission, here,
                active = src == TargetSource.MISSION_DESTINATION || src == TargetSource.MISSION_WAYPOINTS,
                none = "None stored on the turtle")
            if (src == TargetSource.NONE) Muted("No active target — the turtle has nowhere to go.")
            if (src == TargetSource.SET_WAYPOINTS || src == TargetSource.MISSION_WAYPOINTS)
                Muted("Following a waypoint route; the point shown is its final target.")
        }
        Spacer8()
        PinkButton("Set to turtle's current position", { onAsk(Ask.SET_HERE) }, enabled = enabled)
        Muted("Uses the turtle's GPS, not this phone's.")
        Spacer8()
        GreenOutlineButton("Pick on map", onPickOnMap, enabled = enabled)
        GreenOutlineButton("Use mission target", { onAsk(Ask.USE_MISSION) },
            enabled = enabled && t?.mission != null)
        GreenOutlineButton("Clear your target", { onAsk(Ask.CLEAR) }, enabled = enabled && t?.set != null)
    }
}

@Composable
private fun TargetRow(label: String, p: LatLon?, here: LatLon?, active: Boolean, none: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontWeight = FontWeight.Bold, color = Wrangler.Dark, fontSize = 14.sp,
                modifier = Modifier.weight(1f))
            if (active) Text("● Active", color = Wrangler.Primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Text(Format.latLon(p?.lat, p?.lon) ?: none,
            color = if (p != null) Wrangler.Text else Wrangler.TextMuted, fontSize = 14.sp)
        Geo.distanceM(here, p)?.let { Muted("${Format.distance(it)} from the turtle") }
    }
}

// ------------------------------------------------------------------ journey

@Composable
private fun JourneyCard(tel: TurtleTelemetry, open: Boolean, enabled: Boolean, onAsk: (Ask) -> Unit) {
    WCard {
        CardLabel("Journey")
        if (tel.status == null) {
            Muted("Waiting for the turtle's status…")
            return@WCard
        }
        if (open) {
            Text("Trip in progress", fontWeight = FontWeight.Bold, color = Wrangler.Primary, fontSize = 16.sp)
            val id = tel.shore?.journeyId
            if (id != null) {
                val now = tel.shore?.deviceNow ?: (System.currentTimeMillis() / 1000)
                Field("Started", SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(id * 1000)))
                Field("Under way for", elapsed(now - id))
                Field("Journey ID", id.toString())
            }
            Spacer8()
            GreenOutlineButton("End journey", { onAsk(Ask.END) }, enabled = enabled)
        } else {
            Text("No journey open", fontWeight = FontWeight.Bold, color = Wrangler.Dark, fontSize = 16.sp)
            Muted("Starting one stamps the turtle's departure point and tags every reading until you end it.")
            Spacer8()
            GreenOutlineButton("Start journey", { onAsk(Ask.START) }, enabled = enabled)
        }
    }
}

private fun elapsed(s: Long): String = when {
    s < 0 -> "—"
    s < 3_600 -> "${s / 60} min"
    s < 86_400 -> "${s / 3_600} h ${(s % 3_600) / 60} min"
    else -> "${s / 86_400} d ${(s % 86_400) / 3_600} h"
}
