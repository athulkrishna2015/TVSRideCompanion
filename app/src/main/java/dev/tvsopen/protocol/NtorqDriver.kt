// Ntorq protocol driver.
//
// Byte layouts transcribed from TVS Connect v8.14.1,
// com.tvs.bike.core.protocol.ntorq.* (classes12.dex + classes7.dex).
//
// The official app declares offsets with @ParsingMeta(start = N), where N is
// **1-based**: start = 1 is the start byte, start = 2 is the data id. The
// layouts below are converted to 0-based array indices.
// `length = L` means a multi-byte little-endian integer spanning L bytes.
//
// Every layout here is Confidence.VERIFIED_FROM_APK except where noted.

package dev.tvsopen.protocol

/**
 * A field's position and width within a frame.
 *
 * Values are stored **raw** — no scaling here. An earlier version applied
 * `scale` in [FrameLayout.decode] while `Session` divided again, so every
 * scaled quantity (odometer, throttle, 0-60, trip F) arrived at the UI divided
 * twice: the odometer read ~10x low. Engineering conversion lives in exactly
 * one place, `Session.onFrame`, which cites the byte index next to each use.
 */
data class FieldSpec(
    val field: Field,
    /** 0-based byte index. */
    val offset: Int,
    /** Byte count; 1 unless the field is a multi-byte integer. */
    val length: Int = 1,
    val signed: Boolean = false,
) {
    fun read(buf: ByteArray): Long? {
        if (offset < 0 || offset + length > buf.size) return null
        var acc = 0L
        for (i in 0 until length) {
            // **Big-endian.** This was `shl (8 * i)`, which assembles
            // little-endian, and the cluster sends big-endian. Confirmed on
            // hardware: the odometer bytes `02 88 2A` are 16,593 km (165,930
            // tenths), and the dashboard was reading 278,733.0 because
            // `0x2A8802` is those three bytes reversed. It silently corrupted
            // every multi-byte field — odometer, trip F, ISS duration and count
            // — and small values look plausible enough that only a bike that has
            // actually been ridden exposes it.
            acc = acc or ((buf[offset + i].toLong() and 0xFF) shl (8 * (length - 1 - i)))
        }
        if (signed && length < 8) {
            val signBit = 1L shl (length * 8 - 1)
            if (acc and signBit != 0L) acc -= (1L shl (length * 8))
        }
        return acc
    }
}

/**
 * One inbound frame layout. All SmartXonnect speedometer frames are a fixed
 * 20 bytes: index 0 = start, index 1 = data id, index 19 = end (0xFF), with
 * checksum at index 18 for the frames that carry one.
 */
data class FrameLayout(
    val dataId: Int,
    val type: DataFrameType,
    val fields: List<FieldSpec>,
    val checksumAt: Int? = 18,
) {
    fun decode(raw: ByteArray): DecodedFrame? {
        if (raw.size < 20) return null
        if ((raw[0].toInt() and 0xFF) != StartId.A5) return null
        if ((raw[1].toInt() and 0xFF) != dataId) return null

        val fieldMap = LinkedHashMap<Int, Field>()
        val values = LinkedHashMap<Int, Long>()
        for (spec in fields) {
            val v = spec.read(raw) ?: continue
            fieldMap[spec.offset] = spec.field
            values[spec.offset] = v
        }
        return DecodedFrame(StartId.A5, dataId, fieldMap, values, raw)
    }
}

/** Frame catalogue, mirroring `DataFrameType` in the official app. */
enum class DataFrameType(val officialName: String) {
    CHALLENGE_FRAME("CHALLENGE_FRAME"),
    SPEEDOMETER_DATA_5A_10_FRAME("SPEEDOMETER_DATA_5A_10_FRAME"),
    SPEEDOMETER_DATA_5A_11_FRAME("SPEEDOMETER_DATA_5A_11_FRAME"),
    BATTERY_PERCENTAGE_DATA_5A_13_FRAME("BATTERY_PERCENTAGE_DATA_5A_13_FRAME"),
    SPEEDO_METER_1("SPEEDO_METER_1"),
    SPEEDO_METER_2("SPEEDO_METER_2"),
    SPEEDO_METER_3("SPEEDO_METER_3"),
    SPEEDO_METER_4("SPEEDO_METER_4"),
    SPEEDO_METER_5("SPEEDO_METER_5"),
    DATA_LOGGED("DATA_LOGGED"),
    DATA_SPEED_LOGGED("DATA_SPEED_LOGGED"),
    INVALID_FRAME("INVALID_FRAME"),
    DOC_TRANSFER_FRAME("DOC_TRANSFER_FRAME"),
    MUSIC_FRAME("MUSIC_FRAME"),
    DIALER_FRAME("DIALER_FRAME"),
    USER_ID_VALIDATION_FRAME("USER_ID_VALIDATION_FRAME"),
    SPEEDOMETER_TRIP_U546("SPEEDOMETER_TRIP_U546"),
    SPEEDOMETER_DATA_5A_18_FRAME("SPEEDOMETER_DATA_5A_18_FRAME"),
    SPEEDOMETER_DATA_5A_19_FRAME("SPEEDOMETER_DATA_5A_19_FRAME"),
    SPEEDOMETER_DATA_5A_37_FRAME("SPEEDOMETER_DATA_5A_37_FRAME"),
    SPEEDOMETER_DATA_5A_54_FRAME("SPEEDOMETER_DATA_5A_54_FRAME"),
    SPEEDOMETER_DATA_5A_9D_FRAME("SPEEDOMETER_DATA_5A_9D_FRAME"),
    SPEEDOMETER_DATA_5A_6D_FRAME("SPEEDOMETER_DATA_5A_6D_FRAME"),
    SPEEDOMETER_DATA_5A_12_FRAME("SPEEDOMETER_DATA_5A_12_FRAME"),
    SPEEDOMETER_DATA_5A_16_FRAME("SPEEDOMETER_DATA_5A_16_FRAME"),
    SPEEDOMETER_DATA_5A_6B_FRAME("SPEEDOMETER_DATA_5A_6B_FRAME"),
    SPEEDOMETER_DATA_5A_7D_FRAME("SPEEDOMETER_DATA_5A_7D_FRAME"),
    WIFI_PASSWORD_5A_29_FRAME("WIFI_PASSWORD_5A_29_FRAME");
}

/**
 * Ntorq frame table, transcribed from `NtorqFrameIdentifier.getFrameConfig()`.
 *
 * That method returns `mapOf(...)` keyed by the **data id** (byte index 1),
 * which is exactly how frames are dispatched at runtime:
 *
 *   16 -> SPEEDOMETER_DATA_5A_10_FRAME -> NtorqSpeedOMeter1
 *   17 -> SPEEDOMETER_DATA_5A_11_FRAME -> NtorqSpeedOMeter2
 *   25 -> SPEEDOMETER_DATA_5A_19_FRAME -> NtorqSpeedOMeter3
 *   24 -> SPEEDOMETER_DATA_5A_18_FRAME -> NtorqSpeedOMeter4
 *   55 -> SPEEDOMETER_DATA_5A_37_FRAME -> NtorqCalibrationResponse
 *  107 -> MUSIC_FRAME                   -> NtorqDataFrame5A54
 *   41 -> WIFI_PASSWORD_5A_29_FRAME     -> NtorqWifiPasswordFrameData
 *   84 -> SPEEDOMETER_DATA_5A_54_FRAME  -> NtorqDataFrame5A54ACK
 *
 * Note `IncomingFrameIdentifier.identify()` indexes `byteArray[1]`, confirming
 * the data-id dispatch, and `BtData.isValidFrame()` accepts start bytes
 * 0x9B and 0x5A only.
 */
object NtorqFrames {

    /** NtorqSpeedOMeter1 — speed, odometer, fuel, trip, RPM. */
    val SM1 = FrameLayout(
        dataId = 0x10,
        type = DataFrameType.SPEEDOMETER_DATA_5A_10_FRAME,
        fields = listOf(
            FieldSpec(Field.START_BYTE, 0),
            FieldSpec(Field.DATA_ID, 1),
            FieldSpec(Field.SPEED, 2),
            FieldSpec(Field.ODOMETER, 3, length = 3),
            FieldSpec(Field.FUEL_LEVEL, 6),
            FieldSpec(Field.AVERAGE_SPEED, 7),
            FieldSpec(Field.TRIP_STATUS, 8),
            FieldSpec(Field.TOP_SPEED, 9),
            FieldSpec(Field.THROTTLE_POSITION, 10),
            // Official app reads two nibbles out of this single byte:
            //   getBacklightIllumination() -> hex char 0 (high nibble)
            //   getBtPairMode()            -> hex char 1 (low nibble)
            FieldSpec(Field.BACKLIGHT, 11),
            FieldSpec(Field.LOCATION_TAG, 12),
            FieldSpec(Field.TIME_60KMPH, 13),
            FieldSpec(Field.TRIP_F_METER, 14, length = 3),
            FieldSpec(Field.ENGINE_RPM, 17, length = 2),
            FieldSpec(Field.END_BYTE, 19),
        ),
    )

    /** NtorqSpeedOMeter2 — vehicle state, tell-tales, software version. */
    val SM2 = FrameLayout(
        dataId = 0x11,
        type = DataFrameType.SPEEDOMETER_DATA_5A_11_FRAME,
        fields = listOf(
            FieldSpec(Field.START_BYTE, 0),
            FieldSpec(Field.DATA_ID, 1),
            FieldSpec(Field.FUEL_SENSOR, 2),
            FieldSpec(Field.VEHICLE_STATE, 3),
            FieldSpec(Field.SERVICE_REMINDER, 4),
            FieldSpec(Field.VEHICLE_STATE_1, 6),
            FieldSpec(Field.SOFTWARE_VERSION, 7),
            FieldSpec(Field.MIL_BLINK_CODE, 8),
            FieldSpec(Field.VEHICLE_MODEL, 9),
            FieldSpec(Field.VEHICLE_DIAGNOSTICS, 10),
            FieldSpec(Field.ISG_BLINK_CODE, 11),
            FieldSpec(Field.TURN_INDICATOR_STATUS, 12),
            FieldSpec(Field.CONNECTOR_STATUS, 13),
            FieldSpec(Field.END_BYTE, 19),
        ),
    )

    /** NtorqSpeedOMeter3 — fuel economy, distance to empty, OTA state. */
    val SM3 = FrameLayout(
        dataId = 0x19,
        type = DataFrameType.SPEEDOMETER_DATA_5A_19_FRAME,
        fields = listOf(
            FieldSpec(Field.START_BYTE, 0),
            FieldSpec(Field.DATA_ID, 1),
            FieldSpec(Field.ISS_DURATION, 2, length = 3),
            FieldSpec(Field.OTA_STATUS, 5),
            FieldSpec(Field.AVERAGE_FUEL_ECONOMY, 8),
            FieldSpec(Field.INSTANTANEOUS_FUEL_ECONOMY, 9),
            FieldSpec(Field.DISTANCE_TO_EMPTY, 11, length = 2),
            FieldSpec(Field.ISS_COUNT, 15, length = 3),
        ),
    )

    /**
     * NtorqSpeedOMeter4 — engine diagnostics block.
     *
     * The official class declares BAROMETRIC_PRESSURE twice with different
     * lengths at index 6 and index 9, and two RESERVED entries both at index 6.
     * That is an upstream annotation defect (duplicate offsets), so only the
     * unambiguous entries are transcribed here.
     */
    val SM4 = FrameLayout(
        dataId = 0x18,
        type = DataFrameType.SPEEDOMETER_DATA_5A_18_FRAME,
        fields = listOf(
            FieldSpec(Field.START_BYTE, 0),
            FieldSpec(Field.DATA_ID, 1),
            FieldSpec(Field.ENGINE_LOAD, 2),
            FieldSpec(Field.ACCUMULATED_FUEL_INJECTION_TIME, 3, length = 2),
            FieldSpec(Field.BAROMETRIC_PRESSURE, 6),
            FieldSpec(Field.INTAKE_AIR_TEMPRATURE, 7),
            FieldSpec(Field.ENGINE_TEMPRATURE, 8),
            FieldSpec(Field.FUEL_INJECTION_TIME, 9, length = 2),
            FieldSpec(Field.BATTERY_VOLTAGE, 11),
            FieldSpec(Field.ENGINE_RUNNING_TIME, 12, length = 2),
            FieldSpec(Field.DISTANCE_TRAVELED, 14, length = 2),
            FieldSpec(Field.FUEL_INJECTION_VOLUME, 16, length = 2),
            FieldSpec(Field.RESERVED, 18),
            FieldSpec(Field.END_BYTE, 19),
        ),
    )

    /**
     * NtorqCalibrationResponse — instrument calibration.
     *
     * Byte 2 is declared DATA_ID, which conflicts with the dispatch table
     * mapping 0x37 to this class; the real id must be at index 1. Recorded as
     * a discrepancy in docs/PROTOCOL.md.
     */
    val CALIBRATION = FrameLayout(
        dataId = 0x37,
        type = DataFrameType.SPEEDOMETER_DATA_5A_37_FRAME,
        fields = listOf(
            FieldSpec(Field.START_BYTE, 0),
            FieldSpec(Field.DATA_ID, 1),
            FieldSpec(Field.CALIBRATION_COMMAND, 2),
            FieldSpec(Field.CALIBRATION_ID, 3),
            FieldSpec(Field.DATA_LENGTH, 4),
            FieldSpec(Field.CALIBRATION_DATA_1, 5),
            FieldSpec(Field.CALIBRATION_DATA_2, 6),
            FieldSpec(Field.CALIBRATION_DATA_3, 7),
            FieldSpec(Field.CALIBRATION_DATA_4, 8),
            FieldSpec(Field.CHECKSUM, 9),
            FieldSpec(Field.RESERVED, 10, length = 9),
            FieldSpec(Field.END_BYTE, 19),
        ),
    )

    /**
     * NtorqDataFrame5A54 / ACK — media command.
     *
     * Per the dispatch table above, 0x6B (107) -> MUSIC_FRAME and 0x54 (84) ->
     * SPEEDOMETER_DATA_5A_54_FRAME — which is the ACK class,
     * `NtorqDataFrame5A54ACK`, despite the name suggesting otherwise.
     */
    fun music(isAck: Boolean) = FrameLayout(
        dataId = if (isAck) 0x54 else 0x6B,
        type = if (isAck) DataFrameType.SPEEDOMETER_DATA_5A_54_FRAME else DataFrameType.MUSIC_FRAME,
        fields = listOf(
            FieldSpec(Field.START_BYTE, 0),
            FieldSpec(Field.DATA_ID, 1),
            FieldSpec(Field.MUSIC_COMMAND, 2),
            FieldSpec(Field.RESERVED, 3),
            FieldSpec(Field.END_BYTE, 19),
        ),
    )

    /** NtorqWifiPasswordFrameData — 16 raw bytes spanning indices 2..18. */
    val WIFI_PASSWORD = FrameLayout(
        dataId = 0x29,
        type = DataFrameType.WIFI_PASSWORD_5A_29_FRAME,
        fields = listOf(
            FieldSpec(Field.START_BYTE, 0),
            FieldSpec(Field.DATA_ID, 1),
            FieldSpec(Field.BYTE_1, 2),
            FieldSpec(Field.BYTE_2, 3, length = 3),
            FieldSpec(Field.BYTE_3, 4, length = 2),
            FieldSpec(Field.BYTE_4, 5),
            FieldSpec(Field.BYTE_5, 6),
            FieldSpec(Field.BYTE_6, 7),
            FieldSpec(Field.BYTE_7, 8),
            FieldSpec(Field.BYTE_8, 9),
            FieldSpec(Field.BYTE_9, 10),
            FieldSpec(Field.BYTE_10, 11),
            FieldSpec(Field.BYTE_11, 12),
            FieldSpec(Field.BYTE_12, 13),
            FieldSpec(Field.BYTE_13, 14),
            FieldSpec(Field.BYTE_14, 15),
            FieldSpec(Field.BYTE_15, 16),
            FieldSpec(Field.BYTE_16, 17),
            FieldSpec(Field.CHECKSUM, 18),
            FieldSpec(Field.END_BYTE, 19),
        ),
    )

    /** Dispatch table, ordered as in the official `getFrameConfig()`. */
    val all: List<FrameLayout> = listOf(
        SM1, SM2, SM3, SM4, CALIBRATION,
        music(isAck = false), WIFI_PASSWORD, music(isAck = true),
    )

    fun forDataId(id: Int): FrameLayout? = all.firstOrNull { it.dataId == id }
}

/**
 * Driver for the Ntorq protocol family.
 *
 * Covers Ntorq and Raider, which the official app binds to the same
 * `NtorqBLEConnectionConfig`. [model] selects the display name only — the wire
 * format is identical.
 */
class NtorqDriver(
    override val model: VehicleModel = VehicleModel.NTORQ,
) : ProtocolDriver {

    init {
        require(model.sharesNtorqProtocol()) {
            "${model.displayName} does not use the Ntorq protocol; " +
                "use its own driver or GenericDriver"
        }
    }

    /**
     * Navigation is enabled because the control/text layouts are now
     * **proven**, not inferred: they are transcribed from
     * `BleNavigationPacketBuilder` in a working third-party app that drives a
     * Jupiter over the same GATT service this driver uses. The Ntorq shares
     * that service, so the layout is expected to carry over — it is still the
     * first thing to verify on the Ntorq itself.
     *
     * `textMessages` stays off: the message-row ids are transcribed but no
     * working reference for Ntorq exists.
     */
    override val capabilities = Capabilities(
        findMyScooter = true,
        navigationHud = true,
        textMessages = false,
        callerId = false,
        mediaTitle = false,
        buttonGestures = false,
        timeSync = true,
        wifiPassword = true,
        calibration = true,
    )

    override fun confidence() = mapOf(
        "GATT UUIDs" to Confidence.VERIFIED_FROM_APK,
        "AES-CTR handshake key" to Confidence.VERIFIED_FROM_APK,
        "start bytes 0x5A / 0x9B" to Confidence.VERIFIED_FROM_APK,
        "inbound frame layouts" to Confidence.VERIFIED_FROM_APK,
        "navigation control 0x5A 0x4E" to Confidence.VERIFIED_FROM_APK,
        "navigation text 0x5B 0x4F" to Confidence.VERIFIED_FROM_APK,
        "outbound ping 0x4A" to Confidence.INHERITED_UNVERIFIED,
        "text rows 0x4C / 0x63" to Confidence.NEEDS_CAPTURE,
        "pictogram table on Ntorq TFT" to Confidence.NEEDS_CAPTURE,
    )

    /** `BtData.isValidFrame()` accepts only 0x5A and 0x9B inbound. */
    override fun acceptsStartId(startId: Int): Boolean =
        startId == StartId.A5 || startId == StartId.B9

    override fun decode(raw: ByteArray): DecodedFrame? {
        if (raw.size < 2) return null
        val dataId = raw[1].toInt() and 0xFF
        return NtorqFrames.forDataId(dataId)?.decode(raw)
            ?: GenericDriver(model).decode(raw)
    }

    /**
     * 20-byte plaintext keepalive, **official layout**, verified with REA
     * against `BlePacketsProvider.returnMobilePacket`. No checksum byte.
     *
     * [PacketBuilder.mobileDataEnriched] carries the extra time/date/temperature
     * fields the Jupiter report documents; it is opt-in because the official
     * app writes zeros in that region.
     */
    /**
     * The keepalive, now carrying the clock.
     *
     * `PacketBuilder.mobileData` wrote zeros at [6..9] and [12..14] where the
     * official app puts hour, minute, second, AM/PM and the date. A ping every
     * two seconds that says "the time is 00:00:00" is a clock instruction, so
     * the cluster's own time was overwritten with nonsense and the dash showed
     * the wrong time while every other part of the app looked healthy.
     *
     * [Keepalive] carries the layout transcribed from the official sender.
     */
    override fun buildPing(state: DashState): ByteArray? = Keepalive.build(state)

    /** `0x5A 0x4E` — proven layout, see [PacketBuilder.navigationControl]. */
    override fun buildNavControl(nav: NavStep): ByteArray? =
        PacketBuilder.navigationControl(nav)

    /** `0x5B 0x4F` — 17-character text row. */
    override fun buildNavText(nav: NavStep): ByteArray? =
        PacketBuilder.navigationText(nav.streetName)

    /**
     * `0x5B 0x50` — the second navigation line.
     *
     * `NAVIGATION_TEXT_ALT` is a separate data id from `NAVIGATION_TEXT`, not a
     * second use of `0x4F`. Both carry 17 sanitised bytes, zero-padded, `0xFF`
     * terminated, no checksum.
     *
     * The cluster decides how many lines are in use from byte 10 of the `0x4E`
     * control frame, so the bridge sends this alongside a control frame with
     * `rows = 2`. Until that is seen rendering, the bridge still alternates, and
     * this is only sent when a second line has content — see
     * `NavigationBridge.startNavigation`.
     */
    override fun buildNavTextSecondary(nav: NavStep): ByteArray? =
        PacketBuilder.navigationText(nav.streetName, OutboundId.NAVIGATION_TEXT_ALT)

    /**
     * `0x5B 0x4C` (row 1) / `0x5B 0x63` (row 2) — caller ID and SMS text.
     *
     * This returned null, which meant caller ID and message display could not
     * have worked even though `PacketBuilder.textRow` and
     * `PacketBuilder.callerInfo` were both fully written. Row 0 is row 1.
     */
    override fun buildTextRow(row: Int, text: String): ByteArray? =
        PacketBuilder.textRow(
            if (row <= 0) OutboundId.TEXT_ROW1 else OutboundId.TEXT_ROW2,
            text,
        )

    override fun buildCallerInfo(number: String, name: String?, state: Int): ByteArray? =
        PacketBuilder.callerInfo(number, name, state)

    /** `0x5B 0x52` — the cluster shows it as the ignition greeting. */
    override fun buildRiderName(name: String): ByteArray? =
        PacketBuilder.riderName(name)

    override fun buildChallengeResponse(challenge: ByteArray): ByteArray? =
        Challenge.buildResponse(challenge)
}