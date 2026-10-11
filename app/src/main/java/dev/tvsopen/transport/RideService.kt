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
import dev.tvsopen.nav.MapsNotificationListener
import dev.tvsopen.nav.NavigationBridge
import dev.tvsopen.protocol.DriverRegistry
import dev.tvsopen.protocol.NtorqDriver
import dev.tvsopen.protocol.VehicleModel
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
        startForeground(NOTIF_ID, buildNotification("Starting…"))
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
        val transport = ble ?: ScooterBleService(applicationContext).also { ble = it }
        val driver = when (model) {
            VehicleModel.NTORQ -> NtorqDriver()
            else -> DriverRegistry.forModel(model)
        }
        val b = NavigationBridge(transport, scope, driver)
        bridge = b

        scope.launch {
            transport.frames.collect {
                b.onInstruction(MapsNotificationListenerRef.current?.instruction?.value)
            }
        }
        transport.connect(address)
        b.startKeepalive {
            dev.tvsopen.protocol.DashState(
                timeMillis = System.currentTimeMillis(),
                batteryBars = 4,
                signalBars = 3,
            )
        }
        notify("Connected to ${model.displayName}")
    }

    private fun teardown() {
        bridge?.stopKeepalive()
        bridge?.stopNavigation()
        ble?.disconnect()
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
            this, 0, Intent(this, Class.forName("dev.tvsopen.ui.MainActivity")::class.java.name).let {
                Intent(this, dev.tvsopen.ui.MainActivity::class.java)
            },
            PendingIntent.FLAG_IMMUTABLE,
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