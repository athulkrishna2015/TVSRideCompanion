// Pure tests for engine-temperature alerts. No device, no clock flakiness.
package dev.tvsopen.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThermalSessionTest {

    private fun feed(session: ThermalSession, temps: List<Int>, startMs: Long = 0L) =
        temps.mapIndexed { i, t -> session.onReading(t, startMs + i * 5_000L) }

    @Test
    fun `warm up alert fires once only when the ride started cold`() {
        val s = ThermalSession(warmUpC = 60, hotC = 100, nowMs = { 0L })
        val events = feed(s, listOf(40, 55, 60, 61, 62))
        assertEquals(
            listOf(emptyList<String>(), emptyList(), listOf("WARMED UP"), emptyList(), emptyList()),
            events,
        )
    }

    @Test
    fun `warm start does not announce warm up`() {
        val s = ThermalSession(warmUpC = 60, hotC = 100, nowMs = { 0L })
        assertEquals(listOf(emptyList<String>(), emptyList()), feed(s, listOf(75, 76)))
    }

    @Test
    fun `hot alert repeats only after cooldown below hysteresis`() {
        val s = ThermalSession(warmUpC = 60, hotC = 100, nowMs = { 0L })
        val temps = MutableList(70) { 100 } + listOf(96, 97, 100, 100)
        val events = feed(s, temps)
        val firedAt = events.mapIndexedNotNull { i, e -> if ("ENGINE HOT" in e) i else null }
        // First crossing, the 5-minute repeat, then a new crossing after
        // cooling below hot-3 re-armed the alert.
        assertEquals(listOf(0, 60, 72), firedAt)
    }

    @Test
    fun `no reading or a disabled limit produces nothing`() {
        val s = ThermalSession(warmUpC = 60, hotC = 100, nowMs = { 0L })
        assertEquals(emptyList<String>(), s.onReading(0, 0L))
        val off = ThermalSession(warmUpC = 60, hotC = 0, nowMs = { 0L })
        assertEquals(emptyList<String>(), off.onReading(120, 0L))
        assertNull(s.maxC())
    }
}
