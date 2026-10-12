// Bluetooth pairing with the cluster.
//
// Two separate things are often called "pairing", and the app needs both:
//
//   **Bonding** — the Android-level bond. `BluetoothDevice.createBond()`. The
//   official app's own scan screen does this, and a cluster that is bonded is
//   reachable later without advertising, which is the whole reason a
//   "scan once, connect forever" flow is possible.
//
//   **Pair mode** — a state on the *vehicle*. TVS's Ntorq manual is explicit
//   that the connected cluster enters BT pair mode on its own when:
//
//     1. the battery is disconnected and reconnected
//     2. the vehicle fuse is blown and replaced
//     3. the electric start is done with a low battery
//
//   None of those is a phone-side action. The phone cannot put the scooter into
//   pair mode; it can only be ready for it. So the app's job here is to say so
//   plainly rather than presenting a "Pair" button that silently does nothing
//   when the vehicle is not in that state.
//
// Bonding is stateful and slow — a PIN prompt, a timeout, a failure mode — so
// it is published as a flow rather than returned as a Boolean.

package dev.tvsopen.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log as AndroidLog
import dev.tvsopen.log.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where a bond attempt has got to. */
sealed interface BondState {
    data object Idle : BondState
    data object Pairing : BondState
    data class Paired(val address: String) : BondState
    data class Failed(val address: String, val reason: String) : BondState
}

/**
 * Bonding helper.
 *
 * Holds a runtime-registered receiver, so `start()` pairs with the activity
 * lifecycle rather than the process — a receiver registered from a manifest
 * would keep the process alive for the duration of a bonding attempt, which is
 * a lot of battery to spend on a PIN dialog.
 */
class ScooterPairing(private val context: Context) {

    private val _state = MutableStateFlow<BondState>(BondState.Idle)
    val state: StateFlow<BondState> = _state.asStateFlow()

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            // The typed getParcelableExtra is API 33+; the deprecated overload
            // is the only one below that, and this is not a hot path.
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(
                BluetoothDevice.EXTRA_DEVICE,
            ) ?: return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                BluetoothDevice.BOND_BONDING -> {
                    Log.i(TAG, "bonding with ${device.address}")
                    _state.value = BondState.Pairing
                }

                BluetoothDevice.BOND_BONDED -> {
                    Log.i(TAG, "bonded with ${device.address}")
                    _state.value = BondState.Paired(device.address)
                }

                else -> {
                    val reason = intent.getIntExtra(EXTRA_REASON, BluetoothDevice.ERROR)
                    Log.w(TAG, "bond failed with ${device.address}, reason=$reason")
                    _state.value = BondState.Failed(device.address, describeReason(reason))
                }
            }
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            registered = true
        }.onFailure { Log.w(TAG, "could not register bond receiver: ${it.message}") }
    }

    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    fun isBonded(address: String): Boolean =
        adapter?.bondedDevices?.any { it.address == address } == true

    /** Addresses the phone has already bonded, as MAC strings. */
    fun bondedAddresses(): Set<String> =
        adapter?.bondedDevices?.map { it.address }?.toSet() ?: emptySet()

    /**
     * Start bonding. Returns false when the platform refuses outright, e.g. the
     * adapter is off or already at the per-device bonding limit.
     */
    @SuppressLint("MissingPermission")
    fun pair(address: String): Boolean {
        val device: BluetoothDevice = runCatching {
            adapter?.getRemoteDevice(address)
        }.getOrNull() ?: run {
            Log.w(TAG, "pair: no device for $address")
            return false
        }
        if (isBonded(address)) {
            _state.value = BondState.Paired(address)
            return true
        }
        if (device.bondState == BluetoothDevice.BOND_BONDING) {
            _state.value = BondState.Pairing
            return true
        }
        return runCatching {
            val started = device.createBond()
            if (started) {
                _state.value = BondState.Pairing
                Log.i(TAG, "createBond -> $started for $address")
            } else {
                Log.w(TAG, "createBond refused for $address")
                _state.value = BondState.Failed(
                    address,
                    "Android refused to start pairing — Bluetooth off, or too " +
                        "many paired devices already",
                )
            }
            started
        }.getOrElse {
            Log.e(TAG, "createBond threw: ${it.message}")
            _state.value = BondState.Failed(address, it.message ?: "pairing failed")
            false
        }
    }

    /**
     * How to put the vehicle into pair mode, from TVS's Ntorq manual.
     *
     * These are vehicle-side actions. Nothing the phone does can substitute,
     * which is why the UI shows them rather than offering a retry button that
     * would fail identically.
     */
    val pairModeInstructions = listOf(
        "Turn the ignition off and wait a minute",
        "Disconnect the battery, then reconnect it — this is what puts the " +
            "cluster into BT pair mode",
        "The cluster will advertise for a short window; scan during it",
    )

    /**
     * Best-effort translation of a bond failure.
     *
     * `BluetoothDevice.EXTRA_REASON` and the `PAIRING_REASON_*` constants are
     * `@hide` in the SDK and do not resolve at compile time, so the extra is
     * read by its documented literal name and the reasons are matched as raw
     * AOSP values. An unrecognised reason is reported as a number rather than
     * guessed at — a wrong explanation here would send someone chasing the
     * wrong fix.
     */
    private fun describeReason(reason: Int): String = when (reason) {
        REASON_DISCONNECTED ->
            "Disconnected while pairing — the scooter may have left pair mode"
        REASON_AUTH_FAILED -> "Authentication failed; forget the device and pair again"
        REASON_AUTH_REJECTED -> "The scooter rejected the pairing request"
        else -> "Pairing failed (reason $reason)"
    }

    companion object {
        private const val TAG = "Pairing"

        /** The hidden `BluetoothDevice.EXTRA_REASON`, read by literal name. */
        private const val EXTRA_REASON = "android.bluetooth.device.extra.REASON"

        // AOSP BluetoothDevice pairing reasons.
        private const val REASON_DISCONNECTED = 3
        private const val REASON_AUTH_FAILED = 4
        private const val REASON_AUTH_REJECTED = 5
    }
}