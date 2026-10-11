package dev.tvsopen.transport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.tvsopen.R
import dev.tvsopen.log.Log
import dev.tvsopen.nav.MapsNotificationListener
import dev.tvsopen.nav.NavigationBridge
import dev.tvsopen.protocol.DashState
import dev.tvsopen.protocol.DriverRegistry
import dev.tvsopen.protocol.NtorqDriver
import dev.tvsopen.protocol.VehicleModel
import dev.tvsopen.session.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the BLE link and the HUD alive for as long as the session runs.
 *
 * A foreground service, because a ride outlasts any background execution
 * window and the cluster drops the link the moment the presence packet stops.
 */
class RideService : android.app.Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bridge: NavigationBridge? = null
    private var ble: ScooterBleService? = null

    override fun onCreate() {
        super.onCreate()
        // Logs go to the app's own files so a crash can still be diagnosed.
        Log.enableFileSink(java.io.File(filesDir, "logs"))
        Log.i(TAG, "service created (sdk ${android.os.Build.VERSION.SDK_INT})")

        // A `connectedDevice` foreground service needs BLUETOOTH_CONNECT or
        // BLUETOOTH_SCAN to be *granted*, not merely declared. Starting it from
        // a notification tap before the permission dialog is answered throws
        // SecurityException and takes the process down, so degrade instead:
        // post a plain notification and retry on ACTION_CONNECT.
        try {
            startForeground(NOTIF_ID, buildNotification("Starting…"))
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot enter foreground yet: ${e.message}")
            Log.w(TAG, "BLUETOOTH_CONNECT/SCAN not granted; retry after connect")
            val nm = getSystemService(NotificationManager::class.java)
            runCatching {
                nm?.notify(
                    NOTIF_ID,
                    buildNotification("Grant Bluetooth permission to connect"),
                )
            }
        }
    }

    /** Called once the activity has the runtime BLE permissions. */
    private fun enterForeground() {
        runCatching { startForeground(NOTIF_ID, buildNotification("Connecting…")) }
            .onFailure { Log.w(TAG, "foreground still refused: ${it.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FIND_MY -> bridge?.setFindMyScooter(true)
            ACTION_STOP_FIND_MY -> bridge?.setFindMyScooter(false)
            ACTION_START_NAV -> bridge?.startNavigation()
            ACTION_STOP_NAV -> bridge?.stopNavigation()
            ACTION_DISCONNECT -> {
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> Unit
        }
        return START_STICKY
    }

    /** Connect and begin streaming. Called from the UI once a scooter is picked. */
    fun connect(address: String, model: VehicleModel) {
        // By now the permissions the service was refused at startup have been
        // granted, so the foreground notification can go up properly.
        enterForeground()
        val transport = ble ?: ScooterBleService(applicationContext).also { ble = it }
        val driver = when (model) {
            VehicleModel.NTORQ -> NtorqDriver()
            else -> DriverRegistry.forModel(model)
        }
        val b = NavigationBridge(transport, scope, driver)
        bridge = b

        Session.onConnection(ConnectionState.CONNECTING, model, address)
        Log.i(TAG, "connecting to ${model.displayName} at $address")

        // Two independent collectors: one for the bridge (nav state), one for
        // decoding telemetry into the UI session.
        scope.launch {
            transport.frames.collect { frame ->
                b.onInstruction(MapsNotificationListenerRef.current?.instruction?.value)
            }
        }
        scope.launch {
            transport.frames.collect { frame ->
                if (frame.direction == BleFrame.Direction.INBOUND) {
                    driver.decode(frame.bytes)?.let { Session.onFrame(it) }
                }
            }
        }
        scope.launch {
            transport.state.collect { st ->
                Session.onConnection(st, model, address)
                Log.d(TAG, "state -> $st")
                if (st == ConnectionState.CONNECTED) {
                    b.startKeepalive {
                        DashState(
                            timeMillis = System.currentTimeMillis(),
                            batteryBars = batteryBars(),
                            signalBars = 4,
                        )
                    }
                }
            }
        }

        transport.connect(address)
        notify("Connected to ${model.displayName}")
    }

    /** Coarse phone battery as 0-5 bars, for the cluster ping packet. */
    @Suppress("DEPRECATION")
    private fun batteryBars(): Int {
        val pct = android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY
        val level = getSystemService(android.os.BatteryManager::class.java)
            ?.getIntProperty(pct) ?: -1
        if (level < 0) return 0
        return (level / 20).coerceIn(0, 5)
    }

    private fun teardown() {
        bridge?.stopKeepalive()
        bridge?.stopNavigation()
        // ScooterBleService.disconnect() is suspend so it can take the write
        // mutex. Launch it in the service scope rather than blocking onDestroy.
        val transport = ble
        if (transport != null) {
            scope.launch { transport.disconnect() }
        }
        bridge = null
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun notify(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Ride", NotificationManager.IMPORTANCE_LOW),
            )
        }
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, dev.tvsopen.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("TVSRide")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "RideService"
        const val CHANNEL = "ride"
        const val NOTIF_ID = 1
        const val ACTION_FIND_MY = "dev.tvsopen.FIND_MY"
        const val ACTION_STOP_FIND_MY = "dev.tvsopen.STOP_FIND_MY"
        const val ACTION_START_NAV = "dev.tvsopen.START_NAV"
        const val ACTION_STOP_NAV = "dev.tvsopen.STOP_NAV"
        const val ACTION_DISCONNECT = "dev.tvsopen.DISCONNECT"
    }
}

/** Lets the service reach the listener without leaking it through the Intent. */
object MapsNotificationListenerRef {
    @Volatile
    var current: MapsNotificationListener? = null
}