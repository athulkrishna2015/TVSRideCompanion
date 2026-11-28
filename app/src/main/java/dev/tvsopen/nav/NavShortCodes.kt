// Short codes, arrows and rolling windows for the cluster text rows.
//
// The Ntorq shows 10 characters on line 1 and 12 on line 2, so every verbose
// Google Maps phrase has to shrink before it is sent. This object compresses
// one instruction into glanceable short codes (LFT, RGT, twd, ...), prefixes
// an ASCII arrow the sanitiser lets through, and scrolls whatever still
// overflows so the tail is shown instead of clipped.
//
// Pure Kotlin, no Android types, so it stays unit-testable like NavParsing.

package dev.tvsopen.nav

object NavShortCodes {

    private val HTML_TAG = Regex("<[^>]+>")
    private val SPACES = Regex("\\s+")

    // Indian road names are long; shorten the type words first, then fall back
    // to initials for leading proper nouns so a name fits the 12-char row
    // instead of being scrolled when it could have been read at a glance.
    private val ROAD_SHORT = listOf(
        "NATIONAL HIGHWAY" to "NH", "STATE HIGHWAY" to "SH",
        "EXPRESSWAY" to "EXPWY", "HIGHWAY" to "HWY", "AVENUE" to "AVE",
        "JUNCTION" to "JN", "CIRCLE" to "CIR", "SERVICE" to "SVC",
        "FLYOVER" to "FLYOVR", "BRIDGE" to "BR", "SECTOR" to "SEC",
        "NAGAR" to "NGR", "CROSS" to "X", "STREET" to "ST", "ROAD" to "RD",
        "MARG" to "MG", "MAIN" to "MN", "LANE" to "LN", "SOUTH" to "S",
        "NORTH" to "N", "EAST" to "E", "WEST" to "W",
    )

    /** Shorten a place name to [max] characters, preserving the input case. */
    fun fitStreet(text: String, max: Int): String {
        var s = text.replace(Regex("[^A-Za-z0-9 ]"), " ")
            .replace(SPACES, " ").trim()
        for ((long, short) in ROAD_SHORT) {
            s = s.replace(Regex("\\b$long\\b", RegexOption.IGNORE_CASE), short)
        }
        // Do not initialise proper nouns: line 2 is a rolling window, so a
        // long name is read over successive sends. Turning "Example Road"
        // into "E RD" made the place unrecognisable on the first window.
        return s
    }
    private val DASH_RUN = Regex("-+")

    private fun ci(pattern: String): Regex =
        Regex(pattern, RegexOption.IGNORE_CASE)

    // Ordered longest-first: phrase rules before the single words they contain,
    // or "turn sharp left" would first become "turn SHL" and never match.
    private val WORD_RULES: List<Pair<Regex, String>> = listOf(
        ci("\\bdestination\\s+is\\s+on\\s+the\\s+left\\b") to "ARR-L",
        ci("\\bdestination\\s+on\\s+the\\s+left\\b") to "ARR-L",
        ci("\\bdestination\\s+is\\s+on\\s+the\\s+right\\b") to "ARR-R",
        ci("\\bdestination\\s+on\\s+the\\s+right\\b") to "ARR-R",
        ci("\\bmake\\s+a\\s+u-turn\\b") to "UTN",
        ci("\\bmake\\s+a\\s+u\\s+turn\\b") to "UTN",
        ci("\\btake\\s+u-turn\\b") to "UTN",
        ci("\\bu-turn\\b") to "UTN",
        ci("\\bu\\s+turn\\b") to "UTN",
        ci("\\bturn\\s+sharp\\s+left\\b") to "SHL",
        ci("\\bsharp\\s+left\\b") to "SHL",
        ci("\\bturn\\s+sharp\\s+right\\b") to "SHR",
        ci("\\bsharp\\s+right\\b") to "SHR",
        // Fork kept with its side, before the keep-left/keep-right rules below
        // consume the side word on their own: "fork, keep left" must become
        // FRK-L, not FRK plus SLF.
        ci("\\bfork\\b\\s*,?\\s*keep\\s+left\\b") to "FRK-L",
        ci("\\bfork\\b\\s*,?\\s*keep\\s+right\\b") to "FRK-R",
        ci("\\bturn\\s+slight\\s+left\\b") to "SLF",
        ci("\\bslight\\s+left\\b") to "SLF",
        ci("\\bkeep\\s+left\\b") to "SLF",
        ci("\\bturn\\s+slight\\s+right\\b") to "SRG",
        ci("\\bslight\\s+right\\b") to "SRG",
        ci("\\bkeep\\s+right\\b") to "SRG",
        ci("\\bturn\\s+left\\b") to "LFT",
        ci("\\bturn\\s+right\\b") to "RGT",
        // Spanish phrases before the single-word fillers, so "muy a la
        // izquierda" cannot be shortened by a rule for "a" or "la".
        ci("\\bgira\\s+muy\\s+a\\s+la\\s+izquierda\\b") to "M-IZQ",
        ci("\\bmuy\\s+a\\s+la\\s+izquierda\\b") to "M-IZQ",
        ci("\\bgira\\s+muy\\s+a\\s+la\\s+derecha\\b") to "M-DER",
        ci("\\bmuy\\s+a\\s+la\\s+derecha\\b") to "M-DER",
        ci("\\bligeramente\\s+a\\s+la\\s+izquierda\\b") to "L-IZQ",
        ci("\\bmantente\\s+a\\s+la\\s+izquierda\\b") to "L-IZQ",
        ci("\\bligeramente\\s+a\\s+la\\s+derecha\\b") to "L-DER",
        ci("\\bmantente\\s+a\\s+la\\s+derecha\\b") to "L-DER",
        ci("\\bgira\\s+a\\s+la\\s+izquierda\\b") to "IZQ",
        ci("\\bgira\\s+a\\s+la\\s+derecha\\b") to "DER",
        ci("\\bvuelta\\s+en\\s+u\\b") to "UTN",
        ci("\\bgiro\\s+en\\s+u\\b") to "UTN",
        ci("\\bcambio\\s+de\\s+sentido\\b") to "UTN",
        ci("\\bretorno\\b") to "UTN",
        ci("\\bdir[ií]gete\\s+hacia\\b") to "HACIA",
        ci("\\bdir[ií]gete\\b") to "HACIA",
        ci("\\bcontin[uú]a\\s+recto\\b") to "RECTO",
        ci("\\bsigue\\s+recto\\b") to "RECTO",
        ci("\\bcontin[uú]a\\s+por\\b") to "SIGUE",
        ci("\\bcontin[uú]a\\b") to "SIGUE",
        ci("\\brotonda\\b") to "RND",
        ci("\\bglorieta\\b") to "RND",
        ci("\\btoma\\s+la\\s+salida\\b") to "SAL",
        ci("\\bhas\\s+llegado\\b") to "LLEG",
        ci("\\bcarrera\\b") to "Cra",
        ci("\\bavenida\\b") to "Av",
        ci("\\bcalle\\b") to "Cll",
        ci("\\bdiagonal\\b") to "Dg",
        ci("\\btransversal\\b") to "Tv",
        ci("\\bautopista\\b") to "Autop",
        ci("\\btake\\s+the\\s+ramp\\b") to "RMP",
        ci("\\btake\\s+ramp\\b") to "RMP",
        ci("\\bslip\\s+road\\b") to "RMP",
        ci("\\bramp\\b") to "RMP",
        ci("\\btake\\s+the\\s+exit\\b") to "EXT",
        ci("\\btake\\s+exit\\b") to "EXT",
        ci("\\bcontinue\\s+straight\\b") to "STR",
        ci("\\bgo\\s+straight\\b") to "STR",
        ci("\\broundabout\\b") to "RND",
        ci("\\bmerge\\b") to "MRG",
        ci("\\bfork\\b") to "FRK",
        ci("\\bferry\\b") to "FRY",
        ci("\\bstraight\\b") to "STR",
        ci("\\bcontinue\\b") to "STR",
        ci("\\byou\\s+have\\s+arrived\\b") to "ARR",
        ci("\\barrived\\b") to "ARR",
        ci("\\barrival\\b") to "ARR",
        ci("\\barrive\\b") to "ARR",
        ci("\\bdestination\\b") to "DEST",
        ci("\\bwaypoint\\b") to "WPT",
        ci("\\bdepart\\b") to "DPR",
        ci("\\bhead\\s+north\\b") to "N",
        ci("\\bhead\\s+south\\b") to "S",
        ci("\\bhead\\s+east\\b") to "E",
        ci("\\bhead\\s+west\\b") to "W",
        ci("\\btowards\\b") to "twd",
        ci("\\btoward\\b") to "twd",
        ci("\\bthen\\b") to ">",
        ci("\\bonto\\b") to "to",
        ci("\\bstreet\\b") to "St",
        ci("\\broad\\b") to "Rd",
        ci("\\bavenue\\b") to "Ave",
        ci("\\bhighway\\b") to "Hwy",
        ci("\\bboulevard\\b") to "Blvd",
        ci("\\bdrive\\b") to "Dr",
        ci("\\blane\\b") to "Ln",
        ci("\\bcircle\\b") to "Cir",
        ci("\\bcourt\\b") to "Ct",
        ci("\\bplace\\b") to "Pl",
        ci("\\bnorth\\b") to "N",
        ci("\\bsouth\\b") to "S",
        ci("\\beast\\b") to "E",
        ci("\\bwest\\b") to "W",
        ci("\\bkilometers\\b") to "km",
        ci("\\bkilometres\\b") to "km",
        ci("\\bkilometer\\b") to "km",
        ci("\\bmeters\\b") to "m",
        ci("\\bmeter\\b") to "m",
        ci("\\bminutes\\b") to "m",
        ci("\\bminute\\b") to "m",
        ci("\\bmins\\b") to "m",
        ci("\\bhours\\b") to "h",
        ci("\\bhour\\b") to "h",
        ci("\\bhrs\\b") to "h",
        ci("\\bleft\\b") to "LFT",
        ci("\\bright\\b") to "RGT",
        ci("\\bexit\\b") to "EXT",
        // Filler with no glance value. Dropped last so content rules match
        // first: "In 200 meters" leads with its distance once "In" is gone,
        // and "Make a sharp right turn" is just SHR once the verbs go.
        ci("\\bin\\b") to "",
        ci("\\bthe\\b") to "",
        ci("\\ban\\b") to "",
        ci("\\ba\\b") to "",
        ci("\\btake\\b") to "",
        ci("\\benter\\b") to "",
        ci("\\bat\\b") to "",
        ci("\\bmake\\b") to "",
        ci("\\bturn\\b") to "",
    )

    private val ORDINAL_EXIT = ci("\\b(first|second|third|fourth|fifth)\\s+exit\\b")
    private val NUMBERED_EXIT = ci("\\b(\\d+)(?:st|nd|rd|th)?\\s+exit\\b")

    private fun ordinalToNumber(word: String): String = when (word.lowercase()) {
        "first" -> "1"
        "second" -> "2"
        "third" -> "3"
        "fourth" -> "4"
        else -> "5"
    }

    // Compress one free-text instruction into short codes.
    // Idempotent: running it twice does not mangle the codes it produced.
    fun compress(text: String): String {
        var out = HTML_TAG.replace(text, "")
        out = ORDINAL_EXIT.replace(out) { m -> "RND-" + ordinalToNumber(m.groupValues[1]) }
        out = NUMBERED_EXIT.replace(out) { m -> "RND-" + m.groupValues[1] }
        for ((re, replacement) in WORD_RULES) {
            out = re.replace(out, replacement)
        }
        // A sentence-final full stop is never a decimal point. The sanitiser
        // would fold it to a space on the wire anyway; dropping it here keeps
        // one more column for the road name.
        // Maps names frequently contain " - ". A rolling window that meets
        // the separator can show "--", so collapse dash runs and trim edges.
        val clean = out.replace(SPACES, " ").replace(DASH_RUN, "-").trim().removeSuffix(".")
        return clean.trim('-', ' ')
    }

    // ASCII arrow prefix for the cluster text line. Only uses characters the
    // sanitiser keeps (letters, space, +-<>^), because anything else folds to
    // a space on the wire and the arrow would vanish.
    fun asciiArrow(pictogram: Int?): String = when (pictogram) {
        1 -> "<<"
        0, 11, 13, 15, 17, 19, 22, 23 -> "<-"
        2 -> "<"
        4 -> ">>"
        3, 12, 14, 16, 18, 20, 24, 25 -> "->"
        5 -> ">"
        6, 41 -> "U"
        7, 10, 21, 35 -> "^"
        9, 34 -> "X"
        // Compass bearings are directions, not "straight on". All eight used
        // to render as ^, so head south and head west looked like head north.
        50 -> "^"     // north
        51 -> "^>"    // northeast
        52 -> ">>"    // east
        53 -> "v>"    // southeast
        54 -> "vv"    // south
        55 -> "v<"    // southwest
        56 -> "<<"    // west
        57 -> "^<"    // northwest
        65, 66, 67, 68, 69, 70, 71 -> "O"
        else -> ""
    }

    // Unicode arrow for the phone UI, where the full glyph set renders.
    fun uiArrow(pictogram: Int?): String = when (pictogram) {
        1 -> "\u293C"
        0, 11, 13, 15, 17, 19, 22, 23 -> "\u2190"
        2 -> "\u21B0"
        4 -> "\u21C9"   // sharp right (was an up arrow)
        3, 12, 14, 16, 18, 20, 24, 25 -> "\u2192"
        5 -> "\u21B1"
        6, 41 -> "\u21A9"
        7, 10, 21, 35 -> "\u2191"
        9, 34 -> "\u2197"
        50 -> "\u2191"
        51 -> "\u2197"
        52 -> "\u2192"
        53 -> "\u2198"
        54 -> "\u2193"
        55 -> "\u2199"
        56 -> "\u2190"
        57 -> "\u2196"
        65, 66, 67, 68, 69, 70, 71 -> "\u21BB"
        8 -> "\u25CF"
        36, 37 -> "\u26F4"
        else -> ""
    }
}

// Rolling window for text wider than the cluster row.
//
// Short text is returned untouched. Long text scrolls one window per tick over
// text + separator, so every part becomes visible instead of being clipped.
// The separator is cluster-safe (" - "); the phone UI shows full text anyway.
object RollingText {
    private const val GAP = " - "

    fun window(text: String, width: Int, tick: Int): String {
        if (width <= 0) return ""
        if (text.length <= width) return text
        // Trim the join edges so the separator cannot double a dash that is
        // already at the end of the instruction.
        val body = text.trim(' ', '-')
        val loop = body + GAP
        val off = ((tick % loop.length) + loop.length) % loop.length
        // Guard pathological input where the loop is shorter than the row.
        val repeats = (off + width + loop.length - 1) / loop.length
        return loop.repeat(repeats).substring(off, off + width)
    }
}
