// Ride history, computed on the phone.
//
// The official app's "My Rides" list, Total Rides and Last Ride all come from
// TVS's servers, tied to an account. This app has neither, so the same numbers
// are derived locally from what the cluster already sends: a ride is a
// connected period, its distance is the odometer delta, its duration is wall
// clock.
//
// The consequence is deliberate and stated in the UI: a ride recorded here
// exists only on this phone and is lost on uninstall. That is the price of
// having no account, and it is a fair trade for not needing one.

package dev.tvsopen.session

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One ride: a connected period, open or closed. */
data class Ride(
    val startedAt: Long,
    val endedAt: Long?,
    val distanceKm: Double,
) {
    val inProgress: Boolean get() = endedAt == null

    fun durationMinutes(now: Long = System.currentTimeMillis()): Long {
        val end = endedAt ?: now
        return ((end - startedAt).coerceAtLeast(0L)) / 60_000L
    }
}

/**
 * Bounded local ride log.
 *
 * Keeps [MAX_RIDES] recent rides plus two running totals that survive the
 * window. Plain JSON in SharedPreferences: three numbers per ride, a dependency
 * would buy nothing, and the log has to be readable over `adb` when something
 * looks wrong.
 */
class RideLog(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("tvsride", Context.MODE_PRIVATE)

    private val _rides = MutableStateFlow(load())

    /** Most recent first. */
    val rides: StateFlow<List<Ride>> = _rides.asStateFlow()

    /** Every ride ever recorded, including ones the bounded window has dropped. */
    val totalRides: Int get() = sp.getInt(KEY_TOTAL, 0)

    /** Running total across all rides, not just the retained window. */
    val totalDistanceKm: Double
        get() = sp.getString(KEY_DIST, null)?.toDoubleOrNull() ?: 0.0

    private fun load(): List<Ride> = runCatching {
        val arr = JSONArray(sp.getString(KEY, "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Ride(
                startedAt = o.optLong("s"),
                endedAt = if (o.isNull("e")) null else o.optLong("e"),
                distanceKm = o.optDouble("d", 0.0),
            )
        }
    }.getOrDefault(emptyList())

    private fun persist(list: List<Ride>) {
        val arr = JSONArray()
        list.take(MAX_RIDES).forEach { r ->
            arr.put(
                JSONObject()
                    .put("s", r.startedAt)
                    .put("e", r.endedAt ?: JSONObject.NULL)
                    .put("d", r.distanceKm),
            )
        }
        sp.edit().putString(KEY, arr.toString()).apply()
        _rides.value = list
    }

    /** Opens a ride. A ride already in progress is left alone. */
    fun start(now: Long = System.currentTimeMillis()) {
        val current = _rides.value
        if (current.firstOrNull()?.inProgress == true) return
        persist(listOf(Ride(now, null, 0.0)) + current)
    }

    /**
     * Records progress on the open ride.
     *
     * Updates the in-memory value only; the row is written on [finish] or the
     * next [start]. Persisting on every odometer frame would be a disk write at
     * telemetry rate, which the log must never be the reason for.
     */
    fun update(distanceKm: Double) {
        val current = _rides.value
        if (current.firstOrNull()?.inProgress != true) return
        if (distanceKm < 0) return
        _rides.value = listOf(current.first().copy(distanceKm = distanceKm)) + current.drop(1)
    }

    /** Closes the open ride and folds it into the running totals. */
    fun finish(now: Long = System.currentTimeMillis()) {
        val current = _rides.value
        val open = current.firstOrNull() ?: return
        if (!open.inProgress) return
        sp.edit()
            .putInt(KEY_TOTAL, totalRides + 1)
            .putString(KEY_DIST, (totalDistanceKm + open.distanceKm).toString())
            .apply()
        persist(listOf(open.copy(endedAt = now)) + current.drop(1))
    }

    companion object {
        private const val KEY = "ride_log"
        private const val KEY_TOTAL = "ride_total_count"
        private const val KEY_DIST = "ride_total_distance"
        private const val MAX_RIDES = 100
    }
}
