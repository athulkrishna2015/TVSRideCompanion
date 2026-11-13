// Outbound frames that only the Jupiter-family clusters document.
//
// Source: the Scoot release feed's public simulator spec
// (sim/docs/PROTOCOL.md in sudo-Mystic/Scoot-releases), which was itself
// extracted from the Scoot app's protocol layer and dex-verified against
// TVS Connect v8.12.4. The release APK's SHA-256 was checked against that
// feed's manifest, so the two describe the same build.
//
// Everything here is additive: the Ntorq path keeps its existing builders.
// Where the two sources disagree, the disagreement is written down rather
// than silently resolved. See docs/PROTOCOL.md.

package dev.tvsopen.protocol

import java.math.BigInteger
import java.util.Calendar

/**
 * Frame builders for the outbound IDs the Scoot spec documents but this app
 * did not previously build.
 *
 * Every frame is 20 bytes except [pictogram] (21) and [callerFrames] (21 each),
 * which the spec calls out explicitly. None of them carries the phone-side
 * checksum except [calibration], which has its own formula at byte 9.
 */
object ClusterFrames {

    const val SIZE = 20

    private const val START = 0x5B
    private const val END = 0xFF

    // ---- media metadata ---------------------------------------------------

    /**
     * `0x61` / `0x62` / `0x65` music metadata.
     *
     * ```
     * [0]       0x5B
     * [1]       dataId
     * [2..17]   UTF-8 text, truncated to 16 bytes
     * [next]    0xFF, immediately after the text
     * [18]      0x00 padding
     * [19]      0x00
     * ```
     *
     * The spec's wording is "UTF-8 text (truncated to 16 bytes) + [FF] + zero
     * pad + [00]", i.e. the `0xFF` sits immediately after the text rather than
     * at the end. That is the shape the official builder produces, so it is
     * reproduced literally: text at 2..17, `0xFF` at 18, `0x00` at 19.
     *
     * **The data-id to field mapping is UNVERIFIED in both sources.** Our
     * official-constants transcription says 0x61 album, 0x63 artist, 0x65
     * artist-new; the Scoot spec says 0x61 title, 0x62 artist, 0x65 album.
     * Callers therefore pass the id explicitly instead of relying on a name.
     */
    fun mediaMeta(dataId: Int, text: String): ByteArray {
        val b = ByteArray(SIZE)
        b[0] = START.toByte()
        b[1] = dataId.toByte()
        val bytes = truncateUtf8(text, 16)
        bytes.copyInto(b, destinationOffset = 2)
        b[2 + bytes.size] = END.toByte()
        b[19] = 0x00
        return b
    }

    // ---- pictogram --------------------------------------------------------

    /**
     * `0x50` pictogram, addressed by name.
     *
     * `[5B, 50] + UTF-8 name + zero pad to 17 chars + [FF, 00]` — **21 bytes**,
     * and the two-byte `[FF, 00]` tail is verbatim from the dex, not a single
     * terminator.
     *
     * This is the one place the two sources collide outright: on the Ntorq the
     * `0x50` data id was transcribed as the second navigation text row
     * (`NAVIGATION_TEXT_ALT`). The Jupiter spec says `0x50` is the pictogram
     * name frame. They may both be true on different clusters, so the Jupiter
     * driver uses this builder and the Ntorq driver keeps the text-row decode.
     */
    fun pictogram(name: String): ByteArray {
        val b = ByteArray(21)
        b[0] = START.toByte()
        b[1] = 0x50
        val bytes = truncateUtf8(name, 17)
        bytes.copyInto(b, destinationOffset = 2)
        // Zero pad is already there; the tail is the pair, not one byte.
        b[19] = END.toByte()
        b[20] = 0x00
        return b
    }

    // ---- caller information ----------------------------------------------

    /** Caller-info sub IDs, from `TelephonyUtils.writeCallerInformationToCluster`. */
    object CallerSub {
        const val NATIONAL_NUMBER = 0x01
        const val CONTACT_NAME = 0x02
        const val COUNTRY_CODE = 0x03
    }

    /**
     * The three-packet `0x43` caller sequence.
     *
     * Each packet is `5B 43 subId <UTF-8 text> 00-pad to 17 chars FF` — 3 header
     * bytes, 17 text bytes, one terminator, so 21 bytes. Stock sends sub `01`
     * (national number), then `03` (country code), then `02` (contact name, only
     * when non-empty), in that order.
     *
     * The official app pads by `String.length()` (chars) while encoding UTF-8
     * bytes, so non-ASCII text can make the packet longer than its nominal size
     * and the terminator lands late. That behaviour is mirrored deliberately:
     * "fixing" it here would differ from the app the cluster was tested against.
     */
    fun callerFrames(
        nationalNumber: String?,
        countryCode: String?,
        contactName: String?,
    ): List<ByteArray> {
        val out = ArrayList<ByteArray>(3)
        if (!nationalNumber.isNullOrBlank()) {
            out += callerPacket(CallerSub.NATIONAL_NUMBER, nationalNumber)
        }
        if (!countryCode.isNullOrBlank()) {
            out += callerPacket(CallerSub.COUNTRY_CODE, countryCode)
        }
        if (!contactName.isNullOrBlank()) {
            out += callerPacket(CallerSub.CONTACT_NAME, contactName)
        }
        return out
    }

    private fun callerPacket(subId: Int, text: String): ByteArray {
        val b = ByteArray(21)
        b[0] = START.toByte()
        b[1] = 0x43
        b[2] = subId.toByte()
        val bytes = truncateUtf8(text, 17)
        bytes.copyInto(b, destinationOffset = 3)
        // Byte 20, after the 17 text bytes: 3 + 17 = 20.
        b[20] = END.toByte()
        return b
    }

    // ---- brightness (vehicle control) ------------------------------------

    /**
     * `0x5A 0xF1` cluster brightness.
     *
     * Start byte is `0x5A`, not `0x5B`. The wire brightness byte is `1` for
     * level 1 and `level * 2` for levels 2..5, which is why this is not simply
     * the level. The date block is the phone's own date with a zero-based
     * month, and nothing else in the frame is meaningful.
     *
     * There is no checksum: the frame is exempt from the phone-side formula,
     * same as the navigation control frame.
     */
    fun vehicleControl(level: Int, calendar: Calendar = Calendar.getInstance()): ByteArray {
        val l = level.coerceIn(1, 5)
        val b = ByteArray(SIZE)
        b[0] = 0x5A
        b[1] = 0xF1.toByte()
        b[2] = (if (l == 1) 1 else l * 2).toByte()
        b[3] = 9
        b[4] = 0
        b[5] = 2
        b[9] = calendar.get(Calendar.DAY_OF_MONTH).toByte()
        b[11] = calendar.get(Calendar.MONTH).toByte() // zero-based by design
        b[13] = (calendar.get(Calendar.YEAR) and 0xFF).toByte()
        b[15] = 1
        b[16] = 0x01
        b[19] = END.toByte()
        return b
    }

    // ---- first-connection user id ----------------------------------------

    /**
     * `0x22` first-connection user id.
     *
     * `[5B, 22] + idBytes + 0xFF pad + [FF]`, exactly 20 bytes. The payload is
     * `BigInteger.valueOf(id).toByteArray()`, with one `0x00` prefixed when
     * that is two bytes or shorter — the sign-byte quirk — and **`0xFF`** as
     * the pad fill. The Dart reference zero-pads; the dex fills with `0xFF`,
     * and the dex wins.
     *
     * Scoot never sends this automatically. Neither does this app by default:
     * the cluster's expectation is unverified, and a wrong id is a write with
     * no upside.
     */
    fun userId(id: Long): ByteArray {
        val b = ByteArray(SIZE)
        b[0] = START.toByte()
        b[1] = 0x22
        var idBytes = BigInteger.valueOf(id).toByteArray()
        if (idBytes.size <= 2) idBytes = byteArrayOf(0x00) + idBytes
        idBytes.copyInto(b, destinationOffset = 2)
        var i = 2 + idBytes.size
        while (i < SIZE - 1) {
            b[i] = END.toByte()
            i++
        }
        b[SIZE - 1] = END.toByte()
        return b
    }

    // ---- SMS --------------------------------------------------------------

    /**
     * `0x53` SMS notification: `[5B, 53] + text + zero pad to 17 + [FF]`.
     *
     * Model-gated in the official app to the U388/iQube family and never sent
     * to a Jupiter, so this is exposed but not wired to anything by default.
     */
    fun sms(text: String): ByteArray {
        val b = ByteArray(SIZE)
        b[0] = START.toByte()
        b[1] = 0x53
        truncateUtf8(text, 17).copyInto(b, destinationOffset = 2)
        b[19] = END.toByte()
        return b
    }

    // ---- music play/pause state ------------------------------------------

    /**
     * `0x60` play/pause state: `[5B, 60, 00, hour, 00, 00, 00, state, 0 x 11, FF]`.
     *
     * Byte 3 is the current *hour* (not a minute), and the state byte sits at
     * index 7. The state-byte values are UNVERIFIED, so the caller supplies
     * one rather than this builder guessing.
     */
    fun playPauseState(
        stateByte: Int,
        calendar: Calendar = Calendar.getInstance(),
    ): ByteArray {
        val b = ByteArray(SIZE)
        b[0] = START.toByte()
        b[1] = 0x60
        b[3] = calendar.get(Calendar.HOUR_OF_DAY).toByte()
        b[7] = stateByte.toByte()
        b[19] = END.toByte()
        return b
    }

    // ---- calibration ------------------------------------------------------

    /** Calibration command bytes at index 3 (the spec's `b1`). */
    object CalibrationCommand {
        const val IDLE_START_STOP_TIMER = 9
        const val FOLLOW_ME_HEADLAMP_TIMER = 18
        const val TSL_AUTO_CANCEL_TIMER = 23
    }

    /**
     * `0x73` calibration write, byte layout dex-verified.
     *
     * `[5B, 73, b2, b1, 04, valueLE32, checksum, 00 x 9, FF]`, with the
     * checksum at byte 9 computed over bytes 2..8 and a **little-endian** value.
     * This is the one frame in the protocol where little-endian is right.
     *
     * **Cluster acceptance is UNVERIFIED.** Nothing in this app calls this
     * automatically; it exists so a deliberate, owner-driven calibration test
     * does not have to be hand-assembled. Calibration frames change cluster
     * behaviour and must never be sent speculatively.
     */
    fun calibration(
        command: Int,
        value: Int,
        b2: Int = 0x0A,
    ): ByteArray {
        val b = ByteArray(SIZE)
        b[0] = START.toByte()
        b[1] = 0x73
        b[2] = b2.toByte()
        b[3] = command.toByte()
        b[4] = 0x04
        b[5] = (value and 0xFF).toByte()
        b[6] = ((value shr 8) and 0xFF).toByte()
        b[7] = ((value shr 16) and 0xFF).toByte()
        b[8] = ((value shr 24) and 0xFF).toByte()
        b[9] = calibrationChecksum(b)
        b[19] = END.toByte()
        return b
    }

    /** `(256 - (sum(bytes[2..8]) % 256)) and 0xFF`. */
    fun calibrationChecksum(frame: ByteArray): Byte {
        var sum = 0
        for (i in 2..8) sum += frame[i].toInt() and 0xFF
        return ((256 - (sum % 256)) and 0xFF).toByte()
    }

    // ---- helpers ----------------------------------------------------------

    /** Truncate to at most [maxBytes] of UTF-8 without splitting a character. */
    private fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(maxBytes)
        for (ch in text) {
            val enc = ch.toString().toByteArray(Charsets.UTF_8)
            if (out.size() + enc.size > maxBytes) break
            out.write(enc)
        }
        return out.toByteArray()
    }
}

/**
 * The maneuver pictogram catalogue, transcribed from the Jupiter navigation
 * architecture note. Ids are cluster-side values, not this app's own enum.
 *
 * This is a superset of [Pictograms]' Ntorq table: the roundabout families
 * (65-71 and 82-88) and the branch/fork/merge/exit split do not exist there.
 */
object Maneuvers {
    const val SHARP_LEFT = 0
    const val SLIGHT_LEFT_ALT = 1
    const val SLIGHT_LEFT = 2
    const val SHARP_RIGHT = 3
    const val SLIGHT_RIGHT_ALT = 4
    const val SLIGHT_RIGHT = 5
    const val U_TURN_LEFT = 6
    const val STRAIGHT = 7
    const val ARRIVED = 8
    const val T_JUNCTION_LEFT = 11
    const val T_JUNCTION_RIGHT = 12
    const val LEFT_BRANCH = 13
    const val RIGHT_BRANCH = 14
    const val FORK_LEFT = 15
    const val FORK_RIGHT = 16
    const val MERGE_LEFT = 17
    const val MERGE_RIGHT = 18
    const val EXIT_LEFT = 19
    const val EXIT_RIGHT = 20
    const val CROSSROAD = 21
    const val NO_ENTRY = 40
    const val U_TURN_RIGHT = 41

    // India drives on the left, so a roundabout's first exit is a left turn.
    const val ROUNDABOUT_SLIGHT_LEFT = 65
    const val ROUNDABOUT_SHARP_LEFT = 66
    const val ROUNDABOUT_SLIGHT_RIGHT = 67
    const val ROUNDABOUT_STRAIGHT = 68
    const val ROUNDABOUT_RIGHT = 69
    const val ROUNDABOUT_SHARP_RIGHT = 70
    const val ROUNDABOUT_U_TURN = 71
}