// The fuel field is the one place where the decode is proven correct and the
// presentation is still wrong.
//
// The decode is settled: DataParser.extractValue concatenates the field's bytes
// left to right and parses the result as one hex number, which is big-endian,
// and NtorqSpeedOMeter1.getFuelLevel() takes the last hex character of the byte,
// which is the low nibble. This app does both.
//
// What is NOT settled is how that number maps onto the cluster's six-bar gauge.
// The raw value observed while the dash was photographed reading FUEL EMPTY was
// 7 — a value six bars cannot represent. Clamping it to 6 is what displayed a
// full tank on an empty one, so out-of-range values are dropped instead.
//
// This test pins that boundary. It is the difference between "unverified" and
// "actively wrong", and it is cheap to keep.

package dev.tvsopen.protocol

import dev.tvsopen.session.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuelLevelTest {

    private val max = Telemetry.FUEL_BAR_MAX

    /** Mirrors Session.kt: low nibble, then kept only if the gauge can show it. */
    private fun bars(rawByte: Int): Int? =
        rawByte.and(0x0F).takeIf { it in 0..max }

    @Test
    fun `the gauge has six bars`() {
        assertEquals(6, max)
    }

    @Test
    fun `a raw seven is dropped, not clamped to full`() {
        // The case from the real cluster: dash says FUEL EMPTY, byte says 0x07.
        assertNull(bars(0x07))
    }

    @Test
    fun `a raw six is the only full reading a six bar gauge can show`() {
        assertEquals(6, bars(0x06))
    }

    @Test
    fun `values inside the gauge still render`() {
        assertEquals(0, bars(0x00))
        assertEquals(1, bars(0x01))
        assertEquals(2, bars(0x02))
        assertEquals(5, bars(0x05))
    }

    @Test
    fun `the high nibble does not affect the low nibble reading`() {
        // The official getter takes the last hex character, so 0x12 and 0x02 both
        // read as 2. Whatever the high nibble means, it is not part of the bars.
        assertEquals(bars(0x02), bars(0x12))
        assertEquals(2, bars(0x12))
    }

    @Test
    fun `every value above the gauge is rejected`() {
        for (v in (max + 1)..0x0F) {
            assertNull("0x0${v.toString(16)} must not render as a bar count", bars(v))
        }
    }

    @Test
    fun `the previous implementation clamped and showed a full tank`() {
        // Pinned deliberately: the old code returned min(value, 6), so 0x07
        // produced 6 of 6 — "full" — on a tank the cluster called empty.
        val clamped = { v: Int -> if (v > max) max else v }
        assertEquals(6, clamped(0x07))
        assertEquals(null, bars(0x07))
    }
}
