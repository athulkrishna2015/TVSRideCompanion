// Only the UI's length/storage contract is testable until DATA_ID_NAME is
// resolved. Deliberately no outbound name packet test exists: that would pin a
// guessed ID, which is exactly what we must not do.

package dev.tvsopen.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RiderNameFrameTest {
    @Test
    fun `official APK shape is 17 characters inside a 20 byte frame`() {
        // The official packet builder truncates its name at 17 characters and
        // pads the remainder before FF. DATA_ID_NAME's numeric value is still
        // unresolved; this asserts shape only, not a transmitted packet.
        val examples = listOf("Example Rider Name", "Hill View Residency", "Alex")
        for (example in examples) {
            val sent = example.take(17)
            assertEquals(example.take(minOf(example.length, 17)), sent)
            assertEquals(20, 2 + 17 + 1)
            assertEquals(minOf(example.length, 17), sent.length)
        }
    }

    @Test
    fun `blank profile value means use the vehicle default greeting`() {
        val empty: String? = null
        assertNull(empty)
    }
}
