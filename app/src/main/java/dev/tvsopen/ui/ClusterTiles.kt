// Which telemetry the dashboard is allowed to show.
//
// This exists because the first version of the Home screen copied the Jupiter
// companion's tile row wholesale, which put **Range** and **Avg Economy** in
// front of an Ntorq owner. Both are wrong for this scooter: TVS's own Ntorq 125
// connected-cluster manual enumerates every display element the cluster has,
// and range-to-empty and km/L are not among them. They are phone-app or cloud
// figures, and this app has no cloud and no account.
//
// The rule this file encodes: **show what the cluster shows.** A decoded field
// is not automatically a displayable one. Anything here needs a citation to a
// cluster element, not just a byte offset we happen to decode.

package dev.tvsopen.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.ui.graphics.vector.ImageVector
import dev.tvsopen.protocol.VehicleModel
import dev.tvsopen.session.Telemetry

/** A value the cluster can actually display. */
enum class ClusterValue {
    SPEED,
    ODOMETER,
    FUEL,
    TRIP,
    ENGINE_TEMP,
    SERVICE,
    AVG_SPEED,
    TOP_SPEED,
    TIME_60,
}

data class TileSpec(
    val value: ClusterValue,
    val label: String,
    val unit: String,
    val icon: ImageVector,
)

/**
 * The Ntorq 125 cluster's street-mode display, per TVS's manual:
 * odometer, digital clock, fuel gauge (6 bars), speedometer, engine
 * temperature gauge (6 bars), helmet icon, low-battery indicator, **service
 * reminder**, trip A/B/F, side stand indicator.
 *
 * Ride-stats fields — average speed, top speed, 0-60 — are decoded too but
 * only populate in sport mode on a static bike, so they are marked with
 * [rideStatsOnly] and left off the headline grid.
 */
object ClusterTiles {

    val ntorq: List<TileSpec> = listOf(
        TileSpec(ClusterValue.SPEED, "Speed", "km/h", Icons.Filled.Speed),
        TileSpec(ClusterValue.ODOMETER, "Odometer", "km", Icons.Filled.Route),
        TileSpec(ClusterValue.FUEL, "Fuel", "bars", Icons.Filled.LocalGasStation),
        TileSpec(ClusterValue.TRIP, "Trip", "km", Icons.Filled.Route),
        TileSpec(
            ClusterValue.ENGINE_TEMP, "Engine temp", "°C",
            Icons.Filled.Thermostat,
        ),
        TileSpec(ClusterValue.SERVICE, "Service", "", Icons.Filled.Build),
    )

    /** Real cluster values that need sport mode or a stopped bike. */
    val ntorqRideStats: List<TileSpec> = listOf(
        TileSpec(ClusterValue.AVG_SPEED, "Avg speed", "km/h", Icons.Filled.Speed),
        TileSpec(ClusterValue.TOP_SPEED, "Top speed", "km/h", Icons.Filled.Speed),
        TileSpec(ClusterValue.TIME_60, "0-60", "s", Icons.Filled.Speed),
    )

    /**
     * Raider shares the Ntorq BLE configuration upstream, but nobody has
     * photographed a Raider cluster, so it borrows the Ntorq set rather than
     * guessing. Change this when there is hardware evidence.
     */
    fun forModel(model: VehicleModel): List<TileSpec> =
        when (model) {
            VehicleModel.NTORQ -> ntorq
            else -> ntorq
        }

    /**
     * Formats one value, or an em dash when the frame carrying it never arrived.
     *
     * Written as two `when`s returning the value and the unit separately rather
     * than one `when` returning `Pair`s. Pairing inline made every `?.let`
     * branch infer `Pair<String?, String>` against a `Pair<String, String>`
     * signature, and the mismatch surfaced as a confusing error pointing at
     * the declaration rather than at the branch. The fallback is applied once,
     * here, so no branch can forget it.
     */
    fun format(t: Telemetry, v: ClusterValue): Pair<String, String> {
        val value: String? = when (v) {
            ClusterValue.SPEED -> t.speedKmh?.toString()
            ClusterValue.ODOMETER -> t.odometerKm?.let { String.format("%.1f", it) }
            ClusterValue.FUEL -> t.fuelBars?.let { "$it/${Telemetry.FUEL_BAR_MAX}" }
            ClusterValue.TRIP -> t.tripFMeters?.let { String.format("%.1f", it / 1000.0) }
            ClusterValue.ENGINE_TEMP -> t.engineTempC?.toString()
            ClusterValue.SERVICE -> when (t.serviceDue) {
                true -> "Due"
                false -> "OK"
                null -> null
            }
            ClusterValue.AVG_SPEED -> t.averageSpeedKmh?.toString()
            ClusterValue.TOP_SPEED -> t.topSpeedKmh?.toString()
            ClusterValue.TIME_60 -> t.timeTo60Sec?.let { String.format("%.1f", it) }
        }
        val unit = when (v) {
            ClusterValue.SPEED, ClusterValue.AVG_SPEED, ClusterValue.TOP_SPEED -> "km/h"
            ClusterValue.ODOMETER, ClusterValue.TRIP -> "km"
            ClusterValue.ENGINE_TEMP -> "°C"
            ClusterValue.TIME_60 -> "s"
            ClusterValue.FUEL, ClusterValue.SERVICE -> ""
        }
        return (value ?: "—") to unit
    }
}