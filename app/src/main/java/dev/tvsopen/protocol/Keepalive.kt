// The keepalive, rebuilt from the official app rather than from a report.
//
// The wrong-clock bug was here. `mobileData()` wrote zeros at [6..9] and
// [12..14], where the official `0x4A` carries hour, minute, second, AM/PM and
// the date. A cluster that gets a keepalive every two seconds containing a zero
// clock has no reason to keep its own — so it drifted, and the dash showed the
// wrong time. Nothing looked broken: the link was up, telemetry was live, and
// the app was "working".
//
// The layout below is transcribed from `U399DataSenderToCluster.sendMobileData`
// in com.tvsm.connect 2.13.0 (the same package the newer APK ships as), read
// from the concatenated byte arrays in source order:
//
//   bArr {0x5B, 0x4A}
//   bArr3    signal+battery, two decimal digits packed via hex2decimal
//   bArr4[0] call state, posted asynchronously
//   bArr5    {0}
//   bArr6    {2}
//   bArr8    HOUR      Calendar.HOUR, "0" becomes "12"
//   bArr9    MINUTE    Calendar.MINUTE
//   bArr10   SECOND    Calendar.SECOND
//   bArr11   AM/PM     Calendar.AM_PM == 0 ? 0 : 1
//   bArr7    missed calls
//   bArr12   {0}
//   date     DAY_OF_MONTH, MONTH+1, YEAR mod 100
//   {0, 0, 0, 0}
//   bArr2    {0xFF}
//
// Two details worth stating because they are easy to get wrong and invisible
// afterwards:
//
// 1. **Hour is 12-hour, and midnight is 12, not 0.** `Calendar.HOUR` already
//    returns 0-11; the official code rewrites a literal "0" to "12" and
//    zero-pads single digits before parsing. Sending `HOUR_OF_DAY` here would
//    put 19 on a cluster expecting 7.
// 2. **No checksum.** Byte 18 is zero. The enriched layout inherited from the
//    third-party Jupiter report wrote a checksum there; the official builder does
//    not, so a checksum byte is very likely what a cluster reads as a corrupt
//    keepalive.

package dev.tvsopen.protocol

import java.util.Calendar

/** The `0x5B 0x4A` keepalive, laid out as the official app builds it. */
object Keepalive {

    const val SIZE = 20
    private const val START = 0x5B
    private const val DATA_ID_MOBILE_DATA = 0x4A
    private const val END = 0xFF

    /**
     * Pack signal and battery into one byte, the way `hex2decimal` does.
     *
     * Upstream concatenates the two as decimal *strings* and parses the pair as
     * hex — `signal=3, battery=4` becomes `"34"` then `0x34`. That is why this is
     * nibble arithmetic rather than `(signal shl 4) or battery`: identical
     * result, but the upstream route explains the value and needs no separate
     * justification.
     */
    private fun packSignalBattery(signalBars: Int, batteryBars: Int): Byte {
        val digits = "${signalBars.coerceIn(0, 9)}${batteryBars.coerceIn(0, 9)}"
        // Two hex characters by construction, so this cannot throw for the
        // ranges above; guarded anyway because a future caller might widen them.
        return runCatching { Integer.parseInt(digits, 16).toByte() }.getOrDefault(0)
    }

    /**
     * 12-hour clock as the cluster expects it.
     *
     * `Calendar.HOUR` gives 0..11. Upstream rewrites "0" to "12" so the dash
     * reads 12 rather than 0 at midnight and midnight-noon.
     */
    private fun hour12(cal: Calendar): Int {
        val hour = cal.get(Calendar.HOUR)
        return if (hour == 0) 12 else hour
    }

    /** Build one keepalive from [state]. */
    fun build(
        state: DashState,
        missedCalls: Int = 0,
        incomingCall: Boolean = state.incomingCall,
        /** Injectable for tests; defaults to the device's current time. */
        calendar: Calendar = Calendar.getInstance(),
    ): ByteArray {
        val b = ByteArray(SIZE)
        b[0] = START.toByte()
        b[1] = DATA_ID_MOBILE_DATA.toByte()

        b[2] = packSignalBattery(state.signalBars, state.batteryBars)
        b[3] = if (incomingCall) 1 else 0

        b[4] = 0
        b[5] = 2

        // The clock. This block is the entire point of the rewrite.
        b[6] = hour12(calendar).toByte()
        b[7] = calendar.get(Calendar.MINUTE).toByte()
        b[8] = calendar.get(Calendar.SECOND).toByte()
        b[9] = if (calendar.get(Calendar.AM_PM) == Calendar.AM) 0 else 1

        b[10] = missedCalls.coerceAtLeast(0).toByte()
        b[11] = 0

        b[12] = calendar.get(Calendar.DAY_OF_MONTH).toByte()
        b[13] = (calendar.get(Calendar.MONTH) + 1).toByte()
        // YEAR mod 100, by string: upstream takes substring(2) of the 4-digit
        // year, so 2026 -> 26. Modulo gives the same answer without allocating.
        b[14] = (calendar.get(Calendar.YEAR) % 100).toByte()

        // [15..18] stay zero. Notably NOT a checksum — see the file comment.
        b[19] = END.toByte()
        return b
    }
}
