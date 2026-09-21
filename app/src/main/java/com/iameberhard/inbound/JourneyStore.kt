package com.iameberhard.inbound

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keeps the running journey (the track can be thousands of points, hence a file) and the recent
 * destinations, so a ride survives the app being closed or killed in between glances.
 */
class JourneyStore(context: Context) {

    private val file = File(context.filesDir, "journey.json")
    private val prefs = context.getSharedPreferences("inbound", Context.MODE_PRIVATE)

    fun save(journey: Journey?) {
        if (journey == null) {
            file.delete()
            return
        }
        val route = journey.route
        val json = JSONObject()
            .put("savedMs", System.currentTimeMillis())
            .put("destination", journey.destination.toJson())
            .put("source", route.source.name)
            .put("lat", JSONArray(route.lat))
            .put("lon", JSONArray(route.lon))
            .put("cumDist", JSONArray(route.cumDist))
            .put("cumFf", JSONArray(route.cumFf))
            .put("initialM", journey.initialM)
            .put("clockStartMs", journey.clockStartMs)
            .put("clockStartFfS", journey.clockStartFfS)
            .put("carriedElapsedS", journey.carriedElapsedS)
            .put("carriedFfS", journey.carriedFfS)
        runCatching { file.writeText(json.toString()) }
    }

    fun load(): Journey? = runCatching {
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        // Yesterday's ride is not today's.
        if (System.currentTimeMillis() - json.getLong("savedMs") > MAX_AGE_MS) return null
        Journey(
            destination = destination(json.getJSONObject("destination")),
            route = RailRoute(
                doubles(json.getJSONArray("lat")), doubles(json.getJSONArray("lon")),
                doubles(json.getJSONArray("cumDist")), doubles(json.getJSONArray("cumFf")),
                RouteSource.valueOf(json.getString("source")),
            ),
            initialM = json.getDouble("initialM"),
            clockStartMs = json.getLong("clockStartMs"),
            clockStartFfS = json.getDouble("clockStartFfS"),
            carriedElapsedS = json.getDouble("carriedElapsedS"),
            carriedFfS = json.getDouble("carriedFfS"),
        )
    }.getOrNull()

    var recent: List<Destination>
        get() = runCatching {
            val array = JSONArray(prefs.getString(KEY_RECENT, "[]"))
            List(array.length()) { destination(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        private set(value) {
            val array = JSONArray()
            value.forEach { array.put(it.toJson()) }
            prefs.edit().putString(KEY_RECENT, array.toString()).apply()
        }

    fun remember(destination: Destination) {
        recent = (listOf(destination) + recent.filterNot { it.lat == destination.lat && it.lon == destination.lon }).take(8)
    }

    private fun Destination.toJson() = JSONObject()
        .put("name", name).put("detail", detail).put("lat", lat).put("lon", lon).put("station", station)

    private fun destination(o: JSONObject) = Destination(
        o.getString("name"), o.optString("detail"), o.getDouble("lat"), o.getDouble("lon"), o.optBoolean("station"),
    )

    private fun doubles(array: JSONArray) = DoubleArray(array.length()) { array.getDouble(it) }

    private companion object {
        const val KEY_RECENT = "recent"
        const val MAX_AGE_MS = 24 * 3600 * 1000L
    }
}
