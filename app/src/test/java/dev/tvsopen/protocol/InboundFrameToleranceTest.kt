// Tolerence tests for inbound frames we decode but do not fully understand.
// The contract is simple: no crash and no invented telemetry ("—", not 0).
package dev.tvsopen.protocol

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class InboundFrameToleranceTest {

    private fun frame(vararg bytes: Int): ByteArray =
        ByteArray(20) { i -> bytes.getOrNull(i)?.toByte() ?: 0 }

    @Test
    fun `all zero connect frame is rejected without inventing fields`() {
        // Start byte 0 is not a driver-approved lead, so the driver refuses it.
        val d = NtorqDriver().decode(ByteArray(20))
        assertNull(d)
    }

    @Test
    fun `address frame falls through to framing only`() {
        val raw = frame(0x5B, 0x42, 0x00, 0x02, 0x1A, 0x7F)
        val d = NtorqDriver().decode(raw)
        assertNotNull(d)
        assertNull(d!!.field(Field.SPEED))
    }

    @Test
    fun `unknown data id does not crash`() {
        val d = NtorqDriver().decode(frame(0x5A, 0x19, 0x01, 0x02))
        assertNotNull(d)
    }

    @Test
    fun `short frame is rejected`() {
        assertNull(NtorqDriver().decode(byteArrayOf(0x5A, 0x10)))
    }
}
