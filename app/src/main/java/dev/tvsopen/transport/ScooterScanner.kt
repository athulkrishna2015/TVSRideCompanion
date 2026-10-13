// BLE discovery.
//
// This is the missing step between "the app has a full protocol stack" and
// "the app can talk to a scooter". Everything downstream of *which device is it*
// already exists; nothing downstream of *finding it* does.
//
// Two design choices worth stating, because both are judgement calls:
//
//   - **Filtered by default.** The SmartXonnect service UUID narrows this from
//     "every wearable in the room" to "things that might be a cluster". The
//     escape hatch to an unfiltered scan exists because a cluster that is
//     already paired and asleep may advertise nothing at all, and a filter
//     that can never fail open would hide exactly the device you want.
//   - **Time-boxed.** Scanning is the single most expensive thing this app can
//     do to the battery, and it has no reason to keep running once the scooter
//     is found. The scan stops itself rather than relying on the UI to
//     remember.

package dev.tvsopen.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import dev.tvsopen.log.Log
import dev.tvsopen.protocol.Gatt
import dev.tvsopen.protocol.VehicleModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/** One device seen during a scan. */
data class Discovered(
    val address: String,
    val name: String?,
    val rssi: Int,
    /** Either signal fired. Drives the SmartXonnect-only filter. */
    val isSmartXonnect: Boolean,
    /** The service UUID was in the advertisement. The strong signal. */
    val byServiceUuid: Boolean,
    /** The advertised name looks like a TVS cluster. Weaker, and labelled so. */
    val byName: Boolean,
    val lastSeenMillis: Long,
    /** Best guess from the advertised name, or UNKNOWN. A hint, not a decision. */
    val guessedModel: VehicleModel,
)

data class ScanState(
    val scanning: Boolean = false,

    /**
     * Whether to restrict the scan to clusters advertising the service UUID.
     *
     * **Default off**, and this is a measured finding rather than a preference.
     * The filter maps to a `ScanFilter.setServiceUuid(...)`, which the platform
     * applies to the *advertisement packet*. This cluster advertises **no
     * service UUID at all** — that is why a UUID-filtered scan of the rider's
     * own Ntorq finds nothing at all, and why the app has to find it by name
     * (`TVSBT…`).
     *
     * Defaulting this on therefore made the scan screen find no clusters, on the
     * one vehicle that is known to work. The cost of off is a longer result list
     * containing non-clusters, which `publish()` sorts past by putting
     * SmartXonnect devices first.
     */
    val onlySmartXonnect: Boolean = false,
    val devices: List<Discovered> = emptyList(),
    /** Set when the scan could not start at all — usually a missing permission. */
    val error: String? = null,
)

class ScooterScanner(private val context: Context) {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private val found = ConcurrentHashMap<String, Discovered>()

    val isSupported: Boolean
        get() = bluetoothManager?.adapter?.isEnabled == true

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = record(result)

        // Batch callback: the platform prefers this on API 23+, and using it
        // avoids the app being throttled for ignoring `reportDelay`.
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { record(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed, code=$errorCode")
            _state.value = _state.value.copy(
                scanning = false,
                error = when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> "A scan is already running"
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
                        "The app could not be registered for scanning"
                    SCAN_FAILED_FEATURE_UNSUPPORTED ->
                        "This device does not support BLE scanning"
                    SCAN_FAILED_INTERNAL_ERROR -> "Bluetooth stack error — try again"
                    else -> "Scan failed (code $errorCode)"
                },
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun record(result: ScanResult) {
        val addr = result.device?.address ?: return
        val services: List<ParcelUuid> = runCatching {
            result.scanRecord?.serviceUuids ?: emptyList()
        }.getOrDefault(emptyList())
        val isSx = services.any { it.uuid?.toString()?.equals(Gatt.SERVICE, true) == true }

        val name = result.scanRecord?.deviceName
            ?: result.device?.name
        val byName = name != null && CLUSTER_NAME_RE.containsMatchIn(name)
        val d = Discovered(
            address = addr,
            name = name,
            rssi = result.rssi,
            // **Either signal counts.** Filtering only on the service UUID risks
            // hiding the one device we want: an advertisement packet need not
            // carry the service UUID even when the GATT table does, and we have
            // never had a cluster in range to check which it does. Community
            // reports of pairing describe the device advertising as "TVS BT…",
            // so the name is treated as a second, weaker signal — and the UI says
            // which one fired rather than implying certainty.
            isSmartXonnect = isSx || byName,
            byServiceUuid = isSx,
            byName = byName,
            lastSeenMillis = SystemClock.elapsedRealtime(),
            guessedModel = VehicleModel.detect(name),
        )
        val prev = found[addr]
        // Keep the strongest reading: RSSI jitters and the last packet is often
        // a missed one, so a naive overwrite makes the list flicker.
        if (prev == null || d.rssi > prev.rssi) found[addr] = d
        publish()
    }

    private fun publish() {
        val only = _state.value.onlySmartXonnect
        val now = SystemClock.elapsedRealtime()
        val list = found.values
            .filter { !only || it.isSmartXonnect }
            // Drop anything not heard for a while so stale entries do not
            // masquerade as available.
            .filter { now - it.lastSeenMillis < STALE_AFTER_MS }
            .sortedWith(compareByDescending<Discovered> { it.isSmartXonnect }.thenByDescending { it.rssi })
        _state.value = _state.value.copy(devices = list)
    }

    @SuppressLint("MissingPermission")
    fun start(
        onlySmartXonnect: Boolean = _state.value.onlySmartXonnect,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    ) {
        if (_state.value.scanning) return
        val scanner: BluetoothLeScanner? = runCatching {
            bluetoothManager?.adapter?.bluetoothLeScanner
        }.getOrNull()

        if (scanner == null) {
            _state.value = _state.value.copy(
                error = "Bluetooth is off. Turn it on and scan again.",
            )
            return
        }

        found.clear()
        _state.value = _state.value.copy(
            scanning = true,
            onlySmartXonnect = onlySmartXonnect,
            devices = emptyList(),
            error = null,
        )

        val filters = if (onlySmartXonnect) {
            // ParcelUuid wraps a UUID, not a string; Gatt.SERVICE is the string
            // form, so parse it rather than passing it straight through.
            listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(java.util.UUID.fromString(Gatt.SERVICE)))
                    .build(),
            )
        } else {
            emptyList()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val ok = runCatching { scanner.startScan(filters, settings, callback) }
            .onFailure {
                Log.e(TAG, "startScan refused: ${it.message}")
            }.isSuccess

        if (!ok) {
            _state.value = _state.value.copy(
                scanning = false,
                error = "Could not start scanning. Grant Bluetooth permission in " +
                    "More → Permissions and try again.",
            )
            return
        }
        Log.i(TAG, "scanning, onlySmartXonnect=$onlySmartXonnect")

        mainHandler.postDelayed({ stop(timeoutReached = true) }, timeoutMillis)
    }

    /**
     * Flip the service-UUID filter, restarting the scan.
     *
     * Restarting is not only about applying the new filter to the platform.
     * Flipping this switch means "my scooter did not show up, try a different
     * way of looking" — so it has to run a scan even when the last one already
     * timed out. Guarding on `wasScanning` left the switch dead in precisely the
     * situation it was added for: someone staring at an empty list after the
     * timeout taps it, nothing happens, and the escape hatch looks broken.
     */
    fun setFilter(onlySmartXonnect: Boolean) {
        if (_state.value.onlySmartXonnect == onlySmartXonnect) return
        val wasScanning = _state.value.scanning
        stop()
        // Unfiltered scans keep whatever was already found; a fresh filtered scan
        // starts clean, because the old entries may not have advertised the UUID.
        if (!wasScanning) found.clear()
        publish()
        start(onlySmartXonnect)
    }

    @SuppressLint("MissingPermission")
    fun stop(timeoutReached: Boolean = false) {
        mainHandler.removeCallbacksAndMessages(null)
        if (!_state.value.scanning) return
        runCatching {
            bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(callback)
        }.onFailure { Log.w(TAG, "stopScan: ${it.message}") }
        _state.value = _state.value.copy(scanning = false)
        if (timeoutReached) Log.i(TAG, "scan timed out")
    }

    fun clear() {
        found.clear()
        publish()
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    companion object {
        private const val TAG = "Scanner"
        private const val DEFAULT_TIMEOUT_MS = 20_000L
        private const val STALE_AFTER_MS = 8_000L

        /**
         * Advertised names of TVS clusters look like `TVS BT…`.
         *
         * **Inferred, not measured** — this comes from community accounts of
         * pairing, not from a cluster seen in a scan record. It is therefore a
         * second signal alongside the service UUID rather than a replacement for
         * it, and the scan UI labels which one matched. If the real prefix
         * differs this simply never fires; it cannot hide a device, only ever
         * add a possibly-false one.
         */
        private val CLUSTER_NAME_RE = Regex("""\bTVS\s*BT""", RegexOption.IGNORE_CASE)

        // Values from android.bluetooth.le.ScanCallback, inlined so the
        // mapping stays next to the message it produces.
        private const val SCAN_FAILED_ALREADY_STARTED = 1
        private const val SCAN_FAILED_APPLICATION_REGISTRATION_FAILED = 2
        private const val SCAN_FAILED_INTERNAL_ERROR = 3
        private const val SCAN_FAILED_FEATURE_UNSUPPORTED = 4
    }
}
