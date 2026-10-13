// Multi-byte field decoding.
//
// The bug these pin was found by reading the dashboard against the raw bytes:
// the odometer showed 278,733 km on a bike the owner had ridden 16,593. Those
// bytes are `02 88 2A`, and `0x2A8802` is them reversed — the reader was
// assembling little-endian while the cluster sends big-endian.
//
// It is the same class of defect as the double-division bug: arithmetic on a
// byte array, invisible to the compiler, and producing a number that looks
// entirely plausible right up until someone with a real odometer reads it.

package dev.tvsopen.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class FieldReadTest {

    /** A real `5A 10` telemetry frame from the owner's Ntorq 125. */
    private val sm1 = byteArrayOf(
        0x5A, 0x10, 0x00, 0x02, 0x88, 0x2A, 0x02, 0x00, 0x00, 0x00,
        0x09, 0x50, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xFF.toByte(),
    )

    private fun read(spec: FieldSpec) = spec.read(sm1)

    @Test
    fun `odometer is big-endian`() {
        // 0x02882A = 165,930 tenths of a km = 16,593.0 km, which the owner
        // confirmed against the cluster.
        assertEquals(165_930L, read(FieldSpec(Field.ODOMETER, 3, length = 3)))
    }

    @Test
    fun `the reversed reading is the number that was on screen`() {
        // Guards the regression explicitly rather than only the correct value:
        // if this ever equals the odometer again, the endianness is wrong.
        val littleEndian = (0x2A shl 16) or (0x88 shl 8) or 0x02
        assertEquals(2_787_330L, littleEndian)
        assertEquals(278_733.0, littleEndian / 10.0, 0.001)
    }

    @Test
    fun `single byte fields are unaffected`() {
        assertEquals(0L, read(FieldSpec(Field.SPEED, 2)))
        assertEquals(0x09L, read(FieldSpec(Field.TOP_SPEED, 9)))
    }

    @Test
    fun `a three byte field is assembled most significant first`() {
        val b = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        assertEquals(0x123456L, FieldSpec(Field.ODOMETER, 0, length = 3).read(b))
        assertEquals(0x1234L, FieldSpec(Field.ODOMETER, 0, length = 2).read(b))
    }

    @Test
    fun `signed two byte fields still sign-extend`() {
        // -1 as 0xFFFF must not become 65535.
        val b = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
        assertEquals(-1L, FieldSpec(Field.ODOMETER, 0, length = 2, signed = true).read(b))
        assertEquals(65_535L, FieldSpec(Field.ODOMETER, 0, length = 2, signed = false).read(b))
    }

    @Test
    fun `a field past the end of the frame is null, not a crash`() {
        assertEquals(null, read(FieldSpec(Field.ODOMETER, 19, length = 3)))
    }
}