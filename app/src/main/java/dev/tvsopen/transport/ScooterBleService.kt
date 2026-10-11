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
import dev.tvsopen.protocol.Checksum
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
import kotlin.coroutines.resume

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
    fun disconnect() {
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
        val ok = suspendCancellableCoroutine { cont ->
            val result = try {
                g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } catch (e: SecurityException) {
                Log.e(TAG, "write refused: ${e.message}")
                false
            }
            if (cont.isActive) cont.resume(result)
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
            if (cccd != null && ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                @Suppress("DEPRECATION")
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
                if (!ok) Log.w(TAG, "CCCD write refused for $uuid")
            }
        }
    }

    companion object {
        private const val TAG = "ScooterBle"
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Validate an inbound frame's structure without knowing its model. */
        fun looksValid(bytes: ByteArray): Boolean {
            if (bytes.size < 3) return false
            val start = bytes[0].toInt() and 0xFF
            if (start != 0x5A && start != 0x5B && start != 0x9A && start != 0x9B) return false
            if (bytes.size >= 20) {
                val expected = Checksum.compute(bytes, 18)
                if (bytes[18] != expected) return false
            }
            return true
        }

        /** Wrap a UUID string for scanning filters. */
        fun parcelUuid(uuid: String): ParcelUuid = ParcelUuid(UUID.fromString(uuid))
    }
}