// Pins the sanitiser, which is the one piece of this feature guaranteed to run
// on every frame the rider sees.
//
// The bug these cover was found on hardware, not by reading the code: live
// navigation sent "Meppayyur - Avala Rd" to the cluster and the cluster received
// "Meppayyur X Avalla". Substituting a letter for a separator is not a cosmetic
// problem on a 17-character display — a rider cannot tell an 'X' from a real one.

package dev.tvsopen.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class SanitizeTest {

    private fun s(text: String) = String(PacketBuilder.sanitize(text), Charsets.UTF_8)

    @Test
    fun `punctuation is kept or folded, never replaced with a letter`() {
        // This is the hardware finding: the old sanitiser shipped
        // "Meppayyur X Avalla" to the cluster.
        assertEquals("Meppayyur - Avala Rd", s("Meppayyur - Avala Rd"))
        assertEquals("a b c", s("a/b,c.d"))
    }

    @Test
    fun `a hyphen keeps two tokens`() {
        // Folding it would give "NH 44", a different road name.
        assertEquals("NH-44", s("NH-44"))
    }

    @Test
    fun `a phone number keeps its plus`() {
        // Dropping '+' turns an international number into a local one, which
        // is precisely wrong for caller ID.
        assertEquals("+919876543210", s("+919876543210"))
    }

    @Test
    fun `separators do not pile up`() {
        assertEquals("a b c", s("a - b / c"))
        assertEquals("a b", s("a   b"))
    }

    @Test
    fun `letters and digits survive`() {
        assertEquals("Meppayyur Rd 12", s("Meppayyur Rd 12"))
    }

    @Test
    fun `output is capped at the cluster's seventeen columns`() {
        val out = s("Kanjirapally Vazhikkala Road")
        assertEquals(17, out.length)
        assertEquals("Kanjirapally Vazhik", out)
    }

    @Test
    fun `non-latin text yields no garbage bytes`() {
        // The cluster cannot render Indic glyphs; it must get spaces, not mojibake.
        val out = s("NH44 தென் கிழக்கு")
        assertEquals(out, out.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == ' ' })
        assertEquals("NH44", out.trim().take(5))
    }

    @Test
    fun `a string that is entirely separators collapses to empty`() {
        assertEquals("", s("---///"))
    }

    @Test
    fun `text rows and caller info are built and terminated`() {
        val row = PacketBuilder.textRow(OutboundId.TEXT_ROW1, "Hello")
        assertEquals(20, row.size)
        assertEquals(0x5B.toByte(), row[0])
        assertEquals(0x4C.toByte(), row[1])
        assertEquals(0xFF.toByte(), row[19])

        val caller = PacketBuilder.callerInfo("+919876543210", "Aarav")
        assertEquals(20, caller.size)
        assertEquals(0x43.toByte(), caller[1])          // OutboundId.CALLER
        assertEquals(0xFF.toByte(), caller[19])
    }

    @Test
    fun `caller info falls back to the number when there is no name`() {
        val bare = String(PacketBuilder.callerInfo("+919876543210", null), Charsets.UTF_8)
        assertEquals(true, bare.startsWith("+919876543210"))
    }
}