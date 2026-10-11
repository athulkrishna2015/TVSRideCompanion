package dev.tvsopen.ui

import androidx.compose.runtime.Immutable

/**
 * Live telemetry, decoded from inbound cluster frames.
 *
 * Every field maps to a byte offset in one of the Ntorq frame layouts — see
 * `NtorqFrames`. Null means "not received yet", which the UI renders as a
 * placeholder rather than a misleading zero.
 */
@Immutable
data class Telemetry(
    val speedKmh: Int? = null,          // SM1 idx 2
    val odometerKm: Double? = null,     // SM1 idx 3..5, /10
    val fuelBars: Int? = null,          // SM1 idx 6 (nibble)
    val averageSpeedKmh: Int? = null,   // SM1 idx 7
    val tripStatus: Int? = null,        // SM1 idx 8
    val topSpeedKmh: Int? = null,       // SM1 idx 9
    val throttlePercent: Int? = null,   // SM1 idx 10, /2
    val timeTo60Sec: Double? = null,    // SM1 idx 13, /10
    val tripFMeter: Int? = null,        // SM1 idx 14..16, /10
    val engineRpm: Int? = null,         // SM1 idx 17..18
    val serviceDue: Boolean? = null,    // SM2 idx 4
    val softwareVersion: String? = null,// SM2 idx 7
    val vehicleState: Int? = null,      // SM2 idx 3
    val avgEconomyKmpl: Int? = null,    // SM3 idx 8
    val instantEconomyKmpl: Int? = null,// SM3 idx 9
    val distanceToEmptyKm: Int? = null, // SM3 idx 11..12
    val engineTempC: Int? = null,       // SM4 idx 8
    val batteryVoltage: Int? = null,    // SM4 idx 11
    val lastFrameAt: Long = 0L,
) {
    /** Frames stop arriving within this window ⇒ treat the link as stale. */
    fun isFresh(now: Long = System.currentTimeMillis()) = lastFrameAt > 0 && now - lastFrameAt < 10_000
}

/** What the cluster is currently displaying. */
@Immutable
data class HudSnapshot(
    val active: Boolean = false,
    val instructionTitle: String? = null,
    val street: String? = null,
    val distanceMeters: Int = -1,
    val etaMinutes: Int = 0,
    val pictogramId: Int? = null,
    val pictogramLabel: String? = null,
    val framesSent: Int = 0,
    val provider: String? = null,
    val lastError: String? = null,
)

/** Connection + capability state for the header. */
@Immutable
data class ConnectionUi(
    val state: String = "Disconnected",   // mirrors ConnectionState
    val modelName: String = "No vehicle",
    val address: String? = null,
    val capabilitiesNote: String? = null, // e.g. "nav HUD unverified on this model"
)