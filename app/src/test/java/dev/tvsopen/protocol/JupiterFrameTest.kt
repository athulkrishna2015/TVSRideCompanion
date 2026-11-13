package dev.tvsopen.protocol

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frame parity tests for the Jupiter tables added from the Scoot protocol spec.
 *
 * The expected byte values are derived from that spec's published builders
 * (sim/src/engine/frames.js in sudo-Mystic/Scoot-releases) rather than from
 * this implementation, so a transcription mistake in either direction fails
 * here instead of on a cluster.
 */
class JupiterFrameTest {

    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int):
        Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(year, month, day, hour, minute, second)
    }

    private fun frame(vararg bytes: Int): ByteArray =
        ByteArray(20) { i -> if (i < bytes.size) bytes[i].toByte() else 0 }

    // ---- inbound ----------------------------------------------------------

    @Test
    fun `0x10 decodes speed, odometer, fuel, rpm and the call switch big-endian`() {
        val raw = frame(
            0x5A, 0x10,
            45, 0x02, 0x88, 0x2A, 0x35, 30, 1, 60, 25, 0x81, 128, 0,
            0x00, 0x00, 0x7B, 0x0B, 0xB8, 0xFF,
        )
        val d = JupiterDriver(VehicleModel.JUPITER_125).decode(raw)!!
        assertEquals(45L, d.field(Field.SPEED))
        assertEquals(165_930L, d.field(Field.ODOMETER)) // 16,593.0 km
        assertEquals(0x35L, d.field(Field.FUEL_LEVEL))
        assertEquals(3000L, d.field(Field.ENGINE_RPM))
        assertEquals(128L, d.field(Field.SWITCH_STATUS)) // ignition on
        assertEquals(123L, d.field(Field.TRIP_F_METER))
    }

    @Test
    fun `0x18 battery voltage is raw byte 11 and stays unscaled`() {
        val raw = frame(0x5A, 0x18, 0, 0, 0, 0, 0, 0, 0, 0, 0, 121, 0, 0, 0, 0, 0, 0, 0, 0xFF)
        val d = JupiterDriver(VehicleModel.JUPITER_125).decode(raw)!!
        assertEquals(121L, d.field(Field.BATTERY_VOLTAGE)) // UI divides by 10
    }

    @Test
    fun `0x19 distance to empty is big-endian`() {
        val raw = frame(0x5A, 0x19, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01, 0x2C, 0, 0, 0, 0, 0, 0, 0xFF)
        val d = JupiterDriver(VehicleModel.JUPITER_125).decode(raw)!!
        assertEquals(300L, d.field(Field.DISTANCE_TO_EMPTY))
    }

    @Test
    fun `0x6B music command is at byte 2`() {
        val raw = frame(0x5A, 0x6B, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF)
        val d = JupiterDriver(VehicleModel.JUPITER_125).decode(raw)!!
        assertEquals(3L, d.field(Field.MUSIC_COMMAND))
    }

    @Test
    fun `fuel nibble is model-gated 125 vs 110`() {
        assertTrue(JupiterDriver(VehicleModel.JUPITER_125).capabilities.fuelLowNibble)
        assertTrue(!JupiterDriver(VehicleModel.JUPITER_110).capabilities.fuelLowNibble)
    }

    @Test
    fun `jupiter accepts 0x5A 0x9B and 0x5B inbound`() {
        val d = JupiterDriver(VehicleModel.JUPITER_125)
        assertTrue(d.acceptsStartId(0x5A))
        assertTrue(d.acceptsStartId(0x9B))
        assertTrue(d.acceptsStartId(0x5B))
    }

    // ---- outbound ---------------------------------------------------------

    @Test
    fun `navigation control is big-endian`() {
        val p = PacketBuilder.navigationControl(NavStep(350, 12, 4200, 3, ""))
        assertEquals(0x01, p[2].toInt() and 0xFF)
        assertEquals(0x5E, p[3].toInt() and 0xFF)
        assertEquals(0x00, p[4].toInt() and 0xFF)
        assertEquals(0x0C, p[5].toInt() and 0xFF)
        assertEquals(0x00, p[6].toInt() and 0xFF)
        assertEquals(0x10, p[7].toInt() and 0xFF)
        assertEquals(0x68, p[8].toInt() and 0xFF)
    }

    @Test
    fun `pictogram is 21 bytes with the FF 00 tail`() {
        val p = ClusterFrames.pictogram("TURN LEFT")
        assertEquals(21, p.size)
        assertEquals(0x5B, p[0].toInt() and 0xFF)
        assertEquals(0x50, p[1].toInt() and 0xFF)
        assertEquals("TURN LEFT", String(p, 2, 9, Charsets.UTF_8))
        assertEquals(0xFF.toByte(), p[19])
        assertEquals(0x00.toByte(), p[20])
    }

    @Test
    fun `caller sequence is number, country code, name`() {
        val frames = ClusterFrames.callerFrames("9876543210", "91", "Example Rider")
        assertEquals(3, frames.size)
        assertEquals(listOf(1, 3, 2), frames.map { it[2].toInt() and 0xFF })
        assertEquals(21, frames[0].size)
        assertEquals(0x43, frames[0][1].toInt() and 0xFF)
        assertEquals("9876543210", String(frames[0], 3, 10, Charsets.UTF_8))
        assertEquals(0xFF.toByte(), frames[0][20])
    }

    @Test
    fun `caller sequence omits empty name but keeps number and country`() {
        val frames = ClusterFrames.callerFrames("9876543210", null, "")
        assertEquals(1, frames.size)
        assertEquals(1, frames[0][2].toInt() and 0xFF)
    }

    @Test
    fun `media metadata puts FF right after the text and zero at 19`() {
        val p = ClusterFrames.mediaMeta(0x61, "Song")
        assertEquals(20, p.size)
        assertEquals("Song", String(p, 2, 4, Charsets.UTF_8))
        assertEquals(0xFF.toByte(), p[6])
        assertEquals(0x00.toByte(), p[19])
    }

    @Test
    fun `media metadata never exceeds 16 text bytes`() {
        val p = ClusterFrames.mediaMeta(0x62, "A".repeat(40))
        assertEquals(0xFF.toByte(), p[18])
        assertEquals(0x00.toByte(), p[19])
    }

    @Test
    fun `brightness level 1 is wire 1 and levels 2-5 are doubled`() {
        assertEquals(1, ClusterFrames.vehicleControl(1, utc(2026, 9, 13, 0, 0, 0))[2].toInt())
        assertEquals(4, ClusterFrames.vehicleControl(2, utc(2026, 9, 13, 0, 0, 0))[2].toInt())
        assertEquals(10, ClusterFrames.vehicleControl(5, utc(2026, 9, 13, 0, 0, 0))[2].toInt())
    }

    @Test
    fun `brightness frame carries 0x5A lead, zero-based month and the date`() {
        val p = ClusterFrames.vehicleControl(3, utc(2026, 9, 13, 0, 0, 0))
        assertEquals(0x5A, p[0].toInt() and 0xFF)
        assertEquals(0xF1, p[1].toInt() and 0xFF)
        assertEquals(13, p[9].toInt())   // day
        assertEquals(9, p[11].toInt())   // October, zero-based
        assertEquals(2026 and 0xFF, p[13].toInt() and 0xFF)
        assertEquals(0xFF.toByte(), p[19])
    }

    @Test
    fun `user id frame pads with FF not zero`() {
        val p = ClusterFrames.userId(5)
        assertEquals(20, p.size)
        assertEquals(0x5B, p[0].toInt() and 0xFF)
        assertEquals(0x22, p[1].toInt() and 0xFF)
        // BigInteger.toByteArray() is [05]; a value two bytes or shorter gets
        // one 0x00 sign byte prefixed.
        assertEquals(0x00, p[2].toInt())
        assertEquals(0x05, p[3].toInt())
        for (i in 4..18) assertEquals("pad at $i", 0xFF, p[i].toInt() and 0xFF)
        assertEquals(0xFF.toByte(), p[19])
    }

    @Test
    fun `user id frame keeps a three byte id unprefixed`() {
        val p = ClusterFrames.userId(123456)
        assertEquals(0x01, p[2].toInt() and 0xFF)
        assertEquals(0xE2, p[3].toInt() and 0xFF)
        assertEquals(0x40, p[4].toInt() and 0xFF)
        assertEquals(0xFF.toByte(), p[5])
    }

    @Test
    fun `play pause state puts the hour at 3 and the state at 7`() {
        val p = ClusterFrames.playPauseState(0x11, utc(2026, 9, 13, 19, 0, 0))
        assertEquals(0x60, p[1].toInt() and 0xFF)
        assertEquals(19, p[3].toInt())
        assertEquals(0x11, p[7].toInt())
        assertEquals(0xFF.toByte(), p[19])
    }

    @Test
    fun `sms frame is 20 bytes and zero padded`() {
        val p = ClusterFrames.sms("HELLO")
        assertEquals(20, p.size)
        assertEquals(0x53, p[1].toInt() and 0xFF)
        assertEquals("HELLO", String(p, 2, 5, Charsets.UTF_8))
        assertEquals(0x00.toByte(), p[7])
        assertEquals(0xFF.toByte(), p[19])
    }

    @Test
    fun `calibration uses the 0x73 layout with a little-endian value`() {
        val p = ClusterFrames.calibration(
            ClusterFrames.CalibrationCommand.FOLLOW_ME_HEADLAMP_TIMER,
            value = 0x1234,
        )
        assertEquals(0x5B, p[0].toInt() and 0xFF)
        assertEquals(0x73, p[1].toInt() and 0xFF)
        assertEquals(0x04, p[4].toInt())
        assertEquals(0x34, p[5].toInt() and 0xFF) // low byte first
        assertEquals(0x12, p[6].toInt() and 0xFF)
        assertEquals(0, p[7].toInt())
        assertEquals(0, p[8].toInt())
        assertEquals(ClusterFrames.calibrationChecksum(p), p[9])
        assertEquals(0xFF.toByte(), p[19])
    }

    @Test
    fun `calibration checksum is 256 minus the sum of bytes 2 through 8`() {
        // One byte set to 1: (256 - 1) and 0xFF = 0xFF.
        val b = ByteArray(20).also { it[2] = 1 }
        assertEquals(0xFF, ClusterFrames.calibrationChecksum(b).toInt() and 0xFF)
    }

    @Test
    fun `keepalive carries the find-me flag at byte 17`() {
        val on = Keepalive.build(DashState(0L, findMyScooter = true))
        val off = Keepalive.build(DashState(0L))
        assertEquals(1, on[17].toInt())
        assertEquals(0, off[17].toInt())
    }

    @Test
    fun `jupiter ping sets overspeed and ambient temperature`() {
        val state = DashState(0L, temperatureC = 31)
        val p = JupiterDriver(VehicleModel.JUPITER_125).buildPing(state)!!
        assertEquals(120, p[3].toInt())
        assertEquals(71, p[4].toInt()) // 31 + 40
    }

    @Test
    fun `jupiter ping carries the section 2 checksum, ntorq still does not`() {
        val jupiter = JupiterDriver(VehicleModel.JUPITER_125).buildPing(DashState(0L))!!
        val expected = (255 - (jupiter.take(18).sumOf { it.toInt() and 0xFF } % 256)) and 0xFF
        assertEquals(expected, jupiter[18].toInt() and 0xFF)

        val ntorq = NtorqDriver().buildPing(DashState(0L))!!
        assertEquals(0, ntorq[18].toInt())
    }

    @Test
    fun `jupiter has no second text row because 0x50 is the pictogram`() {
        val d = JupiterDriver(VehicleModel.JUPITER_125)
        assertNull(d.buildNavTextSecondary(NavStep(10, 1, 10, 7, "Example Road")))
        assertEquals(0x50, d.buildPictogram("LEFT")!![1].toInt() and 0xFF)
    }

    @Test
    fun `jupiter never answers the keyless challenge`() {
        val d = JupiterDriver(VehicleModel.JUPITER_125)
        assertNull(d.buildChallengeResponse(ByteArray(16) { it.toByte() }))
    }
}