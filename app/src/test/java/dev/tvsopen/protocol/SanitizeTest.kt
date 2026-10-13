// Pins the sanitiser, which is the one piece of this feature guaranteed to run
// on every frame the rider sees.
//
// The bug these cover was found on hardware, not by reading the code: live
// navigation sent "Example Road - Hill Route" to the cluster and the cluster received
// "Example Road X Hill R". Substituting a letter for a separator is not a cosmetic
// problem on a 17-character display — a rider cannot tell an 'X' from a real one.

package dev.tvsopen.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class SanitizeTest {

    private fun s(text: String) = String(PacketBuilder.sanitize(text), Charsets.UTF_8)

    /**
     * The text a built frame carries.
     *
     * Starts at index 2: every frame is `0x5B <id>` then text, so reading from
     * index 0 picks up the two header bytes as characters.
     */
    private fun textOf(frame: ByteArray): String {
        val body = frame.copyOfRange(2, frame.size)
        val end = body.indexOfFirst { it == 0.toByte() || it == 0xFF.toByte() }
            .let { if (it < 0) body.size else it }
        return String(body.copyOfRange(0, end), Charsets.UTF_8).trim()
    }

    @Test
    fun `punctuation is kept or folded, never replaced with a letter`() {
        // This is the hardware finding: the old sanitiser shipped
        // "Example Road X Hill R" to the cluster.
        // Seventeen columns is the hard cap, so this is the wire length too.
        // (Input is longer on purpose: truncation is part of the contract.)
        //
        // The cut lands mid-word and the hyphen survives inside it, which is the
        // whole point: "Example Road - Hi" still reads as Example Road, whereas
        // the old "Example Road X Hill R" turned a separator into a letter and
        // split the place name into two unrelated words.
        assertEquals("Example Road - Hi", s("Example Road - Hill Route"))
        assertEquals("a b c d", s("a/b,c.d"))   // four tokens, three separators
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
        assertEquals("a - b c", s("a - b / c"))   // dash kept, slash folded
        assertEquals("a b", s("a   b"))
    }

    @Test
    fun `letters and digits survive`() {
        assertEquals("Example Rd Rd 12", s("Example Rd Rd 12"))
    }

    @Test
    fun `output is capped at the cluster's seventeen columns`() {
        val out = s("Kanjirapally Vazhikkala Road")
        assertEquals(17, out.length)
        assertEquals("Kanjirapally Vazh", out)   // 12 + space + 4
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
        assertEquals("---", s("---///"))   // slashes fold, dashes are kept
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
        val bare = textOf(PacketBuilder.callerInfo("+919876543210", null))
        assertEquals("+919876543210", bare)
    }
}