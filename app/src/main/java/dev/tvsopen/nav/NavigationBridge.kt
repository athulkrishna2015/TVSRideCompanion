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
                    driver.buildNavControl(nav)?.let { frame ->
                        if (ble.write(frame)) {
                            _hud.value = _hud.value.copy(
                                framesSent = _hud.value.framesSent + 1, lastError = null,
                            )
                        } else {
                            _hud.value = _hud.value.copy(lastError = "nav frame not written")
                        }
                    }

                    // Alternate the single free text line.
                    val line = if (showingMetrics) step.metricsLine(textRowChars) else step.displayLine(textRowChars)
                    driver.buildNavText(
                        NavStep(
                            distanceToTurnMeters = nav.distanceToTurnMeters,
                            etaMinutes = nav.etaMinutes,
                            remainingDistanceMeters = nav.remainingDistanceMeters,
                            pictogram = nav.pictogram,
                            streetName = line,
                        ),
                    )?.let { ble.write(it) }
                    showingMetrics = !showingMetrics
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
        distanceToTurnMeters = distanceMeters.coerceAtLeast(0),
        etaMinutes = etaMinutes,
        remainingDistanceMeters = 0,
        pictogram = pictogram ?: 7,
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