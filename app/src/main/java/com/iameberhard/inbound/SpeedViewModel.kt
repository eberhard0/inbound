package com.iameberhard.inbound

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.location.GnssStatusCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the screen shows. Speeds are m/s, converted to km/h or mph only for display. */
data class Trip(
    /** SystemClock.elapsedRealtime() of the newest fix; 0 until the first one arrives. */
    val lastFixMs: Long = 0L,
    val speedMps: Float = 0f,
    val accuracyM: Float? = null,
    val maxMps: Float = 0f,
    val distanceM: Double = 0.0,
    val movingMs: Long = 0L,
) {
    val averageMps: Float
        get() = if (movingMs > 0L) (distanceM / (movingMs / 1000.0)).toFloat() else 0f
}

/**
 * Reads speed straight from the GPS chip (Location.getSpeed() is Doppler-derived, so it stays
 * accurate at train speeds where position-difference estimates get noisy). Updates only run
 * between start() and stop(), i.e. while the app is on screen — nothing runs in the background.
 *
 * It also owns the journey: every fix is snapped onto the planned track to refresh the ETA.
 */
class SpeedViewModel(app: Application) : AndroidViewModel(app), LocationListener {

    private val locationManager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val prefs = app.getSharedPreferences("inbound", Context.MODE_PRIVATE)

    var trip by mutableStateOf(Trip())
        private set
    var useMph by mutableStateOf(prefs.getBoolean(KEY_MPH, false))
        private set
    var gpsEnabled by mutableStateOf(true)
        private set
    /** Satellites used for the current position. */
    var satellites by mutableIntStateOf(0)
        private set
    /** Satellites whose signal is being received at all; climbs while still searching for a fix. */
    var satellitesInView by mutableIntStateOf(0)
        private set

    private val store = JourneyStore(app)

    var journey by mutableStateOf(store.load())
        private set
    /** One entry per train type; empty until the first fix after a destination was set. */
    var progress by mutableStateOf<Map<TrainType, Progress>>(emptyMap())
        private set
    /** The train the user says they're on: its estimate is the big one, and its track is the one we follow. */
    var trainType by mutableStateOf(store.trainType)
        private set
    var results by mutableStateOf<List<Destination>>(emptyList())
        private set
    var recent by mutableStateOf(store.recent)
        private set
    var searching by mutableStateOf(false)
        private set
    var routing by mutableStateOf(false)
        private set
    /** One-line feedback for the destination picker and the ETA card. */
    var notice by mutableStateOf<String?>(null)
        private set

    private var searchJob: Job? = null
    private var routeJob: Job? = null
    private var offTrackSinceMs = 0L
    private var lastRerouteMs = 0L

    private var last: Location? = null
    private var running = false

    private val gnssCallback = object : GnssStatusCompat.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatusCompat) {
            var used = 0
            var inView = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) used++
                // The list also holds satellites known only from the almanac; count those actually heard.
                if (status.getCn0DbHz(i) > 0f) inView++
            }
            satellites = used
            satellitesInView = maxOf(inView, used)
        }
    }

    fun toggleUnit() {
        useMph = !useMph
        prefs.edit().putBoolean(KEY_MPH, useMph).apply()
    }

    fun resetTrip() {
        trip = trip.copy(maxMps = 0f, distanceM = 0.0, movingMs = 0L)
    }

    fun search(query: String) {
        val text = query.trim()
        if (text.isEmpty()) return
        notice = null
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val me = coroutineContext[Job]
            searching = true
            try {
                results = withContext(Dispatchers.IO) { RailRouter.search(text) }
                if (results.isEmpty()) notice = "Nothing found for \"$text\"."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = "Search failed – check the connection."
            } finally {
                // A newer search may already be running; only the latest one owns the spinner.
                if (searchJob == me) searching = false
            }
        }
    }

    fun selectTrain(type: TrainType) {
        trainType = type
        store.trainType = type
        offTrackSinceMs = 0L
    }

    fun clearSearch() {
        searchJob?.cancel()
        results = emptyList()
        notice = null
    }

    /** Plans the track from where the phone is right now. False when there is no position to start from yet. */
    fun setDestination(destination: Destination): Boolean {
        val here = currentPosition()
        if (here == null) {
            notice = "No GPS fix yet – try again in a moment."
            return false
        }
        store.remember(destination)
        recent = store.recent
        results = emptyList()
        notice = null
        progress = emptyMap()
        plan(destination, here, carryOver = false, keepOldOnFailure = false)
        return true
    }

    /** Asks the router again, e.g. after a straight-line fallback while offline. */
    fun retryRoute() {
        val current = journey ?: return
        val here = currentPosition() ?: return
        plan(current.destination, here, carryOver = true, keepOldOnFailure = true)
    }

    fun endJourney() {
        routeJob?.cancel()
        routing = false
        journey = null
        progress = emptyMap()
        notice = null
        store.save(null)
    }

    private fun plan(destination: Destination, from: Location, carryOver: Boolean, keepOldOnFailure: Boolean) {
        routeJob?.cancel()
        routeJob = viewModelScope.launch {
            val me = coroutineContext[Job]
            routing = true
            try {
                val planned = withContext(Dispatchers.IO) { RailRouter.route(from.latitude, from.longitude, destination) }
                val old = journey
                if (keepOldOnFailure && planned.fast.source == RouteSource.STRAIGHT && old != null) {
                    // A track that may be the wrong one still beats swapping it for a straight line.
                    return@launch
                }
                // What the ride has taught us so far stays valid on the new track; only the clock restarts.
                val before = if (carryOver) old else null
                val learned = if (before != null) progress else emptyMap()
                val clockRunning = before != null && before.clockStartMs != 0L
                val fresh = Journey(
                    destination = destination,
                    legs = Journey.legs(planned.fast, planned.conventional).map { (type, route) ->
                        Leg(
                            type = type,
                            route = route,
                            initialM = before?.legs?.firstOrNull { it.type == type }?.initialM ?: route.totalM,
                            clockStartFfS = if (clockRunning) route.totalFfS else 0.0,
                            carriedElapsedS = learned[type]?.elapsedS ?: 0.0,
                            carriedFfS = learned[type]?.coveredFfS ?: 0.0,
                        )
                    },
                    clockStartMs = if (clockRunning) System.currentTimeMillis() else 0L,
                )
                journey = fresh
                store.save(fresh)
                offTrackSinceMs = 0L
                (last ?: from).let(::updateJourney)
            } finally {
                if (routeJob == me) routing = false
            }
        }
    }

    @SuppressLint("MissingPermission") // only reached once the permission screen is behind us
    private fun currentPosition(): Location? = last ?: runCatching {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { locationManager.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
    }.getOrNull()

    private fun updateJourney(location: Location) {
        var current = journey ?: return
        val now = System.currentTimeMillis()
        val positions = current.legs.associate { it.type to it.route.locate(location.latitude, location.longitude) }

        // Waiting on the platform must not count as a slow train: the clock starts with the first real movement.
        if (current.clockStartMs == 0L && location.hasSpeed() && location.speed >= CLOCK_START_MPS) {
            current = current.copy(
                clockStartMs = now,
                legs = current.legs.map { it.copy(clockStartFfS = positions.getValue(it.type).remainingFfS) },
            )
            journey = current
            store.save(current)
        }

        progress = current.legs.associate { it.type to it.progress(positions.getValue(it.type), now, current.clockStartMs) }

        // Well away from the track of the train we're supposedly on, for a while: it's a different line,
        // so plan again from here.
        val followed = current.leg(trainType)
        val update = progress.getValue(followed.type)
        val accurate = !location.hasAccuracy() || location.accuracy <= 100f
        if (followed.route.source != RouteSource.STRAIGHT && accurate && !update.arrived && update.offTrackM > OFF_TRACK_M) {
            if (offTrackSinceMs == 0L) offTrackSinceMs = now
            if (!routing && now - offTrackSinceMs > OFF_TRACK_PATIENCE_MS && now - lastRerouteMs > REROUTE_INTERVAL_MS) {
                lastRerouteMs = now
                plan(current.destination, location, carryOver = true, keepOldOnFailure = true)
            }
        } else {
            offTrackSinceMs = 0L
        }
    }

    @SuppressLint("MissingPermission") // checked explicitly below
    fun start() {
        if (running) return
        val context = getApplication<Application>()
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        try {
            gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper(),
            )
            LocationManagerCompat.registerGnssStatusCallback(
                locationManager, ContextCompat.getMainExecutor(context), gnssCallback,
            )
            running = true
        } catch (e: RuntimeException) {
            // No GPS provider on this device, or the permission was revoked mid-call.
            gpsEnabled = false
        }
    }

    fun stop() {
        if (!running) return
        locationManager.removeUpdates(this)
        LocationManagerCompat.unregisterGnssStatusCallback(locationManager, gnssCallback)
        satellites = 0
        satellitesInView = 0
        running = false
    }

    override fun onCleared() = stop()

    override fun onLocationChanged(location: Location) {
        val nowMs = location.elapsedRealtimeNanos / 1_000_000L
        val prev = last
        val previous = trip
        val dtS = if (prev != null) (nowMs - prev.elapsedRealtimeNanos / 1_000_000L) / 1000.0 else 0.0

        var speed = when {
            location.hasSpeed() -> location.speed
            prev != null && dtS > 0.2 -> (prev.distanceTo(location) / dtS).toFloat()
            else -> 0f
        }
        // Below walking pace the reading is mostly GPS jitter; show a steady 0 when standing still.
        if (speed < MIN_MOVING_MPS) speed = 0f

        var distance = previous.distanceM
        var moving = previous.movingMs
        if (prev != null && dtS > 0.0) {
            if (dtS <= MAX_STEP_S) {
                // Normal 1 Hz stream: integrate speed, which is smoother than chaining positions.
                val mean = (speed + previous.speedMps) / 2.0
                if (mean > 0.0) {
                    distance += mean * dtS
                    moving += (dtS * 1000).toLong()
                }
            } else if (dtS <= MAX_GAP_S) {
                // Signal gap (tunnel, app briefly in the background): bridge it in a straight line.
                val gap = prev.distanceTo(location)
                if (gap > prev.accuracy + location.accuracy && gap / dtS >= MIN_MOVING_MPS) {
                    distance += gap
                    moving += (dtS * 1000).toLong()
                }
            }
        }

        // A single wild fix must not become the trip maximum.
        val trustworthy = location.hasSpeed() &&
            (!location.hasAccuracy() || location.accuracy <= 50f) &&
            (!location.hasSpeedAccuracy() || location.speedAccuracyMetersPerSecond <= 2.5f)
        val max = if (trustworthy && speed > previous.maxMps) speed else previous.maxMps

        last = location
        trip = Trip(
            lastFixMs = nowMs,
            speedMps = speed,
            accuracyM = if (location.hasAccuracy()) location.accuracy else null,
            maxMps = max,
            distanceM = distance,
            movingMs = moving,
        )
        updateJourney(location)
    }

    override fun onProviderEnabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) gpsEnabled = true
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) gpsEnabled = false
    }

    // Abstract before Android 11; without this override older phones crash when it is called.
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    companion object {
        const val KEY_MPH = "mph"
        const val MIN_MOVING_MPS = 0.5f
        const val MAX_STEP_S = 5.0
        const val MAX_GAP_S = 600.0

        const val CLOCK_START_MPS = 3f
        const val OFF_TRACK_M = 750.0
        const val OFF_TRACK_PATIENCE_MS = 20_000L
        const val REROUTE_INTERVAL_MS = 90_000L
    }
}
