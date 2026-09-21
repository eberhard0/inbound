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
        val legs = JSONArray()
        for (leg in journey.legs) {
            legs.put(
                JSONObject()
                    .put("type", leg.type.name)
                    .put("source", leg.route.source.name)
                    .put("straightMps", leg.route.straightMps)
                    .put("lat", JSONArray(leg.route.lat))
                    .put("lon", JSONArray(leg.route.lon))
                    .put("cumDist", JSONArray(leg.route.cumDist))
                    .put("cumFf", JSONArray(leg.route.cumFf))
                    .put("initialM", leg.initialM)
                    .put("clockStartFfS", leg.clockStartFfS)
                    .put("carriedElapsedS", leg.carriedElapsedS)
                    .put("carriedFfS", leg.carriedFfS),
            )
        }
        val json = JSONObject()
            .put("savedMs", System.currentTimeMillis())
            .put("destination", journey.destination.toJson())
            .put("clockStartMs", journey.clockStartMs)
            .put("legs", legs)
        runCatching { file.writeText(json.toString()) }
    }

    fun load(): Journey? = runCatching {
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        // Yesterday's ride is not today's.
        if (System.currentTimeMillis() - json.getLong("savedMs") > MAX_AGE_MS) return null
        val legs = json.getJSONArray("legs")
        Journey(
            destination = destination(json.getJSONObject("destination")),
            legs = List(legs.length()) { i ->
                val o = legs.getJSONObject(i)
                Leg(
                    type = TrainType.valueOf(o.getString("type")),
                    route = RailRoute(
                        doubles(o.getJSONArray("lat")), doubles(o.getJSONArray("lon")),
                        doubles(o.getJSONArray("cumDist")), doubles(o.getJSONArray("cumFf")),
                        RouteSource.valueOf(o.getString("source")), o.getDouble("straightMps"),
                    ),
                    initialM = o.getDouble("initialM"),
                    clockStartFfS = o.getDouble("clockStartFfS"),
                    carriedElapsedS = o.getDouble("carriedElapsedS"),
                    carriedFfS = o.getDouble("carriedFfS"),
                )
            }.also { require(it.isNotEmpty()) },
            clockStartMs = json.getLong("clockStartMs"),
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

    /** The kind of train the user last said they were on; its estimate is the big one. */
    var trainType: TrainType
        get() = runCatching { TrainType.valueOf(prefs.getString(KEY_TYPE, null)!!) }.getOrDefault(TrainType.EXPRESS)
        set(value) = prefs.edit().putString(KEY_TYPE, value.name).apply()

    private fun Destination.toJson() = JSONObject()
        .put("name", name).put("detail", detail).put("lat", lat).put("lon", lon).put("station", station)

    private fun destination(o: JSONObject) = Destination(
        o.getString("name"), o.optString("detail"), o.getDouble("lat"), o.getDouble("lon"), o.optBoolean("station"),
    )

    private fun doubles(array: JSONArray) = DoubleArray(array.length()) { array.getDouble(it) }

    private companion object {
        const val KEY_RECENT = "recent"
        const val KEY_TYPE = "trainType"
        const val MAX_AGE_MS = 24 * 3600 * 1000L
    }
}
