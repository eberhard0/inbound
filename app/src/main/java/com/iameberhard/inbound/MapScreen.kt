package com.iameberhard.inbound

import android.content.Context
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
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
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.Date

/**
 * Full-screen map: where the phone is right now, the planned track, the destination, and what is left.
 * It is drawn over the readout inside the activity (a Dialog window gets the edge-to-edge insets wrong and
 * cuts the bottom buttons off), so the same on-screen-only GPS feed keeps it up to date.
 */
@Composable
internal fun MapScreen(viewModel: SpeedViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val mph = viewModel.useMph
    // Once a second, so the time left keeps counting down between fixes.
    val tick by produceState(0L) {
        while (true) {
            delay(1000)
            value++
        }
    }
    BackHandler(onBack = onDismiss)
    val journey = viewModel.journey
    val leg = journey?.leg(viewModel.trainType)
    val progress = leg?.let { viewModel.progress[it.type] }
    val here = viewModel.position

    val map = remember { TripMap(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        // Adding the observer replays start/resume, so the map comes up in step with the activity.
        lifecycle.addObserver(map.lifecycleObserver)
        onDispose {
            lifecycle.removeObserver(map.lifecycleObserver)
            map.destroy()
        }
    }
    LaunchedEffect(leg?.route) { map.showRoute(leg?.route) }
    LaunchedEffect(here) { map.showPosition(here) }
    // Opening view: the whole remaining journey if there is one, otherwise just where we are.
    LaunchedEffect(Unit) { if (leg != null) map.frameRoute(leg.route, here) else map.centreOn(here) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { map.view }, modifier = Modifier.fillMaxSize())
        // The map runs edge to edge; the controls keep clear of the status bar, cut-out and gesture area.
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {

            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .widthIn(max = 420.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Card.copy(alpha = 0.94f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    when {
                        journey == null -> Text("No destination set", color = Muted, fontSize = 15.sp)
                        progress == null -> {
                            Text(journey.destination.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Waiting for a GPS fix…", color = Muted, fontSize = 13.sp)
                        }
                        else -> {
                            val nowMs = remember(tick) { System.currentTimeMillis() }
                            Text("TO ${journey.destination.name}".uppercase(), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (progress.arrived) "Arrived" else "${formatDistance(progress.remainingM, mph)} to go",
                                color = if (progress.arrived) Accent else Color.White,
                                style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                                maxLines = 1,
                            )
                            if (!progress.arrived) {
                                val left = ((progress.arrivalMs - nowMs) / 1000).coerceAtLeast(0L).toDouble()
                                Text(
                                    "Arrive ${DateFormat.getTimeFormat(context).format(Date(maxOf(progress.arrivalMs, nowMs)))} · ${formatEta(left)}",
                                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                )
                                Text(
                                    "${leg.type.label} estimate · distance along the track",
                                    color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                Button(onClick = onDismiss) { Text("Close") }
            }

            Row(
                Modifier.align(Alignment.BottomEnd).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (leg != null) Button(onClick = { map.frameRoute(leg.route, viewModel.position) }) { Text("Whole route") }
                Button(onClick = { map.centreOn(viewModel.position) }, enabled = here != null) { Text("My location") }
            }
            Text(
                "© OpenFreeMap · © OpenStreetMap contributors",
                color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            )
        }
    }
}

/**
 * The MapLibre view plus the three things drawn on it: the track, the destination pin, the position dot.
 * Vector tiles come from OpenFreeMap (no key); the bundled style labels everything in English.
 *
 * The map and its style load asynchronously, so what should be shown is kept here and (re)drawn whenever
 * the style becomes ready.
 */
private class TripMap(context: Context) {

    val view: MapView

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var route: RailRoute? = null
    private var position: Pair<Double, Double>? = null
    /** A camera move asked for before the map was ready. */
    private var pendingCamera: (() -> Unit)? = null

    private val padding = (72 * context.resources.displayMetrics.density).toInt()
    private var started = false
    private var resumed = false
    private var destroyed = false

    val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> start()
            Lifecycle.Event.ON_RESUME -> resume()
            Lifecycle.Event.ON_PAUSE -> pause()
            Lifecycle.Event.ON_STOP -> stop()
            else -> Unit
        }
    }

    init {
        MapLibre.getInstance(context)
        view = MapView(context)
        view.onCreate(null)
        view.getMapAsync { ready ->
            map = ready
            ready.uiSettings.apply {
                setLogoEnabled(false)
                setAttributionEnabled(false) // credited in our own corner text instead
                setCompassEnabled(false)
                setRotateGesturesEnabled(false)
                setTiltGesturesEnabled(false)
            }
            val json = context.assets.open("map_dark.json").bufferedReader().use { it.readText() }
            ready.setStyle(Style.Builder().fromJson(json)) { loaded ->
                loaded.addSource(GeoJsonSource(ROUTE))
                loaded.addLayer(
                    LineLayer(ROUTE, ROUTE).withProperties(
                        lineColor(Accent.toArgb()), lineWidth(4f),
                        lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                ContextCompat.getDrawable(context, R.drawable.ic_pin)?.let { loaded.addImage(PIN, it) }
                loaded.addSource(GeoJsonSource(PIN))
                loaded.addLayer(
                    SymbolLayer(PIN, PIN).withProperties(
                        iconImage(PIN), iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                        iconAllowOverlap(true), iconIgnorePlacement(true),
                    ),
                )
                loaded.addSource(GeoJsonSource(ME))
                loaded.addLayer(
                    CircleLayer(ME, ME).withProperties(
                        circleRadius(8f), circleColor(android.graphics.Color.WHITE),
                        circleStrokeWidth(3.5f), circleStrokeColor(Accent.toArgb()),
                    ),
                )
                style = loaded
                draw()
                pendingCamera?.invoke()
                pendingCamera = null
            }
        }
    }

    fun showRoute(route: RailRoute?) {
        this.route = route
        draw()
    }

    fun showPosition(position: Pair<Double, Double>?) {
        this.position = position
        draw()
    }

    fun centreOn(position: Pair<Double, Double>?) {
        if (position == null) return
        whenReady { map ->
            val zoom = maxOf(map.cameraPosition.zoom, 12.5)
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(position.first, position.second), zoom), 600)
        }
    }

    /** Everything still ahead: from the current position (or the start of the track) to the destination. */
    fun frameRoute(route: RailRoute, position: Pair<Double, Double>?) {
        whenReady { map ->
            val points = MutableList(route.lat.size) { LatLng(route.lat[it], route.lon[it]) }
            if (position != null) points += LatLng(position.first, position.second)
            try {
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), padding), 600)
            } catch (_: RuntimeException) {
                // All points in one spot (already there): no box to fit.
                centreOn(position ?: (route.lat.last() to route.lon.last()))
            }
        }
    }

    fun destroy() {
        pause()
        stop()
        if (!destroyed) {
            destroyed = true
            style = null
            map = null
            view.onDestroy()
        }
    }

    private fun start() {
        if (!started && !destroyed) { started = true; view.onStart() }
    }

    private fun resume() {
        if (!resumed && !destroyed) { resumed = true; view.onResume() }
    }

    private fun pause() {
        if (resumed) { resumed = false; view.onPause() }
    }

    private fun stop() {
        if (started) { started = false; view.onStop() }
    }

    private fun whenReady(action: (MapLibreMap) -> Unit) {
        val ready = map
        if (ready != null && style != null) action(ready) else pendingCamera = { map?.let(action) }
    }

    private fun draw() {
        val loaded = style ?: return
        val track = route
        loaded.getSourceAs<GeoJsonSource>(ROUTE)?.setGeoJson(
            if (track == null) NOTHING
            else FeatureCollection.fromFeature(
                Feature.fromGeometry(LineString.fromLngLats(List(track.lat.size) { Point.fromLngLat(track.lon[it], track.lat[it]) })),
            ),
        )
        loaded.getSourceAs<GeoJsonSource>(PIN)?.setGeoJson(
            if (track == null) NOTHING
            else FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(track.lon.last(), track.lat.last()))),
        )
        val here = position
        loaded.getSourceAs<GeoJsonSource>(ME)?.setGeoJson(
            if (here == null) NOTHING else FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(here.second, here.first))),
        )
    }

    private companion object {
        const val ROUTE = "trip-route"
        const val PIN = "trip-pin"
        const val ME = "trip-me"
        val NOTHING: FeatureCollection = FeatureCollection.fromFeatures(emptyList<Feature>())
    }
}
