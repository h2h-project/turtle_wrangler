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
import androidx.compose.runtime.LaunchedEffect
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
import org.hopeturtles.wrangler.ble.TargetSource
import org.hopeturtles.wrangler.ble.TurtleTelemetry
import org.hopeturtles.wrangler.data.JourneyRecord
import org.hopeturtles.wrangler.data.StampEntry
import org.hopeturtles.wrangler.ui.theme.LocalWranglerPalette
import org.hopeturtles.wrangler.ui.theme.Wrangler
import kotlin.math.abs
import kotlin.math.max

/** Sends a command; the callback sees the result after the generic notice is set. */
typealias RunCommand = (label: String, opcode: Int, payload: ByteArray, onResult: (CommandResult) -> Unit) -> Unit

private enum class Ask { SET_HERE, USE_MISSION, CLEAR, END }

/**
 * Navigate tab (app plan Phases 3 and 7b): the journey and its map, where the
 * turtle is going, and how far off the line it is. Every action is a contract
 * v1 command — the same code path as the OLED's Destination and Journey
 * screens. [setupOpen] (the "Start a journey" modal) is hoisted so it is
 * still open on return from the map picker.
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
    trail: List<LatLon>,
    journeys: List<JourneyRecord>,
    stamps: List<StampEntry>,
    onOpenMap: () -> Unit,
    onStartJourney: () -> Unit,
    onEndJourney: () -> Unit,
    onReviewJourney: (Long) -> Unit,
    setupOpen: Boolean,
    onSetupOpen: (Boolean) -> Unit,
) {
    var ask by remember { mutableStateOf<Ask?>(null) }
    val enabled = canCommand && busy == null
    val here = tel.position?.takeIf { it.hasFix }?.let { LatLon(it.lat!!, it.lon!!) }
    val journeyOpen = tel.status?.journeyActive == true
    // A journey opened elsewhere (the turtle's own screen) closes the modal.
    LaunchedEffect(journeyOpen) { if (journeyOpen) onSetupOpen(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NoticeCard(notice, busy)
        if (!live) Muted("Not live — values are the last ones received.")
        JourneyPanel(tel, enabled, trail, journeys, stamps, onOpenMap,
            onStart = { onSetupOpen(true) }, onEnd = { ask = Ask.END })
        ServoDial(tel.sail, tel.status?.servoPresent, canCommand, onServo)
        CourseCard(tel)
        DestinationCard(tel, here, enabled, onPickOnMap, onAsk = { ask = it })
        if (!canCommand) Muted("Commands are unavailable on this link (see Diagnostics).")
        JourneyLogCard(journeys, onReviewJourney)
    }

    // The modal first, so the confirmations below open over it.
    if (setupOpen && !journeyOpen) JourneySetupDialog(
        tel, enabled,
        onSetHere = { ask = Ask.SET_HERE },
        onPickOnMap = onPickOnMap,
        onUseMission = { ask = Ask.USE_MISSION },
        onStart = onStartJourney,
        onDismiss = { onSetupOpen(false) },
    )

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
                            // The OLED hands off to its Journey screen here; the app offers the modal.
                            if (!journeyOpen) onSetupOpen(true)
                        }
                    }
                },
                onDismiss = { ask = null },
            )
        }
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
        Ask.END -> ConfirmDialog(
            title = "End the journey?",
            body = "The turtle stamps its arrival point from its GPS. Without a fix the journey still ends, " +
                "but no arrival point is recorded.",
            confirm = "End journey",
            onConfirm = onEndJourney,
            onDismiss = { ask = null },
        )
        null -> {}
    }
}

// ------------------------------------------------------------------ course

@Composable
private fun CourseCard(tel: TurtleTelemetry) {
    val nav = tel.nav
    WCard {
        CardLabel("Course")
        Text(Format.distance(nav?.distToTargetM) ?: "—", fontSize = 30.sp, fontWeight = FontWeight.Bold,
            color = Wrangler.Ink)
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
        val pal = LocalWranglerPalette.current   // read here: a Canvas draws outside composition
        Canvas(Modifier.weight(1f).height(28.dp).padding(horizontal = 8.dp)) {
            val y = size.height / 2
            drawLine(pal.cardBorder, Offset(0f, y), Offset(size.width, y), 4.dp.toPx(), StrokeCap.Round)
            val cx = size.width / 2
            drawLine(pal.textMuted, Offset(cx, y - 8.dp.toPx()), Offset(cx, y + 8.dp.toPx()), 2.dp.toPx())
            if (xteM != null) {
                val x = cx + (xteM.toFloat() / range).coerceIn(-1f, 1f) * (size.width / 2)
                drawCircle(pal.primary, 7.dp.toPx(), Offset(x, y))
                drawCircle(pal.surface, 7.dp.toPx(), Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
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
            Text(label, fontWeight = FontWeight.Bold, color = Wrangler.Ink, fontSize = 14.sp,
                modifier = Modifier.weight(1f))
            if (active) Text("● Active", color = Wrangler.Primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Text(Format.latLon(p?.lat, p?.lon) ?: none,
            color = if (p != null) Wrangler.Text else Wrangler.TextMuted, fontSize = 14.sp)
        Geo.distanceM(here, p)?.let { Muted("${Format.distance(it)} from the turtle") }
    }
}
