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
import dev.tvsopen.prefs.RidePrefs
import dev.tvsopen.protocol.DashState
import dev.tvsopen.protocol.DriverRegistry
import dev.tvsopen.protocol.NtorqDriver
import dev.tvsopen.protocol.VehicleModel
import dev.tvsopen.session.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /** Parents the per-connection collectors so a reconnect or disconnect
     * cancels them. Without this every connect() added three more collectors
     * on the same flows, and a stale one could restart the *old* bridge's
     * keepalive after it was stopped — duplicate pings. */
    private var linkJob: Job? = null
    private var lastModel: VehicleModel = VehicleModel.NTORQ
    private var lastAddress: String? = null

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
            ACTION_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                if (address.isNullOrBlank()) {
                    Log.w(TAG, "connect requested without an address; ignoring")
                } else {
                    val model = intent.getStringExtra(EXTRA_MODEL)
                        ?.let { runCatching { VehicleModel.valueOf(it) }.getOrNull() }
                        ?: VehicleModel.NTORQ
                    connect(address, model)
                }
            }
            ACTION_FIND_MY -> bridge?.setFindMyScooter(true)
            ACTION_STOP_FIND_MY -> bridge?.setFindMyScooter(false)
            ACTION_START_NAV -> bridge?.startNavigation()
            ACTION_STOP_NAV -> bridge?.stopNavigation()
            ACTION_DISCONNECT -> {
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }
            null -> Unit
            // START_STICKY delivers a null intent after the process is killed.
            // Falling through leaves the service alive and foregrounded with no
            // link, which looks connected on the dash and never recovers. Re-open
            // the last known cluster instead; harmless when there is none.
            else -> resumeLastConnection()
        }
        return START_STICKY
    }

    /**
     * Re-open the last link, if one is remembered and ride mode is still on.
     *
     * This is what makes `START_STICKY` and `RECEIVE_BOOT_COMPLETED` mean
     * something: both start this service without an address, and without this
     * the app would come back to life doing nothing.
     */
    private fun resumeLastConnection() {
        val prefs = RidePrefs.load(this)
        val address = prefs.lastAddress
        if (address.isNullOrBlank()) {
            Log.i(TAG, "sticky restart with no remembered cluster; idling")
            return
        }
        if (!prefs.resumeOnBoot && ble?.let { it.state.value == ConnectionState.CONNECTED } != true) {
            // Only resume automatically when the user opted in; otherwise stay
            // idle rather than silently reattaching to their scooter.
            Log.i(TAG, "sticky restart; not resuming (resume-on-boot is off)")
            return
        }
        val model = prefs.lastModelId
            ?.let { runCatching { VehicleModel.valueOf(it) }.getOrNull() }
            ?: VehicleModel.NTORQ
        Log.i(TAG, "sticky restart; resuming $address")
        connect(address, model)
    }

    /**
     * Connect and begin streaming. Called from the UI once a scooter is picked.
     *
     * Returns false when the link could not even be started (no adapter, bad
     * address, missing permission) — the caller gets "not started" rather than
     * silence, because `transport.connect` itself only returns a Boolean and a
     * SecurityException from `connectGatt` would otherwise kill the service.
     */
    fun connect(address: String, model: VehicleModel): Boolean {
        // Stop the previous link first: same scope, so a second connect without
        // this leaves both keepalive loops running and the cluster gets double
        // pings.
        linkJob?.cancel()
        linkJob = null
        bridge?.stopKeepalive()
        bridge?.stopNavigation()

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
        lastModel = model
        lastAddress = address
        RidePrefs.load(this).updateLastConnection(address, model.name)

        Session.onConnection(ConnectionState.CONNECTING, model, address)
        Log.i(TAG, "connecting to ${model.displayName} at $address")
        notify("Connecting to ${model.displayName}…")

        val started = try {
            transport.connect(address)
        } catch (e: SecurityException) {
            Log.e(TAG, "connect refused (permission?): ${e.message}")
            false
        }
        if (!started) {
            val reason = if (transport.adapter == null) "no Bluetooth adapter"
            else "link already active or address rejected"
            Log.w(TAG, "connect not started: $reason")
            notify("Could not start connection ($reason)")
            Session.onConnection(ConnectionState.DISCONNECTED, model, address)
            return false
        }

        linkJob = scope.launch {
            // One for the bridge (nav state)…
            launch {
                transport.frames.collect { frame ->
                    b.onInstruction(MapsNotificationListenerRef.current?.instruction?.value)
                }
            }
            // …one decoding telemetry into the UI session, answering the
            // cluster's handshake when it challenges…
            launch {
                transport.frames.collect { frame ->
                    if (frame.direction != BleFrame.Direction.INBOUND) return@collect
                    val response = driver.buildChallengeResponse(frame.bytes)
                    if (response != null) {
                        Log.i(TAG, "answering cluster challenge")
                        transport.write(response)
                    } else {
                        driver.decode(frame.bytes)?.let { Session.onFrame(it) }
                    }
                }
            }
            // …and one following the link state. The bridge keeps its own HUD
            // counters, so frames-sent and errors are mirrored into the Session
            // the UI actually reads — otherwise "Hud frames sent" stays 0
            // forever while the bridge counts privately.
            launch {
                transport.state.collect { st ->
                    Session.onConnection(st, model, address)
                    Log.d(TAG, "state -> $st")
                    when (st) {
                        ConnectionState.CONNECTED -> {
                            notify("Connected to ${model.displayName}")
                            b.startKeepalive {
                                DashState(
                                    timeMillis = System.currentTimeMillis(),
                                    batteryBars = batteryBars(),
                                    signalBars = 4,
                                )
                            }
                        }
                        ConnectionState.DISCONNECTED ->
                            notify("Disconnected")
                        else -> Unit
                    }
                }
            }
            launch {
                b.hud.collect { h ->
                    Session.onHud { it.copy(framesSent = h.framesSent, lastError = h.lastError) }
                }
            }
        }
        return true
    }

    /** Coarse phone battery as 0-5 bars, for the cluster ping packet. */
    private fun batteryBars(): Int {
        val pct = android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY
        val level = getSystemService(android.os.BatteryManager::class.java)
            ?.getIntProperty(pct) ?: -1
        if (level < 0) return 0
        return (level / 20).coerceIn(0, 5)
    }

    private fun teardown() {
        linkJob?.cancel()
        linkJob = null
        bridge?.stopKeepalive()
        bridge?.stopNavigation()
        // The state collector is gone with linkJob, so publish the disconnect
        // explicitly — otherwise the UI keeps showing the old link as live.
        Session.onConnection(ConnectionState.DISCONNECTED, lastModel, lastAddress)
        // ScooterBleService.disconnect() is suspend so it can take the write
        // mutex. NonCancellable, because onDestroy cancels the scope right
        // after this and a plain child would die before the GATT client closed.
        val transport = ble
        if (transport != null) {
            scope.launch(NonCancellable) {
                runCatching { transport.disconnect() }
                    .onFailure { Log.w(TAG, "disconnect failed: ${it.message}") }
            }
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
        const val ACTION_CONNECT = "dev.tvsopen.CONNECT"
        const val ACTION_FIND_MY = "dev.tvsopen.FIND_MY"
        const val ACTION_STOP_FIND_MY = "dev.tvsopen.STOP_FIND_MY"
        const val ACTION_START_NAV = "dev.tvsopen.START_NAV"
        const val ACTION_STOP_NAV = "dev.tvsopen.STOP_NAV"
        const val ACTION_DISCONNECT = "dev.tvsopen.DISCONNECT"

        const val EXTRA_ADDRESS = "address"
        const val EXTRA_MODEL = "model"
    }
}

/** Lets the service reach the listener without leaking it through the Intent. */
object MapsNotificationListenerRef {
    @Volatile
    var current: MapsNotificationListener? = null
}