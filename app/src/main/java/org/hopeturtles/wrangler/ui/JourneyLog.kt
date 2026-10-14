package org.hopeturtles.wrangler.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hopeturtles.wrangler.data.JourneyRecord
import org.hopeturtles.wrangler.data.StampEntry
import org.hopeturtles.wrangler.ui.theme.Brand
import org.hopeturtles.wrangler.ui.theme.Wrangler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "13 Oct 2026, 14:05", phone time zone. */
private fun dateTime(unixS: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(unixS * 1000))

private fun day(unixS: Long): String = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(unixS * 1000))

/** The end time, with the date only when it isn't the start's. */
private fun endTime(r: JourneyRecord, at: Long): String = if (day(at) == day(r.id)) clockHm(at) else dateTime(at)

/** "2 h 14 min", or "at least 1 h 2 min" when the end wasn't seen. */
fun journeyDuration(r: JourneyRecord): String = when {
    !r.ended -> "under way"
    r.durationS != null -> elapsed(r.durationS!!)
    else -> "at least ${elapsed(r.minDurationS)}"
}

// ------------------------------------------------------------------ the list

/**
 * Navigate's "Past journeys" card: the journeys this phone logged for the
 * turtle, newest first. Tap one to review its route.
 */
@Composable
fun JourneyLogCard(journeys: List<JourneyRecord>, onOpen: (Long) -> Unit) {
    val past = journeys.filter { it.ended }      // the open one is the Journey panel's
    var all by rememberSaveable { mutableStateOf(false) }
    WCard {
        CardLabel("Past journeys")
        if (past.isEmpty()) {
            Muted("None yet. Journeys appear here once they end, with their route.")
            return@WCard
        }
        Muted("From the turtle's own record and what this phone saw. Tap one to see its route.")
        Spacer(Modifier.height(6.dp))
        val shown = if (all) past else past.take(SHORT_LIST)
        shown.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(color = Wrangler.CardBorder)
            JourneyRow(r, onClick = { onOpen(r.id) })
        }
        if (past.size > SHORT_LIST) TextButton(onClick = { all = !all }) {
            Text(if (all) "Show fewer" else "Show all ${past.size}", color = Wrangler.PinkDark,
                fontWeight = FontWeight.Bold)
        }
    }
}

private const val SHORT_LIST = 5

@Composable
private fun JourneyRow(r: JourneyRecord, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(day(r.id), fontWeight = FontWeight.Bold, color = Wrangler.Ink, fontSize = 14.sp,
                modifier = Modifier.weight(1f))
            Text(journeyDuration(r), color = Wrangler.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("  ›", color = Wrangler.TextMuted, fontSize = 14.sp)
        }
        val times = clockHm(r.id) + " → " +
            (if (r.endSeen) endTime(r, r.endedAt!!) else "ended while out of range")
        val dist = Format.distance(r.plannedM)?.let { " · $it to destination" } ?: ""
        Muted(times + dist)
    }
}

// ------------------------------------------------------------------ review

/** "42 from the turtle · 130 seen by this phone" */
fun routeSources(r: JourneyRecord): String = listOfNotNull(
    r.turtlePoints.takeIf { it > 0 }?.let { "$it from the turtle" },
    r.phonePoints.takeIf { it > 0 }?.let { "$it seen by this phone" },
).joinToString(" · ").ifEmpty { "no points logged" }

/** A logged journey on the map: departure, destination, planned leg, the route seen and the arrival. */
fun journeyScene(r: JourneyRecord, stamps: List<StampEntry>): MapScene {
    val start = r.start
    return MapScene(
        set = r.destination, active = r.destination,
        departure = start, route = start != null && r.destination != null,
        trail = r.route,
        arrival = r.finish.takeIf { r.ended },
        stamps = stamps,
        fitTrail = true,
    )
}

/**
 * One past journey, full screen (opened from "Past journeys"): its route
 * from departure to arrival against the destination, with times, duration
 * and distances. Everything is the turtle's own GPS and clock, as this phone
 * logged it while connected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JourneyReviewScreen(r: JourneyRecord, stamps: List<StampEntry>, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val scene = journeyScene(r, stamps)
    var follow by remember { mutableStateOf(true) }
    var tapped by remember { mutableStateOf<StampEntry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Journey", fontWeight = FontWeight.Bold, color = Color.White)
                        Text(dateTime(r.id), color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Wrangler.Primary),
            )
        },
        containerColor = Wrangler.Background,
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Box(Modifier.weight(1f).heightIn(min = 240.dp).fillMaxWidth()) {
                TurtleMap(scene, Modifier.fillMaxSize(), interactive = true, follow = follow,
                    onUserMoved = { follow = false }, onStampTap = { tapped = it })
                if (scene.fitPoints.isEmpty()) Pill("No positions were logged for this journey", Brand.Dark,
                    Color.White.copy(alpha = 0.9f), Modifier.align(Alignment.TopStart).padding(10.dp))
                if (!follow) TextButton(
                    onClick = { follow = true },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                        .background(Color.White, RoundedCornerShape(50)),
                ) { Text("Recentre", color = Brand.Primary, fontWeight = FontWeight.Bold) }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().background(Wrangler.Surface)
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                tapped?.let { s ->
                    Text((if (s.sent) "Sent" else "Stamped") + " · " +
                        SimpleDateFormat("d MMM, HH:mm:ss", Locale.getDefault()).format(Date(s.atMs)),
                        fontWeight = FontWeight.Bold, color = Wrangler.Ink, fontSize = 14.sp)
                    Muted(Format.latLon(s.lat, s.lon) ?: "—")
                    Spacer(Modifier.height(6.dp))
                }
                Field("Started", dateTime(r.id))
                if (r.endSeen) {
                    Field("Ended", endTime(r, r.endedAt!!))
                    if (r.turtle) Muted("Times from the turtle's own record.")
                } else {
                    Field("Ended", "while out of range")
                    Muted("Some time between ${endTime(r, r.lastSeenAt)} (last seen under way) and " +
                        "${endTime(r, r.endedAt!!)} (seen ended).")
                }
                Field("Duration", journeyDuration(r))
                Spacer(Modifier.height(6.dp))
                Field("Departure", Format.latLon(r.start?.lat, r.start?.lon))
                when {
                    r.departureFromTurtle -> Muted("Stamped by the turtle from its GPS.")
                    r.departure != null -> Muted("The turtle's position when this phone started it.")
                    r.start != null -> Muted("Started on the turtle, so this is the first position logged.")
                }
                Field("Destination", Format.latLon(r.destination?.lat, r.destination?.lon))
                Field("Departure → destination", Format.distance(r.plannedM))
                Field("Distance travelled", Format.distance(r.travelledM.takeIf { r.track.isNotEmpty() }))
                Field(if (r.arrival != null) "Arrival from destination" else "Finished from destination",
                    Format.distance(r.missM))
                if (r.arrival != null && r.turtle) Muted("Arrival stamped by the turtle.")
                if (r.arrival == null && r.finish != null)
                    Muted(if (r.turtle) "The turtle had no GPS fix when it ended; the route ends at its last position."
                        else "No arrival point was logged; the route ends at the last position this phone saw.")
                Field("Route", routeSources(r))
                if (stamps.isNotEmpty()) Field("GPS stamps", "${stamps.size}")
                Spacer(Modifier.height(6.dp))
                Legend(scene, past = true)
                Muted("The route is the turtle's own GPS: points from the turtle's log (readings it hadn't sent " +
                    "to hopeturtles.org yet) and points this phone saw while connected. Gaps are drawn as " +
                    "straight lines. Times are the turtle's clock. $MAP_ATTRIBUTION")
            }
        }
    }
}
