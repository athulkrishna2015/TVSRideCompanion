package dev.tvsopen.transport

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.tvsopen.session.ParkedInfo
import java.util.Locale
import dev.tvsopen.R
import dev.tvsopen.log.Log
import dev.tvsopen.nav.NavInstruction
import dev.tvsopen.nav.NavigationBridge
import dev.tvsopen.prefs.RidePrefs
import dev.tvsopen.protocol.Command
import dev.tvsopen.protocol.DashState
import dev.tvsopen.protocol.DriverRegistry
import dev.tvsopen.protocol.NtorqDriver
import dev.tvsopen.protocol.OutboundId
import dev.tvsopen.protocol.PacketBuilder
import dev.tvsopen.protocol.ProtocolDriver
import dev.tvsopen.protocol.VehicleModel
import dev.tvsopen.session.ClusterAlert
import dev.tvsopen.session.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
    /** Driver for the current link; needed outside connect() by the rider-name send. */
    private var driver: ProtocolDriver? = null
    /** Replays cluster media keys and call-switch presses on the phone. */
    private var mediaKeys: MediaKeys? = null
    private var callButtons: CallButtons? = null
    /**
     * Last call-switch value seen on 0x10 byte 12.
     *
     * The cluster repeats the switch inside a ~50 Hz stream, so acting on every
     * frame would answer and hang up twenty times in half a second. Only a
     * change from 0 to a command value is a press.
     */
    private var lastCallSwitch = 0
    /**
     * Unexpected-drop recovery.
     *
     * The cluster drops the link on its own schedule (ignition, range, a stack
     * hiccup) and never reconnects to us. The official app retries with a
     * 5/10/20/40/60-second backoff, capped at ten attempts, and only then
     * gives up. Without it a ride that starts with one drop is over.
     */
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    /** Set by [teardown] so a deliberate disconnect does not trigger a retry. */
    private var tearingDown = false
    /**
     * Held while connected, released on disconnect and on teardown.
     *
     * A foreground service alone does not keep the CPU awake: with the screen
     * off the coroutines driving the 2 s keepalive and 1.5 s nav control stall,
     * the cluster times out the HUD (the control frame is a liveness signal),
     * and then the link. The Doze exemption keeps the system from killing us;
     * this keeps us actually running. Partial, not full — the screen stays off.
     */
    private var linkWakeLock: android.os.PowerManager.WakeLock? = null

    /** True after a CONNECTED state, so a disconnect records where the scooter was left. */
    private var hadLiveLink = false

    private fun holdWakeLock() {
        if (linkWakeLock?.isHeld == true) return
        val pm = getSystemService(android.os.PowerManager::class.java) ?: return
        linkWakeLock = pm.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK, "TVSRide:link",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.i(TAG, "wake lock held for link lifetime")
    }

    private fun releaseWakeLock() {
        runCatching { if (linkWakeLock?.isHeld == true) linkWakeLock?.release() }
        linkWakeLock = null
    }

    override fun onCreate() {
        super.onCreate()
        // Logs go to the app's own files so a crash can still be diagnosed.
        Log.enableFileSink(java.io.File(filesDir, "logs"))
        Log.i(TAG, "service created (sdk ${android.os.Build.VERSION.SDK_INT})")

        // A `connectedDevice` foreground service needs BLUETOOTH_CONNECT or
        // BLUETOOTH_SCAN to be *granted*, not merely declared. Starting it
        // before the permission dialog is answered throws SecurityException
        // here, so degrade instead of throwing.
        //
        // Note this catch only covers the synchronous SecurityException. A
        // rejected *notification* is reported asynchronously by the system as a
        // RemoteServiceException and cannot be caught here — that is why
        // `buildNotification` creates the channel itself.
        try {
            startForeground(NOTIF_ID, buildNotification("Starting…"))
        } catch (e: SecurityException) {
            Log.e(TAG, "cannot enter foreground: ${e.message}")
            Log.e(TAG, "BLUETOOTH_CONNECT/SCAN not granted; stopping rather than idling")
            // Stopping is the point. If we keep running after a refused
            // startForeground, Android kills the whole app ~10s later with
            // ForegroundServiceDidNotStartInTimeException — a crash that blames
            // whichever Compose handler happened to start the service, rather
            // than the missing permission. MainActivity checks first, so
            // reaching this means we were started some other way.
            stopSelf()
            return
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
            ACTION_UPDATE_RIDER_NAME -> sendRiderNameIfConnected()
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
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        tearingDown = false
        bridge?.stopKeepalive()
        bridge?.stopNavigation()

        // By now the permissions the service was refused at startup have been
        // granted, so the foreground notification can go up properly.
        enterForeground()
        val transport = ble ?: ScooterBleService(applicationContext).also { ble = it }
        // Driven by `sharesNtorqProtocol()`, not by listing models here. This
        // used to special-case `VehicleModel.NTORQ` and defer everything else to
        // the registry, which is a second list that has to agree with the one
        // `Nucleus` registers from — and it silently did not when the Ntorq 125
        // Race was added, because a miss here means the generic profile and a
        // dashboard full of `—` rather than an error. One source of truth.
        val driver = if (model.sharesNtorqProtocol()) {
            NtorqDriver()
        } else {
            DriverRegistry.forModel(model)
        }
        val b = NavigationBridge(transport, scope, driver)
        bridge = b
        this.driver = driver
        mediaKeys = mediaKeys ?: MediaKeys(applicationContext)
        callButtons = callButtons ?: CallButtons(applicationContext)
        lastCallSwitch = 0
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
            // The three causes need saying apart. "link already active or
            // address rejected" was two unrelated failures in one string, and
            // the dead-link one — a GATT client left over from a previous
            // session — looked like a transient no-op rather than the reason the
            // app could not reconnect all day.
            val alreadyUp = Session.connection.value.state == ConnectionState.CONNECTED
            val reason = when {
                transport.adapter == null -> "no Bluetooth adapter"
                alreadyUp -> "already connected"
                else -> "address rejected, or a stale GATT client is still held"
            }
            Log.w(TAG, "connect not started: $reason")
            notify("Could not start connection ($reason)")
            Session.onConnection(ConnectionState.DISCONNECTED, model, address)
            return false
        }

        linkJob = scope.launch {
            // The link owns a wake lock while connected; without it the
            // keepalive and nav loops stall the moment the screen goes off.
            // Held on CONNECTED and released on DISCONNECTED rather than for
            // the whole job: after a drop the collectors stay alive waiting
            // for a reconnect, and holding a lock through that idle wait would
            // drain the battery for nothing.
            // One for the bridge (nav state)…
            //
            // Driven by `Session.hud`, not by BLE frames. It used to be a
            // `transport.frames.collect` that re-read the instruction on every
            // frame through `MapsNotificationListenerRef` — and that ref was
            // never assigned anywhere, so the bridge was handed `null` every
            // time. Navigation mirroring therefore sent **nothing at all**, and
            // looked fine because the UI strip reads `Session.hud` itself and
            // never went through the bridge. Sampling navigation on the BLE
            // clock was wrong twice over: it couples two unrelated rates, and it
            // means a nav step waits for the next frame to be forwarded.
            launch {
                Session.hud.collect { hud ->
                    b.onInstruction(
                        NavInstruction(
                            title = hud.instructionTitle.orEmpty(),
                            street = hud.street.orEmpty(),
                            distanceMeters = hud.distanceMeters,
                            etaMinutes = hud.etaMinutes,
                            pictogram = hud.pictogramId,
                            provider = hud.provider.orEmpty(),
                        ).takeIf { hud.active },
                    )
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
                        val decoded = driver.decode(frame.bytes)
                        if (decoded != null) {
                            Session.onFrame(decoded)
                            handleClusterButton(driver, decoded)
                        }
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
                            reconnectAttempt = 0
                            hadLiveLink = true
                            notify("Connected to ${model.displayName}")
                            holdWakeLock()
                            // Auto-start here, not only from the activity's
                            // LaunchedEffect: if the UI process dies while the
                            // screen is off, nothing else sends START_NAV and
                            // the mirror silently stays down. startNavigation
                            // early-returns when already running, and the
                            // toggle's explicit STOP_NAV still wins — the next
                            // onInstruction(null) clears it.
                            if (RidePrefs.load(this@RideService).navigationMirroring) {
                                b.startNavigation()
                            }
                            // Greeting first, like the official sendInitialData:
                            // the cluster shows it on ignition, and a name saved
                            // while disconnected goes out on the next connect.
                            sendRiderNameIfConnected()
                            if (driver.capabilities.keepalive) {
                                b.startKeepalive {
                                    DashState(
                                        timeMillis = System.currentTimeMillis(),
                                        batteryBars = batteryBars(),
                                        signalBars = 4,
                                    )
                                }
                            }
                        }
                        ConnectionState.DISCONNECTED -> {
                            releaseWakeLock()
                            recordParkedIfPossible()
                            notify("Disconnected")
                            scheduleReconnect(address, model)
                        }
                        else -> Unit
                    }
                }
            }
            launch {
                var lastAlert: ClusterAlert? = null
                Session.alert.collect { alert ->
                    val t = ble ?: return@collect
                    if (t.state.value != ConnectionState.CONNECTED) {
                        lastAlert = alert
                        return@collect
                    }
                    if (alert != null) {
                        sendAlert(driver, t, alert)
                    } else if (lastAlert?.kind == ClusterAlert.Kind.CALL) {
                        sendCallEnded(driver, t)
                    }
                    lastAlert = alert
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

    /**
     * Send the saved rider name when there is a live link, else just remember it.
     *
     * A blank name is not "send an empty greeting" — the cluster shows
     * `HELLO RIDER` in that state, so there is nothing to write. A save made
     * while disconnected goes out on the next connect instead, which is why
     * this never warns: no link is the normal case, not a fault.
     */
    private fun sendRiderNameIfConnected() {
        val name = RidePrefs.load(this).riderName
        val transport = ble
        val drv = driver
        if (name.isNullOrBlank() || transport == null || drv == null) return
        if (transport.state.value != ConnectionState.CONNECTED) {
            Log.i(TAG, "rider name saved; will send on next connection")
            return
        }
        val frame = drv.buildRiderName(name) ?: return
        scope.launch {
            if (transport.write(frame)) Log.i(TAG, "rider name sent (${name.length} chars)")
        }
    }

    private suspend fun sendAlert(
        driver: ProtocolDriver,
        transport: ScooterBleService,
        alert: ClusterAlert,
    ) {
        when (alert.kind) {
            ClusterAlert.Kind.CALL -> {
                val number = alert.subtitle.substringAfterLast('·').trim().ifBlank { alert.subtitle }
                val name = alert.title.takeIf { it.isNotBlank() && it != number }
                val frames = driver.buildCallerFrames(number, null, name)
                if (frames.isNotEmpty()) {
                    Log.i(TAG, "cluster call alert: '$name' '$number' (${frames.size} frames)")
                    frames.forEach { transport.write(it) }
                } else {
                    // Generic fallback: a plain text row with the caller.
                    val text = if (name != null) "$name $number" else number.ifBlank { alert.title }
                    transport.write(PacketBuilder.textRow(OutboundId.CALLER, text))
                }
            }
            ClusterAlert.Kind.MESSAGE -> {
                val text = alert.title.take(17)
                Log.i(TAG, "cluster message alert: '$text'")
                val frame = driver.buildSms(text) ?: PacketBuilder.textRow(OutboundId.SMS, text)
                transport.write(frame)
            }
        }
    }

    private suspend fun sendCallEnded(driver: ProtocolDriver, transport: ScooterBleService) {
        // Best-effort clear: send an idle/ended caller frame where the driver
        // supports it. The cluster typically times the display out on its own.
        val frame = driver.buildCallerInfo("", null, Command.CALL_IDLE)
        if (frame != null) {
            Log.i(TAG, "cluster call alert cleared")
            transport.write(frame)
        }
    }

    private fun recordParkedIfPossible() {
        if (!hadLiveLink) return
        hadLiveLink = false
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            Log.i(TAG, "parked spot not recorded: no location permission")
            return
        }
        val lm = getSystemService(LocationManager::class.java) ?: return
        val loc = bestLastLocation(lm) ?: run {
            Log.i(TAG, "parked spot not recorded: no recent location fix")
            return
        }
        val label = String.format(Locale.US, "%.5f, %.5f", loc.latitude, loc.longitude)
        val info = ParkedInfo(System.currentTimeMillis(), label, null)
        RidePrefs.load(this).saveParked(info.atMillis, info.label)
        Session.onParked(info)
        Log.i(TAG, "parked spot saved: $label")
    }

    @SuppressLint("MissingPermission")
    private fun bestLastLocation(lm: LocationManager): Location? {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        var best: Location? = null
        for (provider in providers) {
            val loc = runCatching {
                val enabled = provider == LocationManager.PASSIVE_PROVIDER || lm.isProviderEnabled(provider)
                if (enabled) lm.getLastKnownLocation(provider) else null
            }.getOrNull() ?: continue
            if (best == null || loc.time > best!!.time) best = loc
        }
        return best
    }

    /**
     * Act on the two inbound channels the cluster uses as a remote control.
     *
     * `0x6B` carries media commands on both families (`0x54` is the ACK/doc
     * channel, not a command). `0x10` byte 12 carries the call switch on the
     * Jupiter; on the Ntorq the same offset is transcribed as a location tag,
     * so the call path is gated on the driver's capability rather than on the
     * id alone.
     */
    private fun handleClusterButton(driver: ProtocolDriver, frame: dev.tvsopen.protocol.DecodedFrame) {
        if (frame.dataId == 0x6B && driver.capabilities.musicKeys) {
            val cmd = frame.field(dev.tvsopen.protocol.Field.MUSIC_COMMAND)?.toInt() ?: return
            mediaKeys?.handle(cmd)
            return
        }
        if (frame.dataId == 0x10 && driver.capabilities.callButtons) {
            val sw = frame.field(dev.tvsopen.protocol.Field.SWITCH_STATUS)?.toInt() ?: 0
            // Edge-trigger: 0 means "no command", 1..4 is a press. The stream
            // repeats the same value for tens of frames, so act only on the
            // transition into a command.
            if (sw in 1..4 && sw != lastCallSwitch) {
                callButtons?.handle(sw)
            }
            lastCallSwitch = sw
        }
    }

    /**
     * Retry an unexpected drop with the official backoff.
     *
     * Launched on the service scope, not on `linkJob`: [connect] cancels
     * `linkJob`, so a retry launched inside it would cancel itself the moment
     * it fired. Ten attempts then stop; a deliberate disconnect never reaches
     * here because [teardown] sets [tearingDown].
     */
    private fun scheduleReconnect(address: String, model: VehicleModel) {
        if (tearingDown) return
        if (reconnectJob?.isActive == true) return
        if (reconnectAttempt >= RECONNECT_DELAYS_MS.size) {
            Log.w(TAG, "giving up after ${RECONNECT_DELAYS_MS.size} reconnect attempts")
            return
        }
        val attempt = reconnectAttempt
        reconnectAttempt++
        val wait = RECONNECT_DELAYS_MS[attempt]
        Log.i(TAG, "unexpected drop; reconnect ${attempt + 1}/${RECONNECT_DELAYS_MS.size} in ${wait}ms")
        reconnectJob = scope.launch {
            delay(wait)
            if (ble?.state?.value == ConnectionState.CONNECTED) return@launch
            connect(address, model)
        }
    }

    private fun teardown() {
        tearingDown = true
        reconnectJob?.cancel()
        reconnectJob = null
        recordParkedIfPossible()
        linkJob?.cancel()
        linkJob = null
        bridge?.stopKeepalive()
        bridge?.stopNavigation()
        releaseWakeLock()
        driver = null
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

    /**
     * Create the channel if it is missing.
     *
     * This has to be callable from `buildNotification`, not just from `notify`.
     * `startForeground` posts to the channel too, and posting to a channel that
     * does not exist yet fails as
     * `CannotPostForegroundServiceNotificationException: Bad notification for
     * startForeground` — an exception the *system* posts back to the app's main
     * thread, so the `try`/`catch` around `startForeground` cannot catch it and
     * the process dies. That is exactly what happened: the channel was only
     * created as a side effect of `notify`, which nothing reached because no
     * connection had ever completed, so the first `startForeground` in
     * `onCreate` always faced a channel that had never been created.
     */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ride", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notify(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        // Before the builder, not after: an FGS notification is a real post.
        ensureChannel()
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
        const val ACTION_UPDATE_RIDER_NAME = "dev.tvsopen.UPDATE_RIDER_NAME"
        const val ACTION_DISCONNECT = "dev.tvsopen.DISCONNECT"

        const val EXTRA_ADDRESS = "address"
        const val EXTRA_MODEL = "model"

        /**
         * Official reconnect backoff, seconds: 5, 10, 20, 40, then 60 for the
         * remaining attempts. Indexed by attempt number.
         */
        private val RECONNECT_DELAYS_MS = longArrayOf(
            5_000, 10_000, 20_000, 40_000,
            60_000, 60_000, 60_000, 60_000, 60_000, 60_000,
        )
    }
}

/**
 * Removed. It was the reason navigation mirroring never sent a frame: the
 * bridge read `current` on every BLE frame, and nothing ever assigned it, so
 * the instruction was always null. The bridge now collects `Session.hud`, which
 * is the same data the listener already writes.
 */
