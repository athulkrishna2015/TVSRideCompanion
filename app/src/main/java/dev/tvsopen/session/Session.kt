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

/**
 * Live telemetry, derived from decoded inbound frames.
 *
 * `avgEconomyKmpl` and `distanceToEmptyKm` are decoded but deliberately not
 * surfaced on the dashboard: TVS's Ntorq 125 manual lists every display
 * element the cluster has, and neither range nor fuel economy is one of them.
 * They are phone-app or cloud figures, and this app has no cloud.
 */
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
    /**
     * Odometer reading when this link was established.
     *
     * The official app shows "Connected Distance" on its Vehicle Overview, but
     * that is computed server-side from ride records. We have no cloud, so this
     * is the same number done locally: odometer now minus odometer at connect.
     */
    val odometerAtConnectKm: Double? = null,
) {
    /**
     * Distance travelled on this connection, or null when it cannot be known —
     * either no odometer yet, or the bike appears to have gone backwards
     * (a reset or a cluster swap), which must not report as a negative ride.
     */
    fun connectedDistanceKm(): Double? {
        val start = odometerAtConnectKm ?: return null
        val now = odometerKm ?: return null
        val d = now - start
        return if (d >= 0) d else null
    }

    /** No frames for this long means the link is stale, not merely idle. */
    fun isFresh(now: Long = System.currentTimeMillis()): Boolean =
        lastFrameAt > 0 && now - lastFrameAt < STALE_AFTER_MS

    companion object {
        const val STALE_AFTER_MS = 10_000L

        /** Bars in the Ntorq 125 cluster's fuel gauge. TVS manual, section 1c. */
        const val FUEL_BAR_MAX = 6
    }
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

    /**
     * Local ride log, created once the Activity supplies a Context.
     *
     * Nullable rather than injected so `Session` stays usable from a
     * BroadcastReceiver that has no Activity to construct it with.
     */
    @Volatile
    private var rideLog: RideLog? = null

    private val _rides = MutableStateFlow<List<Ride>>(emptyList())
    val rides: StateFlow<List<Ride>> = _rides.asStateFlow()

    private val _totalRides = MutableStateFlow(0)
    val totalRides: StateFlow<Int> = _totalRides.asStateFlow()

    private val _totalRideDistanceKm = MutableStateFlow(0.0)
    val totalRideDistanceKm: StateFlow<Double> = _totalRideDistanceKm.asStateFlow()

    fun attachRideLog(context: android.content.Context) {
        if (rideLog != null) return
        synchronized(this) {
            if (rideLog != null) return
            val log = RideLog(context)
            rideLog = log
            _rides.value = log.rides.value
            _totalRides.value = log.totalRides
            _totalRideDistanceKm.value = log.totalDistanceKm
        }
    }

    /** Keep the open ride's distance in step with the odometer. */
    private fun trackRideDistance() {
        val log = rideLog ?: return
        // connectedDistanceKm() is a Telemetry member, not a Session one, so the
        // receiver has to be spelled out.
        log.update(_telemetry.value.connectedDistanceKm() ?: 0.0)
        _rides.value = log.rides.value
    }

    fun setRideMode(on: Boolean) { _rideMode.value = on }

    fun onParked(info: ParkedInfo) { _parked.value = info }

    fun reset() {
        _telemetry.value = Telemetry()
        _hud.value = HudState()
    }

    fun onConnection(state: ConnectionState, model: VehicleModel, address: String?) {
        val previous = _connection.value
        val wasConnected = previous.state == ConnectionState.CONNECTED
        val isConnected = state == ConnectionState.CONNECTED

        // Stamp the odometer baseline on the first frame after a connect, so
        // "Connected Distance" is measured from the start of this link.
        if (isConnected && !wasConnected) {
            _telemetry.value = _telemetry.value.copy(
                odometerAtConnectKm = _telemetry.value.odometerKm,
            )
            rideLog?.start()
        }
        if (!isConnected && wasConnected) {
            rideLog?.finish()
            _totalRides.value = rideLog?.totalRides ?: _totalRides.value
            _totalRideDistanceKm.value = rideLog?.totalDistanceKm ?: _totalRideDistanceKm.value
        }

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

        // Raw integer divided once, here, into a Double — never an Int, so a
        // 7.3 s 0-60 run does not become 7, and a 3098.0 km odometer does not
        // become 3098 and then 309. (It did both of those before the driver
        // stopped pre-scaling in decode.)
        fun scaled(name: Field, div: Double): Double? =
            frame.field(name)?.let { it / div }

        val cur = _telemetry.value
        _telemetry.value = when (frame.dataId) {
            0x10 -> cur.copy(
                speedKmh = frame.field(Field.SPEED)?.toInt() ?: cur.speedKmh,
                odometerKm = scaled(Field.ODOMETER, 10.0) ?: cur.odometerKm,
                // Fuel is the low nibble of that byte. The Ntorq 125 cluster gauge has SIX
                // bars per TVS's own manual, not five, and it explicitly tells
                // riders not to derive mileage from it.
                fuelBars = (frame.field(Field.FUEL_LEVEL)?.toInt()?.and(0x0F))
                    ?.let { if (it > Telemetry.FUEL_BAR_MAX) Telemetry.FUEL_BAR_MAX else it }
                    ?: cur.fuelBars,
                averageSpeedKmh = frame.field(Field.AVERAGE_SPEED)?.toInt() ?: cur.averageSpeedKmh,
                tripStatus = frame.field(Field.TRIP_STATUS)?.toInt() ?: cur.tripStatus,
                topSpeedKmh = frame.field(Field.TOP_SPEED)?.toInt() ?: cur.topSpeedKmh,
                throttlePercent = scaled(Field.THROTTLE_POSITION, 2.0)?.toInt()
                    ?: cur.throttlePercent,
                timeTo60Sec = scaled(Field.TIME_60KMPH, 10.0)
                    ?: cur.timeTo60Sec,
                tripFMeters = scaled(Field.TRIP_F_METER, 10.0)?.toInt() ?: cur.tripFMeters,
                engineRpm = frame.field(Field.ENGINE_RPM)?.toInt() ?: cur.engineRpm,
                lastFrameAt = now,
            ).also { trackRideDistance() }

            0x11 -> cur.copy(
                serviceDue = (frame.field(Field.SERVICE_REMINDER)?.toInt()?.let { it != 0 })
                    ?: cur.serviceDue,
                softwareVersion = frame.field(Field.SOFTWARE_VERSION)?.toInt()
                    ?.let { "0x%X".format(it) } ?: cur.softwareVersion,
                lastFrameAt = now,
            )

            0x19 -> cur.copy(
                avgEconomyKmpl = frame.field(Field.AVERAGE_FUEL_ECONOMY)?.toInt()
                    ?: cur.avgEconomyKmpl,
                instantEconomyKmpl = frame.field(Field.INSTANTANEOUS_FUEL_ECONOMY)?.toInt()
                    ?: cur.instantEconomyKmpl,
                distanceToEmptyKm = frame.field(Field.DISTANCE_TO_EMPTY)?.toInt()
                    ?: cur.distanceToEmptyKm,
                lastFrameAt = now,
            )

            0x18 -> cur.copy(
                engineTempC = frame.field(Field.ENGINE_TEMPRATURE)?.toInt() ?: cur.engineTempC,
                batteryVoltage = frame.field(Field.BATTERY_VOLTAGE)?.toInt() ?: cur.batteryVoltage,
                lastFrameAt = now,
            )

            else -> cur.copy(lastFrameAt = now)
        }
    }

    fun onHud(update: (HudState) -> HudState) {
        _hud.value = update(_hud.value)
    }
}