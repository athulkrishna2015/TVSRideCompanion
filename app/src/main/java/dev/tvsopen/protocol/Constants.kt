// Protocol constants recovered from TVS Connect v8.14.1 (com.tvsm.connect),
// classes com.tvs.bike.core.protocol.FrameConstants / DataFrameType /
// FrameParameterType. Values are verified against the shipped DEX.
//
// Reconstructed independently; this is a clean-room reimplementation of the
// observable wire format, not copied code. See docs/PROTOCOL.md.

package dev.tvsopen.protocol

/** Start bytes that may lead a frame. `FrameConstants.StartId`. */
object StartId {
    const val A5 = 0x5A
    const val B5 = 0x5B
    const val B9 = 0x9B

    /**
     * The handshake challenge frame is also observed with a 0x9A lead in the
     * field (Jupiter report §2). The official enum only carries 0x5A/0x5B/0x9B,
     * so accept 0x9A when parsing inbound challenges.
     */
    const val A9 = 0x9A
}

/** Data-ID (byte 1) catalogue. `FrameConstants.DataId`. */
object DataId {
    const val PING = 0x4A          // outbound "mobile data" keepalive
    const val ODOMETER_FUEL = 0x10 // speed/odo/fuel/call-cmd bundle
    const val SERVICE = 0x11
    const val BATTERY_PCT = 0x13
    const val IGNITION = 0x18      // only broadcast with ignition ON
    const val ECONOMY = 0x19       // economy + distance-to-empty
    const val WIFI_PASSWORD = 0x29
    const val RANGE_37 = 0x37
    const val MUSIC = 0x54         // cluster button -> media
    const val DIALER = 0x6B        // answer/reject from cluster
    const val RANGE_6D = 0x6D
    const val RANGE_7D = 0x7D      // present in DataFrameType, absent from DataId
    const val RANGE_9D = 0x9D
    const val CHALLENGE = 0xF2     // inbound handshake challenge

    /** Outbound navigation control. Not in DataId — it is a phone->cluster frame. */
    const val NAV_CONTROL = 0x4E
    const val NAV_TEXT = 0x4F
    const val NAV_TEXT_ALT = 0x50

    /** Outbound plain-text rows (caller ID, song title, notifications). */
    const val MESSAGE_ROW1 = 0x4C
    const val MESSAGE_ROW2 = 0x63

    /** Handshake response. */
    const val CHALLENGE_RESPONSE = 0xF1

    fun isKnown(id: Int): Boolean = when (id) {
        0x06, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x18, 0x19, 0x23,
        0x29, 0x37, 0x54, 0x6B, 0x6D, 0x7D, 0x9D, 0xF2 -> true
        else -> false
    }
}

/**
 * Semantic field vocabulary. `FrameParameterType` in the official app carries
 * 131 of these; they are the byte-offset *meanings* the cluster attaches to
 * individual frames, and are the reason one parser can serve every model.
 */
enum class Field(val officialName: String) {
    START_BYTE("START_BYTE"),
    DATA_ID("DATA_ID"),
    SPEED("SPEED"),
    ODOMETER("ODOMETER"),
    FUEL_LEVEL("FUEL_LEVEL"),
    FUEL_SENSOR("FUEL_SENSOR"),
    AVERAGE_SPEED("AVERAGE_SPEED"),
    ECONOMY_STATUS("ECONOMY_STATUS"),
    TOP_SPEED("TOP_SPEED"),
    THROTTLE_POSITION("THROTTLE_POSITION"),
    BACKLIGHT("BACKLIGHT"),
    LOCATION_TAG("LOCATION_TAG"),
    SWITCH_STATUS("SWITCH_STATUS"),
    TIME_60KMPH("TIME_60KMPH"),
    AVERAGE_MILEAGE("AVERAGE_MILEAGE"),
    TRIP_F_METER("TRIP_F_METER"),
    RESERVED("RESERVED"),
    ENGINE_RPM("ENGINE_RPM"),
    CHECKSUM("CHECKSUM"),
    END_BYTE("END_BYTE"),
    VEHICLE_STATE("VEHICLE_STATE"),
    VEHICLE_STATE_1("VEHICLE_STATE_1"),
    VEHICLE_STATE_2("VEHICLE_STATE_2"),
    VEHICLE_STATE_3("VEHICLE_STATE_3"),
    SERVICE_REMINDER("SERVICE_REMINDER"),
    BATTERY_VOLTAGE("BATTERY_VOLTAGE"),
    SOFTWARE_VERSION("SOFTWARE_VERSION"),
    VEHICLE_DIAGNOSTICS("VEHICLE_DIAGNOSTICS"),
    VEHICLE_DIAGNOSTICS_1("VEHICLE_DIAGNOSTICS_1"),
    VEHICLE_DIAGNOSTICS_2("VEHICLE_DIAGNOSTICS_2"),
    TURN_INDICATOR_STATUS("TURN_INDICATOR_STATUS"),
    ENGINE_TEMPRATURE("ENGINE_TEMPRATURE"),
    INTAKE_AIR_TEMPRATURE("INTAKE_AIR_TEMPRATURE"),
    FUEL_INJECTION_TIME("FUEL_INJECTION_TIME"),
    GEAR_POSITION("GEAR_POSITION"),
    LEAN_ANGLE("LEAN_ANGLE"),
    CRUISING_RANGE("CRUISING_RANGE"),
    ACCELERATION("ACCELERATION"),
    TORQUE("TORQUE"),
    WHEEL_ANGLE_OFFSET("WHEEL_ANGEL_OFFSET"),
    TRIP_DISTANCE("TRIP_DISTANCE"),
    TRIP_TIME_HR("TRIP_TIME_HR"),
    TRIP_TIME_MIN("TRIP_TIME_MIN"),
    TRIP_MILEAGE("TRIP_MILEAGE"),
    TRIP_FUEL("TRIP_FUEL"),
    LAP_TIME("LAP_TIME"),
    LAP_NUMBER("LAP_NUMBER"),
    BEST_LAP("BEST_LAP"),
    LAP_TRIGGER("LAP_TRIGGER"),
    MILEAGE("MILLEAGE"),
    CURRENT_STATUS("CURRENT_STATUS"),
    FRAME_NO("FRAME_NO"),
    DND_STATE("DND_STATE"),
    CALL_MUTE_STATE("CALL_MUTE_STATE"),
    VEHICLE_LOCK_STATE("VEHICLE_LOCK_STATE"),
    TRUNK_STATE("TRUNK_STATE"),
    INCOMING_CALL_STATE("INCOMING_CALL_STATE"),
    MUSIC_COMMAND("MUSIC_COMMAND"),
    DIALER_BYTE_3("DIALER_BYTE_3"),
    DIALER_BYTE_4("DIALER_BYTE_4"),
    DIALER_BYTE_5("DIALER_BYTE_5"),
    DIALER_BYTE_6("DIALER_BYTE_6"),
    DIALER_BYTE_7("DIALER_BYTE_7"),
    DIALER_BYTE_8("DIALER_BYTE_8"),
    DIALER_BYTE_9("DIALER_BYTE_9"),
    DIALER_BYTE_10("DIALER_BYTE_10"),
    DIALER_BYTE_11("DIALER_BYTE_11"),
    DIALER_BYTE_12("DIALER_BYTE_12"),
    BATTERY_PERCENTAGE("BATTERY_PERCENTAGE"),
    D2E("D2E"),
    SELECTED_TRIP("SELECTED_TRIP"),
    TRIP_A_DISTANCE("TRIP_A_DISTANCE"),
    TRIP_A_MILEAGE("TRIP_A_MILEAGE"),
    TRIP_A_SPEED("TRIP_A_SPEED"),
    TRIP_A_AVG_SPEED("TRIP_A_AVG_SPEED"),
    TRIP_B_DISTANCE("TRIP_B_DISTANCE"),
    TRIP_B_MILEAGE("TRIP_B_MILEAGE"),
    TRIP_B_SPEED("TRIP_B_SPEED"),
    TRIP_B_AVG_SPEED("TRIP_B_AVG_SPEED"),
    OVERSPEED_THRESHOLD("OVERSPEED_THRESHOLD"),
    MIL_BLINK_CODE("MIL_BLINK_CODE"),
    VEHICLE_MODE("VEHICLE_MODE"),
    ISG_BLINK_CODE("ISG_BLINK_CODE"),
    CONNECTOR_STATUS("CONNECTOR_STATUS"),
    ISS_DURATION("ISS_DURATION"),
    OTA_STATUS("OTA_STATUS"),
    AVERAGE_FUEL_ECONOMY("AVERAGE_FUEL_ECONOMY"),
    INSTANTANEOUS_FUEL_ECONOMY("INSTANTANEOUS_FUEL_ECONOMY"),
    DISTANCE_TO_EMPTY("DISTANCE_TO_EMPTY"),
    ISS_COUNT("ISS_COUNT"),
    ENGINE_LOAD("ENGINE_LOAD"),
    ACCUMULATED_FUEL_INJECTION_TIME("ACCUMULATED_FUEL_INJECTION_TIME"),
    MANIFOLD_AIR_PRESSURE("MANIFOLD_AIR_PRESSURE"),
    BAROMETRIC_PRESSURE("BAROMETRIC_PRESSURE"),
    ENGINE_RUNNING_TIME("ENGINE_RUNNING_TIME"),
    DISTANCE_TRAVELED("DISTANCE_TRAVELED"),
    FUEL_INJECTION_VOLUME("FUEL_INJECTION_VOLUME"),
    CALIBRATION_COMMAND("CALIBRATION_COMMAND"),
    CALIBRATION_ID("CALIBRATION_ID"),
    DATA_LENGTH("DATA_LENGTH"),
    CALIBRATION_DATA_1("CALIBRATION_DATA_1"),
    CALIBRATION_DATA_2("CALIBRATION_DATA_2"),
    CALIBRATION_DATA_3("CALIBRATION_DATA_3"),
    CALIBRATION_DATA_4("CALIBRATION_DATA_4"),
    TRIP_STATUS("TRIP_STATUS"),
    VEHICLE_MODEL("VEHICLE_MODEL"),
    CAPTURE_SCREENSHOT("CAPTURE_SCREENSHOT"),
    OVERSPEED_SETTING("OVERSPEED_SETTING"),
    AVERAGE_MILEAGE_ALT("AVERAGR_MILEAGE"),
    RUN_TIME_SINCE_ENGINE_START("RUN_TIME_SINCE_ENGINE_START"),
    TELL_TALE_STATUS("TELL_TALE_STATUS"),
    SCREEN_MATRIX("SCREEN_MATRIX"),
    ABS_MIL_BLINK_CODE("ABS_MIL_BLINK_CODE"),
    BYTE_1("BYTE_1"), BYTE_2("BYTE_2"), BYTE_3("BYTE_3"), BYTE_4("BYTE_4"),
    BYTE_5("BYTE_5"), BYTE_6("BYTE_6"), BYTE_7("BYTE_7"), BYTE_8("BYTE_8"),
    BYTE_9("BYTE_9"), BYTE_10("BYTE_10"), BYTE_11("BYTE_11"), BYTE_12("BYTE_12"),
    BYTE_13("BYTE_13"), BYTE_14("BYTE_14"), BYTE_15("BYTE_15"), BYTE_16("BYTE_16"),
    BYTE_17("BYTE_17"), BYTE_18("BYTE_18");
}

/**
 * GATT identifiers, present verbatim in the official DEX.
 *
 * Transcribed from `com.tvs.bike.core.bikes.*BLEConnectionConfig`. The service,
 * write and read UUIDs are **identical** across Ntorq, Jupiter, Apache and
 * iQube; only the U388 EV family differs (it builds its service UUID from
 * `U388_SERVICE_UUID_KEY` at runtime).
 */
object Gatt {
    /** Shared petrol SmartXonnect service. "TVSM-VGSASBEN TORQ". */
    const val SERVICE = "5456534D-5647-5341-5342-454E544F5251"
    const val WRITE = "00005352-0000-1000-8000-00805f9b34fb"
    const val READ = "00005354-0000-1000-8000-00805f9b34fb"
    const val DEVICE_INFO = "00002902-0000-1000-8000-00805f9b34fb"

    /** Tail of the U388 EV service UUID; the prefix is assembled at runtime. */
    const val U388_SERVICE_UUID_KEY = "9-5342-454E544F5251"

    /** `BluetoothConstants.BLE_MTU`. */
    const val REQUESTED_MTU = 512
}

/** Per-model connection policy, from the `*BLEConnectionConfig` classes. */
data class GattConfig(
    val typeName: String,
    val serviceUuid: String = Gatt.SERVICE,
    val writeUuid: String = Gatt.WRITE,
    val readUuid: String = Gatt.READ,
    /** GATT write type. 2 = WRITE_NO_RESPONSE upstream. */
    val writeType: Int = 2,
    /** Only the iQube sets this true. */
    val autoConnect: Boolean = false,
) {
    companion object {
        // `shouldAutoConnect()` returned false for every BLE config except IQube.
        val DEFAULT = GattConfig("Ntorq")
    }
}

/** Checksum shared by the unencrypted outbound frames. */
object Checksum {
    /** `255 - (sum(bytes[0 until n]) % 256)` — Jupiter report §2.2. */
    fun compute(bytes: ByteArray, upTo: Int): Byte {
        var sum = 0
        for (i in 0 until upTo) sum += bytes[i].toInt() and 0xFF
        return ((255 - (sum % 256)) and 0xFF).toByte()
    }
}