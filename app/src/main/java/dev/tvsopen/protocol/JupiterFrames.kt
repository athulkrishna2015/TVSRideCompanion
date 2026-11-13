// Jupiter-family protocol driver.
//
// Second source, not a re-derivation: the frame tables come from the Scoot
// release feed's public simulator spec (sim/docs/PROTOCOL.md in
// sudo-Mystic/Scoot-releases), extracted from the Scoot app's protocol layer
// and dex-verified against TVS Connect v8.12.4. The Scoot 0.5.10 release APK
// this app was compared against hashes to that feed's manifest, so the spec
// and the binary describe the same build.
//
// The Ntorq driver is untouched. Where the two sources disagree the note is in
// the code at the disagreement and in docs/PROTOCOL.md.

package dev.tvsopen.protocol

/**
 * Jupiter inbound frame table.
 *
 * The Jupiter and Ntorq families share the same 20-byte framing, the same GATT
 * UUIDs and the same 0x10/0x11/0x18/0x19 ids, but **not** the same meanings at
 * every offset. In particular:
 *
 *  - 0x10 byte 8 is ECO/POWER on both, but byte 12 is a call/ignition switch
 *    carrying accept (1) and reject (2) commands, while the Ntorq layout has
 *    `LOCATION_TAG` there.
 *  - 0x10 byte 6 fuel needs the model gate below: the 125 takes the low nibble,
 *    the 110 takes the raw byte.
 *  - 0x11 byte 14 is the cluster theme.
 *  - 0x18 byte 6 is barometric pressure on the U714 variant, and bytes 9-10 are
 *    the 16-bit barometric reading.
 *
 * All multi-byte fields are big-endian, matching [FieldSpec].
 */
object JupiterFrames {

    /** 0x10 primary telemetry. */
    val PRIMARY = FrameLayout(
        dataId = 0x10,
        type = DataFrameType.SPEEDOMETER_DATA_5A_10_FRAME,
        fields = listOf(
            FieldSpec(Field.START_BYTE, 0),
            FieldSpec(Field.DATA_ID, 1),
            FieldSpec(Field.SPEED, 2),                 // PROVISIONAL mapping
            FieldSpec(Field.ODOMETER, 3, length = 3),  // raw tenths of a km
            FieldSpec(Field.FUEL_LEVEL, 6),            // model-gated, see Capabilities
            FieldSpec(Field.AVERAGE_SPEED, 7),
            FieldSpec(Field.ECONOMY_STATUS, 8),        // 0 = Economy, 1 = Power
            FieldSpec(Field.TOP_SPEED, 9),
            FieldSpec(Field.THROTTLE_POSITION, 10),
            FieldSpec(Field.BACKLIGHT, 11),
            // 128 = ignition on, 64 = off; 1..4 are call commands. One byte,
            // two meanings, which is why the value is surfaced raw and the
            // call handling reads it explicitly rather than through a field.
            FieldSpec(Field.SWITCH_STATUS, 12),
            FieldSpec(Field.TIME_60KMPH, 13),          // raw only on Jupiter
            FieldSpec(Field.TRIP_F_METER, 14, length = 3),
            FieldSpec(Field.ENGINE_RPM, 17, length = 2),
            FieldSpec(Field.END_BYTE, 19),
        ),
    )

    /** 0x11 service / diagnostics. */
    val SERVICE = FrameLayout(
        dataId = 0x11,
        type = DataFrameType.SPEEDOMETER_DATA_5A_11_FRAME,
        fields = listOf(
            FieldSpec(Field.START_BYTE, 0),
            FieldSpec(Field.DATA_ID, 1),
            FieldSpec(Field.FUEL_SENSOR, 2),
            FieldSpec(Field.VEHICLE_STATE_1, 3),
            FieldSpec(Field.SERVICE_REMINDER, 4),
            FieldSpec(Field.VEHICLE_STATE_2, 6),
            FieldSpec(Field.SOFTWARE_VERSION, 7),
            FieldSpec(Field.MIL_BLINK_CODE, 8),
            FieldSpec(Field.VEHICLE_MODEL, 9),
            FieldSpec(Field.VEHICLE_DIAGNOSTICS, 10),   // bit 2 = MIL on
            FieldSpec(Field.ISG_BLINK_CODE, 11),
            FieldSpec(Field.TURN_INDICATOR_STATUS, 12),
            FieldSpec(Field.CONNECTOR_STATUS, 13),
            FieldSpec(Field.VEHICLE_MODE, 14),          // cluster theme
            FieldSpec(Field.CAPTURE_SCREENSHOT, 17),
            FieldSpec(Field.END_BYTE, 19),
        ),
    )

    /** 0x18 engine telemetry. Only byte 11 is consumed by the stock app. */
    val ENGINE = FrameLayout(
        dataId = 0x18,
        type = DataFrameType.SPEEDOMETER_DATA_5A_18_FRAME,
        fields = listOf(
            FieldSpec(Field.START_BYTE, 0),
            FieldSpec(Field.DATA_ID, 1),
            FieldSpec(Field.ENGINE_LOAD, 2),
            FieldSpec(Field.ACCUMULATED_FUEL_INJECTION_TIME, 3, length = 2),
            FieldSpec(Field.MANIFOLD_AIR_PRESSURE, 5),
            FieldSpec(Field.BAROMETRIC_PRESSURE, 6),
            FieldSpec(Field.INTAKE_AIR_TEMPRATURE, 7),
            FieldSpec(Field.ENGINE_TEMPRATURE, 8),
            FieldSpec(Field.BAROMETRIC_PRESSURE, 9, length = 2),
            // raw x 0.1 V, no offset: 121 -> 12.1 V. Dex-verified.
            FieldSpec(Field.BATTERY_VOLTAGE, 11),
            FieldSpec(Field.ENGINE_RUNNING_TIME, 12, length = 2),
            FieldSpec(Field.DISTANCE_TRAVELED, 14, length = 2),
            FieldSpec(Field.FUEL_INJECTION_VOLUME, 16, length = 2),
            FieldSpec(Field.END_BYTE, 19),
        ),
    )

    /** 0x19 economy / distance-to-empty / idle-stop. */
    val ECONOMY = FrameLayout(
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
            FieldSpec(Field.END_BYTE, 19),
        ),
    )

    /** 0x6B music button. Command byte is at index 2 on both families. */
    val MUSIC = FrameLayout(
        dataId = 0x6B,
        type = DataFrameType.MUSIC_FRAME,
        fields = listOf(
            FieldSpec(Field.START_BYTE, 0),
            FieldSpec(Field.DATA_ID, 1),
            FieldSpec(Field.MUSIC_COMMAND, 2),
            FieldSpec(Field.END_BYTE, 19),
        ),
    )

    val all: List<FrameLayout> = listOf(PRIMARY, SERVICE, ENGINE, ECONOMY, MUSIC)

    fun forDataId(id: Int): FrameLayout? = all.firstOrNull { it.dataId == id }
}

/**
 * Driver for the Jupiter 110 / 125.
 *
 * Capabilities are enabled from the spec's own confidence statements, not from
 * optimism: every frame it builds is transcribed from a working implementation,
 * and every one is still marked with what remains unverified in
 * [confidence].
 */
class JupiterDriver(
    override val model: VehicleModel,
) : ProtocolDriver {

    init {
        require(model == VehicleModel.JUPITER_110 || model == VehicleModel.JUPITER_125) {
            "${model.displayName} is not a Jupiter variant"
        }
    }

    override val capabilities = Capabilities(
        findMyScooter = true,
        navigationHud = true,
        textMessages = true,
        callerId = true,
        mediaTitle = true,
        buttonGestures = true,
        timeSync = true,
        wifiPassword = false,
        calibration = true,
        brightness = true,
        mediaMeta = true,
        musicKeys = true,
        callButtons = true,
        keepalive = true,
        // Dex-verified split behind JUPITER_U566: the 125 extracts the low
        // nibble of the fuel byte, the 110 passes the raw byte through.
        fuelLowNibble = model == VehicleModel.JUPITER_125,
    )

    override fun confidence() = mapOf(
        "GATT UUIDs" to Confidence.VERIFIED_FROM_APK,
        "start bytes 0x5A / 0x9B / 0x5B inbound" to Confidence.VERIFIED_FROM_APK,
        "inbound frame layouts" to Confidence.VERIFIED_FROM_APK,
        "0x10 byte 2 speed mapping" to Confidence.NEEDS_CAPTURE,
        "0x10 odometer /10 scale" to Confidence.NEEDS_CAPTURE,
        "0x10 fuel bar interpretation" to Confidence.NEEDS_CAPTURE,
        "navigation control 0x5A 0x4E" to Confidence.INHERITED_UNVERIFIED,
        "navigation text 0x5B 0x4F" to Confidence.INHERITED_UNVERIFIED,
        "pictogram 0x5B 0x50" to Confidence.INHERITED_UNVERIFIED,
        "caller sequence 0x5B 0x43" to Confidence.VERIFIED_FROM_APK,
        "brightness 0x5A 0xF1" to Confidence.VERIFIED_FROM_APK,
        "media metadata 0x61 / 0x62 / 0x65" to Confidence.INHERITED_UNVERIFIED,
        "media keys 0x6B" to Confidence.VERIFIED_FROM_APK,
        "rider name 0x5B 0x52" to Confidence.INHERITED_UNVERIFIED,
        "calibration 0x5B 0x73" to Confidence.NEEDS_CAPTURE,
        "keepalive 0x5B 0x4A" to Confidence.INHERITED_UNVERIFIED,
    )

    /**
     * Inbound framing accepts 0x5A, 0x9B and 0x5B: the spec notes the real
     * app's `isValidFrame` accepts 0x9B as an alternate and 0x5B was seen
     * inbound in RE-report captures. The simulator always emits 0x5A.
     */
    override fun acceptsStartId(startId: Int): Boolean =
        startId == StartId.A5 || startId == StartId.B9 || startId == StartId.B5

    override fun decode(raw: ByteArray): DecodedFrame? {
        if (raw.size < 2) return null
        val dataId = raw[1].toInt() and 0xFF
        return JupiterFrames.forDataId(dataId)?.decode(raw)
            ?: GenericDriver(model).decode(raw)
    }

    /**
     * 0x4A presence packet. The Jupiter sender's extra fields are supplied
     * here: a 120 km/h overspeed holder and the ambient temperature. The clock
     * block and the find-me flag are shared with the Ntorq path.
     */
    override fun buildPing(state: DashState): ByteArray? = Keepalive.build(
        state = state,
        overspeedLimitKph = 120,
        ambientTempC = state.temperatureC,
    )

    override fun buildNavControl(nav: NavStep): ByteArray? =
        PacketBuilder.navigationControl(nav)

    override fun buildNavText(nav: NavStep): ByteArray? =
        PacketBuilder.navigationText(nav.streetName)

    /**
     * **Null on Jupiter.** `0x50` is the pictogram-by-name id here, not a
     * second navigation text row. Returning a text frame here would overwrite
     * the pictogram channel, so the bridge falls back to its single-line
     * alternating path, which is what the reference implementation does.
     */
    override fun buildNavTextSecondary(nav: NavStep): ByteArray? = null

    override fun buildTextRow(row: Int, text: String): ByteArray? =
        PacketBuilder.textRow(
            if (row <= 0) OutboundId.TEXT_ROW1 else OutboundId.TEXT_ROW2,
            text,
        )

    /** The three-packet caller sequence, not the single combined row. */
    override fun buildCallerInfo(number: String, name: String?, state: Int): ByteArray? = null

    override fun buildCallerFrames(
        number: String,
        countryCode: String?,
        name: String?,
    ): List<ByteArray> = ClusterFrames.callerFrames(number, countryCode, name)

    override fun buildPictogram(name: String): ByteArray? = ClusterFrames.pictogram(name)

    override fun buildMediaMeta(dataId: Int, text: String): ByteArray? =
        ClusterFrames.mediaMeta(dataId, text)

    override fun buildBrightness(level: Int): ByteArray? =
        ClusterFrames.vehicleControl(level)

    override fun buildUserId(id: Long): ByteArray? = ClusterFrames.userId(id)

    override fun buildSms(text: String): ByteArray? = ClusterFrames.sms(text)

    override fun buildPlayPauseState(stateByte: Int): ByteArray? =
        ClusterFrames.playPauseState(stateByte)

    override fun buildRiderName(name: String): ByteArray? =
        PacketBuilder.riderName(name)

    /**
     * **Null, deliberately.** The 0x9A/0xF2 challenge belongs to the
     * keyless-entry channel; the standing safety rule in this project is
     * detect-only. The challenge is logged by the transport, never answered.
     */
    override fun buildChallengeResponse(challenge: ByteArray): ByteArray? = null
}