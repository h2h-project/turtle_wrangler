package org.hopeturtles.wrangler.ui

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.hopeturtles.wrangler.Notice
import org.hopeturtles.wrangler.ble.LatLon
import org.hopeturtles.wrangler.ble.Op
import org.hopeturtles.wrangler.ble.Payloads
import org.hopeturtles.wrangler.ble.TurtleTelemetry
import org.hopeturtles.wrangler.ui.theme.Wrangler
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point

/** Free OpenStreetMap vector tiles, no API key. Attribution is in the style. */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

/** Markers: identity by entity, shape as well as colour (the target is a ring), legend under the map. */
private data class Mark(val fill: Color, val stroke: Color, val strokeDp: Float)
private val TURTLE_C = Mark(Wrangler.Primary, Color.White, 2f)
private val SET_C = Mark(Color.White, Wrangler.Dark, 4f)
private val MISSION_C = Mark(Wrangler.TextMuted, Color.White, 2f)

/**
 * Pick a destination on the map (Navigate → "Pick on map") → DEST_SET_COORDS.
 * Pan the map under the fixed crosshair; the point under it is the target.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapPickerScreen(
    tel: TurtleTelemetry, canCommand: Boolean, busy: String?, notice: Notice?,
    run: RunCommand, onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val here = tel.position?.takeIf { it.hasFix }?.let { LatLon(it.lat!!, it.lon!!) }
    val set = tel.targets?.set
    val mission = tel.targets?.mission

    var center by remember { mutableStateOf<LatLon?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var confirm by remember { mutableStateOf<LatLon?>(null) }

    val mapView = remember {
        MapLibre.getInstance(ctx)
        MapView(ctx).apply { onCreate(Bundle()) }
    }
    LifecycleForward(mapView)

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isRotateGesturesEnabled = false      // north stays up, like the compass
            m.uiSettings.isTiltGesturesEnabled = false
            val (start, zoom) = when {
                set != null -> set to 16.0
                here != null -> here to 16.0
                mission != null -> mission to 9.0
                else -> LatLon(20.0, 0.0) to 1.0
            }
            m.cameraPosition = CameraPosition.Builder().target(LatLng(start.lat, start.lon)).zoom(zoom).build()
            center = start
            m.addOnCameraMoveListener { m.cameraPosition.target?.let { center = LatLon(it.latitude, it.longitude) } }
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                dot(style, "mission", mission, MISSION_C)
                dot(style, "set", set, SET_C)
                dot(style, "turtle", here, TURTLE_C)
            }
            map = m
        }
    }
    // Keep the turtle dot following live Position updates.
    LaunchedEffect(map, here) {
        map?.style?.getSourceAs<GeoJsonSource>("turtle")?.let { src ->
            here?.let { src.setGeoJson(Point.fromLngLat(it.lon, it.lat)) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pick destination", fontWeight = FontWeight.Bold, color = Wrangler.Dark) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Navigate",
                            tint = Wrangler.Dark)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Wrangler.Surface),
            )
        },
        containerColor = Wrangler.Background,
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
                Crosshair(Modifier.align(Alignment.Center))
            }
            Column(
                Modifier.fillMaxWidth().background(Wrangler.Surface).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    JumpTo("Turtle", here, TURTLE_C, map, 16.0)
                    JumpTo("Your target", set, SET_C, map, 16.0)
                    JumpTo("Mission", mission, MISSION_C, map, 9.0)
                }
                Text(Format.latLon(center?.lat, center?.lon) ?: "—", fontWeight = FontWeight.Bold,
                    color = Wrangler.Dark, fontSize = 16.sp)
                Muted(Geo.distanceM(here, center)?.let { "${Format.distance(it)} from the turtle" }
                    ?: "Turtle position unknown (no GPS fix)")
                if (busy != null || notice != null) NoticeCard(notice, busy)
                PinkButton("Set destination here", { confirm = center },
                    enabled = canCommand && busy == null && center != null)
                Muted("Map tiles need internet on this phone. © OpenFreeMap © OpenMapTiles © OpenStreetMap contributors")
            }
        }
    }

    confirm?.let { p ->
        ConfirmDialog(
            title = "Set the destination here?",
            body = "The turtle will steer for ${Format.latLon(p.lat, p.lon)}" +
                (Geo.distanceM(here, p)?.let { ", ${Format.distance(it)} from where it is now" } ?: "") +
                ". This replaces your current target.",
            confirm = "Set destination",
            onConfirm = {
                val payload = Payloads.coordsE7(p.lat, p.lon) ?: return@ConfirmDialog
                run("Set destination", Op.DEST_SET_COORDS, payload) { r -> if (r.code.ok) onBack() }
            },
            onDismiss = { confirm = null },
        )
    }
}

/** One marker as its own source + layer (constant style, no expressions). */
private fun dot(style: Style, id: String, p: LatLon?, c: Mark) {
    val src = GeoJsonSource(id)
    p?.let { src.setGeoJson(Point.fromLngLat(it.lon, it.lat)) }
    style.addSource(src)
    style.addLayer(
        CircleLayer("$id-dot", id).withProperties(
            circleRadius(8f), circleColor(c.fill.toArgb()),
            circleStrokeWidth(c.strokeDp), circleStrokeColor(c.stroke.toArgb()),
        )
    )
}

/** Legend entry that also flies the map to that point. */
@Composable
private fun JumpTo(label: String, p: LatLon?, c: Mark, map: MapLibreMap?, zoom: Double) {
    TextButton(
        onClick = { map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p!!.lat, p.lon), zoom)) },
        enabled = p != null && map != null,
    ) {
        Box(
            Modifier.size(12.dp)
                .background(if (p != null && c.fill != Color.White) c.fill else Wrangler.Surface, CircleShape)
                .border(if (c.fill == Color.White) 3.dp else 0.dp,
                    if (p != null) c.stroke else Wrangler.CardBorder, CircleShape)
        )
        Text(" $label", color = if (p != null) Wrangler.Text else Wrangler.TextMuted, fontSize = 13.sp)
    }
}

@Composable
private fun Crosshair(modifier: Modifier) {
    Canvas(modifier.size(36.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.width / 2
        for (col in listOf(Color.White to 5.dp.toPx(), Wrangler.Dark to 2.dp.toPx())) {
            drawLine(col.first, Offset(c.x - r, c.y), Offset(c.x - 5.dp.toPx(), c.y), col.second)
            drawLine(col.first, Offset(c.x + 5.dp.toPx(), c.y), Offset(c.x + r, c.y), col.second)
            drawLine(col.first, Offset(c.x, c.y - r), Offset(c.x, c.y - 5.dp.toPx()), col.second)
            drawLine(col.first, Offset(c.x, c.y + 5.dp.toPx()), Offset(c.x, c.y + r), col.second)
        }
    }
}

/** MapView needs the host lifecycle forwarded to it. */
@Composable
private fun LifecycleForward(mapView: MapView) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, mapView) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            val st = owner.lifecycle.currentState
            if (st.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (st.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }
}
