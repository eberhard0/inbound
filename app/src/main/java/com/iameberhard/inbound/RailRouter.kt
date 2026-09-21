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

    /** The quickest track, plus the one ordinary trains take where the quickest is a high-speed line. */
    class Planned(val fast: RailRoute, val conventional: RailRoute?)

    /** Never fails: the worst case is the padded straight line. */
    fun route(fromLat: Double, fromLon: Double, to: Destination): Planned {
        val fast = runCatching { openRailRouting(fromLat, fromLon, to, avoidHighSpeed = false) }.getOrNull()
            ?: runCatching { bRouter(fromLat, fromLon, to) }.getOrNull()
            ?: RailRoute.straight(fromLat, fromLon, to)
        if (fast.highSpeedShare() <= 0.1) return Planned(fast, null)

        val classic = runCatching { openRailRouting(fromLat, fromLon, to, avoidHighSpeed = true) }.getOrNull()
        // A huge detour means there is no sensible classic line (e.g. the destination is a high-speed-only
        // station); slower trains are then estimated on the fast track instead.
        return Planned(fast, classic?.takeIf { it.totalM < fast.totalM * MAX_CLASSIC_DETOUR })
    }

    private fun openRailRouting(fromLat: Double, fromLon: Double, to: Destination, avoidHighSpeed: Boolean): RailRoute {
        val request = JSONObject()
            .put("points", JSONArray().put(JSONArray().put(fromLon).put(fromLat)).put(JSONArray().put(to.lon).put(to.lat)))
            .put("profile", "all_tracks")
            .put("points_encoded", false)
            .put("instructions", false)
            .put("details", JSONArray().put("time"))
        if (avoidHighSpeed) {
            request.put("ch.disable", true).put(
                "custom_model",
                JSONObject().put("priority", JSONArray().put(JSONObject().put("if", "max_speed > 200").put("multiply_by", "0.05"))),
            )
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
