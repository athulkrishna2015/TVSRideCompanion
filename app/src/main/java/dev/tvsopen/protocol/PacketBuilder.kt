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
    /** Navigation text row 1 (`DATA_ID_NAVIGATIONDATA1` in the reference app). */
    const val NAVIGATION_TEXT = 0x4F
    /** Navigation text row 2 (`DATA_ID_NAVIGATIONDATA2`). */
    const val NAVIGATION_TEXT_ALT = 0x50
    /** Caller info. */
    const val CALLER = 0x43
    /** SMS / notification text. */
    const val SMS = 0x53
    /** Text row 1. */
    const val TEXT_ROW1 = 0x4C
    /** Text row 2 (also the artist-name packet upstream). */
    const val TEXT_ROW2 = 0x63
    /** Rider name shown by the cluster greeting. Official `DATA_ID_52`. */
    const val RIDER_NAME = 0x52
    const val ARTIST_NAME = 0x63
    const val ARTIST_NAME_NEW = 0x65
    const val ALBUM_NAME = 0x61
    /** Climate. */
    const val CLIMATE = 0x5D
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
     * Keepalive — **official layout**, verified with REA against
     * `BlePacketsProvider` in the official APK.
     *
     * All three official variants (`returnMobilePacket`,
     * `returnValidMobilePacket`, `returnMobilePacketWithACK`) build:
     *
     * ```
     * [0]      0x5B                    start byte
     * [1]      0x4A                    mobile data
     * [2]      signal / network level   0 if no SIM
     * [3]      battery                 from shared prefs
     * [4]      incoming call status
     * [5]      call mute state
     * [6]      packet validity         0 / 1 / 2
     * [7]      0x02
     * [8..10]  zero
     * [11]     network type
     * [12..18] zero                    (0x01 at [12] in the ACK variant)
     * [19]     0xFF                    end byte
     * ```
     *
     * **There is no checksum byte.** The official builder never writes one.
     * The ACK variant differs only by setting [12] = 0x01.
     */
    fun mobileData(state: DashState, ack: Boolean = false): ByteArray {
        val b = ByteArray(MOBILE_DATA_SIZE)
        b[0] = StartId.B5.toByte()
        b[1] = OutboundId.MOBILE_DATA.toByte()
        b[2] = state.signalBars.coerceIn(0, 5).toByte()
        b[3] = state.batteryBars.coerceIn(0, 5).toByte()
        b[4] = if (state.incomingCall) 1 else 0
        b[5] = if (state.callMuted) 1 else 0
        b[6] = 0
        b[7] = 0x02
        b[8] = 0; b[9] = 0; b[10] = 0
        b[11] = state.networkCode.toByte()
        if (ack) b[12] = 0x01
        b[19] = 0xFF.toByte()
        return b
    }

    /**
     * Keepalive — **enriched layout** used by the third-party Jupiter app.
     *
     * The Jupiter reverse-engineering report documents extra fields that the
     * official app leaves zeroed:
     *
     * ```
     * [2]      signal (high nibble) / battery (low nibble)
     * [4]      temperature °C + 40
     * [6..8]   hour, minute, second
     * [9]      AM/PM
     * [11]     network type
     * [12..14] day, month, year mod 100
     * [17]     find-my-scooter flag
     * [18]     checksum
     * ```
     *
     * That app drives real hardware, so the cluster tolerates this shape — but
     * it is **not** the official format, and it writes a checksum at [18] where
     * the official app writes zero. Prefer [mobileData] until a capture shows
     * the Ntorq needs time sync or a working find-my flag.
     */
    fun mobileDataEnriched(state: DashState, findMeByteIndex: Int = 17): ByteArray {
        val b = ByteArray(MOBILE_DATA_SIZE)
        b[0] = StartId.B5.toByte()
        b[1] = OutboundId.MOBILE_DATA.toByte()

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
     * Transcribed from `BleNavigationPacketBuilder.buildControlPacket` in the
     * working third-party Jupiter app — a *proven* layout, not an inference:
     *
     * ```
     * [0]  0x5A      start byte (90, START_BYTE_CONTROL)
     * [1]  0x4E      navigation control (78)
     * [2..3] distance to turn, metres   (saturating 16-bit)
     * [4..5] remaining time, minutes    (16-bit)
     * [6..8] remaining distance, metres (24-bit; falls back to distance if <= 0)
     * [9]  maneuver id (pictogram)
     * [10] text rows in use (1)
     * [11] active flag (1/0)
     * [12..15] zero
     * [16..19] zero
     * [19] 0xFF terminator
     * ```
     *
     * **No checksum.** The control frame does not carry one — only the
     * outbound mobile-data packet does.
     *
     * Distance and remaining-distance saturate rather than wrap: >= 65535 m
     * writes 0xFFFF, and remaining-distance <= 0 reuses distance-to-turn.
     */
    fun navigationControl(
        nav: NavStep,
        dataId: Int = OutboundId.NAVIGATION_CONTROL,
    ): ByteArray {
        val b = ByteArray(MOBILE_DATA_SIZE)
        b[0] = StartId.A5.toByte()
        b[1] = dataId.toByte()

        val d = nav.distanceToTurnMeters
        if (d >= 0xFFFF) {
            b[2] = 0xFF.toByte(); b[3] = 0xFF.toByte()
        } else {
            // Clamped at 0: the field is unsigned, so a negative distance would
            // write 0xFFFF — the *maximum*, i.e. "turn in 65 km" rather than
            // "unknown". Anything negative means unknown and becomes 0.
            putUInt16(b, 2, d.coerceIn(0, 0xFFFF))
        }

        putUInt16(b, 4, nav.etaMinutes.coerceIn(0, 0xFFFF))

        var remaining = nav.remainingDistanceMeters
        if (remaining <= 0) remaining = d
        putUInt24(b, 6, remaining.coerceIn(0, 0xFFFFFF))

        b[9] = nav.pictogram.toByte()
        // Upstream hardcodes one text row, but NavStep carries the count, so use
        // it. Ignoring the field made "rows" a lie that no caller could correct.
        b[10] = nav.rows.coerceIn(1, 0xFF).toByte()
        b[11] = if (nav.active) 1 else 0
        // [12..18] remain zero
        b[19] = 0xFF.toByte()
        return b
    }

    /**
     * Sanitise text for the cluster display.
     *
     * Transcribed from `BluetoothUtil.createMobileToClusterArray` in the
     * official APK, which is stricter than it first appears:
     *
     * 1. Emoji and symbol codepoints collapse to a single `@` — the regex covers
     *    `\p{So}`, `\p{Cn}`, U+1F300-1F6FF, U+1F900-1F9FF, U+1FA70-1FAFF,
     *    U+2600-26FF and ZWJ. Collapsing matters because an emoji is two UTF-16
     *    chars and the row is only seventeen wide.
     * 2. Any remaining character outside `[A-Za-z0-9 ]` becomes `X`.
     * 3. Text is truncated to the row width.
     * 4. Trimmed, then UTF-8 encoded.
     *
     * The cluster cannot render Indic or emoji glyphs. Indian street names are
     * routinely mixed-script, so step 2 is what keeps "NH-44 தென்" from turning
     * into mojibake on the dash.
     */
    // Punctuation to fold to a space: appositive dash, slash, comma, full stop
        // and the rest. All render as a gap, and a gap reads correctly where an
        // 'X' read as corruption.
        //
        // '-' and '+' are deliberately **kept**, not folded. '-' separates the
        // two halves of "NH-44" and folding it gives "NH 44", which is a
        // different road name; and dropping '+' from "+919876543210" silently
        // turns an international number into a local one, which is exactly
        // wrong for caller ID. Both are ASCII printable and very likely
        // renderable, though that is **unverified on the cluster** — worth
        // confirming on the next live session.
        private const val KEPT_PUNCTUATION = "+-"

        // Run of anything that folds to a space.
        private val SPACE_RUN = Regex(" +")

        fun sanitize(text: String, maxBytes: Int = 17): ByteArray {
        // Collapse each emoji/symbol run to a single '@', exactly as upstream
        // `createMobileToClusterArray` does. '@' is the official unknown-glyph
        // marker — a dropped emoji would silently close the gap between two
        // words, and any other choice would differ from what the cluster's own
        // app sends for the same input.
        val symbolised = EMOJI_OR_SYMBOL.replace(text, "@")

        val filtered = buildString(symbolised.length) {
            var lastWasSpace = false
            for (ch in symbolised) {
                val ok = (ch in 'A'..'Z') || (ch in 'a'..'z') ||
                    (ch in '0'..'9') || ch in KEPT_PUNCTUATION
                when {
                    ok -> {
                        append(ch)
                        lastWasSpace = false
                    }
                    // Any other separator becomes one space, never two in a row.
                    !lastWasSpace -> {
                        append(' ')
                        lastWasSpace = true
                    }
                }
            }
        }

        // After sanitising, `filtered` is pure ASCII, so this branch and the
        // byte-accurate one below are always equivalent. It was kept because
        // the check exists in the upstream helper and a future change to the
        // character filter must not silently make the ASCII path wrong.
        val clamped = if (isAsciiPrintable(filtered)) {
            filtered.take(maxBytes)
        } else {
            truncateToBytes(filtered, maxBytes)
        }

        return clamped.replace(SPACE_RUN, " ").trim().toByteArray(Charsets.UTF_8)
    }

    /**
     * Whether [sanitize] produced something the cheap path can measure.
     *
     * Must stay in step with the character filter in [sanitize], including
     * [KEPT_PUNCTUATION] — when these two disagreed, every string containing a
     * '+' or a '-' silently fell through to the byte-accurate branch and came
     * out differently from the identical string without one. Five tests caught
     * it; the check is worth keeping only if it is kept correct.
     */
    private fun isAsciiPrintable(s: String): Boolean =
        s.all {
            (it in 'A'..'Z') || (it in 'a'..'z') || (it in '0'..'9') ||
                it == ' ' || it in KEPT_PUNCTUATION
        }

    /** Byte-accurate truncation that never splits a multibyte character. */
    private fun truncateToBytes(text: String, maxBytes: Int): String {
        val sb = StringBuilder()
        var used = 0
        for (ch in text) {
            val size = ch.toString().toByteArray(Charsets.UTF_8).size
            // `>=` rather than `>`: the official helper packs 17 *bytes* into a
            // 17-character field, so a multi-byte character would overrun and
            // corrupt the trailing terminator. Never exceed the budget.
            if (used + size > maxBytes) break
            sb.append(ch)
            used += size
        }
        return sb.toString()
    }

    private val EMOJI_OR_SYMBOL = Regex(
        "[\\p{So}\\p{Cn}" +
            "\\x{1F300}-\\x{1F6FF}" +
            "\\x{1F900}-\\x{1F9FF}" +
            "\\x{1FA70}-\\x{1FAFF}" +
            "\\x{2600}-\\x{26FF}" +
            "\\u200D]"
    )

    /**
     * Navigation text row.
     *
     * `0x5B 0x4F` (`DATA_ID_NAVIGATIONDATA1 == 79`), 17 bytes of UTF-8 text
     * zero-padded to fill, `0xFF` terminator. **No checksum.**
     *
     * Text is passed through [sanitize]; the cluster rejects non-ASCII glyphs.
     */
    /**
     * One navigation text line. `0x5B 0x4F` by default, `0x5B 0x50` for the
     * second line via [dataId].
     */
    fun navigationText(text: String, dataId: Int = OutboundId.NAVIGATION_TEXT): ByteArray {
        val b = ByteArray(MOBILE_DATA_SIZE)
        b[0] = StartId.B5.toByte()
        b[1] = dataId.toByte()
        val bytes = sanitize(text)
        // 20 - 3: two header bytes and the 0xFF terminator.
        val n = minOf(bytes.size, b.size - 3)
        if (n > 0) bytes.copyInto(b, destinationOffset = 2, endIndex = n)
        b[19] = 0xFF.toByte()
        return b
    }

    /**
     * A row of plain text for caller ID / notifications.
     *
     * `0x5B 0x4C` (76) for row 1 and `0x5B 0x63` (99) for row 2, matching
     * `createMobileToClusterArray`. Same 17-byte sanitised payload.
     */
    fun textRow(dataId: Int, text: String, maxChars: Int = 17): ByteArray {
        val b = ByteArray(MOBILE_DATA_SIZE)
        b[0] = StartId.B5.toByte()
        b[1] = dataId.toByte()
        val bytes = sanitize(text, maxChars)
        // Bound the copy by the destination, not by 17. `maxChars` above 17 used
        // to throw IndexOutOfBounds because 2 + 17 + 1 already reaches the
        // terminator at index 19.
        val n = minOf(bytes.size, b.size - 3)
        if (n > 0) bytes.copyInto(b, destinationOffset = 2, endIndex = n)
        b[19] = 0xFF.toByte()
        return b
    }

    /**
     * Rider-name frame for the cluster greeting.
     *
     * `0x5B 0x52`, 17 sanitised characters, zero-padded, `0xFF` terminator.
     * Transcribed from the official app three independent ways:
     * `BluetoothUtil.createRiderNameArray` inlines `{91, 82}` in its bytecode
     * (v2.13.0), the same builder's array-data payload in v8.14.1 reads
     * `[5B 52]`, and `NtorqMobileToCluster.sendRiderName` passes the name
     * through `createMobileToClusterArray(name, DATA_ID_52)` with
     * `DATA_ID_52 = 82` in `BluetoothConstants`.
     *
     * The sanitize path (not raw UTF-8) matches the current official builder.
     * Sent on every connect, like the official `sendInitialData`, and whenever
     * the saved name changes while connected.
     */
    fun riderName(name: String): ByteArray = textRow(OutboundId.RIDER_NAME, name)

    /** Caller info: number, name and a category/state byte. */
    fun callerInfo(number: String, name: String?, state: Int = Command.CALL_RINGING): ByteArray {
        val text = if (name.isNullOrBlank()) number else "$name · $number"
        return textRow(OutboundId.CALLER, text)
    }

    /**
     * Removed: `trafficSignal()` built `0x5B 0x52` text frames for
     * traffic-signal hints, but `0x52` under `0x5B` is the **rider-name**
     * channel (`DATA_ID_52`, verified three ways — see [riderName]). Sending
     * TSL text there would overwrite the greeting, and TSL on the Ntorq is
     * calibration-based (`sendTslSignal`/`sendTslTimer`) rather than a text
     * row anyway. The `VEHICLE_MODE = 0x52` constant went with it: the official
     * vehicle-control frame leads with `0x5A`, a different namespace.
     */

    /** Calibration write, per the `NtorqCalibrationResponse` layout. */
    fun calibration(command: Int, id: Int, data: ByteArray): ByteArray {
        val b = ByteArray(MOBILE_DATA_SIZE)
        b[0] = StartId.A5.toByte()
        b[1] = 0x37
        b[2] = command.toByte()
        b[3] = id.toByte()
        b[4] = data.size.toByte()
        // ByteArray.take() returns List<Byte>, which has no copyInto — convert first.
        data.take(4).toByteArray().copyInto(b, destinationOffset = 5)
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
