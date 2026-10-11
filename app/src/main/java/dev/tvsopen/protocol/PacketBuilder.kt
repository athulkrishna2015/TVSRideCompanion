// Outbound packet builders.
//
// Layouts transcribed from TVS Connect v8.14.1,
// com.tvs.bluetooth.core.utils.BlePacketsProvider (classes7/12.dex) and
// com.tvs.bluetooth.core.utils.BluetoothConstants.
//
// Only what is needed to get a cluster talking and show navigation is
// implemented. Everything here is Confidence.INHERITED_UNVERIFIED or worse
// until exercised on hardware — see docs/CAPTURE.md.

package dev.tvsopen.protocol

/**
 * Outbound data IDs. These are a different namespace from [DataId] (which is
 * mostly inbound); the official app keeps them in `BluetoothConstants`.
 */
object OutboundId {
    /** "Mobile data" keepalive / find-my-scooter. */
    const val MOBILE_DATA = 0x4A
    /** Navigation control (the arrow + distances). */
    const val NAVIGATION_CONTROL = 0x4E
    /** Alternate navigation control. */
    const val NAVIGATION_CONTROL_2 = 0x49
    /** Caller info. */
    const val CALLER = 0x43
    /** SMS / notification text. */
    const val SMS = 0x53
    /** Text row 1. */
    const val TEXT_ROW1 = 0x4C
    /** Text row 2 (also the artist-name packet upstream). */
    const val TEXT_ROW2 = 0x63
    const val ARTIST_NAME = 0x63
    const val ARTIST_NAME_NEW = 0x65
    const val ALBUM_NAME = 0x61
    /** Climate. */
    const val CLIMATE = 0x5D
    /** Vehicle mode / ride mode. */
    const val VEHICLE_MODE = 0x52
    /** Challenge response. */
    const val CHALLENGE_RESPONSE = 0xF1
    const val CHALLENGE = 0xF2
}

/** Music/call command bytes, from `BluetoothConstants`. */
object Command {
    const val VOL_UP = 0
    const val VOL_DOWN = 1
    const val PLAY = 2
    const val PAUSE = 3
    const val TOGGLE = 4
    const val NEXT = 5
    const val PREV = 6

    const val CALL_NEUTRAL = 0
    const val CALL_RINGING = 0
    const val CALL_ACCEPTED = 1
    const val CALL_REJECTED = 2
    const val CALL_DISCONNECTED = 2
    const val CALL_ENDED = 5
    const val CALL_IDLE = -1

    const val VALID_PACKET = 0
    const val INVALID_PACKET = 1
}

/** Traffic-signal-light assistance actions. */
enum class TslAction(val code: Int) {
    NO_ACTION(0), OFF(1), LEFT(2), RIGHT(3), HAZARD(4), DO_NOTHING(5);
}

/** Calibration commands and ids, from `BluetoothConstants`. */
object Calibration {
    const val CLEAR_LOG = 0
    const val MODIFY_PARAMS = 1
    const val WRITE = 2
    const val WRITE_REQUEST = 3
    const val READ = 4

    const val ID_FOLLOW_ME_HEAD_LAMP = 0
    const val ID_TSL_ON_OFF = 1
    const val ID_IDLE_START_STOP = 2
}

/**
 * Builds outbound frames.
 *
 * Every frame the cluster accepts is start byte, data id, a payload, a
 * checksum, and a `0xFF` terminator, padded to a fixed length. The exact
 * lengths are model-specific; the ones below follow the keepalive shape and
 * are the sizes most consistent with `BlePacketsProvider`.
 */
object PacketBuilder {

    /** Total frame size for the mobile-data packet. */
    const val MOBILE_DATA_SIZE = 20

    /**
     * Keepalive / find-my-scooter.
     *
     * `0x5B 0x4A`, payload, checksum at index 18, `0xFF` terminator.
     * Byte 17 is the find-me flag the Jupiter report identified as the
     * reliable way to make the cluster flash its indicators.
     */
    fun mobileData(state: DashState, findMeByteIndex: Int = 17): ByteArray {
        val b = ByteArray(MOBILE_DATA_SIZE)
        b[0] = StartId.B5.toByte()
        b[1] = OutboundId.MOBILE_DATA.toByte()

        // signal in the high nibble, battery in the low nibble
        b[2] = (((state.signalBars and 0x0F) shl 4) or (state.batteryBars and 0x0F)).toByte()
        b[3] = 0
        b[4] = (state.temperatureC + 40).toByte()

        val cal = java.util.Calendar.getInstance()
        var hour = cal.get(java.util.Calendar.HOUR)
        if (hour == 0) hour = 12
        b[5] = 0
        b[6] = hour.toByte()
        b[7] = cal.get(java.util.Calendar.MINUTE).toByte()
        b[8] = cal.get(java.util.Calendar.SECOND).toByte()
        b[9] = if (cal.get(java.util.Calendar.AM_PM) == java.util.Calendar.PM) 1 else 0

        b[10] = 0
        b[11] = state.networkCode.toByte()
        b[12] = cal.get(java.util.Calendar.DAY_OF_MONTH).toByte()
        b[13] = (cal.get(java.util.Calendar.MONTH) + 1).toByte()
        b[14] = (cal.get(java.util.Calendar.YEAR) % 100).toByte()
        b[15] = 0
        b[16] = 0
        b[findMeByteIndex] = if (state.findMyScooter) 1 else 0
        b[18] = Checksum.compute(b, 18)
        b[19] = 0xFF.toByte()
        return b
    }

    /**
     * Navigation control: distance, ETA, remaining distance, pictogram.
     *
     * `0x5B 0x4E` per the Jupiter report §4.1. The cluster firmware renders the
     * distance-to-turn integer itself on line 1, so this packet mainly carries
     * the arrow and the two distances.
     */
    fun navigationControl(
        nav: NavStep,
        dataId: Int = OutboundId.NAVIGATION_CONTROL,
    ): ByteArray {
        val b = ByteArray(MOBILE_DATA_SIZE)
        b[0] = StartId.B5.toByte()
        b[1] = dataId.toByte()
        putUInt16(b, 2, nav.distanceToTurnMeters.coerceIn(0, 0xFFFF))
        putUInt16(b, 4, nav.etaMinutes.coerceIn(0, 0xFFFF))
        putUInt24(b, 6, nav.remainingDistanceMeters.coerceIn(0, 0xFFFFFF))
        b[9] = nav.pictogram.toByte()
        b[10] = nav.rows.toByte()
        b[18] = Checksum.compute(b, 18)
        b[19] = 0xFF.toByte()
        return b
    }

    /**
     * A row of plain text. Upstream the caller-info and SMS paths build these
     * through `generateByteArrayForName`, which pads to the frame length and
     * terminates with `0xFF`.
     */
    fun textRow(dataId: Int, text: String, maxChars: Int = 17): ByteArray {
        val payload = text.take(maxChars).toByteArray(Charsets.UTF_8)
        val b = ByteArray(MOBILE_DATA_SIZE)
        b[0] = StartId.B5.toByte()
        b[1] = dataId.toByte()
        payload.copyInto(b, destinationOffset = 2)
        b[18] = Checksum.compute(b, 18)
        b[19] = 0xFF.toByte()
        return b
    }

    /** Caller info: number, name and a category/state byte. */
    fun callerInfo(number: String, name: String?, state: Int = Command.CALL_RINGING): ByteArray {
        val text = if (name.isNullOrBlank()) number else "$name · $number"
        return textRow(OutboundId.CALLER, text)
    }

    /** Traffic-signal-light hint shown on the cluster. */
    fun trafficSignal(action: TslAction): ByteArray = textRow(
        OutboundId.VEHICLE_MODE,
        when (action) {
            TslAction.LEFT -> "← TURN LEFT"
            TslAction.RIGHT -> "TURN RIGHT →"
            TslAction.HAZARD -> "⚠ HAZARD AHEAD"
            TslAction.OFF -> ""
            else -> ""
        }.take(17),
    )

    /** Calibration write, per the `NtorqCalibrationResponse` layout. */
    fun calibration(command: Int, id: Int, data: ByteArray): ByteArray {
        val b = ByteArray(MOBILE_DATA_SIZE)
        b[0] = StartId.A5.toByte()
        b[1] = 0x37
        b[2] = command.toByte()
        b[3] = id.toByte()
        b[4] = data.size.toByte()
        data.take(4).copyInto(b, destinationOffset = 5)
        b[9] = Checksum.compute(b, 9)
        b[19] = 0xFF.toByte()
        return b
    }

    // ---- little-endian helpers ----

    private fun putUInt16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun putUInt24(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
    }
}