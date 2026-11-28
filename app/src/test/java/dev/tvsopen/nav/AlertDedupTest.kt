// Pure tests for silent-repost dedup; no wall clock, no Android.
package dev.tvsopen.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertDedupTest {

    @Test
    fun `first notification is mirrored, immediate repost is not`() {
        val dedup = AlertDedup(cooldownMs = 8_000L, nowMs = { 0L })
        assertTrue(dedup.firstTime("call:key", at = 1_000L))
        assertFalse(dedup.firstTime("call:key", at = 1_001L))
    }

    @Test
    fun `after the cooldown the same key is mirrored again`() {
        val dedup = AlertDedup(cooldownMs = 8_000L, nowMs = { 0L })
        assertTrue(dedup.firstTime("msg:key", at = 1_000L))
        assertTrue(dedup.firstTime("msg:key", at = 9_001L))
    }

    @Test
    fun `different keys are independent`() {
        val dedup = AlertDedup(cooldownMs = 8_000L, nowMs = { 0L })
        assertTrue(dedup.firstTime("call:a", at = 1_000L))
        assertTrue(dedup.firstTime("call:b", at = 1_001L))
    }

    @Test
    fun `clear re-arms every key`() {
        val dedup = AlertDedup(cooldownMs = 8_000L, nowMs = { 0L })
        assertTrue(dedup.firstTime("call:a", at = 1_000L))
        dedup.clear()
        assertTrue(dedup.firstTime("call:a", at = 1_001L))
    }
}
