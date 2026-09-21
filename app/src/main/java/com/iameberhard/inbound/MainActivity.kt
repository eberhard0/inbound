package com.iameberhard.inbound

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.delay
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal val Accent = Color(0xFF3DDC97)
internal val Track = Color(0xFF1C232B)
internal val Card = Color(0xFF12171D)
internal val Muted = Color(0xFF8A96A3)
internal val Warning = Color(0xFFFFB454)

private const val MPS_TO_KMH = 3.6f
private const val MPS_TO_MPH = 2.236936f
private const val METERS_PER_MILE = 1609.344

/** A fix older than this means the signal is gone (tunnel, deep inside a carriage). */
private const val FIX_TIMEOUT_MS = 5000L

class MainActivity : ComponentActivity() {

    private val viewModel: SpeedViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // A trip display that dims after 30 seconds is useless; the flag only applies while on screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Color.Black)) {
                App(viewModel)
            }
        }
    }
}

private fun hasFineLocation(context: Context) = ContextCompat.checkSelfPermission(
    context, Manifest.permission.ACCESS_FINE_LOCATION,
) == PackageManager.PERMISSION_GRANTED

@Composable
private fun App(viewModel: SpeedViewModel) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasFineLocation(context)) }
    var denied by remember { mutableStateOf(false) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    var showMap by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        denied = !granted
    }
    val ask = {
        launcher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    // The permission may have been granted in system settings while we were away.
    LifecycleResumeEffect(Unit) {
        granted = hasFineLocation(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        if (!granted && !askedOnce) {
            askedOnce = true
            ask()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
        Box(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp),
        ) {
            if (granted) {
                LifecycleStartEffect(Unit) {
                    viewModel.start()
                    onStopOrDispose { viewModel.stop() }
                }
                Speedometer(viewModel, onMap = { showMap = true })
            } else {
                PermissionScreen(
                    denied = denied,
                    onAsk = ask,
                    onOpenSettings = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    },
                )
            }
        }
        // Over the readout, edge to edge; the GPS feed started above keeps running underneath.
        if (showMap && granted) MapScreen(viewModel, onDismiss = { showMap = false })
    }
}

@Composable
private fun PermissionScreen(denied: Boolean, onAsk: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Inbound needs your precise location", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "Speed and your place on the track come from the GPS chip, which is only used while the app is " +
                "on screen. Your position leaves the phone once per journey: to look up the railway line " +
                "to your destination.",
            color = Muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAsk) { Text("Allow location") }
        if (denied) {
            Spacer(Modifier.height(8.dp))
            Text(
                "If no prompt appears, open settings and set Location to " +
                    "\"Allow only while using the app\" with \"Use precise location\" on.",
                color = Muted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onOpenSettings) { Text("Open app settings") }
        }
    }
}

@Composable
private fun Speedometer(viewModel: SpeedViewModel, onMap: () -> Unit) {
    val context = LocalContext.current
    // Ticks once a second so a lost signal is noticed even though no new fix arrives to tell us.
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            delay(1000)
            value = SystemClock.elapsedRealtime()
        }
    }

    var picking by rememberSaveable { mutableStateOf(false) }
    val pick = {
        viewModel.clearSearch()
        picking = true
    }

    val trip = viewModel.trip
    val mph = viewModel.useMph
    val factor = if (mph) MPS_TO_MPH else MPS_TO_KMH
    val unit = if (mph) "mph" else "km/h"
    val live = trip.lastFixMs != 0L && now - trip.lastFixMs < FIX_TIMEOUT_MS

    val speed = if (live) trip.speedMps * factor else null
    val distance = if (mph) trip.distanceM / METERS_PER_MILE else trip.distanceM / 1000.0
    val stats = listOf(
        "MAX" to "${(trip.maxMps * factor).roundToInt()} $unit",
        "AVERAGE" to "${(trip.averageMps * factor).roundToInt()} $unit",
        "DISTANCE" to String.format(Locale.getDefault(), if (distance < 100) "%.1f %s" else "%.0f %s", distance, if (mph) "mi" else "km"),
        "MOVING TIME" to formatDuration(trip.movingMs),
    )

    // GPS status sits under the unit: a headline plus a smaller detail line.
    val satellites = viewModel.satellites
    val inView = viewModel.satellitesInView
    val accuracy = trip.accuracyM
    val status = when {
        !viewModel.gpsEnabled -> GpsStatus("Location is off", null, Warning)
        live -> GpsStatus(
            if (accuracy != null) "GPS ±${accuracy.roundToInt()} m" else "GPS locked",
            "$satellites of $inView satellites",
            Accent,
        )
        trip.lastFixMs != 0L -> GpsStatus("GPS signal lost", "$satellites of $inView satellites", Warning)
        // The in-view count climbing is the sign that a fix is on its way.
        inView > 0 -> GpsStatus("Searching for GPS", "$inView satellites in view", Muted)
        else -> GpsStatus("Searching for GPS", "works best near a window", Muted)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val short = maxHeight < 480.dp
        Column(Modifier.fillMaxSize()) {
            if (!viewModel.gpsEnabled) {
                LocationOffRow(onEnableGps = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) })
            }
            if (wide) {
                // Unfolded / landscape: readout on the left, trip figures on the right.
                Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Readout(speed, unit, status, viewModel::toggleUnit, Modifier.weight(1f).fillMaxHeight())
                    Spacer(Modifier.width(16.dp))
                    Column(
                        Modifier.width(if (short) 340.dp else 300.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        EtaCard(viewModel, mph, now, pick, Modifier.fillMaxWidth())
                        if (short) {
                            StatGrid(stats, Modifier.fillMaxWidth())
                        } else {
                            stats.forEach { (label, value) -> StatCard(label, value, Modifier.fillMaxWidth()) }
                        }
                    }
                }
            } else {
                Readout(speed, unit, status, viewModel::toggleUnit, Modifier.weight(1f).fillMaxWidth())
                EtaCard(viewModel, mph, now, pick, Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                StatGrid(stats, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                UnitToggle(mph = mph, onToggle = viewModel::toggleUnit)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onMap) { Text("Map") }
                TextButton(onClick = viewModel::resetTrip) { Text("Reset trip") }
            }
        }
    }

    if (picking) DestinationDialog(viewModel, onDismiss = { picking = false })
}

/**
 * Time to go, arrival time and distance along the track. [tick] changes every second so the countdown
 * keeps running between fixes (and through tunnels).
 */
@Composable
private fun EtaCard(viewModel: SpeedViewModel, mph: Boolean, tick: Long, onPick: () -> Unit, modifier: Modifier = Modifier) {
    val journey = viewModel.journey
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Card)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (journey == null) {
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !viewModel.routing, onClick = onPick).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (viewModel.routing) "Finding the track…" else "Where are you going?",
                    color = Muted, fontSize = 15.sp, modifier = Modifier.weight(1f),
                )
                if (!viewModel.routing) Text("Set destination", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("TO", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Text(journey.destination.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onPick) { Text("Change") }
                TextButton(onClick = viewModel::endJourney) { Text("End") }
            }
            val followed = journey.leg(viewModel.trainType)
            val progress = viewModel.progress[followed.type]
            if (progress == null) {
                Text(if (viewModel.routing) "Finding the track…" else "Waiting for a GPS fix…", color = Muted, fontSize = 15.sp)
            } else {
                EtaFigures(followed, progress, viewModel.routing, mph, tick, viewModel::retryRoute)
                Spacer(Modifier.height(8.dp))
                TrainRows(journey, viewModel.progress, followed.type, tick, viewModel::selectTrain)
            }
        }
        // Still reachable after "End", so a finished ride can be exported.
        if (viewModel.logRows > 0) LogRow(viewModel)
    }
}

@Composable
private fun EtaFigures(leg: Leg, progress: Progress, routing: Boolean, mph: Boolean, tick: Long, onRetry: () -> Unit) {
    val context = LocalContext.current
    val nowMs = remember(tick) { System.currentTimeMillis() }
    val secondsLeft = ((progress.arrivalMs - nowMs) / 1000).coerceAtLeast(0L)

    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            if (progress.arrived) "Arrived" else formatEta(secondsLeft.toDouble()),
            style = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
            color = if (progress.arrived) Accent else Color.White,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (!progress.arrived) {
            Column(horizontalAlignment = Alignment.End) {
                Text("ARRIVE", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Text(
                    DateFormat.getTimeFormat(context).format(Date(maxOf(progress.arrivalMs, nowMs))),
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Track)) {
        Box(Modifier.fillMaxWidth(progress.fraction).fillMaxHeight().clip(RoundedCornerShape(50)).background(Accent))
    }
    Spacer(Modifier.height(6.dp))

    if (!progress.arrived) {
        Text(
            "${formatDistance(progress.remainingM, mph)} to go · at best ${formatEta(progress.bestCaseS)}",
            color = Muted, fontSize = 13.sp, maxLines = 1,
        )
    }
    when {
        routing -> Text("Re-planning the track…", color = Warning, fontSize = 13.sp)
        leg.route.source == RouteSource.STRAIGHT -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("No rail line found – straight-line estimate", color = Warning, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry) { Text("Retry") }
        }
        progress.offTrackM > SpeedViewModel.OFF_TRACK_M && !progress.arrived ->
            Text("Off the planned track – will re-plan from here", color = Warning, fontSize = 13.sp)
    }
}

/** Shows that the ride is being recorded, and saves the recording where it can be picked up for tuning. */
@Composable
private fun LogRow(viewModel: SpeedViewModel) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Ride log · ${viewModel.logRows} rows", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        TextButton(
            enabled = viewModel.logRows > 0,
            onClick = {
                val name = viewModel.exportLog()
                Toast.makeText(context, if (name != null) "Saved to Downloads: $name" else "Couldn't save the log", Toast.LENGTH_LONG).show()
            },
        ) { Text("Export") }
    }
}

/**
 * Every kind of train that could be running on this route, side by side. Tapping one says "that's mine":
 * it becomes the big figure above and the track the app follows.
 */
@Composable
private fun TrainRows(journey: Journey, progress: Map<TrainType, Progress>, selected: TrainType, tick: Long, onSelect: (TrainType) -> Unit) {
    val context = LocalContext.current
    val nowMs = remember(tick) { System.currentTimeMillis() }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        journey.legs.forEach { leg ->
            val p = progress[leg.type]
            if (p != null) {
                val mine = leg.type == selected
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (mine) Accent.copy(alpha = 0.16f) else Track)
                        .clickable { onSelect(leg.type) }
                        .padding(8.dp),
                ) {
                    Text(leg.type.label.uppercase(), color = if (mine) Accent else Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        if (p.arrived) "Arrived" else formatEtaShort(((p.arrivalMs - nowMs) / 1000).coerceAtLeast(0L).toDouble()),
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    )
                    if (!p.arrived) {
                        Text(
                            DateFormat.getTimeFormat(context).format(Date(maxOf(p.arrivalMs, nowMs))),
                            color = Muted, fontSize = 12.sp, maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DestinationDialog(viewModel: SpeedViewModel, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Destination", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    singleLine = true,
                    placeholder = { Text("Station or place") },
                    trailingIcon = {
                        if (viewModel.searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.search(query) }),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    viewModel.notice ?: "The track is planned from where you are now, so set it once you're on the train or at the station.",
                    color = if (viewModel.notice != null) Warning else Muted,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))

                val searched = viewModel.results.isNotEmpty()
                val list = if (searched) viewModel.results else viewModel.recent
                if (list.isNotEmpty()) {
                    Text(if (searched) "RESULTS" else "RECENT", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    list.forEach { destination ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { if (viewModel.setDestination(destination)) onDismiss() }
                                .padding(vertical = 10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    destination.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                                )
                                if (destination.station) {
                                    Spacer(Modifier.width(8.dp))
                                    Text("STATION", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            if (destination.detail.isNotEmpty()) {
                                Text(destination.detail, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

private class GpsStatus(val headline: String, val detail: String?, val color: Color)

@Composable
private fun LocationOffRow(onEnableGps: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(Warning))
        Spacer(Modifier.width(8.dp))
        Text("Location is turned off", color = Warning, fontSize = 13.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onEnableGps) { Text("Turn on") }
    }
}

/** Digital speed readout: LCD-style digits over dim "888" ghost segments, unit and GPS status below. */
@Composable
private fun Readout(
    speed: Float?,
    unit: String,
    status: GpsStatus,
    onToggleUnit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // Three monospace digits are about 1.8 em wide; the whole block about 1.9 em tall.
        val em: Dp = min(maxWidth / 2f, maxHeight / 1.9f)
        val density = LocalDensity.current
        val digitSize = with(density) { em.toSp() }
        val unitSize = with(density) { (em * 0.2f).toSp() }
        val statusSize = with(density) { (em * 0.13f).toSp() }
        val detailSize = with(density) { (em * 0.1f).toSp() }
        val digits = TextStyle(
            fontSize = digitSize,
            lineHeight = digitSize,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )

        Column(
            // Tapping the readout switches km/h <-> mph, same as the toggle below.
            Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggleUnit,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                Text("888", color = Track, style = digits, maxLines = 1)
                Text(
                    // Right-aligned over the ghost digits, like a real display; dashes while there is no fix.
                    text = (speed?.roundToInt()?.toString() ?: "--").padStart(3),
                    color = if (speed != null) Color.White else Muted,
                    style = digits,
                    maxLines = 1,
                )
            }
            Text(unit, color = Accent, style = TextStyle(fontSize = unitSize, fontWeight = FontWeight.Medium))
            Spacer(Modifier.height(em * 0.08f))
            Text(
                "● ${status.headline}",
                color = status.color,
                style = TextStyle(fontSize = statusSize, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            if (status.detail != null) {
                Text(status.detail, color = Muted, style = TextStyle(fontSize = detailSize), maxLines = 1)
            }
        }
    }
}

@Composable
private fun StatGrid(stats: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        stats.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { (label, value) -> StatCard(label, value, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Card)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun UnitToggle(mph: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Card)
            .padding(4.dp),
    ) {
        listOf("km/h" to false, "mph" to true).forEach { (label, isMph) ->
            val selected = mph == isMph
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Accent else Color.Transparent)
                    .clickable(enabled = !selected, onClick = onToggle)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            ) {
                Text(label, color = if (selected) Color.Black else Muted, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }
    }
}

internal fun formatEta(seconds: Double): String {
    val minutes = (seconds / 60).roundToInt()
    return when {
        minutes < 1 -> "< 1 min"
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}

/** Same, squeezed for the narrow per-train cells. */
private fun formatEtaShort(seconds: Double): String {
    val minutes = (seconds / 60).roundToInt()
    return when {
        minutes < 1 -> "< 1 min"
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}

internal fun formatDistance(metres: Double, mph: Boolean): String {
    val value = if (mph) metres / METERS_PER_MILE else metres / 1000.0
    return String.format(Locale.getDefault(), if (value < 100) "%.1f %s" else "%.0f %s", value, if (mph) "mi" else "km")
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return String.format(Locale.US, "%d:%02d:%02d", totalSeconds / 3600, (totalSeconds / 60) % 60, totalSeconds % 60)
}
