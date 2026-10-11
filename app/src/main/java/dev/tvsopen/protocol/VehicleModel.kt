// Universal vehicle model abstraction.
//
// The official TVS Connect app splits its protocol code one package per model
// (protocol/ntorq, protocol/jupiter, protocol/apache, protocol/raider,
// protocol/u388, u399, u467, u546) with a shared core in protocol/ and a
// per-model GATT configuration in bikes/*BLEConnectionConfig.
//
// We keep that shape: one shared core, one small driver per model, selected at
// runtime. A model with no driver yet still connects and reports raw frames.

package dev.tvsopen.protocol

enum class VehicleModel(
    val id: String,
    val displayName: String,
    /** Cluster size class, used to pick sensible default layouts. */
    val display: Display = Display.STANDARD,
) {
    NTORQ("ntorq", "Ntorq 150 (SX)", Display.LARGE_TFT),
    JUPITER_110("jupiter110", "Jupiter 110"),
    JUPITER_125("jupiter125", "Jupiter 125"),
    APACHE_160("apache160", "Apache RTR 160"),
    APACHE_180("apache180", "Apache RTR 180"),

    /**
     * Shares `NtorqBLEConnectionConfig` upstream — see [sharesNtorqProtocol].
     * Separate model, identical wire protocol.
     */
    RAIDER_125("raider125", "Raider 125"),
    RAIDER_180("raider180", "Raider 180"),

    U388("u388", "U388"),
    U399("u399", "U399"),
    IQUBE("iqube", "iQube"),
    UNKNOWN("unknown", "Unknown SmartXonnect vehicle");

    enum class Display { STANDARD, LARGE_TFT }

    /**
     * Models the official app binds to `NtorqBLEConnectionConfig`.
     *
     * Transcribed from `com.tvs.bike.core.BikeVariants`, whose
     * `getConnectionConfiguration()` returns `NtorqBLEConnectionConfig` for
     * *both* Ntorq and Raider. There is no `RaiderBLEConnectionConfig`, and
     * `protocol/raider/*` only adds `5A54ACK`/`5A6B`/`5A9D` frames — a
     * superset, not a separate protocol. So the Ntorq driver serves Raider too.
     */
    fun sharesNtorqProtocol(): Boolean = this == NTORQ || this == RAIDER_125 || this == RAIDER_180

    companion object {
        /**
         * Best-effort match from a advertised local name / model string.
         * Returns [UNKNOWN] rather than throwing — the driver registry then
         * falls back to the generic profile.
         */
        fun detect(name: String?): VehicleModel {
            val n = name?.uppercase() ?: return UNKNOWN
            return when {
                n.contains("NTORQ") -> NTORQ
                n.contains("JUPITER") -> JUPITER_125
                n.contains("APACHE") -> APACHE_160
                n.contains("RAIDER") -> RAIDER_125
                n.contains("IQUBE") -> IQUBE
                n.contains("U388") -> U388
                n.contains("U399") -> U399
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Capabilities a given model/driver combination actually implements.
 *
 * Deliberately explicit rather than assumed: the Jupiter reference document
 * documents navigation and text messages for the Jupiter, but that is not
 * evidence the Ntorq accepts them. Until it is verified against hardware, the
 * Ntorq driver reports these false and the UI hides the feature instead of
 * sending packets the cluster may reject.
 */
data class Capabilities(
    val findMyScooter: Boolean = true,
    val navigationHud: Boolean = false,
    val textMessages: Boolean = false,
    val callerId: Boolean = false,
    val mediaTitle: Boolean = false,
    val buttonGestures: Boolean = false,
    val timeSync: Boolean = true,
    val wifiPassword: Boolean = false,
    val calibration: Boolean = false,
)

/**
 * A decoded telemetry frame. [fields] maps byte offsets to semantic names so
 * models that pack the same quantity at different offsets can share one parser.
 */
data class DecodedFrame(
    val startId: Int,
    val dataId: Int,
    val fields: Map<Int, Field>,
    val values: Map<Int, Long>,
    val raw: ByteArray,
) {
    fun field(name: Field): Long? = values.entries.firstOrNull { fields[it.key] == name }?.value

    override fun equals(other: Any?): Boolean =
        this === other || (other is DecodedFrame && startId == other.startId &&
            dataId == other.dataId && fields == other.fields && values == other.values)

    override fun hashCode(): Int =
        (((startId * 31 + dataId) * 31 + fields.hashCode()) * 31 + values.hashCode())

    companion object {
        val IGNITION_OFF = DecodedFrame(0, 0, emptyMap(), emptyMap(), ByteArray(0))
    }
}