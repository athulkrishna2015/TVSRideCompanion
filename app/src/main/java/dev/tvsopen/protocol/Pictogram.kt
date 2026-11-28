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

    /**
     * Id to send when the maneuver is genuinely unknown.
     *
     * `continue_straight` (7) in the official table. A wrong-but-plausible arrow
     * is worse than an honest straight-on at a junction the rider has to read
     * anyway, and the text line carries the real instruction regardless.
     */
    const val STRAIGHT = 7

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

    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    private val rules: List<Pair<Regex, Int>> = listOf(
        Regex(
            "u[- ]?turn|make a u|vuelta en u|giro en u|cambio de sentido|\\bretorno\\b",
            RegexOption.IGNORE_CASE,
        ) to Pictograms.U_TURN_LEGACY.id,
        // Specific exit rules first: the generic roundabout rule below matches
        // "second exit at the roundabout" and would otherwise win.
        Regex(
            "\\bexit\\b.*\\broundabout\\b|\\broundabout\\b.*\\b(?:first|1st)\\s+exit\\b|\\bfirst exit\\b|\\bprimera salida\\b|\\b1\\.?a? salida\\b",
            RegexOption.IGNORE_CASE,
        ) to 65,
        Regex("\\broundabout\\b.*\\b(?:second|2nd)\\s+exit\\b", RegexOption.IGNORE_CASE) to 66,
        Regex("\\broundabout\\b.*\\b(?:third|3rd)\\s+exit\\b|\\b(?:third|3rd)\\s+exit\\b.*\\broundabout\\b", RegexOption.IGNORE_CASE) to 67,
        Regex("\\broundabout\\b.*\\b(?:fourth|4th)\\s+exit\\b|\\b(?:fourth|4th)\\s+exit\\b.*\\broundabout\\b", RegexOption.IGNORE_CASE) to 68,
        Regex("\\broundabout\\b.*\\b(?:fifth|5th)\\s+exit\\b|\\b(?:fifth|5th)\\s+exit\\b.*\\broundabout\\b", RegexOption.IGNORE_CASE) to 69,
        Regex("\\broundabout\\b.*\\b(?:sixth|6th)\\s+exit\\b|\\b(?:sixth|6th)\\s+exit\\b.*\\broundabout\\b", RegexOption.IGNORE_CASE) to 70,
        Regex("\\broundabout\\b.*\\b(?:seventh|7th)\\s+exit\\b|\\b(?:seventh|7th)\\s+exit\\b.*\\broundabout\\b", RegexOption.IGNORE_CASE) to 71,
        Regex("\\broundabout\\b|\\brotonda\\b|\\bglorieta\\b", RegexOption.IGNORE_CASE) to 68,
        Regex(
            "\\bsharp left\\b|\\bmuy a la izquierda\\b|\\bgiro cerrado a la izquierda\\b",
            RegexOption.IGNORE_CASE,
        ) to 1,
        Regex(
            "\\bslight left\\b|\\bkeep left\\b|\\bligeramente a la izquierda\\b|\\bmantente a la izquierda\\b|\\bcarril izquierdo\\b",
            RegexOption.IGNORE_CASE,
        ) to 2,
        Regex(
            "\\bsharp right\\b|\\bmuy a la derecha\\b|\\bgiro cerrado a la derecha\\b",
            RegexOption.IGNORE_CASE,
        ) to 4,
        Regex(
            "\\bslight right\\b|\\bkeep right\\b|\\bligeramente a la derecha\\b|\\bmantente a la derecha\\b|\\bcarril derecho\\b",
            RegexOption.IGNORE_CASE,
        ) to 5,
        Regex("\\bmerge\\b.*\\bleft\\b", RegexOption.IGNORE_CASE) to 22,
        Regex("\\bmerge\\b.*\\bright\\b", RegexOption.IGNORE_CASE) to 24,
        Regex("\\bmerge\\b|\\bincorporate\\b", RegexOption.IGNORE_CASE) to 14,
        Regex("\\bturn left\\b|\\bleft\\b|\\bgira a la izquierda\\b|\\bizquierda\\b", RegexOption.IGNORE_CASE) to 0,
        Regex("\\bturn right\\b|\\bright\\b|\\bgira a la derecha\\b|\\bderecha\\b", RegexOption.IGNORE_CASE) to 3,
        Regex("\\bearlier\\b|\\bexit\\b|\\btoma la salida\\b|\\bsal por\\b|\\bsalida\\b", RegexOption.IGNORE_CASE) to 9,
        Regex("\\bfork\\b|\\bbifurcacion\\b", RegexOption.IGNORE_CASE) to 15,
        Regex(
            "\\barrive|\\bdestination\\b|\\byou have arrived\\b|\\bhas llegado\\b|\\bllegaras\\b|\\bdestino\\b",
            RegexOption.IGNORE_CASE,
        ) to 8,
        Regex("\\bhead\\s+northeast\\b", RegexOption.IGNORE_CASE) to 51,
        Regex("\\bhead\\s+southeast\\b", RegexOption.IGNORE_CASE) to 53,
        Regex("\\bhead\\s+southwest\\b", RegexOption.IGNORE_CASE) to 55,
        Regex("\\bhead\\s+northwest\\b", RegexOption.IGNORE_CASE) to 57,
        Regex("\\bhead\\s+north\\b", RegexOption.IGNORE_CASE) to 50,
        Regex("\\bhead\\s+east\\b", RegexOption.IGNORE_CASE) to 52,
        Regex("\\bhead\\s+south\\b", RegexOption.IGNORE_CASE) to 54,
        Regex("\\bhead\\s+west\\b", RegexOption.IGNORE_CASE) to 56,
        Regex(
            "\\bstraight\\b|\\bcontinue\\b|\\bcontinua\\b|\\bsigue recto\\b|\\brecto\\b|\\bdirigete\\b|\\bdir[ií]gete\\b",
            RegexOption.IGNORE_CASE,
        ) to 7,
    )

    /** Returns a pictogram id, or null when nothing matches. */
    fun fromText(text: String?): Int? {
        val raw = text?.takeIf { it.isNotBlank() } ?: return null
        val t = COMBINING_MARKS.replace(
            java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD),
            "",
        )
        for ((re, id) in rules) if (re.containsMatchIn(t)) return id
        return null
    }
}