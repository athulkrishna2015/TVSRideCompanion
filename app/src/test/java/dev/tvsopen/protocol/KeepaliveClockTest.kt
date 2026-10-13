// The keepalive tells the cluster what time it is.
//
// Found on hardware: the dash showed the wrong time while everything else
// worked. The keepalive was writing zeros at [6..9] and [12..14], where the
// official `0x4A` carries hour, minute, second, AM/PM and the date — so a ping
// arriving every two seconds was telling the cluster it was midnight. The link
// was up, telemetry was live, and nothing about that looks like a bug.
//
// The layout is transcribed from `U399DataSenderToCluster.sendMobileData` in
// com.tvsm.connect 2.13.0. Two of its details are pinned below because both
// produce a plausible-looking wrong answer: hour is 12-hour with midnight as 12
// rather than 0, and byte 18 is zero rather than a checksum.

package dev.tvsopen.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class KeepaliveClockTest {

    /** A calendar pinned to a known instant, so the test cannot drift. */
    private fun at(
        y: Int, mo: Int, d: Int, h24: Int, mi: Int, s: Int,
        tz: String = "UTC",
    ): Calendar = Calendar.getInstance(TimeZone.getTimeZone(tz)).apply {
        clear()
        set(y, mo - 1, d, h24, mi, s)
    }

    private fun ping(cal: Calendar, state: DashState = DashState(0)) =
        Keepalive.build(state, calendar = cal)

    @Test
    fun `header and terminator are right`() {
        val b = ping(at(2026, 10, 13, 13, 35, 19))
        assertEquals(0x5B, b[0].toInt() and 0xFF)
        assertEquals(0x4A, b[1].toInt() and 0xFF)
        assertEquals(20, b.size)
        assertEquals(0xFF, b[19].toInt() and 0xFF)
    }

    @Test
    fun `afternoon puts the time where the cluster reads it`() {
        // 13:35:19 local. The bug: these five bytes were all zero.
        val b = ping(at(2026, 10, 13, 13, 35, 19))
        assertEquals(1, b[6].toInt())   // hour  -> 1 PM in 12-hour form
        assertEquals(35, b[7].toInt())  // minute
        assertEquals(19, b[8].toInt())  // second
        assertEquals(1, b[9].toInt())   // AM/PM -> PM
    }

    @Test
    fun `date is sent too`() {
        val b = ping(at(2026, 10, 13, 9, 5, 3))
        assertEquals(13, b[12].toInt())  // day of month
        assertEquals(10, b[13].toInt())  // month, 1-based
        assertEquals(26, b[14].toInt())  // year mod 100
    }

    @Test
    fun `hour is 12-hour, so an afternoon time is never 13`() {
        // The whole point: HOUR_OF_DAY would write 13 into a field the cluster
        // reads as 12-hour, so the dash would show 13:35 as 1:35 AM.
        val b = ping(at(2026, 10, 13, 13, 35, 19))
        assertEquals(1, b[6].toInt())
        assertTrue("hour must fit 1..12", b[6].toInt() in 1..12)
    }

    @Test
    fun `midnight is 12, not zero`() {
        // Upstream rewrites a literal "0" to "12" so midnight reads 12.
        val midnight = ping(at(2026, 10, 13, 0, 0, 0))
        assertEquals(12, midnight[6].toInt())
        assertEquals(0, midnight[9].toInt())  // AM
    }

    @Test
    fun `noon is 12 PM`() {
        val noon = ping(at(2026, 10, 13, 12, 0, 0))
        assertEquals(12, noon[6].toInt())
        assertEquals(1, noon[9].toInt())  // PM
    }

    @Test
    fun `every hour of the day maps into 1 to 12`() {
        for (h in 0..23) {
            val b = ping(at(2026, 10, 13, h, 30, 0))
            assertTrue("hour $h became ${b[6].toInt()}", b[6].toInt() in 1..12)
            val expectedPm = if (h >= 12) 1 else 0
            assertEquals("AM/PM wrong at $h", expectedPm, b[9].toInt())
        }
    }

    @Test
    fun `byte 18 is zero, not a checksum`() {
        // The enriched layout from the third-party report wrote a checksum here.
        // The official builder does not, so a checksum is likely what the cluster
        // reads as a corrupt keepalive — which would look like random drops.
        val b = ping(at(2026, 10, 13, 13, 35, 19))
        assertEquals(0, b[18].toInt())
    }

    @Test
    fun `signal and battery pack into one byte as two hex digits`() {
        // Upstream builds the string "34" then hex2decimal -> 0x34.
        val b = Keepalive.build(
            DashState(0, signalBars = 3, batteryBars = 4),
            calendar = at(2026, 10, 13, 13, 35, 19),
        )
        assertEquals(0x34, b[2].toInt() and 0xFF)
    }

    @Test
    fun `packing is independent of the clock`() {
        val a = ping(at(2026, 10, 13, 13, 35, 19), DashState(0, signalBars = 5, batteryBars = 5))
        val b = ping(at(2026, 10, 13, 1, 2, 3), DashState(0, signalBars = 5, batteryBars = 5))
        assertEquals(a[2].toInt(), b[2].toInt())
        assertTrue("clock must differ", a[7].toInt() != b[7].toInt())
    }

    @Test
    fun `the old layout is not silently still in use`() {
        // Pinned so the bug cannot come back as a quiet revert: every one of
        // these was a zero byte that carried real information.
        val b = ping(at(2026, 10, 13, 13, 35, 19))
        assertTrue("minute must not be zero", b[7].toInt() != 0)
        assertTrue("second must not be zero", b[8].toInt() != 0)
        assertTrue("AM/PM must not be zero for PM", b[9].toInt() != 0)
        assertTrue("day must not be zero", b[12].toInt() != 0)
    }
}
