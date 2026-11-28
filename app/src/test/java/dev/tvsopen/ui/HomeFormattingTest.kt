// Pure formatting tests for the UI's honesty contract: "—"/stale labels must
// not silently become a plausible number or a frozen "live".
package dev.tvsopen.ui

import dev.tvsopen.session.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeFormattingTest {

    @Test
    fun `relative time is non-negative and bucketed`() {
        assertEquals("0m", relativeTime(0))
        assertEquals("0m", relativeTime(-5_000L)) // clock skew
        assertEquals("59m", relativeTime(59 * 60_000L))
        assertEquals("1h", relativeTime(60 * 60_000L))
        assertEquals("23h", relativeTime(23 * 60 * 60_000L))
        assertEquals("1d", relativeTime(24 * 60 * 60_000L))
    }

    @Test
    fun `freshness distinguishes unknown, live, old and stale`() {
        assertEquals("No telemetry received yet", freshnessLabel(null))
        assertEquals("Telemetry live", freshnessLabel(0))
        assertEquals("Telemetry live", freshnessLabel(2_999L))
        assertEquals("Telemetry 3s old", freshnessLabel(3_000L))
        assertEquals("Telemetry stale — ${Telemetry.STALE_AFTER_MS / 1000}s since last frame",
            freshnessLabel(Telemetry.STALE_AFTER_MS))
        assertEquals("Telemetry live", freshnessLabel(-1_000L))
    }
}
