package org.hopeturtles.wrangler.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.hopeturtles.wrangler.ble.LatLon
import org.hopeturtles.wrangler.ble.TargetSource
import org.hopeturtles.wrangler.ble.Targets
import org.hopeturtles.wrangler.ble.TurtleTelemetry
import org.hopeturtles.wrangler.data.JourneyRecord
import org.hopeturtles.wrangler.data.StampEntry
import org.hopeturtles.wrangler.ui.theme.Brand
import org.hopeturtles.wrangler.ui.theme.Wrangler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "14:05", phone time zone. */
fun clockHm(unixS: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(unixS * 1000))

/** The target the turtle is steering for, from `0112` Targets' active source. */
fun activeTarget(t: Targets?): LatLon? = when (t?.activeSource) {
    TargetSource.SET_DESTINATION, TargetSource.SET_WAYPOINTS -> t.set
    TargetSource.MISSION_DESTINATION, TargetSource.MISSION_WAYPOINTS -> t.mission
    else -> null
}

private fun activeLabel(t: Targets?): String? = when (t?.activeSource) {
    TargetSource.SET_DESTINATION -> "your target"
    TargetSource.SET_WAYPOINTS -> "your route's final target"
    TargetSource.MISSION_DESTINATION -> "the mission target"
    TargetSource.MISSION_WAYPOINTS -> "the mission route's final target"
    else -> null
}

/** The open journey's id, or null when none is open (or not known yet). */
fun openJourney(tel: TurtleTelemetry): Long? =
    tel.shore?.journeyId.takeIf { tel.status?.journeyActive == true }

/** Only the turtle: where it is, its heading, and whether the fix is old. */
fun turtleScene(tel: TurtleTelemetry): MapScene = MapScene(
    turtle = turtleAt(tel), headingDeg = tel.nav?.headingDeg,
    stale = Format.fixStale(tel.position), fixAgeS = tel.position?.fixAgeS,
)

/** The turtle's position when it has a fix; no marker at all without one. */
fun turtleAt(tel: TurtleTelemetry): LatLon? =
    tel.position?.takeIf { it.hasFix }?.let { LatLon(it.lat!!, it.lon!!) }

/**
 * The full live-map scene: the turtle and its heading, both targets, the
 * course line, this connection's trail and the GPS stamps. While a journey
 * is open, also its departure and planned leg, and only its own stamps.
 */
fun liveScene(
    tel: TurtleTelemetry, trail: List<LatLon>, journeys: List<JourneyRecord>, stamps: List<StampEntry>,
): MapScene {
    val turtle = turtleAt(tel)
    val active = activeTarget(tel.targets)
    val jid = openJourney(tel)
    val dep = jid?.let { id -> journeys.find { it.id == id }?.departure }
    return turtleScene(tel).copy(
        set = tel.targets?.set, mission = tel.targets?.mission, active = active,
        course = turtle != null && active != null,
        departure = dep, route = dep != null && active != null,
        trail = trail,
        stamps = if (jid != null) stamps.filter { it.journeyId == jid } else stamps,
    )
}

// ------------------------------------------------------------------ the panel

/**
 * Navigate's Journey panel (app plan Phase 7b): the journey map with a status
 * pill. With no journey open the map is grey and shows only the turtle; once
 * one is live it is in colour with the departure, the destination, the leg
 * between them, the trail and the course line. Tap the map for the full map.
 */
@Composable
fun JourneyPanel(
    tel: TurtleTelemetry,
    enabled: Boolean,
    trail: List<LatLon>,
    journeys: List<JourneyRecord>,
    stamps: List<StampEntry>,
    onOpenMap: () -> Unit,
    onStart: () -> Unit,
    onEnd: () -> Unit,
) {
    val known = tel.status != null
    val jid = openJourney(tel)
    val live = jid != null
    val scene = if (live) liveScene(tel, trail, journeys, stamps)
        else turtleScene(tel)
    WCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Journey", fontWeight = FontWeight.Bold, color = Wrangler.Ink, fontSize = 15.sp,
                modifier = Modifier.weight(1f))
            when {
                !known -> Pill("Waiting…", Wrangler.TextMuted, Wrangler.CardBorder)
                live -> Pill("● Live", Color.White, Wrangler.Fuchsia)
                else -> Pill("No journey", Wrangler.TextMuted, Wrangler.CardBorder)
            }
        }
        Spacer(Modifier.height(10.dp))
        MapFrame(scene, grey = !live, onOpenMap, Modifier.height(240.dp))
        Spacer(Modifier.height(10.dp))

        if (!known) {
            Muted("Waiting for the turtle's status…")
            return@WCard
        }
        if (live) {
            val now = tel.shore?.deviceNow ?: (System.currentTimeMillis() / 1000)
            Field("Started", SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(jid!! * 1000)))
            Field("Under way for", elapsed(now - jid))
            Field("Departure", scene.departure?.let { Format.latLon(it.lat, it.lon) }
                ?: "stamped on the turtle")
            if (scene.departure == null)
                Muted("This journey wasn't started from this phone, so its departure point isn't on the map.")
            Field("Destination", scene.active?.let { Format.latLon(it.lat, it.lon) } ?: "none")
            activeLabel(tel.targets)?.let { Muted("Steering for $it.") }
            Field("To go", Format.distance(tel.nav?.distToTargetM))
            Spacer(Modifier.height(6.dp))
            Legend(scene)
            if (trail.isEmpty()) Muted("The trail starts when this phone connects, and moves with the turtle.")
            Spacer(Modifier.height(8.dp))
            GreenOutlineButton("End journey", onEnd, enabled = enabled)
        } else {
            Text("No journey open", fontWeight = FontWeight.Bold, color = Wrangler.Ink, fontSize = 16.sp)
            Muted("A journey runs from a departure to a destination. The turtle stamps its departure " +
                "point and tags every reading until you end it.")
            Spacer(Modifier.height(8.dp))
            PinkButton("Start journey", onStart, enabled = enabled)
        }
    }
}

/**
 * A map in a rounded frame; tapping it opens the full map. The overlays say
 * when there's no fix or the fix is old, so a blank or faded map isn't a mystery.
 */
@Composable
fun MapFrame(scene: MapScene, grey: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, Wrangler.CardBorder,
        RoundedCornerShape(10.dp))) {
        TurtleMap(scene, Modifier.fillMaxSize(), grey = grey)
        // On top of the MapView, so the tap is ours, not the map's.
        Box(Modifier.fillMaxSize().clickable(onClick = onOpen))
        val note = when {
            scene.turtle == null -> "No GPS fix — the turtle's position is unknown"
            scene.stale -> "fix ${scene.fixAgeS ?: "?"} s old"
            else -> null
        }
        note?.let { Pill(it, Brand.Dark, Color.White.copy(alpha = 0.9f), Modifier.align(Alignment.TopStart).padding(8.dp)) }
        Pill("Open map ›", Brand.Dark, Color.White.copy(alpha = 0.9f),
            Modifier.align(Alignment.BottomEnd).padding(8.dp))
    }
}

/**
 * Key to the journey map's marks: shape as well as colour. [past]: a logged
 * journey, where the target is its destination and the trail its route.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Legend(scene: MapScene, past: Boolean = false) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (scene.turtle != null) LegendDot("Turtle", TURTLE_C)
        if (scene.departure != null) LegendDot("Departure", DEPARTURE_C)
        if (scene.arrival != null) LegendDot("Arrival", ARRIVAL_C)
        if (scene.set != null) LegendDot(if (past) "Destination" else "Your target", SET_C)
        if (scene.mission != null) LegendDot("Mission", MISSION_C)
        if (scene.route) LegendLine("Planned leg", ROUTE_COLOR, dashed = true)
        if (scene.course) LegendLine("Course", COURSE_COLOR)
        if (scene.trail.isNotEmpty()) LegendLine(if (past) "Route" else "Trail", TRAIL_COLOR)
        if (scene.stamps.any { it.lat != null && !it.sent }) LegendDot("Stamped", STAMPED_C)
        if (scene.stamps.any { it.lat != null && it.sent }) LegendDot("Sent", SENT_C)
    }
}

@Composable
fun LegendDot(label: String, c: Mark) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(if (c.fill == Color.White) Wrangler.Surface else c.fill, CircleShape)
            .border(if (c.fill == Color.White) 3.dp else 0.dp, c.stroke, CircleShape))
        Text(" $label", color = Wrangler.Text, fontSize = 12.sp)
    }
}

@Composable
private fun LegendLine(label: String, color: Color, dashed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(18.dp).height(10.dp)) {
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 3.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(5f, 4f)) else null)
        }
        Text(" $label", color = Wrangler.Text, fontSize = 12.sp)
    }
}

fun elapsed(s: Long): String = when {
    s < 0 -> "—"
    s < 3_600 -> "${s / 60} min"
    s < 86_400 -> "${s / 3_600} h ${(s % 3_600) / 60} min"
    else -> "${s / 86_400} d ${(s % 86_400) / 3_600} h"
}

// ------------------------------------------------------------------ setup

/**
 * "Start journey" modal: check off the departure and the destination, then
 * start. The departure is always the turtle's own position when it starts
 * (JOURNEY_START takes no coordinates), so it's ready once the turtle has a
 * fresh fix. The destination is the turtle's active target; the three ways
 * to set one each ask first (the caller's ConfirmDialogs) or open the picker.
 */
@Composable
fun JourneySetupDialog(
    tel: TurtleTelemetry,
    enabled: Boolean,
    onSetHere: () -> Unit,
    onPickOnMap: () -> Unit,
    onUseMission: () -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = tel.position
    val depOk = p?.hasFix == true && !Format.fixStale(p)
    val target = activeTarget(tel.targets)
    val destOk = target != null
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(Wrangler.Surface, RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Start a journey", fontWeight = FontWeight.Bold, color = Wrangler.Ink, fontSize = 20.sp)
            Muted("Set where it leaves from and where it's going. Both must be ticked to start.")

            Step(1, "Departure", depOk)
            Muted("The turtle's own position when you start: it stamps it from its GPS, not this phone's.")
            Text(
                when {
                    p == null || !p.hasFix -> "No GPS fix yet — take the turtle somewhere with open sky."
                    !depOk -> "Its fix is ${p.fixAgeS} s old — waiting for a fresh one."
                    else -> "Now at ${Format.latLon(p.lat, p.lon)} (${p.sats ?: "?"} satellites)."
                },
                color = if (depOk) Wrangler.Text else Wrangler.PinkDark, fontSize = 14.sp,
            )
            ChoiceButton("Set to turtle's current position", checked = depOk, onClick = {}, enabled = false)

            Spacer(Modifier.height(4.dp))
            Step(2, "Destination", destOk)
            if (destOk) {
                Text("${Format.latLon(target!!.lat, target.lon)} — ${activeLabel(tel.targets)}",
                    color = Wrangler.Text, fontSize = 14.sp)
                Geo.distanceM(turtleAt(tel), target)?.let { Muted("${Format.distance(it)} from the turtle") }
                ChoiceButton("Destination set", checked = true, onClick = {}, enabled = false)
                Muted("To change it, use the Destination card on Navigate.")
            } else {
                Muted("The turtle has no target. Choose one:")
                ChoiceButton("Set to turtle's current position", checked = false, onClick = onSetHere,
                    enabled = enabled, pink = true)
                ChoiceButton("Pick on map", checked = false, onClick = onPickOnMap, enabled = enabled)
                ChoiceButton("Use mission target", checked = false, onClick = onUseMission,
                    enabled = enabled && tel.targets?.mission != null)
                if (tel.targets?.mission == null) Muted("No mission target is stored on the turtle.")
            }

            if (tel.status?.rtcSynced == false) {
                Text("The turtle's clock isn't set, so it can't date a journey. Set it from the GPS tab first.",
                    color = Wrangler.PinkDark, fontSize = 13.sp)
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = Wrangler.TextMuted) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onDismiss(); onStart() },
                    enabled = enabled && depOk && destOk,
                    colors = ButtonDefaults.buttonColors(containerColor = Wrangler.Primary),
                ) { Text("Start journey", fontWeight = FontWeight.Bold) }
            }
            if (!(depOk && destOk)) Muted("Start unlocks once both are ticked.")
        }
    }
}

@Composable
private fun Step(n: Int, title: String, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(24.dp).background(if (done) Wrangler.Primary else Wrangler.Surface, CircleShape)
                .border(2.dp, if (done) Wrangler.Primary else Wrangler.CardBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Filled.Check, contentDescription = "done", tint = Color.White,
                modifier = Modifier.size(16.dp))
            else Text("$n", color = Wrangler.TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text("  $title", fontWeight = FontWeight.Bold, color = Wrangler.Ink, fontSize = 16.sp)
    }
}

/** An option button; once [checked] it is greyed out with a tick. */
@Composable
private fun ChoiceButton(text: String, checked: Boolean, onClick: () -> Unit, enabled: Boolean, pink: Boolean = false) {
    val on = enabled && !checked
    OutlinedButton(
        onClick = onClick, enabled = on, modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, when {
            !on -> Wrangler.CardBorder
            pink -> Wrangler.Pink
            else -> Wrangler.Light
        }),
    ) {
        if (checked) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Wrangler.Primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontWeight = FontWeight.Bold, color = when {
            !on -> Wrangler.TextMuted
            pink -> Wrangler.PinkDark
            else -> Wrangler.Ink
        })
    }
}
