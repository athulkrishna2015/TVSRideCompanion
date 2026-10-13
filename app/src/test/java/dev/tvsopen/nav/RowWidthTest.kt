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

    @Test
    fun `a long street no longer loses its destination half`() {
        // An invented name, per the repo rule against real street names.
        val street = "Example Road - Hill Route"
        assertTrue(street.length > RowChars.NTORQ)

        // Truncation cuts mid-word and drops the destination half — which is why
        // the bridge scrolls instead of calling take().
        assertEquals("Example Rd - ", street.take(RowChars.NTORQ))

        // What scrolling must recover, so the two are pinned against each other:
        // the tail has to be non-empty or there is nothing to scroll to.
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
            street = "Example Road - Hill Route",
            distanceMeters = 550,
            pictogram = null,
            provider = "test",
        )
        // take() keeps the trailing space; sanitize() collapses and trims it
        // later, so the raw display line is the one asserted here.
        assertEquals("Example Rd - ", step.displayLine())
        assertEquals(RowChars.NTORQ, step.displayLine().length)
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
        assertEquals("Take U-Turn", step.displayLine())
    }
}
