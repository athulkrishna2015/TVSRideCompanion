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
import android.util.Log
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
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

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
            gatt?.run {
                disconnect()
                close()
            }
            gatt = null
            writeChar = null
        }
        _state.value = ConnectionState.DISCONNECTED
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
            Log.d(TAG, "write dropped, not connected")
            return@withLock false
        }
        // Explicit <Boolean>: the lambda's last expression is a conditional on
        // `cont.resume(...)` which returns Unit, so inference would pick the
        // wrong T and `ok` would be typed as the continuation result itself.
        val ok = suspendCancellableCoroutine<Boolean> { cont ->
            // writeCharacteristic returns an Int status, not a Boolean; compare against
            // GATT_SUCCESS so the try/catch produces a real Boolean. This
            // reports whether the write was *accepted* — the on-wire result
            // arrives later in onCharacteristicWrite.
            val result = try {
                g.writeCharacteristic(
                    ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                ) == BluetoothGatt.GATT_SUCCESS
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
            emit(BleFrame(BleFrame.Direction.OUTBOUND, bytes, System.currentTimeMillis()))
        }
        ok
    }

    private fun emit(frame: BleFrame) {
        _lastFrameAt.value = frame.atMillis
        _frames.tryEmit(frame)
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
                    writeChar = null
                    _state.value = ConnectionState.DISCONNECTED
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
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverServices(g: BluetoothGatt) {
        if (!g.discoverServices()) Log.w(TAG, "discoverServices refused")
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(g: BluetoothGatt, svc: android.bluetooth.BluetoothGattService) {
        for (uuid in listOf(Gatt.READ, Gatt.DEVICE_INFO)) {
            val ch = svc.getCharacteristic(UUID.fromString(uuid)) ?: continue
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD)
            // Parentheses are required: `and` is an infix *function*, not a bitwise
            // operator, so `props and FLAG != 0` does not parse as intended.
            val canNotify = (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
            if (cccd != null && canNotify) {
                // The two writeDescriptor overloads differ in return type: the API-33 one
                // returns an Int status code, the deprecated one a Boolean. An
                // if/else mixing them infers `Comparable & Serializable`, so
                // normalise both to Boolean here.
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                        BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
                if (!ok) Log.w(TAG, "CCCD write refused for $uuid")
            }
        }
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