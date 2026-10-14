// The rider-name frame, `0x5B 0x52`.
//
// The ID was unresolved for most of this project — two guessed values (0x56,
// then 0x5A) were written and reverted rather than shipped — and is now
// established three independent ways:
//
// 1. v2.13.0 `BluetoothUtil.createRiderNameArray` inlines `{91, 82}` in its
//    bytecode (const/16 #91 / aput + const/16 #82 / aput, register-tracked).
// 2. v8.14.1's equivalent builder emits a fill-array-data payload `[5B 52]`.
// 3. `NtorqMobileToCluster.sendRiderName` calls
//    `createMobileToClusterArray(name, DATA_ID_52)` with `DATA_ID_52 = 82`
//    stated symbolically in `BluetoothConstants`.
//
// These pin the wire format, not the guess.

package dev.tvsopen.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RiderNameFrameTest {

    @Test
    fun `rider name is a 20-byte 5B 52 frame`() {
        val f = PacketBuilder.riderName("Aarav")
        assertEquals(20, f.size)
        assertEquals(0x5B, f[0].toInt() and 0xFF)
        assertEquals(0x52, f[1].toInt() and 0xFF)
        assertEquals(0xFF, f[19].toInt() and 0xFF)
        assertEquals("Aarav", String(f.copyOfRange(2, 2 + 5), Charsets.UTF_8))
        // Zero padding between the name and the terminator.
        assertTrue(f.copyOfRange(7, 19).all { it == 0.toByte() })
    }

    @Test
    fun `rider name matches the generic text-row layout`() {
        // Same builder, same shape — the greeting is one more 0x5B text row,
        // which is why the ID is the only thing that had to be verified.
        assertArrayEquals(
            PacketBuilder.textRow(OutboundId.RIDER_NAME, "Aarav"),
            PacketBuilder.riderName("Aarav"),
        )
    }

    @Test
    fun `a long name is cut at seventeen characters`() {
        val f = PacketBuilder.riderName("Example Rider Name Long")
        assertEquals("Example Rider Nam", String(f.copyOfRange(2, 19), Charsets.UTF_8).trim())
        assertEquals(0xFF, f[19].toInt() and 0xFF)
    }

    @Test
    fun `a blank name is the caller's problem, not the frame's`() {
        // The service never sends a blank name — the cluster shows HELLO RIDER
        // by itself — so the builder just sanitizes what it is given.
        val f = PacketBuilder.riderName("   ")
        assertEquals(20, f.size)
        assertEquals(0x52, f[1].toInt() and 0xFF)
    }

    @Test
    fun `driver exposes the greeting when the cluster renders it`() {
        val f = NtorqDriver().buildRiderName("Aarav")
        assertEquals(0x52, f!![1].toInt() and 0xFF)
    }
}
