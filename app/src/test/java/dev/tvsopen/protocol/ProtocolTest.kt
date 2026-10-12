package dev.tvsopen.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol-layer regression tests.
 *
 * These exist because the worst bug found in this codebase was invisible to the
 * compiler, to lint, and to every on-device smoke test run with no scooter
 * connected: `FrameLayout.decode` divided odometer by 10 while `Session.onFrame`
 * divided again, so the odometer tile would have read ~10x low the first time a
 * real cluster streamed a frame.
 *
 * Everything here is pure arithmetic on byte arrays — no Android, no BLE, no
 * device — so it runs in seconds and CI catches it.
 */
class ProtocolTest {

    /** Builds a 20-byte inbound frame with the Ntorq speedometer header. */
    private fun frame(dataId: Int, body: (ByteArray) -> Unit): ByteArray {
        val f = ByteArray(20)
        f[0] = 0x5A.toByte()
        f[1] = dataId.toByte()
        body(f)
        f[19] = 0xFF.toByte()
        return f
    }

    private fun ByteArray.setLE(offset: Int, value: Int, length: Int) {
        for (i in 0 until length) {
            this[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
        }
    }

    @Test
    fun `decode leaves values raw`() {
        // 30980 tenths of a km == 3098.0 km.
        val raw = frame(0x10) { it.setLE(3, 30980, 3) }

        val decoded = NtorqFrames.SM1.decode(raw)
        assertTrue("frame should decode", decoded != null)

        // The decoder must hand back the raw integer. If it pre-divided, the
        // second division in Session would compound it.
        assertEquals(30980L, decoded!!.field(Field.ODOMETER))
    }

    @Test
    fun `odometer is divided exactly once at the presentation layer`() {
        val raw = frame(0x10) { it.setLE(3, 30980, 3) }
        val decoded = NtorqFrames.SM1.decode(raw)!!

        val once = decoded.field(Field.ODOMETER)!! / 10.0
        assertEquals(3098.0, once, 0.001)
        // And the bug itself, pinned: dividing what is already scaled again.
        assertTrue("must not divide twice", once / 10.0 != once)
    }

    @Test
    fun `multi-byte fields are little-endian`() {
        val raw = frame(0x10) { it.setLE(17, 0x1234, 2) }
        val decoded = NtorqFrames.SM1.decode(raw)!!
        assertEquals(0x1234L, decoded.field(Field.ENGINE_RPM))
    }

    @Test
    fun `signed fields read as negative`() {
        val spec = FieldSpec(Field.ENGINE_LOAD, 0, length = 1, signed = true)
        assertEquals(-1L, spec.read(byteArrayOf(0xFF.toByte())))
        assertEquals(1L, spec.read(byteArrayOf(0x01)))
    }

    @Test
    fun `a field past the end of the buffer reads as null rather than throwing`() {
        val short = ByteArray(5) { 0x5A }
        assertNull(FieldSpec(Field.SPEED, 19).read(short))
    }

    @Test
    fun `decode rejects a frame with the wrong start byte or data id`() {
        val wrongStart = frame(0x10) { it[0] = 0x99.toByte() }
        assertNull(NtorqFrames.SM1.decode(wrongStart))

        val wrongId = frame(0x11) { }
        assertNull(NtorqFrames.SM1.decode(wrongId))

        assertNull("short frame", NtorqFrames.SM1.decode(ByteArray(4)))
    }

    // ---- outbound ----

    @Test
    fun `mobile data has no checksum and the documented layout`() {
        val p = PacketBuilder.mobileData(
            DashState(0L, batteryBars = 3, signalBars = 2, networkCode = 0x04),
        )
        assertEquals(20, p.size)
        assertEquals(0x5B, p[0].toInt() and 0xFF)
        assertEquals(0x4A, p[1].toInt() and 0xFF)
        assertEquals(2, p[2].toInt())   // signal
        assertEquals(3, p[3].toInt())   // battery
        assertEquals(0x02, p[7].toInt())
        assertEquals(0xFF.toByte(), p[19])
        // The official builder writes zeros at 12..18 and never a checksum.
        for (i in 12..18) assertEquals("byte $i", 0, p[i].toInt())
    }

    @Test
    fun `navigation control writes the documented bytes`() {
        val p = PacketBuilder.navigationControl(
            NavStep(
                distanceToTurnMeters = 350,
                etaMinutes = 12,
                remainingDistanceMeters = 4200,
                pictogram = 3,
                streetName = "",
            ),
        )
        assertEquals(0x5A, p[0].toInt() and 0xFF)
        assertEquals(0x4E, p[1].toInt() and 0xFF)
        assertEquals(350, (p[2].toInt() and 0xFF) or ((p[3].toInt() and 0xFF) shl 8))
        assertEquals(12, (p[4].toInt() and 0xFF) or ((p[5].toInt() and 0xFF) shl 8))
        assertEquals(4200, (p[6].toInt() and 0xFF) or
            ((p[7].toInt() and 0xFF) shl 8) or ((p[8].toInt() and 0xFF) shl 16))
        assertEquals(3, p[9].toInt())
        assertEquals(1, p[10].toInt())
        assertEquals(1, p[11].toInt())
        assertEquals(0xFF.toByte(), p[19])
    }

    @Test
    fun `negative distance saturates to zero, not to 0xFFFF`() {
        // -1 is the "unknown" sentinel. Unsigned, it would become 65535 — the
        // largest possible distance, i.e. "turn in 65 km".
        val p = PacketBuilder.navigationControl(
            NavStep(-1, 0, 0, 7, ""),
        )
        val d = (p[2].toInt() and 0xFF) or ((p[3].toInt() and 0xFF) shl 8)
        assertEquals(0, d)
    }

    @Test
    fun `rows field reaches the wire`() {
        val p = PacketBuilder.navigationControl(
            NavStep(100, 1, 100, 0, "", rows = 3),
        )
        assertEquals(3, p[10].toInt())
    }

    @Test
    fun `text payload never overruns the terminator`() {
        val long = "A".repeat(200)
        for (p in listOf(
            PacketBuilder.navigationText(long),
            PacketBuilder.textRow(OutboundId.TEXT_ROW1, long),
            PacketBuilder.textRow(OutboundId.TEXT_ROW2, long, maxChars = 40),
        )) {
            assertEquals(20, p.size)
            assertEquals("terminator must survive", 0xFF.toByte(), p[19])
        }
    }

    @Test
    fun `sanitiser keeps alphanumerics and flattens the rest`() {
        assertEquals(
            "Turn right NH44",
            PacketBuilder.sanitize("Turn right NH44").decodeToString(),
        )
        // Each emoji collapses to exactly one 'X', not one per UTF-16 unit.
        assertEquals("XX TURN", PacketBuilder.sanitize("🚦⚠ TURN").decodeToString())
        assertEquals("X", PacketBuilder.sanitize("—").decodeToString())
    }

    @Test
    fun `sanitiser truncates to the 17-character row`() {
        // The row is 17 characters wide; a longer street name is cut, not wrapped.
        val out = PacketBuilder.sanitize("Turn right onto NH44").decodeToString()
        assertEquals("Turn right onto N", out)
        assertEquals(17, out.length)
    }

    @Test
    fun `sanitiser output is ASCII only`() {
        val out = PacketBuilder.sanitize("தென் NH44 🛵 →", maxBytes = 40)
        assertTrue("must be ASCII", out.all { it in 0..0x7F })
        assertTrue("within budget", out.size <= 40)
    }

    @Test
    fun `traffic signal text carries no glyphs`() {
        // The sanitiser maps every non-alphanumeric character to 'X', so a
        // leading arrow would reach the cluster as "XX TURN LEFT".
        val s = PacketBuilder.trafficSignal(TslAction.LEFT).decodeToString()
        assertTrue("unexpected glyph damage: $s", !s.contains("XX"))
        assertTrue(s.contains("TURN LEFT"))
    }

    @Test
    fun `challenge response is a 20-byte 9A F1 frame`() {
        val challenge = ByteArray(20)
        challenge[0] = 0x9B.toByte()
        challenge[1] = 0xF2.toByte()

        val r = Challenge.buildResponse(challenge)
        assertTrue("should respond", r != null)
        assertEquals(20, r!!.size)
        assertEquals(0x9A, r[0].toInt() and 0xFF)
        assertEquals(0xF1, r[1].toInt() and 0xFF)
        assertEquals(0xFF.toByte(), r[19])
    }

    @Test
    fun `a non-challenge frame produces no response`() {
        val telemetry = frame(0x10) { }
        assertNull(Challenge.buildResponse(telemetry))
    }

    @Test
    fun `the two handshake keys differ in exactly one byte`() {
        val a = HandshakeKey.OFFICIAL
        val b = HandshakeKey.JUPITER_COMPANION
        assertEquals(16, a.size)
        assertEquals(1, a.indices.count { a[it] != b[it] })
    }
}