// Silent-repost dedup for mirrored calls and messages.
//
// Android re-posts the same notification when its content changes (e.g. the
// unread count grows). Without a cooldown the cluster is pinged repeatedly
// for one event. Pure JVM: no Android types, injectable clock, no raw sender
// or message text stored anywhere.

package dev.tvsopen.nav

class AlertDedup(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val seen = HashMap<String, Long>()

    /** True only when this key has not been mirrored recently. */
    fun firstTime(key: String, at: Long = nowMs()): Boolean {
        // Bound memory: drop entries older than four cooldowns once large.
        if (seen.size > MAX_KEYS) {
            val cutoff = at - cooldownMs * 4
            val it = seen.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value < cutoff) it.remove()
            }
        }
        val last = seen[key]
        if (last != null && at - last < cooldownMs) return false
        seen[key] = at
        return true
    }

    fun clear() = seen.clear()

    companion object {
        const val DEFAULT_COOLDOWN_MS = 8_000L
        private const val MAX_KEYS = 128
    }
}
