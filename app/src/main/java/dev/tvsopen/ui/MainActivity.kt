package dev.tvsopen.ui

import android.Manifest
import android.bluetooth.le.ScanFilter
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tvsopen.Nucleus
import dev.tvsopen.log.Log
import dev.tvsopen.prefs.RidePrefs
import dev.tvsopen.protocol.VehicleModel
import dev.tvsopen.session.Session
import dev.tvsopen.transport.ConnectionState
import dev.tvsopen.transport.RideService
import dev.tvsopen.transport.ScooterPairing
import dev.tvsopen.transport.ScooterScanner
import dev.tvsopen.ui.theme.ThemePrefs
import dev.tvsopen.ui.theme.TVSRideTheme
import java.util.Locale

/**
 * Single-activity host: bottom navigation between Home and More.
 *
 * Deliberately thin. The protocol layer is the substance; the UI observes state
 * and sends intents to [RideService]. There is no account, no repository and no
 * network client, because there is no network client in the app at all.
 */
class MainActivity : AppCompatActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            // Whatever was granted or refused, the first-run set is now answered.
            // Recording it here rather than only when everything is granted means a
            // rider who declines the phone permissions is not asked again on every
            // launch, and one who accepts is asked for the rest.
            val wasFirstRun = isFirstRun
            if (wasFirstRun) markFirstRunComplete()

            val denied = granted.filterValues { !it }.keys
            if (denied.isNotEmpty()) {
                Log.i("MainActivity", "declined: ${denied.joinToString()}")
            }

            // Connect if the permissions needed for BLE were granted, even if
            // the optional phone/contact grants were declined. The initial
            // implementation required every requested permission, which made
            // declining caller-ID permissions prevent scooter connection.
            if (hasBlePermission()) {
                if (RidePrefs.load(this).lastAddress.isNullOrBlank()) openScanner()
                else connectToRememberedCluster()
            }
        }

    /**
     * Whether this is the first launch, so every permission can be asked at once.
     *
     * Recorded **after** the permission dialog returns rather than before, so a
     * launch that is killed mid-dialog asks again next time instead of
     * recording a set of permissions that were never actually answered.
     */
    private val isFirstRun: Boolean
        get() = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_FIRST_RUN, true)

    private fun markFirstRunComplete() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_FIRST_RUN, false).apply()
    }

    /**
     * Everything the app can need, requested in one go on first launch.
     *
     * Three groups, and the grouping is why this is a single prompt sequence
     * rather than one dialog per feature:
     *
     * 1. Bluetooth — without it nothing works.
     * 2. Notification posting (Android 13+) — runtime permission for the
     *    connection foreground-service notice. Notification *listener access*
     *    for maps nav is a separate Settings toggle; Android cannot grant that
     *    through this dialog.
     * 3. Contacts, phone state, call log — caller ID only.
     *
     * Asking once, up front, is better than the alternative this replaces: a
     * rider granted Bluetooth, connected successfully, started a route, and saw
     * nothing on the cluster, with no indication that a permission was missing.
     */
    private fun requestAllPermissionsOnFirstRun() {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            missing += Manifest.permission.BLUETOOTH_SCAN
            missing += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        // Caller ID. Denying these costs only the cluster's caller display, and
        // the navigation mirror works without them.
        missing += Manifest.permission.READ_PHONE_STATE
        missing += Manifest.permission.READ_CONTACTS
        missing += Manifest.permission.READ_CALL_LOG

        // Request CALL_PHONE only when it is declared (it is intentionally not:
        // Android restricts it to the default dialer). READ_PHONE_STATE,
        // READ_CONTACTS and READ_CALL_LOG are all runtime-grantable.

        val needed = missing.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            markFirstRunComplete()
            if (RidePrefs.load(this).lastAddress.isNullOrBlank()) openScanner()
            else connectToRememberedCluster()
            return
        }
        Log.i("MainActivity", "first run: requesting ${needed.size} permissions")
        permissionLauncher.launch(needed.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Nucleus.init()
        // Enable the rotating file sink from the UI process too, so logs are
        // captured even before the ride service starts.
        Log.enableFileSink(java.io.File(filesDir, "logs"))
        ThemePrefs.load(this)
        val ridePrefs = RidePrefs.load(this)
        Session.attachRideLog(applicationContext)
        Session.loadParked(applicationContext)
        // Restore the riding state so the toggle reflects what survived a
        // restart rather than silently reverting to off.
        Session.setRideMode(ridePrefs.rideMode)
        autoConnectState = ridePrefs.autoConnect
        Log.i("App", "activity created; drivers registered; theme=${ThemePrefs.mode}")

        setContent {
            // No theme wrapper here: AppRoot applies TVSRideTheme around the
            // Scaffold. Wrapping twice meant the outer instance supplied the
            // colours and the inner one re-supplied them, so the two could
            // disagree after a theme change.
            AppRoot(
                autoConnect = autoConnectState,
                onFindMyChange = { send(it) },
                onNavMirrorChange = { send(it) },
                onScan = { openScanner() },
                onDisconnect = { send(RideService.ACTION_DISCONNECT) },
                onExit = { exitApp() },
                onOpenNotificationAccess = { openNotificationAccess() },
                onAutoConnectChange = { enabled ->
                    autoConnectState = enabled
                    val prefs = RidePrefs.load(this@MainActivity)
                    prefs.autoConnect = enabled
                    if (enabled) {
                        prefs.lastAddress?.let { setUpCompanion(it) }
                    } else {
                        stopObservingCompanion()
                    }
                },
                // Re-read on every recomposition: the user grants notification
                // access in system settings and comes back, so a value sampled
                // once in onCreate is permanently stale.
                hasNotificationAccess = notificationAccess,
            )
        }
    }

    @Composable
    private fun AppRoot(
        autoConnect: Boolean,
        onFindMyChange: (String) -> Unit,
        onNavMirrorChange: (String) -> Unit,
        onScan: () -> Unit,
        onDisconnect: () -> Unit,
        onExit: () -> Unit,
        onOpenNotificationAccess: () -> Unit,
        onAutoConnectChange: (Boolean) -> Unit,
        hasNotificationAccess: Boolean,
    ) {
        var tab by rememberSaveable { mutableStateOf(0) }
        var findMy by rememberSaveable { mutableStateOf(false) }
        // On by default: mirroring is the app's reason to exist.
        //
        // It was false, so a rider who installed this, granted notification
        // access, started a route and looked at the cluster saw nothing — with no
        // indication that a switch existed to turn on. Asking for a feature
        // twice is the wrong default when enabling it is free: the bridge does
        // nothing until a maps app actually posts a navigation notification,
        // and there is no cloud, account or network call involved.
        //
        // The toggle stays, because someone may want the cluster left alone.
        var navMirroring by rememberSaveable {
            mutableStateOf(RidePrefs.load(this@MainActivity).navigationMirroring)
        }

        // A mutableStateOf-backed preference: reading it here makes the window
        // flag follow the More-screen toggle without a restart. Scoped to the
        // resumed lifecycle, so leaving the app clears the flag at once — the
        // toggle must never keep the screen awake from the background.
        val keepScreenOn = RidePrefs.load(this@MainActivity).keepScreenOn
        LifecycleResumeEffect(keepScreenOn) {
            if (keepScreenOn) {
                this@MainActivity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onPauseOrDispose {
                this@MainActivity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        val telemetry by Session.telemetry.collectAsStateWithLifecycle()
        val hud by Session.hud.collectAsStateWithLifecycle()
        val connection by Session.connection.collectAsStateWithLifecycle()

        // Everything the UI shows comes from the process-scoped Session, which
        // the BLE service and the notification listener both write into. The
        // activity only observes.

        // Start the mirror once a connection exists, because it defaults on.
        //
        // Without this the default was cosmetic: the switch showed "on" while
        // nothing had been sent to the service, so the cluster showed no turn
        // instructions and there was no reason visible for it.
        //
        // Keyed on the connection state, so it fires on the transition to
        // CONNECTED and not on every recomposition. `startNavigation` returns
        // early if the bridge is already running, and the action is idempotent.
        // Deliberately not fired while disconnected — asking the service to start
        // a bridge it cannot write to would log a dropped write per tick, which is
        // exactly the spam that was just fixed.
        //
        // Declared *after* `connection` on purpose: an earlier version sat above
        // the collect calls and would not compile, because a local val cannot be
        // read before its declaration.
        LaunchedEffect(connection.state, navMirroring) {
            if (navMirroring && connection.state == ConnectionState.CONNECTED) {
                onNavMirrorChange(RideService.ACTION_START_NAV)
            } else if (!navMirroring && connection.state == ConnectionState.CONNECTED) {
                onNavMirrorChange(RideService.ACTION_STOP_NAV)
            }
        }
        val parked by Session.parked.collectAsStateWithLifecycle()
        val rideMode by Session.rideMode.collectAsStateWithLifecycle()

        // The remembered model is the one the rider chose; the connection's
        // model is UNKNOWN before a link exists, so use the stored value then.
        val activeModel = if (connection.model != VehicleModel.UNKNOWN) {
            connection.model
        } else {
            RidePrefs.load(this@MainActivity).lastModelId
                ?.let { runCatching { VehicleModel.valueOf(it) }.getOrNull() }
                ?: VehicleModel.UNKNOWN
        }

        TVSRideTheme(mode = ThemePrefs.mode, darkTheme = isSystemInDarkTheme()) {
            Scaffold(
                bottomBar = {
                    // Hidden while the scanner is open. The scan screen is a
                    // full-screen takeover with its own back arrow, so a live
                    // nav bar underneath was an affordance that lied: tapping a
                    // tab highlighted it and left you exactly where you were.
                    if (!scanning.value) NavigationBar {
                        NavigationBarItem(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
                            label = { Text("Home") },
                        )
                        NavigationBarItem(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            icon = { Icon(Icons.Filled.MoreHoriz, contentDescription = "More") },
                            label = { Text("More") },
                        )
                        NavigationBarItem(
                            selected = tab == 2,
                            onClick = { tab = 2 },
                            icon = { Icon(Icons.Filled.Speed, contentDescription = "Overview") },
                            label = { Text("Overview") },
                        )
                        NavigationBarItem(
                            selected = tab == 3,
                            onClick = { tab = 3 },
                            icon = { Icon(Icons.AutoMirrored.Filled.Article, contentDescription = "Log") },
                            label = { Text("Log") },
                        )
                    }
                },
            ) { padding ->
                androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                    if (scanning.value) {
                        // Full-screen takeover rather than a dialog: the scan has
                        // a filter switch, a list and a model picker, and a
                        // dialog would either clip or cover the nav bar.
                        ScanScreen(
                            scanner = scanner,
                            pairing = pairing,
                            lastModelId = RidePrefs.load(this@MainActivity).lastModelId,
                            onPick = { address, model ->
                                scanning.value = false
                                RidePrefs.load(this@MainActivity)
                                    .updateSelectedModel(address, model.name)
                                val prefs = RidePrefs.load(this@MainActivity)
                                prefs.updateLastConnection(address, model.name)
                                if (autoConnectState) setUpCompanion(address)
                                connectTo(address, model)
                            },
                            onBack = { scanning.value = false },
                        )
                    } else if (tab == 0) {
                        HomeScreen(
                            telemetry = telemetry,
                            hud = hud,
                            connection = connection,
                            parked = parked,
                            rideMode = rideMode,
                            findMyEnabled = findMy,
                            vehicleModel = activeModel,
                            navMirroring = navMirroring,
                            onFindMyChange = { on ->
                                findMy = on
                                // Off used to send ACTION_FIND_MY too, so the
                                // rider could never switch it back off from the
                                // UI — the toggle started the action regardless
                                // of its new value.
                                onFindMyChange(
                                    if (on) RideService.ACTION_FIND_MY
                                    else RideService.ACTION_STOP_FIND_MY,
                                )
                            },
                            onNavMirrorChange = { on ->
                                navMirroring = on
                                RidePrefs.load(this@MainActivity).navigationMirroring = on
                                onNavMirrorChange(
                                    if (on) RideService.ACTION_START_NAV
                                    else RideService.ACTION_STOP_NAV,
                                )
                            },
                            onRideModeChange = { on ->
                                // No local copy to assign: `rideMode` above is
                                // the collected Session flow, so pushing into
                                // Session and the prefs is the whole update.
                                // `this@MainActivity` because inside the Scaffold
                                // content lambda a bare `this` is the BoxScope.
                                RidePrefs.load(this@MainActivity).updateRideMode(on)
                                Session.setRideMode(on)
                            },
                            onOpenOverview = { tab = 2 },
                            onOpenParked = { tab = 2 },
                            // The Connect card now opens the scanner. It used to
                            // send ACTION_CONNECT with no address, which the
                            // service logged and ignored — the only reason a
                            // connection could never start.
                            onConnect = { openScanner() },
                        )
                    } else if (tab == 1) {
                        MoreScreen(
                            connection = connection,
                            vehicleModel = activeModel,
                            riderName = RidePrefs.load(this@MainActivity).riderName,
                            onRiderNameChange = { name ->
                                RidePrefs.load(this@MainActivity).riderName = name
                                // Gate the send, not the save: without BLE
                                // permission send() would toast about Bluetooth
                                // for what is only a name being stored. The
                                // service sends it on the next connection anyway.
                                if (hasBlePermission()) {
                                    send(RideService.ACTION_UPDATE_RIDER_NAME)
                                }
                            },
                            keepScreenOn = keepScreenOn,
                            onKeepScreenOnChange = {
                                RidePrefs.load(this@MainActivity).updateKeepScreenOn(it)
                            },
                            autoConnect = autoConnect,
                            onAutoConnectChange = onAutoConnectChange,
                            notificationAccessGranted = hasNotificationAccess,
                            onOpenNotificationAccess = onOpenNotificationAccess,
                            onScan = onScan,
                            onDisconnect = onDisconnect,
                            onExit = onExit,
                        )
                    } else if (tab == 2) {
                        // Must match the nav bar order: Home, More, Overview,
                        // Log. These two destinations were swapped, so tapping
                        // Overview rendered the log and tapping Log rendered
                        // the dashboard.
                        OverviewScreen(telemetry = telemetry)
                    } else {
                        LogScreen()
                    }
                }
            }
        }
    }

    /**
     * Ask the ride service to do something.
     *
     * Guarded on the Bluetooth permissions, and the guard is not optional.
     * `RideService` is a `connectedDevice` foreground service, which Android
     * refuses unless `BLUETOOTH_CONNECT` or `BLUETOOTH_SCAN` is **granted**.
     * Without this check the sequence is:
     *
     *   startForegroundService -> service onCreate -> startForeground throws
     *   SecurityException -> caught and logged -> service never calls
     *   startForeground -> ~10s later Android kills the app with
     *   `ForegroundServiceDidNotStartInTimeException`.
     *
     * Five of those, observed on device. Catching the exception in the service
     * stopped the *service* crashing but left the app to die instead, ten
     * seconds later, with a stack trace pointing at a Compose tap handler rather
     * than at the permission that was actually missing. Check before starting.
     */
    private fun send(action: String) {
        if (!hasBlePermission()) {
            Log.w("MainActivity", "refusing to start $action: Bluetooth not granted")
            Toast.makeText(this, "Grant Bluetooth permission first", Toast.LENGTH_LONG).show()
            requestBlePermissions()
            return
        }
        runCatching { startForegroundService(Intent(this, RideService::class.java).setAction(action)) }
            .onFailure {
                Log.w("MainActivity", "could not start ride service: ${it.message}")
                Toast.makeText(this, "Ride service not running", Toast.LENGTH_LONG).show()
            }
    }

    /**
     * Removed: `requestCallerIdPermissions` plus its launcher.
     *
     * It was defined but never called — the first-run flow requests the same
     * three permissions through `permissionLauncher`, and nothing else asked on
     * demand. Worse, its `onResult` was only invoked on the all-granted path;
     * a partial grant dropped the result entirely. Dead code with a live bug in
     * it is worse than no code.
     */

    private fun requestBlePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            connectToRememberedCluster()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun connectToRememberedCluster() {
        val prefs = RidePrefs.load(this)
        val address = prefs.lastAddress
        if (address.isNullOrBlank()) {
            Toast.makeText(this, "No scooter chosen yet — tap Connect to scan", Toast.LENGTH_LONG)
                .show()
            return
        }
        val model = prefs.lastModelId
            ?.let { runCatching { VehicleModel.valueOf(it) }.getOrNull() }
            ?: VehicleModel.DEFAULT_MODEL
        connectTo(address, model)
    }

    private fun connectTo(address: String, model: VehicleModel) {
        runCatching {
            startForegroundService(
                Intent(this, RideService::class.java)
                    .setAction(RideService.ACTION_CONNECT)
                    .putExtra(RideService.EXTRA_ADDRESS, address)
                    .putExtra(RideService.EXTRA_MODEL, model.name),
            )
        }.onFailure {
            Toast.makeText(this, "Could not start the ride service", Toast.LENGTH_LONG).show()
        }
    }

    /** True when both runtime BLE permissions are already granted. */
    private fun hasBlePermission(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Read on every composition, so it must not touch the disk.
     *
     * `Settings.Secure.getString` is a provider query; doing it per recomposition
     * would hammer it. Cached, and invalidated whenever the activity resumes —
     * which is exactly when the user comes back from granting access.
     */
    private var notificationAccess by mutableStateOf(false)
    private var autoConnectState by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        notificationAccess = hasNotificationAccess()
        autoConnectState = RidePrefs.load(this).autoConnect
        if (isFirstRun && hasBlePermission()) {
            // BLE may already have been granted from the system permission
            // manager before this activity's first resume. Still ask for the
            // remaining first-run permissions once.
            if (!autoConnectTried) {
                autoConnectTried = true
                requestAllPermissionsOnFirstRun()
            }
            return
        }
        // Once, on the first resume only. Reconnecting on every resume would
        // fight the user for the GATT link while they are still reading the
        // screen.
        if (!autoConnectTried) {
            autoConnectTried = true
            if (isFirstRun) {
                // First launch: ask for everything at once, then connect. The
                // permission result handler is what performs the connect, so this
                // deliberately does not also call autoConnectOnLaunch — doing both
                // would start two foreground services for the same cluster.
                requestAllPermissionsOnFirstRun()
            } else {
                autoConnectOnLaunch()
            }
        }
    }

    private var autoConnectTried = false

    private var pendingCompanionAddress: String? = null
    private var afterCompanion: (() -> Unit)? = null

    private val companionLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val address = pendingCompanionAddress
            pendingCompanionAddress = null
            if (result.resultCode == RESULT_OK && address != null) {
                startObservingCompanion(address)
            } else {
                Log.w("MainActivity", "vehicle association was not completed")
            }
            afterCompanion?.invoke()
            afterCompanion = null
        }

    /**
     * Scan state lives on the activity, not inside AppRoot.
     *
     * Both the Connect card and the More tab's Scan button have to open the
     * same scanner, and the permission callback is wired in onCreate — outside
     * AppRoot entirely. Hoisting it keeps one scanner instance for the whole
     * activity, which also means leaving and re-entering the screen resumes the
     * same scan instead of losing the results.
     */
    private val scanning = mutableStateOf(false)
    private val scanner: ScooterScanner by lazy { ScooterScanner(this) }
    private val pairing: ScooterPairing by lazy { ScooterPairing(this) }

    private fun openScanner() {
        if (!hasBlePermission()) {
            requestBlePermissions()
            return
        }
        // The switch on ScanScreen reads RidePrefs, so the scan started here
        // must use the same persisted value — otherwise the UI says ON after
        // process death while the scan is still unfiltered.
        scanner.start(RidePrefs.load(this).onlySmartXonnect)
        scanning.value = true
    }

    /**
     * Reconnect to the last scooter without scanning, on every launch.
     *
     * The whole point of storing [RidePrefs.lastAddress]. Scanning on each launch
     * would be slower, and on this cluster actively fruitless: it advertises no
     * service UUID, so a UUID-filtered scan finds nothing, and finding it by name
     * means a name that could in principle match something else.
     *
     * The stored address is the only thing that identifies a cluster, so this is
     * also the only path that can reconnect to *your* scooter rather than to
     * whichever cluster happens to be nearest.
     *
     * Failures are silent by design. The cluster is often simply not there — the
     * ignition is off, or the battery is flat — and a toast on every launch would
     * be noise. [RideService] owns the retry and the state the UI shows.
     */
    private fun autoConnectOnLaunch() {
        if (!hasBlePermission()) {
            Log.i("MainActivity", "auto-connect skipped: Bluetooth not granted")
            return
        }
        val prefs = RidePrefs.load(this)
        if (prefs.lastAddress.isNullOrBlank()) {
            // Genuinely first run, or never connected. Say so once, because
            // otherwise the Home screen just shows "Disconnected" with no
            // explanation of why nothing is happening.
            Log.i("MainActivity", "auto-connect skipped: no scooter stored yet")
            return
        }
        val model = prefs.lastModelId
            ?.let { runCatching { VehicleModel.valueOf(it) }.getOrNull() }
            ?: VehicleModel.DEFAULT_MODEL
        Log.i("MainActivity", "auto-connecting to ${model.displayName}")
        connectToRememberedCluster()
        // Opt-in only: register with Android so a later ignition can connect
        // without the app being opened first.
        prefs.lastAddress?.let { setUpCompanion(it) }
    }

    /** System association for presence-based auto-connect (Android 12+). */
    private fun isCompanionAssociated(address: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val cdm = getSystemService(CompanionDeviceManager::class.java) ?: return false
        return runCatching {
            @Suppress("DEPRECATION")
            cdm.associations.any { it.equals(address, ignoreCase = true) }
        }.getOrDefault(false)
    }

    private fun startObservingCompanion(address: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val cdm = getSystemService(CompanionDeviceManager::class.java) ?: return
        runCatching { cdm.startObservingDevicePresence(address) }
            .onSuccess {
                RidePrefs.load(this).observedCompanion = address
                Log.i("MainActivity", "Android is watching for the vehicle")
            }
            .onFailure { Log.w("MainActivity", "could not watch for the vehicle: ${it.message}") }
    }

    private fun stopObservingCompanion() {
        val observed = RidePrefs.load(this).observedCompanion ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                getSystemService(CompanionDeviceManager::class.java)
                    ?.stopObservingDevicePresence(observed)
            }
        }
        RidePrefs.load(this).observedCompanion = null
    }

    private fun setUpCompanion(address: String, then: () -> Unit = {}) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            then()
            return
        }
        val prefs = RidePrefs.load(this)
        if (!prefs.autoConnect) {
            then()
            return
        }
        val cdm = getSystemService(CompanionDeviceManager::class.java)
        if (cdm == null) {
            Log.w("MainActivity", "no Companion Device Manager on this phone")
            then()
            return
        }
        if (isCompanionAssociated(address)) {
            startObservingCompanion(address)
            then()
            return
        }
        // The system dialog was already shown and declined. Do not nag on
        // every launch; More -> Auto-connect can be toggled to try again.
        if (address in prefs.companionAsked) {
            Log.i("MainActivity", "association already asked for $address; not repeating")
            then()
            return
        }
        prefs.companionAsked = prefs.companionAsked + address
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(
                ScanFilter.Builder().setDeviceAddress(address.uppercase(Locale.ROOT)).build(),
            )
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(true)
            .build()
        pendingCompanionAddress = address
        afterCompanion = then
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                companionLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }

            @Suppress("DEPRECATION")
            override fun onDeviceFound(intentSender: IntentSender) {
                companionLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }

            override fun onFailure(error: CharSequence?) {
                pendingCompanionAddress = null
                afterCompanion = null
                Log.w("MainActivity", "vehicle association failed: $error")
                then()
            }
        }
        Log.i("MainActivity", "asking to associate the vehicle for auto-connect")
        runCatching { cdm.associate(request, callback, null) }
            .onFailure {
                pendingCompanionAddress = null
                afterCompanion = null
                Log.w("MainActivity", "vehicle association refused: ${it.message}")
                then()
            }
    }

    /**
     * Explicit exit: stop the foreground service, release companion presence
     * observation (otherwise Android can relaunch the service when the cluster
     * appears), then close every activity. Preferences and rides are kept.
     */
    private fun exitApp() {
        stopObservingCompanion()
        runCatching { stopService(Intent(this, RideService::class.java)) }
            .onFailure { Log.w("MainActivity", "could not stop ride service: ${it.message}") }
        finishAffinity()
    }

    private fun hasNotificationAccess(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.split(':')
            ?.any { it.equals(packageName, ignoreCase = true) } == true

    private fun openNotificationAccess() {
        val i = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        runCatching { startActivity(i) }
            .onFailure {
                Toast.makeText(this, "Open notification access from system settings", Toast.LENGTH_LONG).show()
            }
    }

    private companion object {
        const val PREFS = "tvsride"
        const val KEY_FIRST_RUN = "first_run_complete"
    }
}
