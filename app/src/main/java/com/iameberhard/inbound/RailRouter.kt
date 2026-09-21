package com.iameberhard.inbound

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * The app's only network use: place search (OpenStreetMap Nominatim) and one track lookup per journey
 * (OpenRailRouting, then BRouter's rail profile). All calls block; run them off the main thread.
 */
object RailRouter {
    private const val USER_AGENT = "Inbound/1.0 (personal Android train ETA app)"

    /** Stations first: a city name alone would otherwise put the pin on the town hall. */
    fun search(query: String): List<Destination> {
        var found = nominatim(query)
        if (found.none { it.station }) {
            Thread.sleep(1100) // Nominatim allows one request a second.
            found = found + nominatim("$query station")
        }
        return found
            .distinctBy { String.format(Locale.US, "%.4f,%.4f", it.lat, it.lon) }
            .sortedByDescending { it.station }
    }

    private fun nominatim(query: String): List<Destination> {
        val q = URLEncoder.encode(query, "UTF-8")
        val array = JSONArray(get("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8&q=$q"))
        return List(array.length()) { i ->
            val o = array.getJSONObject(i)
            val display = o.getString("display_name")
            val category = o.optString("category")
            val type = o.optString("type")
            Destination(
                name = o.optString("name").ifEmpty { display.substringBefore(',') },
                detail = display.substringAfter(", ", ""),
                lat = o.getString("lat").toDouble(),
                lon = o.getString("lon").toDouble(),
                station = (category == "railway" && type in setOf("station", "halt", "stop", "tram_stop", "platform")) ||
                    (category == "building" && type == "train_station") ||
                    (category == "public_transport" && type in setOf("station", "stop_position", "platform")),
            )
        }
    }

    /**
     * The quickest track, plus the one ordinary trains take where the quickest is a high-speed line.
     * [note] says how the fast track was found, for the ride log.
     */
    class Planned(val fast: RailRoute, val conventional: RailRoute?, val note: String)

    private enum class Tracks { ANY, HIGH_SPEED_NETWORK, NO_HIGH_SPEED }

    /** Never fails: the worst case is the padded straight line. */
    fun route(fromLat: Double, fromLon: Double, to: Destination): Planned {
        val plain = runCatching { openRailRouting(fromLat, fromLon, to.lat, to.lon, Tracks.ANY) }.getOrNull()
            ?: runCatching { bRouter(fromLat, fromLon, to) }.getOrNull()
            ?: RailRoute.straight(fromLat, fromLon, to)
        if (plain.highSpeedShare() <= 0.1) return Planned(plain, null, "plain ${plain.source}")

        // A station's pin often sits nearer to some other track than to its high-speed platforms (Tokyo: the
        // conventional lines and a subway), and the router then drags the whole route onto that network.
        // High-speed trains stay on standard-gauge mainline track, so plan on that alone – and if the pin itself
        // doesn't connect, feel around it for the platforms.
        var note = "plain"
        var fast = plain
        for ((label, lat, lon) in around(to)) {
            val dedicated = runCatching { openRailRouting(fromLat, fromLon, lat, lon, Tracks.HIGH_SPEED_NETWORK) }.getOrNull() ?: continue
            if (dedicated.totalFfS <= plain.totalFfS * 1.02) {
                fast = dedicated
                note = "high-speed network via $label"
            }
            break
        }

        val classic = runCatching { openRailRouting(fromLat, fromLon, to.lat, to.lon, Tracks.NO_HIGH_SPEED) }.getOrNull()
        // A huge detour means there is no sensible classic line (e.g. the destination is a high-speed-only
        // station); slower trains are then estimated on the fast track instead.
        return Planned(fast, classic?.takeIf { it.totalM < fast.totalM * MAX_CLASSIC_DETOUR }, note)
    }

    /** The pin itself, then eight compass points 200 m and 400 m out. */
    private fun around(to: Destination): List<Triple<String, Double, Double>> {
        val points = mutableListOf(Triple("pin", to.lat, to.lon))
        for (radius in listOf(200, 400)) {
            for ((i, compass) in listOf("E", "W", "N", "S", "NE", "SW", "NW", "SE").withIndex()) {
                val bearing = Math.toRadians(listOf(90.0, 270.0, 0.0, 180.0, 45.0, 225.0, 315.0, 135.0)[i])
                points += Triple(
                    "$radius m $compass",
                    to.lat + radius * Math.cos(bearing) / 111_320.0,
                    to.lon + radius * Math.sin(bearing) / (111_320.0 * Math.cos(Math.toRadians(to.lat))),
                )
            }
        }
        return points
    }

    private fun openRailRouting(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, tracks: Tracks): RailRoute {
        val request = JSONObject()
            .put("points", JSONArray().put(JSONArray().put(fromLon).put(fromLat)).put(JSONArray().put(toLon).put(toLat)))
            .put("profile", "all_tracks")
            .put("points_encoded", false)
            .put("instructions", false)
            .put("details", JSONArray().put("time"))
        val rules = when (tracks) {
            Tracks.ANY -> emptyList()
            // Mainline only, and no track that is tagged with a non-standard gauge (untagged is fine).
            Tracks.HIGH_SPEED_NETWORK -> listOf("railway_class != RAIL" to "0", "gauge > 0 && gauge < 1400" to "0", "gauge > 1500" to "0")
            Tracks.NO_HIGH_SPEED -> listOf("max_speed > 200" to "0.05")
        }
        if (rules.isNotEmpty()) {
            val priority = JSONArray()
            for ((condition, factor) in rules) priority.put(JSONObject().put("if", condition).put("multiply_by", factor))
            request.put("ch.disable", true).put("custom_model", JSONObject().put("priority", priority))
        }
        val path = JSONObject(post("https://routing.openrailrouting.org/route", request.toString()))
            .getJSONArray("paths").getJSONObject(0)
        val (lat, lon) = coordinates(path.getJSONObject("points").getJSONArray("coordinates"))
        val cumDist = Geo.cumulative(lat, lon)

        // "time" details are [fromIndex, toIndex, milliseconds] per stretch of track.
        val cumFf = DoubleArray(lat.size)
        val times = path.optJSONObject("details")?.optJSONArray("time")
        if (times != null) {
            for (k in 0 until times.length()) {
                val entry = times.getJSONArray(k)
                val a = entry.getInt(0)
                val b = entry.getInt(1).coerceAtMost(lat.size - 1)
                val seconds = entry.getDouble(2) / 1000.0
                val length = cumDist[b] - cumDist[a]
                for (i in a + 1..b) {
                    cumFf[i] = cumFf[a] + seconds * if (length > 0) (cumDist[i] - cumDist[a]) / length else 1.0
                }
            }
        }
        return if (cumFf.last() > 0 && (1 until cumFf.size).all { cumFf[it] >= cumFf[it - 1] }) {
            RailRoute(lat, lon, cumDist, cumFf, RouteSource.RAIL)
        } else {
            RailRoute(lat, lon, cumDist, uniformTimes(cumDist), RouteSource.RAIL_NO_TIMES)
        }
    }

    private fun bRouter(fromLat: Double, fromLon: Double, to: Destination): RailRoute {
        val url = String.format(
            Locale.US,
            "https://brouter.de/brouter?lonlats=%.6f,%.6f%%7C%.6f,%.6f&profile=rail&alternativeidx=0&format=geojson",
            fromLon, fromLat, to.lon, to.lat,
        )
        val line = JSONObject(get(url)).getJSONArray("features").getJSONObject(0)
            .getJSONObject("geometry").getJSONArray("coordinates")
        val (lat, lon) = coordinates(line)
        val cumDist = Geo.cumulative(lat, lon)
        return RailRoute(lat, lon, cumDist, uniformTimes(cumDist), RouteSource.RAIL_NO_TIMES)
    }

    /** GeoJSON order is [lon, lat]. */
    private fun coordinates(line: JSONArray): Pair<DoubleArray, DoubleArray> {
        if (line.length() < 2) throw IOException("empty route")
        val lat = DoubleArray(line.length()) { line.getJSONArray(it).getDouble(1) }
        val lon = DoubleArray(line.length()) { line.getJSONArray(it).getDouble(0) }
        return lat to lon
    }

    private fun uniformTimes(cumDist: DoubleArray) = DoubleArray(cumDist.size) { cumDist[it] / RailRoute.DEFAULT_LINE_SPEED }

    private const val MAX_CLASSIC_DETOUR = 1.5

    private fun get(url: String) = request(url, null)

    private fun post(url: String, json: String) = request(url, json)

    private fun request(url: String, body: String?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
            }
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            if (body != null) connection.outputStream.use { it.write(body.toByteArray()) }
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
