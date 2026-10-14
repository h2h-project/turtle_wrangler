package org.hopeturtles.wrangler.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hopeturtles.wrangler.ble.LatLon
import org.hopeturtles.wrangler.ble.TurtleTelemetry
import org.hopeturtles.wrangler.data.JourneyRecord
import org.hopeturtles.wrangler.data.StampEntry
import org.hopeturtles.wrangler.ui.theme.Brand
import org.hopeturtles.wrangler.ui.theme.Wrangler
import org.maplibre.android.maps.MapLibreMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen live map (app plan Phase 7b), opened from the map cards: the
 * same layers with room to pan and zoom. Panning by hand stops the camera
 * following the turtle until "Recentre". "Pick a destination here" hands the
 * map's centre to the picker, which still asks before sending.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveMapScreen(
    tel: TurtleTelemetry,
    trail: List<LatLon>,
    journeys: List<JourneyRecord>,
    stamps: List<StampEntry>,
    canPick: Boolean,
    onPick: (LatLon?) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val scene = liveScene(tel, trail, journeys, stamps)
    val live = openJourney(tel) != null
    var follow by remember { mutableStateOf(true) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var tapped by remember { mutableStateOf<StampEntry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Map", fontWeight = FontWeight.Bold, color = Color.White) },
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
            Box(Modifier.weight(1f).fillMaxWidth()) {
                TurtleMap(
                    scene, Modifier.fillMaxSize(), interactive = true, follow = follow,
                    onUserMoved = { follow = false }, onMap = { map = it }, onStampTap = { tapped = it },
                )
                Pill(if (live) "● Live journey" else "No journey",
                    if (live) Color.White else Wrangler.TextMuted,
                    if (live) Wrangler.Fuchsia else Wrangler.CardBorder,
                    Modifier.align(Alignment.TopEnd).padding(10.dp))
                val note = when {
                    scene.turtle == null -> "No GPS fix — the turtle's position is unknown"
                    scene.stale -> "fix ${scene.fixAgeS ?: "?"} s old"
                    else -> null
                }
                note?.let { Pill(it, Brand.Dark, Color.White.copy(alpha = 0.9f),
                    Modifier.align(Alignment.TopStart).padding(10.dp)) }
                if (!follow) TextButton(
                    onClick = { follow = true },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                        .background(Color.White, androidx.compose.foundation.shape.RoundedCornerShape(50)),
                ) { Text("Recentre", color = Brand.Primary, fontWeight = FontWeight.Bold) }
            }
            Column(
                Modifier.fillMaxWidth().background(Wrangler.Surface).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tapped?.let { s ->
                    Text((if (s.sent) "Sent" else "Stamped") + " · " +
                        SimpleDateFormat("d MMM, HH:mm:ss", Locale.getDefault()).format(Date(s.atMs)),
                        fontWeight = FontWeight.Bold, color = Wrangler.Ink, fontSize = 14.sp)
                    Muted(Format.latLon(s.lat, s.lon) ?: "—")
                }
                Legend(scene)
                Muted(when {
                    trail.isEmpty() -> "Trail: none yet. It starts when this phone connects."
                    else -> "Trail: this connection only (${trail.size} points)."
                })
                PinkButton("Pick a destination here", {
                    onPick(map?.cameraPosition?.target?.let { LatLon(it.latitude, it.longitude) })
                }, enabled = canPick)
                Muted("Positions are the turtle's own GPS, never this phone's. $MAP_ATTRIBUTION")
            }
        }
    }
}
