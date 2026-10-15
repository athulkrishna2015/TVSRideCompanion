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

    /**
     * Line 1 width on this cluster. The Ntorq has 10 chars on line 1 and
     * 12 on line 2; this property is retained for the legacy one-line path.
     */
    var textRowChars: Int = RowChars.NTORQ

    /** Ntorq second-line width, which is where the street/distance row goes. */
    var secondTextRowChars: Int = RowChars.NTORQ_LINE_2

    /** Ntorq first-line width, which is where the maneuver goes. */
    var firstTextRowChars: Int = RowChars.NTORQ_LINE_1

    var controlIntervalMs: Long = 1500
    var textSwapIntervalMs: Long = 3000

    var pingIntervalMs: Long = 2000

    private var findMy = false

    // Rolling offset for overflowing text. Advances once per send so a line
    // wider than the row scrolls instead of clipping its tail. Reset whenever
    // the instruction changes so a new turn starts at its head, not mid-scroll.
    private var scrollTick = 0
    private var lastInstructionKey: String? = null

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
        // The control frame is a liveness signal the cluster expects roughly
        // every 1.5 s. Alternating a single line runs on the slower text cadence
        // by design, but once both rows go out together there is no reason to
        // wait for it — so the two-row path holds the control cadence instead.
        var lastTwoRows = false

        controlJob = scope.launch {
            _hud.value = _hud.value.copy(active = true, navigationDataId = navigationDataId)
            while (isActive) {
                val step = _hud.value.instruction
                if (step != null) {
                    val key = "${step.title}|${step.street}|${step.distanceMeters}"
                    if (key != lastInstructionKey) {
                        lastInstructionKey = key
                        scrollTick = 0
                    }
                    val nav = step.toNavStep()
                    // Both cluster lines are always populated together: the
                    // maneuver (compressed + arrow, line 1) and the ETA/distance
                    // (line 2). Each rolls independently when it overflows its
                    // row. The control frame advertises rows = 2 so the cluster
                    // shows both at once instead of alternating.
                    val street = step.displayLine(secondTextRowChars, scrollTick)
                    val fullMetrics = step.metricsLine(firstTextRowChars, scrollTick)

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
                    // The rider confirmed asymmetric physical rows. The second
                    // data ID and control row count remain inherited from the
                    // protocol transcription; their display behavior still
                    // needs a live console capture.
                    val metrics = fullMetrics
                    val twoRows = driver.buildNavTextSecondary(nav.copy(streetName = street)) != null &&
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
                        // The maneuver goes on the primary channel, 0x4F.
                        //
                        // This was backwards: metrics went on 0x4F and the
                        // maneuver on 0x50, so a cluster rendering the primary
                        // nav text channel showed ETA forever and the turn
                        // instruction never appeared — exactly "only ETA, no
                        // turn-by-turn". 0x4F (`DATA_ID_NAVIGATIONDATA1`) is the
                        // only nav text ID the current official sender defines;
                        // 0x50 is secondary. The cluster takes the distance for
                        // line 1 from the control frame itself, so the text line
                        // must be the instruction, matching the manual's
                        // "AFTER 900m / TAKE RIGHT" layout.
                        driver.buildNavText(nav.copy(streetName = street))?.let { ble.write(it) }
                        driver.buildNavTextSecondary(
                            nav.copy(streetName = metrics),
                        )?.let { ble.write(it) }
                    } else {
                        val line = if (showingMetrics) metrics else street
                        driver.buildNavText(nav.copy(streetName = line))?.let { ble.write(it) }
                        showingMetrics = !showingMetrics
                    }
                    lastTwoRows = twoRows
                    scrollTick++
                }
                delay(if (lastTwoRows || showingMetrics) controlIntervalMs else textSwapIntervalMs)
            }
        }
    }

    fun stopNavigation() {
        controlJob?.cancel()
        controlJob = null
        scrollTick = 0
        lastInstructionKey = null
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

    companion object {
        private const val TAG = "NavBridge"
    }
}
