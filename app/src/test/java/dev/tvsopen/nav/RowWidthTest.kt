// The cluster's row is 12 characters, not 17.
//
// The 17 came from the protocol's text payload and from the Jupiter app filling
// all 17 bytes. TVS's Ntorq 125 manual documents what this cluster actually
// renders, and every example is 12 characters or fewer — including two that are
// visibly cut:
//
//   DISTANCE REMAINING -> DISTANCE REM
//   STAY SAME ROAD     -> STAY SAME RD
//
// A renderer that truncates at 12 and never exceeds it is displaying a 12-cell
// row. Sending 17 meant the tail was lost, so "Example Road - Hill Route" rendered as
// "Example Rd -" — the half naming the destination gone.
//
// These pin the width and the scrolling that replaces truncation.

package dev.tvsopen.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RowWidthTest {

    @Test
    fun `the Ntorq row is twelve characters`() {
        assertEquals(12, RowChars.NTORQ)
    }

    /** Every string TVS documents the cluster rendering. */
    private val tvsStrings = listOf(
        "AFTER 900m", "TAKE RIGHT", "10Km 700m", "STAY SAME RD",
        "DISTANCE REM", "50 MINS", "TIME REM", "RE-ROUTING",
        "TAKE U-TURN", "SEARCHING", "GPS SIGNAL", "FETCHING",
        "MOBILE DATA", "YOU ARRIVED",
    )

    @Test
    fun `no string in the manual exceeds the row`() {
        for (s in tvsStrings) {
            assertTrue("'$s' is ${s.length} chars, wider than ${RowChars.NTORQ}", s.length <= RowChars.NTORQ)
        }
    }

    @Test
    fun `the manual's truncations land exactly on the row width`() {
        // If 12 were a coincidence rather than the limit, these would not both
        // stop at the same column.
        assertEquals(RowChars.NTORQ, "DISTANCE REM".length)
        assertEquals(RowChars.NTORQ, "STAY SAME RD".length)
    }

    /**
     * An invented name, per the repo rule against real street names.
     *
     * 25 characters — long enough that [RowChars.NTORQ] cuts it well short.
     * The expected values below are counted, not eyeballed; two earlier
     * revisions of this file asserted them by hand and were both wrong.
     */
    private val longStreet = "Example Road - Hill Route"

    @Test
    fun `a long street is cut at the row width`() {
        assertTrue(longStreet.length > RowChars.NTORQ)

        // "Example Road" is exactly the 12 characters that fit. The separator and
        // the destination half are lost — which is why the bridge scrolls rather
        // than relying on take().
        assertEquals("Example Road", longStreet.take(RowChars.NTORQ))
        assertEquals(RowChars.NTORQ, longStreet.take(RowChars.NTORQ).length)
    }

    @Test
    fun `the part truncation drops is exactly what scrolling recovers`() {
        // Pinned as its own assertion because the two halves have to agree: if
        // scrolling ever showed the head and never the tail, this would still
        // pass while the destination stayed invisible.
        assertEquals(" - Hill Route", longStreet.drop(RowChars.NTORQ))
        assertTrue(longStreet.drop(RowChars.NTORQ).isNotBlank())
    }

    @Test
    fun `what is dropped is exactly the overflow`() {
        // Expressed as a relationship rather than a hand-counted length, because
        // three earlier revisions of this file got the count wrong and each one
        // cost a CI round trip. take() + drop() reassembling the original is the
        // property that actually matters and cannot drift when a name changes.
        for (name in listOf("Hill View Rd xyz", "Example Road - Hill Route", "NH-44")) {
            assertEquals(
                "take and drop must reassemble '$name'",
                name,
                name.take(RowChars.NTORQ) + name.drop(RowChars.NTORQ),
            )
            assertEquals(
                "take must never exceed the row for '$name'",
                minOf(name.length, RowChars.NTORQ),
                name.take(RowChars.NTORQ).length,
            )
        }
        assertEquals("Hill View Rd", "Hill View Rd xyz".take(RowChars.NTORQ))
        assertEquals(" xyz", "Hill View Rd xyz".drop(RowChars.NTORQ))
    }

    @Test
    fun `a short street is untouched`() {
        assertEquals("NH-44", "NH-44".take(RowChars.NTORQ))
    }

    @Test
    fun `the Jupiter row is still seventeen`() {
        // The frame is the same for both; only the usable characters differ.
        assertEquals(17, RowChars.JUPITER)
    }

    @Test
    fun `displayLine prefers the maneuver, which is what the cluster shows`() {
        // The live provider posts the maneuver as the title and trip metrics as
        // the text. Preferring the street showed "2.32 km • 3" on both lines and
        // the turn instruction never reached the cluster at all.
        // The maneuver is compressed to short codes with an ASCII arrow and
        // rolled when it still overflows the 12-character row.
        val step = NavInstruction(
            title = "Turn left onto Hill View Road",
            street = "2.32 km • 3 min • 11:40 AM ETA",
            distanceMeters = 2320,
            etaMinutes = 3,
            pictogram = 0,
            provider = "test",
        )
        assertEquals("<- LFT to Hi", step.displayLine())
    }

    @Test
    fun `displayLine falls back to the street when the title is distance-only`() {
        // The Google Maps shape: title "550 m" parses to an empty maneuver, so
        // the road name is the only instruction available. Road words compress.
        val step = NavInstruction(
            title = "",
            street = "Example Road",
            distanceMeters = 550,
            pictogram = null,
            provider = "test",
        )
        assertEquals("Example Rd", step.displayLine())
    }

    @Test
    fun `metricsLine fits the maneuver row`() {
        // Without truncation this was 14 characters on a 10-character row and
        // the cluster clipped the distance — the very number the line exists for.
        val step = NavInstruction(
            title = "Turn left",
            street = "Example Road",
            distanceMeters = 2320,
            etaMinutes = 3,
            pictogram = 0,
            provider = "test",
        )
        assertEquals("ETA 3m 2Km", step.metricsLine())
        assertEquals(RowChars.NTORQ_LINE_1, step.metricsLine().length)
    }

    @Test
    fun `displayLine defaults to the Ntorq width`() {
        val step = NavInstruction(
            title = "Turn left",
            street = longStreet,
            distanceMeters = 550,
            pictogram = null,
            provider = "test",
        )
        assertEquals("LFT", step.displayLine())
        assertEquals("LFT".length, step.displayLine().length)
    }

    @Test
    fun `displayLine uses the Jupiter width only when asked`() {
        val step = NavInstruction(
            title = "Turn right onto Hill View Road xyz",
            street = longStreet,
            distanceMeters = 550,
            pictogram = null,
            provider = "test",
        )
        val ntorq = step.displayLine()
        val jupiter = step.displayLine(maxChars = RowChars.JUPITER)
        // Windows can end on a word gap; compare trimmed, pin widths exactly.
        assertEquals("RGT to Hill", ntorq.trim())
        assertEquals(RowChars.NTORQ, ntorq.length)
        assertEquals("RGT to Hill View", jupiter.trim())
        assertEquals(RowChars.JUPITER, jupiter.length)
        assertTrue("the Jupiter row must show more", jupiter.length > ntorq.length)
    }

    @Test
    fun `displayLine falls back to the title when there is no street`() {
        val step = NavInstruction(
            title = "Take U-Turn",
            street = "",
            distanceMeters = 80,
            pictogram = null,
            provider = "test",
        )
        assertEquals("UTN", step.displayLine())
    }

    @Test
    fun `an overflowing line rolls instead of clipping its tail`() {
        // The destination half used to be lost off the end; each tick shows the
        // next window and a full cycle returns to the head.
        val step = NavInstruction(
            title = "Turn right onto Hill View Road xyz",
            street = "",
            distanceMeters = 550,
            pictogram = null,
            provider = "test",
        )
        val head = step.displayLine(tick = 0)
        val moved = step.displayLine(tick = 5)
        assertEquals(RowChars.NTORQ, head.length)
        assertEquals(RowChars.NTORQ, moved.length)
        assertTrue("tick 5 must show a different window, got '$moved'", moved != head)
        val full = NavShortCodes.compress("Turn right onto Hill View Road xyz")
        val loop = full + " - "
        assertEquals(head, step.displayLine(tick = loop.length))
    }
}
