// Parsing of maps-app navigation notifications.
//
// Split out of `MapsNotificationListener` for one reason: everything here is
// pure, and the bugs in it are arithmetic-shaped — a provider puts the distance
// somewhere you did not look, a unit you did not handle, a time in a format you
// did not parse. Those are exactly the bugs that survive an on-device smoke test
// because the app still *shows* something, just the wrong thing.
//
// So this is a plain object with no Android types in its signatures, and
// `NavParsingTest` pins the cases that actually occurred on hardware.
//
// The provider formats are not a spec; they were observed. Google Maps during
// live navigation on 2026-10-12:
//
//   EXTRA_TITLE     "550 m"                  <- distance, not a maneuver
//   EXTRA_TEXT      "Example Road"
//   EXTRA_SUB_TEXT  "1:58 PM"                <- arrival clock, not a duration
//
// Earlier providers are assumed, not observed: most put the distance in the
// text and a duration in the ETA slot. Both shapes are accepted because only
// one of them can be right for a given app and we cannot test the rest yet.

package dev.tvsopen.nav

import android.app.Notification
import dev.tvsopen.protocol.ManeuverResolver

/** Result of reading one navigation notification. */
data class ParsedNav(
    /** The maneuver, or empty when the provider's title was only a distance. */
    val maneuver: String,
    /** Where the provider put the instruction. */
    val street: String,
    /** Metres to the maneuver; -1 when the notification carried no distance. */
    val distanceMeters: Int,
    val etaMinutes: Int,
    val pictogram: Int?,
)

/**
 * What kind of notification this is, for the cluster.
 *
 * The Ntorq 125 shows navigation, caller ID and SMS in the same display
 * regions, so the app has to know which it is looking at rather than treating
 * everything as a turn-by-turn step.
 */
enum class ClusterNotice { NAVIGATION, INCOMING_CALL, MESSAGE, MEDIA }

/** Which maps app mode is producing the navigation. */
enum class NavMode { DRIVING, TWO_WHEELER, UNKNOWN }

/**
 * Classify a notification by package and extras.
 *
 * **Structural, not a package list.** Dialers and messaging apps are
 * effectively unbounded in number and none of them share a documented extras
 * contract, so keying on a list of packages would work only for the few
 * installed today. The classification keys on what the platform guarantees:
 * the call extras, and the messaging `MessagingStyle` extras.
 *
 * Car vs bike is also structural. Google Maps sets
 * `EXTRA_NAVIGATION_MODE` to 2 for two-wheeler and 1 for driving, in both
 * modes — so a bike route is detected from the same notification as a car
 * route and needs no separate handling.
 */
fun classify(
    pkg: String,
    extras: android.os.Bundle,
    isMapsPackage: Boolean,
): Pair<ClusterNotice, NavMode> {
    // Incoming call first: a dialer's notification is ongoing and has a title
    // and text, so it would otherwise be misread as navigation.
    if (extras.containsKey(Notification.EXTRA_INCOMING_CALL_NUMBER) ||
        extras.containsKey("android.telecom.extra.INCOMING_CALL_EXTRAS")
    ) {
        return ClusterNotice.INCOMING_CALL to NavMode.UNKNOWN
    }
    if (isMapsPackage) {
        val mode = when (extras.getInt(Notification.EXTRA_NAVIGATION_MODE, 0)) {
            1 -> NavMode.DRIVING
            2 -> NavMode.TWO_WHEELER
            else -> NavMode.UNKNOWN
        }
        return ClusterNotice.NAVIGATION to mode
    }
    // MessagingStyle is what a messaging app sets for a conversation, and is
    // the only reliable way to tell a message from a random app notification.
    if (extras.getParcelable<Notification.MessagingStyle>(Notification.EXTRA_MESSAGING_STYLE) != null) {
        return ClusterNotice.MESSAGE to NavMode.UNKNOWN
    }
    if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION) &&
        pkg.contains("music", ignoreCase = true)
    ) {
        return ClusterNotice.MEDIA to NavMode.UNKNOWN
    }
    return ClusterNotice.MEDIA to NavMode.UNKNOWN
}

object NavParsing {

    /**
     * `150 m`, `1.5 km`, `850m`, `2,3 km` — providers vary on punctuation.
     *
     * The unit alternatives are **longest first, and that is load-bearing**.
     * Regex alternation is ordered and tries `m` before `mi`, so
     * `(m|km|mi|ft)` matches the `m` of `"1 mi"` and a mile is read as one
     * metre — a 1600x error that looks entirely plausible in the UI. `mi` has
     * to be attempted before `m`.
     */
    private val DISTANCE_RE =
        Regex("""(\d+(?:[.,]\d+)?)\s*(km|mi|ft|m)""", RegexOption.IGNORE_CASE)

    /** `1 min`, `25 mins`, `1 hr`, `2 hours`. */
    private val ETA_RE =
        Regex("""(\d+)\s*(min|mins|minute|minutes|hr|hrs|hour|hours)""", RegexOption.IGNORE_CASE)

    /** A standalone arrival time: `1:58 PM`, `09:04 am`. */
    private val CLOCK_RE = Regex("""^\s*(\d{1,2}):(\d{2})\s*([AaPp])\.?[Mm]\.?\s*$""")

    /**
     * Parse the four notification string fields.
     *
     * [title] is searched for a distance as well as [text] and [bigText],
     * because Google Maps puts it there and nowhere else — searching only the
     * text meant `distanceMeters` stayed -1 for an entire journey.
     */
    fun parse(
        title: String,
        text: String,
        bigText: String,
        subText: String,
        nowMinutes: Int = nowMinutes(),
    ): ParsedNav {
        val distance = extractDistance(title, text, bigText, subText)
        val street = text.ifBlank { bigText }.ifBlank { subText }
        // Having found the distance in the title, do not then display the title
        // as the maneuver: "550 m" is not an instruction.
        val maneuver = title.trim().takeUnless { isDistanceOnly(it) }.orEmpty()
        return ParsedNav(
            maneuver = maneuver,
            street = street,
            distanceMeters = distance,
            etaMinutes = extractEtaMinutesAt(nowMinutes, subText, bigText, text, title),
            // Tier 2 resolution: the cluster icon table is closed, so fall back
            // to the words when the provider gives us no usable icon.
            pictogram = ManeuverResolver.fromText("$maneuver $street"),
        )
    }

    fun extractDistance(vararg texts: String?): Int {
        for (t in texts) {
            if (t.isNullOrBlank()) continue
            val m = DISTANCE_RE.find(t) ?: continue
            val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
            return when (m.groupValues[2].lowercase()) {
                "km" -> (value * 1000).toInt()
                "mi" -> (value * 1609).toInt()
                "ft" -> (value * 0.3048).toInt()
                else -> value.toInt()
            }
        }
        return -1
    }

    /**
     * Duration first, then a wall-clock arrival time.
     *
     * The order matters: a notification carrying both is a duration, and
     * reinterpreting "25 min" as a clock would be nonsense.
     */
    /**
     * [nowMinutes] has to be a parameter of the implementation rather than a
     * defaulted one on this signature: Kotlin only permits a default *after* a
     * `vararg` when it is passed by name, and the call site here is a spread of
     * provider fields. So the public entry point reads the clock and delegates.
     */
    fun extractEtaMinutes(vararg texts: String?): Int = extractEtaMinutesAt(nowMinutes(), *texts)

    fun extractEtaMinutesAt(nowMinutes: Int, vararg texts: String?): Int {
        for (t in texts) {
            if (t.isNullOrBlank()) continue

            // Every unit in the string is summed, not just the first match.
            // Google Maps writes the ETA as "6 hr 22 min · 302 km · 4:16 AM
            // ETA"; `find` stopped at "6 hr" and reported 6 h, dropping 22 min
            // off every long trip. A nav ETA is one duration written across
            // several units, so the units add up rather than compete.
            val total = ETA_RE.findAll(t)
                .mapNotNull { m ->
                    val n = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                    when (m.groupValues[2].lowercase()) {
                        "hr", "hrs", "hour", "hours" -> n * 60
                        else -> n
                    }
                }
                .sum()
            if (total > 0) return total

            val clock = minutesUntilClock(t, nowMinutes)
            if (clock >= 0) return clock
        }
        return 0
    }

    /**
     * True when the string is nothing *but* a distance, e.g. `550 m`.
     *
     * Both ends are checked, and that is the point. `find` alone is not enough:
     * in `"turn right in 550 m"` the match also ends at the final character, so
     * an end-anchored test would classify a genuine instruction as a distance
     * and discard the maneuver. Requiring index 0 as well is what separates
     * them.
     */
    fun isDistanceOnly(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return true
        val m = DISTANCE_RE.find(t) ?: return false
        return m.range.first == 0 && m.range.last == t.lastIndex
    }

    /**
     * Minutes until a wall-clock arrival time, or -1 if this is not one.
     *
     * Rolls over at midnight rather than going negative. [nowMinutes] is a
     * parameter, not a call to the clock, so this is testable and does not
     * silently change answer depending on when the suite runs.
     */
    fun minutesUntilClock(clock: String, nowMinutes: Int): Int {
        val m = CLOCK_RE.find(clock) ?: return -1
        var hour = m.groupValues[1].toIntOrNull() ?: return -1
        val minute = m.groupValues[2].toIntOrNull() ?: return -1
        val meridiem = m.groupValues[3]
        if (meridiem.equals("p", ignoreCase = true) && hour < 12) hour += 12
        if (meridiem.equals("a", ignoreCase = true) && hour == 12) hour = 0
        if (hour > 23 || minute > 59) return -1
        val delta = hour * 60 + minute - nowMinutes
        return if (delta < 0) delta + 24 * 60 else delta
    }

    private fun nowMinutes(): Int {
        val c = java.util.Calendar.getInstance()
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
    }
}
