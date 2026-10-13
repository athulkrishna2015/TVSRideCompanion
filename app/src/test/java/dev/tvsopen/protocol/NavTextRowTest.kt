// Two navigation lines at once, instead of alternating one.
//
// The cluster's control frame carries a row count at byte 10, and the official
// app exposes a second navigation text data id (`0x50`) alongside `0x4F`. Both
// are transcribed from the APK; neither has been seen rendering on this bike.
// These tests pin the packet shape so the fallback path stays correct and the
// two-row path is one flag away from working.

package dev.tvsopen.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavTextRowTest {

    private fun nav(text: String, rows: Int = 1) = NavStep(
        distanceToTurnMeters = 550,
        etaMinutes = 12,
        remainingDistanceMeters = 4200,
        // Pictogram 0 is turn_left in Pictograms.ALL; STRAIGHT is the only one
        // exposed as a constant, so the raw id is used rather than inventing a
        // constant that no production code references.
        pictogram = 0,
        streetName = text,
        rows = rows,
    )

    @Test
    fun `primary and secondary lines are different data ids`() {
        val first = PacketBuilder.navigationText("Example Road")
        val second = PacketBuilder.navigationText("Example Road", OutboundId.NAVIGATION_TEXT_ALT)

        assertEquals(0x4F, first[1].toInt() and 0xFF)
        assertEquals(0x50, second[1].toInt() and 0xFF)
        // Same start byte, so the cluster routes them the same way.
        assertEquals(first[0].toInt() and 0xFF, second[0].toInt() and 0xFF)
    }

    @Test
    fun `both lines are 20 bytes and FF terminated`() {
        for (id in listOf(OutboundId.NAVIGATION_TEXT, OutboundId.NAVIGATION_TEXT_ALT)) {
            val f = PacketBuilder.navigationText("Any Street", id)
            assertEquals(20, f.size)
            assertEquals(0xFF, f[19].toInt() and 0xFF)
        }
    }

    @Test
    fun `both lines carry the same 17 sanitised characters`() {
        val first = PacketBuilder.navigationText("Example Road")
        val second = PacketBuilder.navigationText("Example Road", OutboundId.NAVIGATION_TEXT_ALT)
        for (i in 2..18) {
            assertEquals(
                "payload byte $i must match between rows",
                first[i].toInt() and 0xFF,
                second[i].toInt() and 0xFF,
            )
        }
    }

    @Test
    fun `the control frame advertises two rows when asked`() {
        val one = PacketBuilder.navigationControl(nav("Example Road", rows = 1))
        val two = PacketBuilder.navigationControl(nav("Example Road", rows = 2))

        assertEquals(1, one[10].toInt() and 0xFF)
        assertEquals(2, two[10].toInt() and 0xFF)
        // Only the row count differs; distance, ETA and pictogram are unchanged.
        for (i in listOf(2, 3, 4, 5, 6, 7, 8, 9, 11)) {
            assertEquals("byte $i must not depend on row count", one[i], two[i])
        }
    }

    @Test
    fun `the row count is clamped to at least one`() {
        // Byte 10 is unsigned and upstream hardcodes 1; a zero would claim no
        // text at all and close the HUD.
        val zero = PacketBuilder.navigationControl(nav("Example Road", rows = 0))
        assertEquals(1, zero[10].toInt() and 0xFF)
    }

    @Test
    fun `distinct street and metrics lines differ on the wire`() {
        // The reason two rows are worth having: the alternating path could only
        // ever show one of these at a time.
        val street = PacketBuilder.navigationText("Example Road")
        val metrics = PacketBuilder.navigationText("ETA 12m 550m")
        assertNotEquals(
            street.copyOfRange(2, 19).toList(),
            metrics.copyOfRange(2, 19).toList(),
        )
    }

    @Test
    fun `a long street is truncated to the row, not overflowing`() {
        val f = PacketBuilder.navigationText("A Very Long Street Name Indeed 12345")
        assertEquals(17, f.copyOfRange(2, 19).count { it != 0.toByte() })
        assertEquals(0xFF, f[19].toInt() and 0xFF)
        assertTrue("payload must not run into the terminator", f[18].toInt() != 0xFF)
    }
}
