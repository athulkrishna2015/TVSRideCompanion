// Pins the short-code compression, the arrow tables and the rolling window.
//
// All street names are invented, per the repo rule against real locations.

package dev.tvsopen.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavShortCodesTest {

    @Test
    fun `maneuver phrases compress to short codes`() {
        assertEquals("LFT", NavShortCodes.compress("Turn left"))
        assertEquals("RGT", NavShortCodes.compress("Turn right"))
        assertEquals("SLF", NavShortCodes.compress("Turn slight left"))
        assertEquals("SLF", NavShortCodes.compress("Keep left"))
        assertEquals("SRG", NavShortCodes.compress("Turn slight right"))
        assertEquals("SRG", NavShortCodes.compress("Keep right"))
        assertEquals("SHL", NavShortCodes.compress("Turn sharp left"))
        assertEquals("SHR", NavShortCodes.compress("Make a sharp right turn"))
        assertEquals("UTN", NavShortCodes.compress("Make a U-turn"))
        assertEquals("UTN", NavShortCodes.compress("Take U-Turn"))
        assertEquals("STR", NavShortCodes.compress("Continue straight"))
        assertEquals("STR", NavShortCodes.compress("Go straight"))
    }

    @Test
    fun `modifiers and road words shrink`() {
        assertEquals("twd", NavShortCodes.compress("toward"))
        assertEquals("twd", NavShortCodes.compress("Towards"))
        assertEquals("LFT", NavShortCodes.compress("left"))
        assertEquals("RGT", NavShortCodes.compress("right"))
        assertEquals("DEST", NavShortCodes.compress("destination"))
        assertEquals("ARR", NavShortCodes.compress("You have arrived"))
        assertEquals("ARR-L", NavShortCodes.compress("Destination is on the left"))
        assertEquals("ARR-R", NavShortCodes.compress("Destination on the right"))
        assertEquals("MRG to Rd", NavShortCodes.compress("Merge onto road"))
        assertEquals("RMP", NavShortCodes.compress("Take the ramp"))
        assertEquals("EXT 5", NavShortCodes.compress("Take exit 5"))
        assertEquals("RND", NavShortCodes.compress("Enter roundabout"))
        assertEquals("RND-2", NavShortCodes.compress("Take the second exit"))
        assertEquals("FRK-L", NavShortCodes.compress("At the fork, keep left"))
    }

    @Test
    fun `a full google-style instruction compresses end to end`() {
        assertEquals(
            "200 m, SRG to Main St, > LFT",
            NavShortCodes.compress("In 200 meters, turn slight right onto Main Street, then turn left."),
        )
    }

    @Test
    fun `road suffixes and compass points shrink`() {
        assertEquals("Example Rd", NavShortCodes.compress("Example Road"))
        assertEquals("N", NavShortCodes.compress("Head north"))
        assertEquals("Hwy 101", NavShortCodes.compress("Highway 101"))
    }

    @Test
    fun `compression is idempotent`() {
        val once = NavShortCodes.compress("Turn left onto Example Road toward Hill View, then turn right")
        val twice = NavShortCodes.compress(once)
        assertEquals(once, twice)
    }

    @Test
    fun `cluster arrows are ascii and phone arrows are unicode`() {
        assertEquals("<-", NavShortCodes.asciiArrow(0))
        assertEquals("->", NavShortCodes.asciiArrow(3))
        assertEquals("^", NavShortCodes.asciiArrow(7))
        assertEquals("U", NavShortCodes.asciiArrow(6))
        assertEquals("U", NavShortCodes.asciiArrow(41))
        assertEquals("O", NavShortCodes.asciiArrow(68))
        assertEquals("", NavShortCodes.asciiArrow(null))
        assertEquals("", NavShortCodes.asciiArrow(999))
        assertEquals("\u2190", NavShortCodes.uiArrow(0))
        assertEquals("\u2192", NavShortCodes.uiArrow(3))
        assertEquals("\u2191", NavShortCodes.uiArrow(7))
    }

    @Test
    fun `ascii arrows survive the cluster sanitiser`() {
        // Every arrow must be made of characters PacketBuilder keeps, or the
        // prefix would fold to spaces on the wire.
        val kept = "+-<>^ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 "
        for (id in listOf(0, 1, 2, 3, 4, 5, 6, 7, 9, 41, 68)) {
            val arrow = NavShortCodes.asciiArrow(id)
            assertTrue("arrow '$arrow' for $id uses unkept chars", arrow.all { it in kept })
        }
    }

    @Test
    fun `short text never scrolls`() {
        assertEquals("LFT", RollingText.window("LFT", 12, 0))
        assertEquals("LFT", RollingText.window("LFT", 12, 7))
        assertEquals("", RollingText.window("LFT", 0, 0))
    }

    @Test
    fun `long text rolls one window per tick and wraps`() {
        val text = "RGT to Hill View Rd xyz"
        val width = 12
        val loop = text + " - "
        assertEquals(text.take(width), RollingText.window(text, width, 0))
        assertTrue(RollingText.window(text, width, 5) != RollingText.window(text, width, 0))
        assertEquals(
            RollingText.window(text, width, 0),
            RollingText.window(text, width, loop.length),
        )
        for (t in 0 until loop.length) {
            assertEquals(width, RollingText.window(text, width, t).length)
        }
    }
}
