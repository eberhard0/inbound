package com.iameberhard.inbound

import android.content.Context
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
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
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

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
    DisposableEffect(Unit) {
        map.view.onResume()
        onDispose {
            map.view.onPause()
            map.view.onDetach()
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
                                    "${formatEta(left)} · ${leg.type.label} · along the track",
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
                "© OpenStreetMap contributors",
                color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            )
        }
    }
}

/** The osmdroid view plus the three things drawn on it: the track, the destination pin, the position dot. */
private class TripMap(context: Context) {

    val view: MapView
    private val track: Polyline
    private val destination: Marker
    private val me: Marker

    init {
        Configuration.getInstance().apply {
            userAgentValue = "Inbound/1.0 (personal Android train ETA app)"
            // Tile cache in the app's own cache dir: no storage permission, cleared with the app.
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
        view = MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            setMinZoomLevel(3.0)
            isVerticalMapRepetitionEnabled = false
            setScrollableAreaLimitLatitude(MapView.getTileSystem().maxLatitude, MapView.getTileSystem().minLatitude, 0)
            // The app is dark throughout; inverted standard tiles make a decent night map.
            overlayManager.tilesOverlay.setColorFilter(NIGHT)
            controller.setZoom(5.0)
        }
        val density = context.resources.displayMetrics.density
        track = Polyline(view).apply {
            outlinePaint.color = Accent.toArgb()
            outlinePaint.strokeWidth = 4 * density
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            setOnClickListener { _, _, _ -> false }
        }
        destination = Marker(view).apply {
            setIcon(ContextCompat.getDrawable(context, R.drawable.ic_pin))
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setInfoWindow(null)
            setOnMarkerClickListener { _, _ -> true }
            isEnabled = false
        }
        me = Marker(view).apply {
            setIcon(GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(android.graphics.Color.WHITE)
                setStroke((4 * density).toInt(), Accent.toArgb())
                setSize((22 * density).toInt(), (22 * density).toInt())
            })
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setInfoWindow(null)
            setOnMarkerClickListener { _, _ -> true }
            isEnabled = false
        }
        view.overlays.addAll(listOf(track, destination, me))
    }

    fun showRoute(route: RailRoute?) {
        track.setPoints(if (route == null) emptyList() else List(route.lat.size) { GeoPoint(route.lat[it], route.lon[it]) })
        destination.isEnabled = route != null
        if (route != null) destination.position = GeoPoint(route.lat.last(), route.lon.last())
        view.invalidate()
    }

    fun showPosition(position: Pair<Double, Double>?) {
        me.isEnabled = position != null
        if (position != null) me.position = GeoPoint(position.first, position.second)
        view.invalidate()
    }

    fun centreOn(position: Pair<Double, Double>?) {
        if (position == null) return
        whenLaidOut { view.controller.animateTo(GeoPoint(position.first, position.second), maxOf(view.zoomLevelDouble, 14.0), 600L) }
    }

    /** Everything still ahead: from the current position (or the start of the track) to the destination. */
    fun frameRoute(route: RailRoute, position: Pair<Double, Double>?) {
        val points = MutableList(route.lat.size) { GeoPoint(route.lat[it], route.lon[it]) }
        if (position != null) points += GeoPoint(position.first, position.second)
        val box = BoundingBox.fromGeoPoints(points)
        whenLaidOut {
            if (box.latitudeSpan < 1e-4 && box.longitudeSpanWithDateLine < 1e-4) centreOn(position) else view.zoomToBoundingBox(box.increaseByScale(1.4f), true)
        }
    }

    private fun whenLaidOut(block: () -> Unit) {
        if (view.isLayoutOccurred) block() else view.addOnFirstLayoutListener { _, _, _, _, _ -> block() }
    }
}

/**
 * Night palette for the standard (light-only) OSM tiles: invert the brightness, then turn the hue half a
 * circle so colours come back to where they were – sea stays blue, woods green – just dark. A plain invert
 * gives brown sea and purple land.
 */
private val NIGHT = ColorMatrixColorFilter(
    floatArrayOf(
        0.574f, -1.43f, -0.144f, 0f, 255f,
        -0.426f, -0.43f, -0.144f, 0f, 255f,
        -0.426f, -1.43f, 0.856f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    ),
)
