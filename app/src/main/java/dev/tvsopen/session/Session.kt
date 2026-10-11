// Single source of truth for the UI.
//
// The Compose screens must not reach into the BLE service directly: a GATT
// callback has no business knowing a ViewModel exists. Everything the UI shows
// is published here as StateFlow, and the service pushes into it.

package dev.tvsopen.session

import dev.tvsopen.protocol.DecodedFrame
import dev.tvsopen.protocol.Field
import dev.tvsopen.protocol.VehicleModel
import dev.tvsopen.transport.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live telemetry, derived from decoded inbound frames. */
data class Telemetry(
    val speedKmh: Int? = null,            // SM1 idx 2
    val odometerKm: Double? = null,       // SM1 idx 3..5, /10
    val fuelBars: Int? = null,            // SM1 idx 6, low nibble
    val averageSpeedKmh: Int? = null,     // SM1 idx 7
    val tripStatus: Int? = null,          // SM1 idx 8
    val topSpeedKmh: Int? = null,         // SM1 idx 9
    val throttlePercent: Int? = null,     // SM1 idx 10, /2
    val timeTo60Sec: Double? = null,      // SM1 idx 13, /10
    val tripFMeters: Int? = null,         // SM1 idx 14..16, /10
    val engineRpm: Int? = null,           // SM1 idx 17..18
    val serviceDue: Boolean? = null,      // SM2 idx 4
    val softwareVersion: String? = null,  // SM2 idx 7
    val avgEconomyKmpl: Int? = null,      // SM3 idx 8
    val instantEconomyKmpl: Int? = null,  // SM3 idx 9
    val distanceToEmptyKm: Int? = null,   // SM3 idx 11..12
    val engineTempC: Int? = null,         // SM4 idx 8
    val batteryVoltage: Int? = null,      // SM4 idx 11
    val lastFrameAt: Long = 0L,
) {
    /** No frames for this long means the link is stale, not merely idle. */
    fun isFresh(now: Long = System.currentTimeMillis()): Boolean =
        lastFrameAt > 0 && now - lastFrameAt < STALE_AFTER_MS

    companion object { const val STALE_AFTER_MS = 10_000L }
}

/** What the cluster is displaying right now. */
data class HudState(
    val active: Boolean = false,
    val instructionTitle: String? = null,
    val street: String? = null,
    val distanceMeters: Int = -1,
    val etaMinutes: Int = 0,
    val pictogramId: Int? = null,
    val provider: String? = null,
    val framesSent: Int = 0,
    val lastError: String? = null,
)

data class ConnectionState1(
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val model: VehicleModel = VehicleModel.UNKNOWN,
    val address: String? = null,
)

/**
 * Where the scooter was last seen.
 *
 * There is no cloud in this project, so this cannot be a server-side record.
 * It is written locally when the phone has a fix while the cluster is connected,
 * and it never leaves the device — there is no permission that would let it.
 */
data class ParkedInfo(
    val atMillis: Long,
    val label: String? = null,
    val distanceMeters: Int? = null,
)

/**
 * Session store.
 *
 * Mutable rather than a ViewModel: the BLE service and the notification
 * listener are framework components with their own lifecycles, and neither can
 * easily hold a ViewModel. A process-scoped singleton they can both write to is
 * simpler and has no lifecycle trap.
 */
object Session {

    private val _telemetry = MutableStateFlow(Telemetry())
    val telemetry: StateFlow<Telemetry> = _telemetry.asStateFlow()

    private val _hud = MutableStateFlow(HudState())
    val hud: StateFlow<HudState> = _hud.asStateFlow()

    private val _connection = MutableStateFlow(ConnectionState1())
    val connection: StateFlow<ConnectionState1> = _connection.asStateFlow()

    private val _parked = MutableStateFlow<ParkedInfo?>(null)
    val parked: StateFlow<ParkedInfo?> = _parked.asStateFlow()

    private val _rideMode = MutableStateFlow(false)
    val rideMode: StateFlow<Boolean> = _rideMode.asStateFlow()

    fun setRideMode(on: Boolean) { _rideMode.value = on }

    fun onParked(info: ParkedInfo) { _parked.value = info }

    fun reset() {
        _telemetry.value = Telemetry()
        _hud.value = HudState()
    }

    fun onConnection(state: ConnectionState, model: VehicleModel, address: String?) {
        _connection.value = ConnectionState1(state, model, address)
    }

    /**
     * Fold one decoded frame into the telemetry snapshot.
     *
     * Only fields the frame actually carries are overwritten, so a slow frame
     * (say `0x19`, once a second) does not blank values that arrived with the
     * fast `0x10` stream.
     */
    fun onFrame(frame: DecodedFrame) {
        val now = System.currentTimeMillis()
        val f = frame.fields
        val v = frame.values

        fun idx(name: Field): Long? =
            f.entries.firstOrNull { it.value == name }?.let { v[it.key] }

        fun scaled(name: Field, div: Double): Int? =
            idx(name)?.let { (it / div).toInt() }

        val cur = _telemetry.value
        _telemetry.value = when (frame.dataId) {
            0x10 -> cur.copy(
                speedKmh = idx(Field.SPEED)?.toInt() ?: cur.speedKmh,
                odometerKm = scaled(Field.ODOMETER, 10.0)?.toDouble()
                    ?: cur.odometerKm,
                // Fuel is the low nibble of that byte.
                fuelBars = (idx(Field.FUEL_LEVEL)?.toInt()?.and(0x0F))
                    ?.let { if (it > 5) 5 else it } ?: cur.fuelBars,
                averageSpeedKmh = idx(Field.AVERAGE_SPEED)?.toInt() ?: cur.averageSpeedKmh,
                tripStatus = idx(Field.TRIP_STATUS)?.toInt() ?: cur.tripStatus,
                topSpeedKmh = idx(Field.TOP_SPEED)?.toInt() ?: cur.topSpeedKmh,
                throttlePercent = scaled(Field.THROTTLE_POSITION, 2.0)
                    ?: cur.throttlePercent,
                timeTo60Sec = scaled(Field.TIME_60KMPH, 10.0)?.toDouble()
                    ?: cur.timeTo60Sec,
                tripFMeters = scaled(Field.TRIP_F_METER, 10.0) ?: cur.tripFMeters,
                engineRpm = idx(Field.ENGINE_RPM)?.toInt() ?: cur.engineRpm,
                lastFrameAt = now,
            )

            0x11 -> cur.copy(
                serviceDue = (idx(Field.SERVICE_REMINDER)?.toInt()?.let { it != 0 })
                    ?: cur.serviceDue,
                softwareVersion = idx(Field.SOFTWARE_VERSION)?.toInt()
                    ?.let { "0x%X".format(it) } ?: cur.softwareVersion,
                lastFrameAt = now,
            )

            0x19 -> cur.copy(
                avgEconomyKmpl = idx(Field.AVERAGE_FUEL_ECONOMY)?.toInt()
                    ?: cur.avgEconomyKmpl,
                instantEconomyKmpl = idx(Field.INSTANTANEOUS_FUEL_ECONOMY)?.toInt()
                    ?: cur.instantEconomyKmpl,
                distanceToEmptyKm = idx(Field.DISTANCE_TO_EMPTY)?.toInt()
                    ?: cur.distanceToEmptyKm,
                lastFrameAt = now,
            )

            0x18 -> cur.copy(
                engineTempC = idx(Field.ENGINE_TEMPRATURE)?.toInt() ?: cur.engineTempC,
                batteryVoltage = idx(Field.BATTERY_VOLTAGE)?.toInt() ?: cur.batteryVoltage,
                lastFrameAt = now,
            )

            else -> cur.copy(lastFrameAt = now)
        }
    }

    fun onHud(update: (HudState) -> HudState) {
        _hud.value = update(_hud.value)
    }
}