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
    var textRowChars: Int = 17

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

        controlJob = scope.launch {
            _hud.value = _hud.value.copy(active = true, navigationDataId = navigationDataId)
            while (isActive) {
                val step = _hud.value.instruction
                if (step != null) {
                    val nav = step.toNavStep()
                    val street = step.displayLine(textRowChars)
                    val metrics = step.metricsLine(textRowChars)

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

    private fun NavInstruction.metricsLine(max: Int): String {
        val d = if (distanceMeters >= 1000)
            "${distanceMeters / 1000}.${distanceMeters % 1000 / 100}km"
        else "${distanceMeters}m"
        return if (etaMinutes > 0) "ETA ${etaMinutes}m · $d".take(max) else d.take(max)
    }

    companion object {
        private const val TAG = "NavBridge"
    }
}