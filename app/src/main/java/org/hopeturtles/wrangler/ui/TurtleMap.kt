package org.hopeturtles.wrangler.ui

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.hopeturtles.wrangler.ble.LatLon
import org.hopeturtles.wrangler.data.StampEntry
import org.hopeturtles.wrangler.ui.theme.Wrangler
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Free OpenStreetMap vector tiles, no API key. Attribution is in the style. */
const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

const val MAP_ATTRIBUTION = "© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors"

/**
 * Everything the live map can draw (app plan Phase 7b). Positions are the
 * TURTLE's, from its own GPS — never the phone's. A null or empty layer
 * isn't drawn.
 */
data class MapScene(
    val turtle: LatLon? = null,
    val headingDeg: Double? = null,
    /** The fix is old: the turtle is drawn faded. */
    val stale: Boolean = false,
    val fixAgeS: Int? = null,
    val set: LatLon? = null,
    val mission: LatLon? = null,
    /** The target the turtle is steering for (one of [set] / [mission]); ringed in pink. */
    val active: LatLon? = null,
    /** Line turtle → [active]. */
    val course: Boolean = false,
    /** Where the open journey left from, as this phone recorded it. */
    val departure: LatLon? = null,
    /** The journey's planned leg: [departure] → [active], dashed. */
    val route: Boolean = false,
    val trail: List<LatLon> = emptyList(),
    val stamps: List<StampEntry> = emptyList(),
) {
    /** What the camera keeps in view while it follows. */
    val fitPoints: List<LatLon>
        get() = listOfNotNull(turtle, active.takeIf { course || route }, departure.takeIf { route })
            .ifEmpty { listOfNotNull(set, mission) }
}

/** Marker styles: identity by entity, shape as well as colour (targets are rings). */
data class Mark(val fill: Color, val stroke: Color, val strokeDp: Float, val radiusDp: Float = 8f)

val TURTLE_C = Mark(Wrangler.Primary, Color.White, 2f)
val SET_C = Mark(Color.White, Wrangler.Dark, 4f)
val MISSION_C = Mark(Wrangler.TextMuted, Color.White, 2f)
val DEPARTURE_C = Mark(Wrangler.Pink, Color.White, 2.5f, 7f)
val STAMPED_C = Mark(Color.White, Wrangler.Fuchsia, 2.5f, 5f)
val SENT_C = Mark(Wrangler.Fuchsia, Color.White, 1.5f, 5f)
val COURSE_COLOR = Wrangler.PinkDark
val ROUTE_COLOR = Wrangler.Dark
val TRAIL_COLOR = Wrangler.Fuchsia

/**
 * A MapLibre map of [scene]. [follow]: keep the camera on [MapScene.fitPoints];
 * a hand pan or zoom calls [onUserMoved] (the caller turns follow off until
 * "Recentre"). [interactive] false: a still picture for a card (tap handling
 * is the caller's). [grey]: desaturated, for "no journey open". Texture mode,
 * so it scrolls inside a Column and the grey filter reaches it.
 */
@Composable
fun TurtleMap(
    scene: MapScene,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    follow: Boolean = true,
    grey: Boolean = false,
    onUserMoved: () -> Unit = {},
    onMap: (MapLibreMap) -> Unit = {},
    onStampTap: ((StampEntry) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var failed by remember { mutableStateOf(false) }

    val mapView = remember {
        MapLibre.getInstance(ctx)
        MapView(ctx, MapLibreMapOptions.createFromAttributes(ctx).textureMode(true)).apply { onCreate(Bundle()) }
    }
    LifecycleForward(mapView)

    // The latest scene for the click listener, which is registered once.
    var latest by remember { mutableStateOf(scene) }
    latest = scene

    LaunchedEffect(mapView) {
        mapView.addOnDidFailLoadingMapListener { failed = true }
        mapView.getMapAsync { m ->
            m.uiSettings.isRotateGesturesEnabled = false      // north stays up, like the compass
            m.uiSettings.isTiltGesturesEnabled = false
            if (!interactive) m.uiSettings.setAllGesturesEnabled(false)
            m.uiSettings.isAttributionEnabled = interactive
            m.uiSettings.isLogoEnabled = interactive
            m.setMaxZoomPreference(19.0)
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) onUserMoved()
            }
            if (onStampTap != null) m.addOnMapClickListener { at ->
                val px = m.projection.toScreenLocation(at)
                val hit = m.queryRenderedFeatures(px, "stamped-dot", "sent-dot").firstOrNull()
                val i = hit?.getNumberProperty("i")?.toInt()
                val s = i?.let { latest.stamps.getOrNull(it) }
                if (s != null) onStampTap(s)
                s != null
            }
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { st ->
                addLayers(st, density.density)
                failed = false
                style = st
            }
            map = m
            onMap(m)
        }
    }

    LaunchedEffect(style, scene) { style?.let { draw(it, scene) } }

    val fit = scene.fitPoints
    LaunchedEffect(map, follow, fit) {
        val m = map ?: return@LaunchedEffect
        if (!follow) return@LaunchedEffect
        val pad = with(density) { 48.dp.roundToPx() }
        val update = when (fit.size) {
            0 -> null
            1 -> CameraUpdateFactory.newLatLngZoom(LatLng(fit[0].lat, fit[0].lon), 16.0)
            else -> CameraUpdateFactory.newLatLngBounds(
                LatLngBounds.Builder().includes(fit.map { LatLng(it.lat, it.lon) }).build(), pad)
        }
        update?.let { if (interactive) m.animateCamera(it, 600) else m.moveCamera(it) }
    }

    Box(modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize().then(
                if (grey) Modifier.graphicsLayer { renderEffect = GREY_EFFECT } else Modifier),
        )
        if (failed) {
            Box(Modifier.fillMaxSize().background(Wrangler.Background), contentAlignment = Alignment.Center) {
                Text("Map needs data or WiFi on this phone", color = Wrangler.TextMuted,
                    fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

/** Full desaturation (ITU-R BT.601 luma), for the "no journey" map. */
private val GREY_EFFECT = RenderEffect.createColorFilterEffect(
    ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
).asComposeRenderEffect()

// ------------------------------------------------------------------ layers

private val SOURCES = listOf("trail", "route", "course", "mission", "set", "active", "departure",
    "stamped", "sent", "turtle")

/** Every source and layer, empty, bottom to top. [draw] fills them. */
private fun addLayers(style: Style, dp: Float) {
    SOURCES.forEach { style.addSource(GeoJsonSource(it)) }
    style.addImage("turtle-arrow", turtleIcon(dp, arrow = true))
    style.addImage("turtle-dot", turtleIcon(dp, arrow = false))

    style.addLayer(LineLayer("trail-line", "trail").withProperties(
        lineColor(TRAIL_COLOR.toArgb()), lineWidth(2.5f), lineCap(Property.LINE_CAP_ROUND),
        lineJoin(Property.LINE_JOIN_ROUND)))
    style.addLayer(LineLayer("route-line", "route").withProperties(
        lineColor(ROUTE_COLOR.toArgb()), lineWidth(2.5f), lineDasharray(arrayOf(2f, 2f))))
    style.addLayer(LineLayer("course-line", "course").withProperties(
        lineColor(COURSE_COLOR.toArgb()), lineWidth(3.5f), lineCap(Property.LINE_CAP_ROUND)))
    circle(style, "active", Mark(Color.Transparent, Wrangler.Pink, 4f, 14f))
    circle(style, "mission", MISSION_C)
    circle(style, "set", SET_C)
    circle(style, "departure", DEPARTURE_C)
    circle(style, "stamped", STAMPED_C)
    circle(style, "sent", SENT_C)
    style.addLayer(SymbolLayer("turtle-icon", "turtle").withProperties(
        iconImage("turtle-dot"), iconAllowOverlap(true), iconIgnorePlacement(true),
        iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP)))
}

/** One marker style as a circle layer (constant style, no expressions). */
private fun circle(style: Style, id: String, c: Mark) {
    style.addLayer(CircleLayer("$id-dot", id).withProperties(
        circleRadius(c.radiusDp), circleColor(c.fill.toArgb()),
        circleStrokeWidth(c.strokeDp), circleStrokeColor(c.stroke.toArgb())))
}

private fun draw(style: Style, s: MapScene) {
    fun pt(p: LatLon) = Point.fromLngLat(p.lon, p.lat)
    fun src(id: String) = style.getSourceAs<GeoJsonSource>(id)
    val none = FeatureCollection.fromFeatures(emptyArray<Feature>())
    fun point(id: String, p: LatLon?) = src(id)?.setGeoJson(
        p?.let { FeatureCollection.fromFeature(Feature.fromGeometry(pt(it))) } ?: none)
    fun line(id: String, ps: List<LatLon>) = src(id)?.setGeoJson(
        if (ps.size >= 2) FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(ps.map(::pt))))
        else none)

    point("turtle", s.turtle)
    (style.getLayer("turtle-icon") as? SymbolLayer)?.setProperties(
        iconImage(if (s.headingDeg != null) "turtle-arrow" else "turtle-dot"),
        iconRotate((s.headingDeg ?: 0.0).toFloat()),
        iconOpacity(if (s.stale) 0.45f else 1f))
    point("set", s.set)
    point("mission", s.mission)
    point("active", s.active)
    point("departure", s.departure)
    line("trail", s.trail + listOfNotNull(s.turtle))     // up to the marker
    line("course", if (s.course) listOfNotNull(s.turtle, s.active) else emptyList())
    line("route", if (s.route) listOfNotNull(s.departure, s.active) else emptyList())
    // Stamps carry their index in the scene's list, for the tap lookup.
    fun stamps(id: String, sent: Boolean) = src(id)?.setGeoJson(FeatureCollection.fromFeatures(
        s.stamps.mapIndexedNotNull { i, e ->
            if (e.sent != sent || e.lat == null || e.lon == null) null
            else Feature.fromGeometry(Point.fromLngLat(e.lon, e.lat)).apply { addNumberProperty("i", i) }
        }))
    stamps("stamped", false)
    stamps("sent", true)
}

/**
 * The turtle marker: a green disc, and with [arrow] a point ahead of it
 * (drawn north-up; the layer rotates it to the heading).
 */
private fun turtleIcon(dp: Float, arrow: Boolean): Bitmap {
    val size = (40 * dp).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val mid = size / 2f
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TURTLE_C.fill.toArgb() }
    val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2 * dp
        strokeJoin = Paint.Join.ROUND
    }
    if (arrow) {
        val p = android.graphics.Path().apply {
            moveTo(mid, 1.5f * dp)
            lineTo(mid + 7 * dp, mid - 4 * dp)
            lineTo(mid - 7 * dp, mid - 4 * dp)
            close()
        }
        c.drawPath(p, fill)
        c.drawPath(p, edge)
    }
    c.drawCircle(mid, mid, 9 * dp, fill)
    c.drawCircle(mid, mid, 9 * dp, edge)
    return bmp
}

/** MapView needs the host lifecycle forwarded to it. */
@Composable
fun LifecycleForward(mapView: MapView) {
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

/** A small rounded pill, e.g. the journey status over a map. */
@Composable
fun Pill(text: String, fg: Color, bg: Color, modifier: Modifier = Modifier) {
    Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp))
}
