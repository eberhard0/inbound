package com.iameberhard.inbound

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A CSV of what the app saw and what it predicted, one row every few seconds while it is on screen. Exported
 * to Downloads, a ride can be replayed against the real arrival time – the only honest way to tune the
 * estimate. Stays on the phone unless the user exports it.
 */
class RideLog(private val context: Context) {

    private val file = File(context.filesDir, "ride-log.csv")
    private var lastRowMs = 0L

    var rows = if (file.exists()) (file.useLines { it.count() } - 1).coerceAtLeast(0) else 0
        private set

    /** A new destination starts a new log. */
    fun begin(destination: Destination, version: String) {
        file.writeText(HEADER + "\n")
        rows = 0
        lastRowMs = 0L
        event("start to=${destination.name.replace(',', ' ')} @${destination.lat};${destination.lon} app=$version")
    }

    /** Something worth knowing when replaying: re-plan, train type switched, clock started… */
    fun event(text: String) = append(listOf(stamp(), System.currentTimeMillis().toString()) + List(COLUMNS - 3) { "" } + text.replace(',', ' '))

    fun fix(
        lat: Double, lon: Double, speedMps: Double, accuracyM: Float?,
        followed: TrainType, position: RailRoute.Position, pace: Pace,
        progress: Map<TrainType, Progress>,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastRowMs < INTERVAL_MS) return
        lastRowMs = now
        val mine = progress[followed]
        fun eta(type: TrainType) = progress[type]?.let { ((it.arrivalMs - now) / 1000).toString() } ?: ""
        append(
            listOf(
                stamp(), now.toString(), f(lat, 6), f(lon, 6), f(speedMps, 1), accuracyM?.let { f(it.toDouble(), 0) } ?: "",
                followed.name, f(position.remainingM, 0), f(position.remainingFfS, 0), f(position.lineMps, 1), f(position.offTrackM, 0),
                f(pace.ema, 3), f(pace.mean, 3), pace.count.toString(),
                mine?.let { f(it.elapsedS, 0) } ?: "", mine?.let { f(it.coveredFfS, 0) } ?: "",
                eta(TrainType.HIGH_SPEED), eta(TrainType.EXPRESS), eta(TrainType.LOCAL), "",
            ),
        )
    }

    /** Copies the log into the phone's Downloads folder; returns the file name, or null if that failed. */
    fun export(): String? {
        if (Build.VERSION.SDK_INT < 29 || rows == 0) return null
        return runCatching {
            val name = "inbound-ride-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".csv"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: return null
            name
        }.getOrNull()
    }

    private fun append(cells: List<String>) {
        // Hours of riding stay well under this; it only guards against a log nobody ever exports.
        if (file.length() > MAX_BYTES) return
        runCatching {
            if (!file.exists()) file.writeText(HEADER + "\n")
            file.appendText(cells.joinToString(",") + "\n")
            rows++
        }
    }

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun f(value: Double, decimals: Int) = String.format(Locale.US, "%.${decimals}f", value)

    private companion object {
        const val INTERVAL_MS = 5_000L
        const val MAX_BYTES = 8L * 1024 * 1024
        const val HEADER = "time,wall_ms,lat,lon,gps_mps,accuracy_m,followed,remaining_m,remaining_ff_s,line_mps,off_track_m," +
            "pace_ema,pace_mean,pace_count,elapsed_s,covered_ff_s,eta_high_speed_s,eta_express_s,eta_local_s,event"
        val COLUMNS = HEADER.split(',').size
    }
}
