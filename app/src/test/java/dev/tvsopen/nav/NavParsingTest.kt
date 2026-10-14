// Pins the navigation-notification parsing to formats actually observed.
//
// Every case here came from a real notification on 2026-10-12 with Google Maps
// navigating. The first three are regressions: the original code returned -1 for
// the distance and 0 for the ETA on all of them, and none of that was visible
// as an error — the strip just quietly said "0 min to arrival" while Maps said
// "550 m".

package dev.tvsopen.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavParsingTest {

    /** 2026-10-12 21:00 local, so the clock cases are deterministic. */
    private val ninePm = 21 * 60

    /** 13:00 — used where the target is an afternoon clock time. */
    private val onePm = 13 * 60

    // --- The exact Google Maps notification that exposed the bug -------------

    @Test
    fun `google maps puts the distance in the title`() {
        val p = NavParsing.parse(
            title = "550 m",
            text = "Example Road",
            bigText = "",
            subText = "1:58 PM",
        )
        assertEquals("the title is the only place the distance appears", 550, p.distanceMeters)
    }

    @Test
    fun `google maps distance is not mistaken for a maneuver`() {
        val p = NavParsing.parse("550 m", "Example Road", "", "1:58 PM")
        assertEquals("", p.maneuver)
        assertEquals("Example Road", p.street)
    }

    @Test
    fun `a distance with no unit is not a distance`() {
        assertEquals(-1, NavParsing.extractDistance("Level 3", "Sector 21"))
    }

    @Test
    fun `a real instruction ending in a distance survives`() {
        // The trap: the regex match also ends at the last character here, so a
        // test anchored only at the end would throw the maneuver away.
        assertEquals(false, NavParsing.isDistanceOnly("turn right in 550 m"))
        assertEquals(true, NavParsing.isDistanceOnly("550 m"))
        assertEquals(true, NavParsing.isDistanceOnly(" 1.5 km "))
        assertEquals(true, NavParsing.isDistanceOnly(""))
        assertEquals(false, NavParsing.isDistanceOnly("Turn left"))
    }

    // --- Units ---------------------------------------------------------------

    @Test
    fun `units convert`() {
        assertEquals(1500, NavParsing.extractDistance("1.5 km"))
        assertEquals(1500, NavParsing.extractDistance("1,5 km"))  // comma decimal
        assertEquals(150, NavParsing.extractDistance("150m"))      // no space
        assertEquals(1609, NavParsing.extractDistance("1 mi"))
        assertEquals(304, NavParsing.extractDistance("1000 ft"))   // 304.8 truncated
        assertEquals(0, NavParsing.extractDistance("0 m"))         // Maps' first step
    }

    @Test
    fun `no distance anywhere yields the sentinel`() {
        assertEquals(-1, NavParsing.extractDistance("", "", "Heading north"))
    }

    @Test
    fun `minutes are not miles`() {
        // Observed live: Google Maps arrival text is "Drive 1 min (450 m) to
        // Perambra", and longest-first alternation matched the "mi" in "min",
        // decoding every arrival as a 1609 m turn. The real distance sits later
        // in the same string and must be the one found.
        assertEquals(450, NavParsing.extractDistance("Drive 1 min (450 m) to Perambra"))
        assertEquals(850, NavParsing.extractDistance("Drive 3 min (850 m) to Perambra"))
        assertEquals(-1, NavParsing.extractDistance("1 min"))
        // A genuine mile still converts — the lookahead only rejects a unit
        // glued to more letters.
        assertEquals(1609, NavParsing.extractDistance("1 mi"))
        assertEquals(1609, NavParsing.extractDistance("in 1 mi"))
    }

    @Test
    fun `google maps arrival shape parses end to end`() {
        // Recorded from a live notification, 2026-10-14. The distance is the
        // parenthesised metres in the text, not the trip total in the sub-text
        // and not a mile read out of "min".
        val parsed = NavParsing.parse(
            title = "Arrive at 11:41 AM",
            text = "Drive 1 min (450 m) to Perambra",
            bigText = "",
            subText = "1 hr 8 min · 38 km · 9:16 PM ETA",
        )
        assertEquals(450, parsed.distanceMeters)
        assertEquals(68, parsed.etaMinutes)
        assertEquals("Arrive at 11:41 AM", parsed.maneuver)
    }

    // --- ETA -----------------------------------------------------------------

    @Test
    fun `google maps reports arrival as a clock time`() {
        // Baseline 13:00, because "1:58 PM" means 13:58 — against a 21:00
        // baseline the next 1:58 PM is tomorrow and the answer is 1018, which is
        // correct and is why the baseline has to be stated rather than assumed.
        val p = NavParsing.parse("550 m", "Example Road", "", "1:58 PM", onePm)
        assertEquals("1:58 PM from 13:00", 58, p.etaMinutes)
    }

    @Test
    fun `clock eta handles midnight rollover`() {
        // All against 21:00, so every value below is unambiguous:
        assertEquals("23:30 tonight", 150, NavParsing.minutesUntilClock("11:30 PM", ninePm))
        assertEquals("21:10 tonight", 10, NavParsing.minutesUntilClock("9:10 PM", ninePm))
        assertEquals("09:20 tomorrow, not tonight", 740, NavParsing.minutesUntilClock("9:20 am", ninePm))
        assertEquals("00:10 tomorrow", 190, NavParsing.minutesUntilClock("12:10 am", ninePm))
    }

    @Test
    fun `clock eta rejects things that are not clock times`() {
        assertEquals(-1, NavParsing.minutesUntilClock("550 m", ninePm))
        assertEquals(-1, NavParsing.minutesUntilClock("Example Road", ninePm))
        assertEquals(-1, NavParsing.minutesUntilClock("25:00 PM", ninePm))
        assertEquals(-1, NavParsing.minutesUntilClock("", ninePm))
    }

    @Test
    fun `a multi-unit duration is summed, not truncated`() {
        // Observed verbatim from Google Maps. `find` reported 6 h and threw
        // away the 22 min, so every long trip under-reported by 22 minutes.
        assertEquals(382, NavParsing.extractEtaMinutes("6 hr 22 min · 302 km · 4:16 AM ETA"))
        assertEquals(22, NavParsing.extractEtaMinutes("22 min"))
        assertEquals(95, NavParsing.extractEtaMinutes("1 hr 35 min"))
        assertEquals("minutes are not hours", 55, NavParsing.extractEtaMinutes("55 minutes"))
    }

    @Test
    fun `a duration beats a clock time in the same notification`() {
        // "25 min" must not be reinterpreted as half past one.
        assertEquals(25, NavParsing.extractEtaMinutes("25 min", "1:25 PM"))
        assertEquals(120, NavParsing.extractEtaMinutes("2 hr"))
    }

    @Test
    fun `missing eta is zero not negative`() {
        assertEquals(0, NavParsing.extractEtaMinutes("", "Example Road"))
    }

    // --- Shape tolerance -----------------------------------------------------

    @Test
    fun `big text stands in for empty text`() {
        val p = NavParsing.parse("550 m", "", "Example Road", "1:58 PM", onePm)
        assertEquals("Example Road", p.street)
        assertEquals(550, p.distanceMeters)
    }

    @Test
    fun `transitional 'Starting navigation' still parses without inventing data`() {
        // Observed as the very first notification. No distance, no ETA: the
        // result must be the sentinels, not zeros dressed up as real readings.
        val p = NavParsing.parse("Starting navigation…", "", "", "", ninePm)
        assertEquals(-1, p.distanceMeters)
        assertEquals(0, p.etaMinutes)
        assertTrue(p.maneuver.isNotEmpty())
    }
}
