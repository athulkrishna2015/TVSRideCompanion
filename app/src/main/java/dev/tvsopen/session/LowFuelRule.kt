// Low-fuel reminder rule.
//
// Pure JVM: a drop to one bar within one connection fires once; a bouncing
// gauge must climb back to the re-arm level before it fires again. A missing
// reading (disconnect or an out-of-range byte) resets the rule. Nothing is
// written to the cluster and no notification is posted from here.

package dev.tvsopen.session

class LowFuelRule(
    private val lowBars: Int = DEFAULT_LOW_BARS,
    private val rearmBars: Int = DEFAULT_REARM_BARS,
) {
    private var armed = true

    /** Returns a warning label when the rule fires, else null. */
    fun next(bars: Int?, maxBars: Int = 6): String? {
        if (bars == null || bars !in 0..maxBars) {
            armed = true
            return null
        }
        if (bars >= rearmBars) {
            armed = true
            return null
        }
        if (bars in 1..lowBars && armed) {
            armed = false
            return "LOW FUEL"
        }
        return null
    }

    fun reset() {
        armed = true
    }

    companion object {
        const val DEFAULT_LOW_BARS = 1
        const val DEFAULT_REARM_BARS = 3
    }
}
