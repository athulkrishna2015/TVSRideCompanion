// The bridge: one nav instruction in, cluster frames out.
//
// This is the piece that makes the app provider-agnostic and account-free.
// Nothing here talks to TVS or to a maps SDK.

package dev.tvsopen.nav

import android.util.Log
import dev.tvsopen.protocol.DashState
import dev.tvsopen.protocol.NavStep
import dev.tvsopen.protocol.OutboundId
import dev.tvsopen.protocol.PacketBuilder
import dev.tvsopen.protocol.Pictograms
import dev.tvsopen.protocol.ProtocolDriver
import dev.tvsopen.transport.ScooterBleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the cluster is currently showing, for the UI and for diagnostics. */
data class HudState(
    val active: Boolean = false,
    val instruction: NavInstruction? = null,
    val framesSent: Int = 0,
    val lastError: String? = null,
    /** Which navigation data id is in use — 0x4E or 0x49, or null if disabled. */
    val navigationDataId: Int? = null,
)

/**
 * Forwards navigation to the cluster.
 *
 * ## Cadence
 *
 * The cluster treats the navigation control frame as a *liveness* signal, not
 * a one-shot: the official app re-sends it roughly every 1.5 s while a route is
 * active. Stopping mid-route makes the HUD close. [controlIntervalMs] is
 * therefore not a tuning knob but part of the protocol.
 *
 * ## Text cycling
 *
 * The cluster firmware renders the distance-to-turn integer itself on line 1
 * next to the arrow. Only one line of free text remains, and it is 17
 * characters. Sending alternating street name and trip metrics every few
 * seconds (per the Jupiter report §4.2) is what makes the small line useful.
 */
class NavigationBridge(
    private val ble: ScooterBleService,
    private val scope: CoroutineScope,
    private val driver: ProtocolDriver,
) {

    private val _hud = MutableStateFlow(HudState())
    val hud: StateFlow<HudState> = _hud.asStateFlow()

    private var controlJob: Job? = null
    private var pingJob: Job? = null

    /** 0x4E or 0x49; whichever the cluster accepts. Default 0x4E. */
    var navigationDataId: Int = OutboundId.NAVIGATION_CONTROL

    /** Text row capacity on this cluster. Jupiter: 17. Ntorq TFT: unknown. */
    /**
     * Characters per navigation line on this cluster.
     *
     * 12, not the 17 the payload implies — TVS's manual documents `DISTANCE
     * REM` for `DISTANCE REMAINING` and `STAY SAME RD` for `STAY SAME ROAD`, both
     * cut at 12, and nothing it shows is longer. Set from [RowChars.NTORQ] when
     * the bridge is built for an Ntorq.
     */
    var textRowChars: Int = RowChars.NTORQ

    var controlIntervalMs: Long = 1500
    var textSwapIntervalMs: Long = 3000

    /**
     * Send both navigation lines at once instead of alternating one.
     *
     * Off by default. `0x5B 0x50` and the control frame's row-count byte are
     * transcribed, not measured — no capture has shown a second line rendering on
     * this cluster — while the single-row alternating path is known to reach the
     * display. Turning this on without the scooter available would trade a
     * working display for an unverified one.
     */
    var twoRowNav: Boolean = false

    /**
     * Let the **cluster** scroll a payload longer than its own row.
     *
     * The manual documents that the cluster scrolls long text itself, so
     * sending the whole street name and letting it do the work is the better
     * display if it is true. Off by default: the documentation describes the
     * behaviour but nothing here has observed it, and the app-side window in
     * [Scroll] is the path that is known to produce a displayable 12 characters.
     *
     * With this off the app sends successive windows instead, which is why the
     * scroller exists at all.
     */
    var scrollNav: Boolean = false

    /** How long each scrolling window stays up, in ms. */
    var scrollTickMs: Long = 1200

    /**
     * Extra characters fetched beyond the row so there is something to scroll.
     *
     * A long name is truncated to `width + lookahead` before scrolling rather
     * than in full: a 40-character destination is not a 40-character scroll on a
     * 12-cell row, it is three seconds of waiting to read past the useful part.
     */
    var scrollLookahead: Int = 12
    var pingIntervalMs: Long = 2000

    private var findMy = false

    /**
     * Start the keepalive. The cluster drops the link if the presence packet
     * stops, so this runs for the whole session, ride or not.
     */
    fun startKeepalive(dash: () -> DashState) {
        if (pingJob?.isActive == true) return
        pingJob = scope.launch {
            while (isActive) {
                val state = dash().copy(findMyScooter = findMy)
                driver.buildPing(state)?.let { ble.write(it) }
                delay(pingIntervalMs)
            }
        }
    }

    fun stopKeepalive() {
        pingJob?.cancel()
        pingJob = null
    }

    /** Flash the indicators and beep. Toggled into the keepalive packet. */
    fun setFindMyScooter(enabled: Boolean) {
        findMy = enabled
        Log.i(TAG, "find-my-scooter -> $enabled")
    }

    /** Begin mirroring navigation. No-op when the driver has no nav support. */
    fun startNavigation() {
        if (!driver.capabilities.navigationHud) {
            Log.w(TAG, "${driver.model.displayName} nav not enabled for this driver")
            return
        }
        if (controlJob?.isActive == true) return
        var showingMetrics = false

        // One scroller per source string, recreated when the step changes.
        //
        // Keyed by content rather than index: a new turn-by-turn step replaces
        // the whole string, and carrying the offset across would resume
        // mid-word on a street whose name shares no characters with the last.
        var streetScroll = Scroll("", textRowChars)
        var metricsScroll = Scroll("", textRowChars)

        controlJob = scope.launch {
            _hud.value = _hud.value.copy(active = true, navigationDataId = navigationDataId)
            while (isActive) {
                val step = _hud.value.instruction
                if (step != null) {
                    val nav = step.toNavStep()
                    val fullStreet = step.displayLine(textRowChars + scrollLookahead)
                    val fullMetrics = step.metricsLine(textRowChars)

                    // Long street names scroll rather than lose their tail.
                    //
                    // The row is 12 characters and Indian street names rarely
                    // fit. Truncating showed "Meppayyur -" and dropped the half
                    // naming the destination, which is the half that matters.
                    //
                    // [scrollNav] decides who does the scrolling. When the
                    // cluster's own scrolling is trusted it gets the full string
                    // and the row walks itself; otherwise the app walks a window
                    // across the row, which is the displayable-certain path and
                    // the default while the scooter is unavailable.
                    if (streetScroll.full != fullStreet) streetScroll = Scroll(fullStreet, textRowChars)
                    if (metricsScroll.full != fullMetrics) metricsScroll = Scroll(fullMetrics, textRowChars)

                    val street = if (streetScroll.scrolls) {
                        if (scrollNav) fullStreet.take(textRowChars)
                        else streetScroll.current()
                    } else {
                        fullStreet
                    }
                    val metrics = if (metricsScroll.scrolls) {
                        if (scrollNav) fullMetrics.take(textRowChars)
                        else metricsScroll.current()
                    } else {
                        fullMetrics
                    }
                    // Advance after showing, so this tick renders the window the
                    // scroller is already on. holdTicks is the dwell: two ticks
                    // at each end, one through the middle.
                    val hold = maxOf(streetScroll.holdTicks(), metricsScroll.holdTicks())
                    repeat(hold - 1) { delay(scrollTickMs) }
                    streetScroll.advance()
                    metricsScroll.advance()

                    // Two lines when the driver has a second row, else alternate.
                    //
                    // Alternating was the only option while the cluster had one
                    // line: street, then ETA, every 3 s, so the rider never saw
                    // both at once and had to wait for the one they wanted.
                    // `0x5B 0x50` is a distinct data id rather than a second use
                    // of `0x4F`, and the control frame's byte 10 tells the cluster
                    // how many lines are populated — so with both set they can be
                    // shown together.
                    //
                    // UNVERIFIED on this cluster: byte 10's meaning is from the
                    // Jupiter report, and no capture has shown `0x50` on the
                    // wire. So the second row is only sent when there is a
                    // distinct second line to show, and [twoRowNav] falls back
                    // to the old alternating behaviour, which is known to work.
                    val twoRows = twoRowNav &&
                        driver.buildNavTextSecondary(nav.copy(streetName = street)) != null &&
                        street.isNotBlank() && metrics.isNotBlank()

                    val control = nav.copy(rows = if (twoRows) 2 else 1)
                    driver.buildNavControl(control)?.let { frame ->
                        if (ble.write(frame)) {
                            _hud.value = _hud.value.copy(
                                framesSent = _hud.value.framesSent + 1, lastError = null,
                            )
                        } else {
                            _hud.value = _hud.value.copy(lastError = "nav frame not written")
                        }
                    }

                    if (twoRows) {
                        driver.buildNavText(nav.copy(streetName = street))?.let { ble.write(it) }
                        driver.buildNavTextSecondary(
                            nav.copy(streetName = metrics),
                        )?.let { ble.write(it) }
                    } else {
                        val line = if (showingMetrics) metrics else street
                        driver.buildNavText(nav.copy(streetName = line))?.let { ble.write(it) }
                        showingMetrics = !showingMetrics
                    }
                }
                delay(if (showingMetrics) controlIntervalMs else textSwapIntervalMs)
            }
        }
    }

    fun stopNavigation() {
        controlJob?.cancel()
        controlJob = null
        _hud.value = HudState(active = false)
    }

    fun onInstruction(step: NavInstruction?) {
        _hud.value = _hud.value.copy(instruction = step)
    }

    private fun NavInstruction.toNavStep() = NavStep(
        distanceToTurnMeters = distanceMeters.coerceIn(0, 0xFFFF),
        etaMinutes = etaMinutes,
        remainingDistanceMeters = 0,
        // Tier 2 of the pictogram resolver: when the provider gives no usable
        // icon and the words do not resolve, send straight-ahead. Named, not a
        // bare 7, so the value stays checkable against the table above.
        pictogram = pictogram ?: Pictograms.STRAIGHT,
        streetName = street,
    )

    /**
     * The ETA / distance line.
     *
     * Formats to match TVS's own rendering, from the manual's navigation page:
     * `10Km 700m` for a multi-kilometre distance, plain metres below that. The
     * old `1.2km` style appeared nowhere in the documentation.
     *
     * Returns the **full** string, deliberately not truncated. [Scroll] does the
     * cutting, because this line routinely exceeds a 12-cell row and the rest of
     * it is worth showing.
     */
    private fun NavInstruction.metricsLine(max: Int): String {
        val d = if (distanceMeters >= 1000)
            "${distanceMeters / 1000}Km ${distanceMeters % 1000}m"
        else "${distanceMeters}m"
        return if (etaMinutes > 0) "ETA ${etaMinutes}m $d" else d
    }

    /**
     * A window that walks a long string across the row and back.
     *
     * The row is 12 characters. An Indian street name rarely fits — "Meppayyur -
     * Avalla" is 17, and "Kumbakonam Gandhi Nagar" is longer still — so
     * truncating showed `"Meppayyur -"` and dropped the half that says where you
     * are going. The cluster scrolls long text itself, so the app should hand it
     * the whole string and let it do that.
     *
     * Scrolling is done **app-side** rather than by sending successive windows,
     * and that is the honest part: whether the cluster scrolls a payload longer
     * than its own row is not established — [scrollNav] is therefore off until
     * the scooter is available. The windowing here is the fallback that is known
     * to be displayable, and it is what runs by default.
     *
     * Pauses at each end rather than wrapping immediately, so a word is readable
     * before it leaves.
     */
    private class Scroll(
        val full: String,
        private val width: Int,
    ) {
        private var offset = 0

        /** True when the text is wider than the row and actually needs moving. */
        val scrolls: Boolean get() = full.length > width

        /** The window to show now. Does not advance; call [advance] for that. */
        fun current(): String =
            if (!scrolls) full
            else full.substring(offset, (offset + width).coerceAtMost(full.length))

        /**
         * Move one character along, then back.
         *
         * Bounces rather than wrapping: reaching the far end returns through the
         * same text rather than jumping back to the start, so the name is read
         * once, continuously. The caller holds each window for a display tick
         * (or two, see [holdTicks]), which is the dwell time.
         */
        fun advance() {
            if (!scrolls) return
            val maxOffset = full.length - width
            offset = if (offset >= maxOffset) 0 else offset + 1
        }

        /**
         * Ticks to hold this window before moving on.
         *
         * 2 at each end so the first and last characters are readable before the
         * text starts moving, 1 through the middle where context is enough.
         */
        fun holdTicks(): Int =
            if (!scrolls) 1
            else if (offset == 0 || offset >= full.length - width) 2
            else 1
    }

    companion object {
        private const val TAG = "NavBridge"
    }
}