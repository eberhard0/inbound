package com.iameberhard.inbound

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Destination(
    val name: String,
    /** Rest of the address, to tell two places with the same name apart. */
    val detail: String,
    val lat: Double,
    val lon: Double,
    val station: Boolean,
)

enum class RouteSource {
    /** Real track with the router's per-section running times. */
    RAIL,
    /** Real track, but only its length is known. */
    RAIL_NO_TIMES,
    /** No track found: straight line, padded for curvature. */
    STRAIGHT,
}

/**
 * The track from where the journey was planned to the destination. [cumDist] is metres and [cumFf]
 * seconds of free-flow running time (no stops, line speed) from the first point to each point.
 */
class RailRoute(
    val lat: DoubleArray,
    val lon: DoubleArray,
    val cumDist: DoubleArray,
    val cumFf: DoubleArray,
    val source: RouteSource,
) {
    val totalM get() = cumDist.last()
    val totalFfS get() = cumFf.last()

    class Position(val remainingM: Double, val remainingFfS: Double, val offTrackM: Double)

    /** Snaps a GPS fix onto the track and reports what is left from there. */
    fun locate(pLat: Double, pLon: Double): Position {
        if (source == RouteSource.STRAIGHT) {
            // Nothing to snap to; re-measure the padded straight line every time instead.
            val metres = Geo.distance(pLat, pLon, lat.last(), lon.last()) * STRAIGHT_PADDING
            return Position(metres, metres / DEFAULT_LINE_SPEED, 0.0)
        }
        // Flat-earth metres around the fix: exact enough near it, and far segments never win anyway.
        val kx = 111_320.0 * cos(Math.toRadians(pLat))
        val ky = 110_574.0
        var best = Double.MAX_VALUE
        var bestI = 0
        var bestT = 0.0
        for (i in 0 until lat.size - 1) {
            val ax = (lon[i] - pLon) * kx
            val ay = (lat[i] - pLat) * ky
            val dx = (lon[i + 1] - pLon) * kx - ax
            val dy = (lat[i + 1] - pLat) * ky - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val cx = ax + t * dx
            val cy = ay + t * dy
            val d2 = cx * cx + cy * cy
            if (d2 < best) {
                best = d2
                bestI = i
                bestT = t
            }
        }
        val doneM = cumDist[bestI] + bestT * (cumDist[bestI + 1] - cumDist[bestI])
        val doneFf = cumFf[bestI] + bestT * (cumFf[bestI + 1] - cumFf[bestI])
        return Position(totalM - doneM, totalFfS - doneFf, sqrt(best))
    }

    companion object {
        /** Rail lines are rarely straight; typical detour over the crow-flies distance. */
        const val STRAIGHT_PADDING = 1.25

        /** Assumed line speed (m/s) where the router gives none: about 110 km/h. */
        const val DEFAULT_LINE_SPEED = 30.0

        fun straight(fromLat: Double, fromLon: Double, to: Destination): RailRoute {
            val metres = Geo.distance(fromLat, fromLon, to.lat, to.lon) * STRAIGHT_PADDING
            return RailRoute(
                doubleArrayOf(fromLat, to.lat), doubleArrayOf(fromLon, to.lon),
                doubleArrayOf(0.0, metres), doubleArrayOf(0.0, metres / DEFAULT_LINE_SPEED),
                RouteSource.STRAIGHT,
            )
        }
    }
}

/**
 * A destination plus what has been learned on the way there.
 *
 * The ETA is "free-flow time still ahead × slowdown". The slowdown starts at [PRIOR_SLOWDOWN] (trains
 * stop at stations and rarely run at full line speed) and is then pulled towards what this ride actually
 * shows: real seconds spent per free-flow second covered. Only two positions are needed for that, so it
 * keeps working when the app – and with it the GPS – was closed for most of the ride.
 */
data class Journey(
    val destination: Destination,
    val route: RailRoute,
    /** Track distance when the destination was first set; the progress bar's 100 %. */
    val initialM: Double,
    /** Wall-clock ms when the train was first seen moving on this route; 0 = clock not started. */
    val clockStartMs: Long = 0L,
    val clockStartFfS: Double = 0.0,
    /** Evidence carried over from before a re-route. */
    val carriedElapsedS: Double = 0.0,
    val carriedFfS: Double = 0.0,
) {
    fun progress(position: RailRoute.Position, nowMs: Long): Progress {
        var elapsed = carriedElapsedS
        var covered = carriedFfS
        if (clockStartMs != 0L) {
            elapsed += (nowMs - clockStartMs).coerceAtLeast(0L) / 1000.0
            covered += (clockStartFfS - position.remainingFfS).coerceAtLeast(0.0)
        }
        val slowdown = ((PRIOR_SLOWDOWN * PRIOR_WEIGHT_S + elapsed) / (PRIOR_WEIGHT_S + covered))
            .coerceIn(1.0, MAX_SLOWDOWN)
        val arrived = position.remainingM < ARRIVED_M
        val eta = if (arrived) 0.0 else position.remainingFfS * slowdown
        return Progress(
            remainingM = position.remainingM,
            bestCaseS = if (arrived) 0.0 else position.remainingFfS,
            arrivalMs = nowMs + (eta * 1000).toLong(),
            fraction = if (initialM > 0) (1 - position.remainingM / initialM).coerceIn(0.0, 1.0).toFloat() else 0f,
            offTrackM = position.offTrackM,
            arrived = arrived,
            elapsedS = elapsed,
            coveredFfS = covered,
        )
    }

    companion object {
        const val PRIOR_SLOWDOWN = 1.3
        /** The prior counts as much as this many seconds of observed free-flow running. */
        const val PRIOR_WEIGHT_S = 600.0
        const val MAX_SLOWDOWN = 4.0
        const val ARRIVED_M = 250.0
    }
}

class Progress(
    val remainingM: Double,
    /** Seconds if the train ran non-stop at line speed from here. */
    val bestCaseS: Double,
    /** Estimated arrival as wall-clock ms, so the countdown keeps running through a tunnel. */
    val arrivalMs: Long,
    val fraction: Float,
    val offTrackM: Double,
    val arrived: Boolean,
    val elapsedS: Double,
    val coveredFfS: Double,
)

object Geo {
    private const val EARTH_RADIUS = 6_371_000.0

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS * atan2(sqrt(h), sqrt(1 - h))
    }

    fun cumulative(lat: DoubleArray, lon: DoubleArray): DoubleArray {
        val out = DoubleArray(lat.size)
        for (i in 1 until lat.size) out[i] = out[i - 1] + distance(lat[i - 1], lon[i - 1], lat[i], lon[i])
        return out
    }
}
