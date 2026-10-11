// Per-model protocol driver.
//
// One implementation per vehicle family. The shared BLE transport knows
// nothing about packet layouts; everything model-specific lives behind this
// interface so that adding a model never touches the transport.

package dev.tvsopen.protocol

import kotlinx.coroutines.flow.Flow

/**
 * Frame layouts per model.
 *
 * The official app hardcodes a byte->meaning map inside each generated
 * parser (`NtorqSpeedOMeter1`, `JupiterSpeedOMeter1`, …). We keep the same
 * shape but make it declarative data so a partially-verified model is
 * explicit rather than half-guessed.
 */
interface ProtocolDriver {
    val model: VehicleModel
    val capabilities: Capabilities

    /** Human-readable provenance of each fact, surfaced in the UI. */
    fun confidence(): Map<String, Confidence>

    // ---- inbound ----

    /**
     * Which start bytes this model leads frames with. The generic profile
     * accepts all observed ones so an unrecognised model still streams.
     */
    fun acceptsStartId(startId: Int): Boolean

    /** Decode an inbound frame, or null if it is not one this model emits. */
    fun decode(raw: ByteArray): DecodedFrame?

    // ---- outbound ----

    /** Keepalive packet sent continuously to hold the link and feed the dash. */
    fun buildPing(state: DashState): ByteArray?

    /** Navigation control packet. Null when [Capabilities.navigationHud] is false. */
    fun buildNavControl(nav: NavStep): ByteArray?

    /** Navigation text packet. Null when the model has no text channel. */
    fun buildNavText(nav: NavStep): ByteArray?

    /** Plain 17-char text row for caller ID / song titles / notifications. */
    fun buildTextRow(row: Int, text: String): ByteArray?

    // ---- handshake ----

    /**
     * Respond to a challenge frame. Returns null when the model has no
     * handshake (the TechEnclave consensus is that some units do not).
     */
    fun buildChallengeResponse(challenge: ByteArray): ByteArray?
}

/** Confidence attached to every reverse-engineered fact. */
enum class Confidence {
    /** Confirmed by the official DEX. */
    VERIFIED_FROM_APK,

    /** Documented by the Jupiter report, assumed shared. Untested here. */
    INHERITED_UNVERIFIED,

    /** Placeholder pending hardware capture. */
    NEEDS_CAPTURE,
}

/** Phone-side state mirrored into the cluster. */
data class DashState(
    val timeMillis: Long,
    val batteryBars: Int = 0,   // 0..5
    val signalBars: Int = 0,   // 0..5
    val temperatureC: Int = 25,
    val networkCode: Int = 0x04,
    val findMyScooter: Boolean = false,
)

/** One navigation instruction, provider-agnostic. */
data class NavStep(
    val distanceToTurnMeters: Int,
    val etaMinutes: Int,
    val remainingDistanceMeters: Int,
    val pictogram: Int,
    val streetName: String,
    val rows: Int = 2,
)

/**
 * Driver registry. Models without a dedicated driver fall back to
 * [GenericDriver], which decodes framing only — enough to prove the link works
 * and to capture raw frames for later analysis.
 */
object DriverRegistry {
    private val drivers = mutableListOf<ProtocolDriver>()

    fun register(driver: ProtocolDriver) {
        drivers.removeAll { it.model == driver.model }
        drivers += driver
    }

    fun forModel(model: VehicleModel): ProtocolDriver =
        drivers.firstOrNull { it.model == model } ?: GenericDriver(model)
}

/**
 * Framing-only driver: validates start/data bytes and checksum, exposes the
 * data ID, and decodes nothing model-specific. This is the honest default for
 * a model nobody has reverse engineered yet.
 */
class GenericDriver(
    override val model: VehicleModel,
) : ProtocolDriver {

    override val capabilities = Capabilities(
        findMyScooter = false,
        navigationHud = false,
        textMessages = false,
    )

    override fun confidence() = mapOf(
        "framing" to Confidence.VERIFIED_FROM_APK,
        "payload layout" to Confidence.NEEDS_CAPTURE,
    )

    override fun acceptsStartId(startId: Int): Boolean =
        startId == StartId.A5 || startId == StartId.B5 ||
            startId == StartId.B9 || startId == StartId.A9

    override fun decode(raw: ByteArray): DecodedFrame? {
        if (raw.size < 3) return null
        if (!acceptsStartId(raw[0].toInt() and 0xFF)) return null
        return DecodedFrame(
            startId = raw[0].toInt() and 0xFF,
            dataId = raw[1].toInt() and 0xFF,
            fields = mapOf(0 to Field.START_BYTE, 1 to Field.DATA_ID),
            values = mapOf(0 to (raw[0].toLong() and 0xFF), 1 to (raw[1].toLong() and 0xFF)),
            raw = raw,
        )
    }

    override fun buildPing(state: DashState): ByteArray? = null
    override fun buildNavControl(nav: NavStep): ByteArray? = null
    override fun buildNavText(nav: NavStep): ByteArray? = null
    override fun buildTextRow(row: Int, text: String): ByteArray? = null
    override fun buildChallengeResponse(challenge: ByteArray): ByteArray? = null
}