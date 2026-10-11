// Maneuver pictogram IDs.
//
// Transcribed from TVS Connect v8.14.1, com.tvsm.connect.utils.ManeuverUtils
// (classes7.dex), method getShortInstruction(int).
//
// That method has two branches. The first is gated on
// `KEYS.VEHICLETYPEID == "22"`. The second, reached when the vehicle type is
// anything else, is the mapping written out here — it is therefore the
// **non-vehicle-type-22** table and, pending hardware confirmation, the best
// available candidate for the Ntorq.
//
// The Jupiter report in the parent folder documents a *different* table for the
// Jupiter (e.g. 1 = "slight left down", 13 = "left branch off straight"). This
// table is the official app's own and is preferred over the inherited one.
// See docs/NAVIGATION.md for the side-by-side.

package dev.tvsopen.protocol

/**
 * One cluster pictogram. [id] is the byte sent in the navigation control
 * packet; [label] is the official app's own string resource name, which keeps
 * the two in sync when the cluster or the app changes.
 */
data class Pictogram(
    val id: Int,
    val label: String,
    /** Roundabout exit bearings are angles; others are null. */
    val roundaboutExitDegrees: Int? = null,
)

object Pictograms {

    val ALL: List<Pictogram> = listOf(
        // --- basic turns ---
        Pictogram(0, "turn_left"),
        Pictogram(1, "sharp_left_turn"),
        Pictogram(2, "slight_left"),
        Pictogram(3, "turn_right"),
        Pictogram(4, "sharp_right_turn"),
        Pictogram(5, "slight_right"),
        Pictogram(6, "make_a_u_turn"),
        Pictogram(7, "continue_straight"),
        Pictogram(8, "destination"),

        // --- slip roads ---
        Pictogram(9, "exit_highway"),
        Pictogram(10, "enter_highway"),

        // --- junctions and forks ---
        Pictogram(11, "take_next_left"),
        Pictogram(12, "take_next_right"),
        Pictogram(13, "left_at_junction"),
        Pictogram(14, "right_at_junction"),
        Pictogram(15, "left_at_fork"),
        Pictogram(16, "right_at_fork"),
        Pictogram(17, "sharp_left_at_junciton"),
        Pictogram(18, "sharp_right_at_junciton"),
        Pictogram(19, "take_left_at_junction"),
        Pictogram(20, "take_right_at_junction"),
        Pictogram(21, "continue_straight"),   // duplicate of 7 upstream
        Pictogram(22, "continue_left"),
        Pictogram(23, "left_after_right_curve"),
        Pictogram(24, "continue_right"),
        Pictogram(25, "right_after_left_curve"),

        // --- ferries ---
        Pictogram(36, "take_ferry"),
        Pictogram(37, "leave_ferry"),

        // --- compass bearings ---
        Pictogram(50, "head_north"),
        Pictogram(51, "head_northeast"),
        Pictogram(52, "head_east"),
        Pictogram(53, "head_southeast"),
        Pictogram(54, "head_south"),
        Pictogram(55, "head_southwest"),
        Pictogram(56, "head_west"),
        Pictogram(57, "head_northwest"),

        // --- roundabout exits (India drives left, so these run clockwise) ---
        Pictogram(65, "roundabout_exit_at_45", 45),
        Pictogram(66, "roundabout_exit_at_90", 90),
        Pictogram(67, "roundabout_exit_at_135", 135),
        Pictogram(68, "roundabout_exit_at_180", 180),
        Pictogram(69, "roundabout_exit_at_225", 225),
        Pictogram(70, "roundabout_exit_at_270", 270),
        Pictogram(71, "roundabout_exit_at_315", 315),

        // --- highway (separate default branch upstream) ---
        Pictogram(34, "exit_highway"),
        Pictogram(35, "enter_highway"),
    )

    private val byId = ALL.associateBy { it.id }

    /**
     * `41` is special-cased twice upstream, ahead of the switch, and is handled
     * in *both* vehicle-type branches — evidence it is a U-turn that was later
     * given its own id.
     */
    val U_TURN_LEGACY = Pictogram(41, "make_a_u_turn")

    fun forId(id: Int): Pictogram? = byId[id] ?: if (id == U_TURN_LEGACY.id) U_TURN_LEGACY else null

    fun label(id: Int): String = forId(id)?.label ?: "turn_left"

    /** Known ids, for the calibration UI to enumerate. */
    val ids: List<Int> = (ALL.map { it.id } + U_TURN_LEGACY.id).distinct().sorted()
}

/**
 * Text-keyword fallback.
 *
 * Cluster pictograms are a closed set that changes between model generations
 * and app releases, so a byte table alone is brittle. When a provider gives us
 * instruction text but we cannot map its icon, we resolve from the words. This
 * is the second tier of the two-tier resolution described in the Jupiter
 * report, applied per model.
 */
object ManeuverResolver {

    private val rules: List<Pair<Regex, Int>> = listOf(
        Regex("u[- ]?turn|make a u", RegexOption.IGNORE_CASE) to Pictograms.U_TURN_LEGACY.id,
        Regex("\\broundabout\\b", RegexOption.IGNORE_CASE) to 68,
        Regex("\\bexit\\b.*\\broundabout\\b|\\bfirst exit\\b", RegexOption.IGNORE_CASE) to 65,
        Regex("\\bsharp left\\b", RegexOption.IGNORE_CASE) to 1,
        Regex("\\bslight left\\b|\\bkeep left\\b", RegexOption.IGNORE_CASE) to 2,
        Regex("\\bsharp right\\b", RegexOption.IGNORE_CASE) to 4,
        Regex("\\bslight right\\b|\\bkeep right\\b", RegexOption.IGNORE_CASE) to 5,
        Regex("\\bturn left\\b|\\bleft\\b", RegexOption.IGNORE_CASE) to 0,
        Regex("\\bturn right\\b|\\bright\\b", RegexOption.IGNORE_CASE) to 3,
        Regex("\\bearlier\\b|\\bexit\\b", RegexOption.IGNORE_CASE) to 9,
        Regex("\\bmerge\\b", RegexOption.IGNORE_CASE) to 14,
        Regex("\\bfork\\b", RegexOption.IGNORE_CASE) to 15,
        Regex("\\barrive|\\bdestination\\b|\\byou have arrived\\b", RegexOption.IGNORE_CASE) to 8,
        Regex("\\bstraight\\b|\\bcontinue\\b", RegexOption.IGNORE_CASE) to 7,
    )

    /** Returns a pictogram id, or null when nothing matches. */
    fun fromText(text: String?): Int? {
        val t = text?.takeIf { it.isNotBlank() } ?: return null
        for ((re, id) in rules) if (re.containsMatchIn(t)) return id
        return null
    }
}