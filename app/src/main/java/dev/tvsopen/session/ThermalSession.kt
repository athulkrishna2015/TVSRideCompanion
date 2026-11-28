// Engine-temperature session logic.
//
// Pure JVM: no Android types, injectable clock. Alerts are computed from the
// telemetry we already decode (0x18 ENGINE_TEMPRATURE); nothing is written to
// the cluster, because that display behaviour is unverified on the Ntorq.

package dev.tvsopen.session

class ThermalSession(
    private val warmUpC: Int = DEFAULT_WARM_UP_C,
    private val hotC: Int = DEFAULT_HOT_C,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val temps = ArrayDeque<Pair<Long, Int>>()
    private var sessionStarted = false
    private var startedBelowWarm = false
    private var warmedUpDone = false
    private var aboveHotSince = 0L
    private var lastHotAlert = 0L
    private var lastReadingAt = 0L

    /** Returns zero or more labels for this reading. */
    fun onReading(tempC: Int, now: Long = nowMs()): List<String> {
        if (tempC <= 0) return emptyList() // 0 means "no reading" / limit off
        if (hotC <= 0) return emptyList()
        if (!sessionStarted || now - lastReadingAt > GAP_MS) reset(now, tempC)
        lastReadingAt = now
        temps.addLast(now to tempC)
        while (temps.isNotEmpty() && now - temps.first().first > WINDOW_MS) temps.removeFirst()

        val events = mutableListOf<String>()
        if (!warmedUpDone && startedBelowWarm && tempC >= warmUpC) {
            warmedUpDone = true
            events += "WARMED UP"
        }
        if (tempC >= hotC) {
            if (aboveHotSince == 0L) {
                aboveHotSince = now
                events += "ENGINE HOT"
                lastHotAlert = now
            } else if (now - lastHotAlert >= HOT_REPEAT_MS) {
                events += "ENGINE HOT"
                lastHotAlert = now
            }
        } else if (aboveHotSince != 0L && tempC <= hotC - HYSTERESIS_C) {
            aboveHotSince = 0L
            lastHotAlert = 0L
        }
        return events
    }

    fun reset(now: Long = nowMs(), tempC: Int = 0) {
        temps.clear()
        sessionStarted = true
        startedBelowWarm = tempC in 1 until warmUpC
        warmedUpDone = false
        aboveHotSince = 0L
        lastHotAlert = 0L
        lastReadingAt = now
    }

    fun maxC(): Int? = temps.maxOfOrNull { it.second }

    companion object {
        const val DEFAULT_WARM_UP_C = 60
        const val DEFAULT_HOT_C = 100
        private const val WINDOW_MS = 30 * 60_000L
        private const val GAP_MS = 3 * 60_000L
        private const val HOT_REPEAT_MS = 5 * 60_000L
        private const val HYSTERESIS_C = 3
    }
}
