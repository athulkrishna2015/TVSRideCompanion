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
// row. Sending 17 meant the tail was lost, so "Meppayyur - Avalla" rendered as
// "Meppayyur -" — the half naming the destination gone.
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

    @Test
    fun `a long street no longer loses its destination half`() {
        // The real regression: 17 chars, cut to 12, drops "Avalla" entirely.
        val street = "Meppayyur - Avalla"
        assertTrue(street.length > RowChars.NTORQ)
        assertEquals("Meppayyur -", street.take(RowChars.NTORQ))
        assertTrue("the tail must survive somewhere", street.drop(RowChars.NTORQ).isNotBlank())
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
    fun `displayLine defaults to the Ntorq width`() {
        val step = NavInstruction(
            title = "Turn left",
            street = "Meppayyur - Avalla",
            distanceMeters = 550,
            pictogram = null,
            provider = "test",
        )
        assertEquals("Meppayyur - ", step.displayLine().trimEnd())
        assertEquals(12, step.displayLine().length)
    }
}
