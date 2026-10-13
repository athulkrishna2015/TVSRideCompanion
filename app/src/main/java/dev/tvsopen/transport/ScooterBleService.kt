// BLE transport.
//
// Model-agnostic: it knows about GATT and framing, not about what any
// particular cluster's frames mean. Per-model meaning lives behind
// ProtocolDriver.

package dev.tvsopen.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import dev.tvsopen.log.Log
import dev.tvsopen.protocol.Gatt
import dev.tvsopen.protocol.GattConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID

enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, RECONNECTING }

/**
 * Human-readable label. The enum's own `name` renders as "DISCONNECTED", which
 * is shouting in a status pill.
 */
val ConnectionState.label: String
    get() = when (this) {
        ConnectionState.DISCONNECTED -> "Disconnected"
        ConnectionState.SCANNING -> "Scanning"
        ConnectionState.CONNECTING -> "Connecting"
        ConnectionState.CONNECTED -> "Connected"
        ConnectionState.RECONNECTING -> "Reconnecting"
    }

data class BleFrame(val direction: Direction, val bytes: ByteArray, val atMillis: Long) {
    enum class Direction { INBOUND, OUTBOUND }
    val hex: String get() = bytes.joinToString(" ") { "%02X".format(it) }
    override fun equals(other: Any?) =
        this === other || (other is BleFrame && direction == other.direction &&
            bytes.contentEquals(other.bytes) && atMillis == other.atMillis)
    override fun hashCode() = ((direction.ordinal * 31 + bytes.contentHashCode()) * 31 + atMillis.hashCode())
}

/**
 * One GATT link to one cluster.
 *
 * ## Why the write mutex
 *
 * Android's BLE stack is not thread-safe. With a navigation broadcast, a
 * keepalive ping and button events all in flight, concurrent
 * `writeCharacteristic` calls produce status-133 disconnects. Every write here
 * goes through a [Mutex], serialising them into a FIFO queue.
 *
 * ## Why write-without-response
 *
 * Upstream sets `getCharacteristicsWriteType() == 2`
 * (`WRITE_TYPE_NO_RESPONSE`) and requests a 512-byte MTU on every petrol
 * model, so a full frame fits in one packet and no flow control is needed.
 */
class ScooterBleService(private val context: Context) {

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<BleFrame>(replay = 0, extraBufferCapacity = 256)
    val frames: SharedFlow<BleFrame> = _frames.asSharedFlow()

    private val _lastFrameAt = MutableStateFlow(0L)
    val lastFrameAt: StateFlow<Long> = _lastFrameAt.asStateFlow()

    private val writeLock = Mutex()
    // Written on the binder thread by the GATT callback and read on a coroutine
    // by connect/write, so the visibility guarantee is not optional: without it
    // a reconnect can read a stale non-null and refuse, which is exactly the
    // failure this class had.
    @Volatile
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    /**
     * Whether the current link outage has already been reported.
     *
     * Guarded by [writeLock] rather than volatile: every read and write happens
     * inside `write`, so the lock is already held and a separate field would
     * only invite a second synchronisation scheme to drift out of step.
     */
    private var dropWarned = false

    val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun isSupported(): Boolean =
        context.packageManager.hasSystemFeature("android.hardware.bluetooth_le")

    // ---- lifecycle ----

    @SuppressLint("MissingPermission")
    fun connect(address: String, config: GattConfig = GattConfig.DEFAULT): Boolean {
        if (gatt != null) return false
        val a = adapter ?: return false
        val device: BluetoothDevice = try {
            a.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "bad address $address: ${e.message}")
            return false
        }
        _state.value = ConnectionState.CONNECTING

        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, config.autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, config.autoConnect, gattCallback)
        }
        return true
    }

    @SuppressLint("MissingPermission")
    suspend fun disconnect() {
        // Suspend rather than plain fun: withLock is a suspend function, and a
        // non-suspend caller could not use it. Waiting here also guarantees no
        // write is in flight when the GATT client is closed.
        writeLock.withLock {
            gatt?.let { closeGatt(it) }
            writeChar = null
        }
        _state.value = ConnectionState.DISCONNECTED
    }

    /**
     * Close a GATT client and drop our reference to it.
     *
     * `close()` is what releases the client registration; without it the client
     * leaks and stays in `gatt`. That matters because `connect()` treats a
     * non-null `gatt` as "already connected" and refuses — so a single dropped
     * link left the app unable to reconnect for the life of the process, and
     * the only recovery was force-stopping it. Observed on hardware: the cluster
     * dropped with status 8 after ~2 minutes, and every later Connect was
     * refused with "link already active".
     *
     * Called from the binder callback as well as from [disconnect], so the
     * identity check matters — a stale callback must not clear a newer client.
     */
    @SuppressLint("MissingPermission")
    private fun closeGatt(g: BluetoothGatt) {
        writeChar = null
        runCatching { g.close() }
            .onFailure { Log.w(TAG, "gatt close: ${it.message}") }
        if (gatt === g) gatt = null
    }

    /**
     * Enqueue a write. Never throws; BLE failures are reported through
     * [ConnectionState] and the log, because a dropped frame is recoverable
     * and an exception in a caller coroutine is not.
     */
    suspend fun write(bytes: ByteArray): Boolean = writeLock.withLock {
        val g = gatt
        val ch = writeChar
        if (g == null || ch == null || _state.value != ConnectionState.CONNECTED) {
            // Log the *first* drop of an outage, not every attempt.
            //
            // The keepalive pings every 2 s for the whole session, so with the
            // link down this line fired 30 times a minute, forever. It drowned
            // the log: a 13-hour capture was mostly one repeated message, and the
            // frames that actually explain a fault became the hard part to find.
            // The per-frame dedup in `Log.frame` cannot help here because these
            // writes never reach it — they are dropped before any frame is built.
            //
            // Reset on the first successful write, so each outage is reported
            // exactly once and a reconnect is not silent.
            if (!dropWarned) {
                dropWarned = true
                val why = when {
                    g == null -> "no gatt"
                    ch == null -> "no write characteristic"
                    else -> "state is ${_state.value}"
                }
                Log.w(TAG, "link not ready, dropping writes ($why)")
            }
            return@withLock false
        }
        // Explicit <Boolean>: the lambda's last expression is a conditional on
        // `cont.resume(...)` which returns Unit, so inference would pick the
        // wrong T and `ok` would be typed as the continuation result itself.
        val ok = suspendCancellableCoroutine<Boolean> { cont ->
            val result = try {
                // The 3-argument overload exists only on API 33+. Calling it on
                // an older phone throws NoSuchMethodError and takes the writer
                // down, so the legacy path sets the value and type on the
                // characteristic first — that is the only form API 26-32 has.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(
                        ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                    ) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    ch.value = bytes
                    @Suppress("DEPRECATION")
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(ch)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "write refused: ${e.message}")
                false
            }
            // resumeWith, not resume: the `resume` extension on Continuation is
            // ambiguous against CancellableContinuation's member here, and the
            // inferred type leaks into `ok`.
            cont.resumeWith(Result.success(result))
        }
        if (ok) {
            // Re-arm the outage warning: the link came back, so the next drop is
            // news again rather than a continuation of the one already reported.
            dropWarned = false
            emit(BleFrame(BleFrame.Direction.OUTBOUND, bytes, System.currentTimeMillis()))
        }
        ok
    }

    private fun emit(frame: BleFrame) {
        _lastFrameAt.value = frame.atMillis
        _frames.tryEmit(frame)
        // Bytes go in, hex comes out inside Log.frame, which also skips the
        // formatting cost entirely when capture is disabled.
        Log.frame(
            if (frame.direction == BleFrame.Direction.INBOUND) "IN " else "OUT",
            frame.bytes,
            frame.atMillis,
        )
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected (status=$status)")
                    _state.value = ConnectionState.CONNECTED
                    try {
                        g.requestMtu(Gatt.REQUESTED_MTU)
                        discoverServices(g)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "post-connect setup refused: ${e.message}")
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "GATT disconnected (status=$status)")
                    _state.value = ConnectionState.DISCONNECTED
                    // Must release the client here, not just clear the
                    // characteristic: `connect()` bails out while `gatt` is
                    // non-null, so leaving a dead client in place made every
                    // subsequent reconnect a silent no-op. See [closeGatt].
                    closeGatt(g)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(UUID.fromString(Gatt.SERVICE))
            if (svc == null) {
                Log.w(TAG, "service ${Gatt.SERVICE} not found on ${g.device.address}")
                return
            }
            val ch = svc.getCharacteristic(UUID.fromString(Gatt.WRITE))
            if (ch == null) {
                Log.w(TAG, "write characteristic ${Gatt.WRITE} missing")
                return
            }
            writeChar = ch
            Log.i(TAG, "service ready, write char props=0x${ch.properties.toString(16)}")
            // Notified frames carry telemetry; subscribe when the cluster offers it.
            enableNotifications(g, svc)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            emit(BleFrame(BleFrame.Direction.INBOUND, value, System.currentTimeMillis()))
        }

        @Deprecated("Superseded by the ByteArray overload on API 33+")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            val v = characteristic.value ?: return
            emit(BleFrame(BleFrame.Direction.INBOUND, v, System.currentTimeMillis()))
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU=$mtu status=$status")
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            Log.d(TAG, "descriptor write status=$status")
            // One CCCD write at a time: firing the next while one is pending
            // makes the stack refuse it, so the queue advances here.
            writeNextCccd(g)
        }
    }

    /** Pending CCCD writes, drained one per [onDescriptorWrite]. Guarded because
     * the callback arrives on a binder thread, not the caller's. */
    private val cccdQueue = ArrayDeque<BluetoothGattDescriptor>()
    private val cccdLock = Any()

    @SuppressLint("MissingPermission")
    private fun writeNextCccd(g: BluetoothGatt) {
        val next = synchronized(cccdLock) {
            if (cccdQueue.isNotEmpty()) cccdQueue.removeFirst() else null
        } ?: return
        // The two writeDescriptor overloads differ in return type: the API-33 one
        // returns an Int status code, the deprecated one a Boolean. An
        // if/else mixing them infers `Comparable & Serializable`, so
        // normalise both to Boolean here.
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                g.writeDescriptor(next, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothGatt.GATT_SUCCESS
            } catch (e: SecurityException) {
                Log.e(TAG, "CCCD write refused: ${e.message}")
                false
            }
        } else {
            @Suppress("DEPRECATION")
            next.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            try {
                @Suppress("DEPRECATION")
                g.writeDescriptor(next)
            } catch (e: SecurityException) {
                Log.e(TAG, "CCCD write refused: ${e.message}")
                false
            }
        }
        if (!ok) {
            Log.w(TAG, "CCCD write refused; continuing with the rest")
            writeNextCccd(g)
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverServices(g: BluetoothGatt) {
        if (!g.discoverServices()) Log.w(TAG, "discoverServices refused")
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(g: BluetoothGatt, svc: android.bluetooth.BluetoothGattService) {
        val pending = ArrayList<BluetoothGattDescriptor>()
        for (uuid in listOf(Gatt.READ, Gatt.DEVICE_INFO)) {
            val ch = svc.getCharacteristic(UUID.fromString(uuid)) ?: continue
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD)
            // Parentheses are required: `and` is an infix *function*, not a bitwise
            // operator, so `props and FLAG != 0` does not parse as intended.
            val canNotify = (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
            if (cccd != null && canNotify) pending += cccd
            else Log.d(TAG, "no notifiable CCCD for $uuid")
        }
        if (pending.isEmpty()) return
        synchronized(cccdLock) {
            cccdQueue.clear()
            cccdQueue.addAll(pending)
        }
        writeNextCccd(g)
    }

    companion object {
        private const val TAG = "ScooterBle"
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * Cheap structural check for an inbound frame, model-agnostic.
         *
         * **Deliberately does not verify a checksum.** Decompiling the official
         * builders showed that the outbound mobile-data and navigation frames
         * carry none, and several inbound layouts (`ApacheBasicData`,
         * `NtorqWifiPasswordFrameData`) declare `CHECKSUM` at index 18 or 19
         * while others declare none. A checksum check here would reject valid
         * frames.
         *
         * Model-specific validation belongs in each [dev.tvsopen.protocol.ProtocolDriver],
         * which knows its own layout.
         */
        fun looksValid(bytes: ByteArray): Boolean {
            if (bytes.size < 3) return false
            val start = bytes[0].toInt() and 0xFF
            return start == 0x5A || start == 0x5B || start == 0x9A || start == 0x9B
        }

        /** Wrap a UUID string for scanning filters. */
        fun parcelUuid(uuid: String): ParcelUuid = ParcelUuid(UUID.fromString(uuid))
    }
}