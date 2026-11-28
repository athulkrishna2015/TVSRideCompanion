// Pure hysteresis tests for the low-fuel reminder.
package dev.tvsopen.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LowFuelRuleTest {

    @Test
    fun `fires once when the gauge drops to one bar`() {
        val rule = LowFuelRule()
        assertNull(rule.next(4))
        assertNull(rule.next(2))
        assertEquals("LOW FUEL", rule.next(1))
        assertNull(rule.next(1)) // same reading reposted
        assertNull(rule.next(2)) // bounce does not re-arm
    }

    @Test
    fun `rearms only after reaching three bars`() {
        val rule = LowFuelRule()
        assertEquals("LOW FUEL", rule.next(1))
        assertNull(rule.next(2))
        assertNull(rule.next(3))
        assertEquals("LOW FUEL", rule.next(1))
    }

    @Test
    fun `missing or out of range reading resets`() {
        val rule = LowFuelRule()
        assertEquals("LOW FUEL", rule.next(1))
        assertNull(rule.next(null))
        assertEquals("LOW FUEL", rule.next(1))
        assertNull(rule.next(7, maxBars = 6))
        assertEquals("LOW FUEL", rule.next(1))
    }
}
