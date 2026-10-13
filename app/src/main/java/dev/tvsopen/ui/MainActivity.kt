package dev.tvsopen.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
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
            if (granted.values.all { it }) {
                // Previously this only toasted and dropped the grant. Now that
                // permission is in hand, finish the job the button asked for.
                connectToRememberedCluster()
            } else {
                Toast.makeText(this, "Bluetooth permission denied", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Nucleus.init()
        // Enable the rotating file sink from the UI process too, so logs are
        // captured even before the ride service starts.
        Log.enableFileSink(java.io.File(filesDir, "logs"))
        ThemePrefs.load(this)
        val ridePrefs = RidePrefs.load(this)
        // Restore the riding state so the toggle reflects what survived a
        // restart rather than silently reverting to off.
        Session.setRideMode(ridePrefs.rideMode)
        Log.i("App", "activity created; drivers registered; theme=${ThemePrefs.mode}")

        setContent {
            // No theme wrapper here: AppRoot applies TVSRideTheme around the
            // Scaffold. Wrapping twice meant the outer instance supplied the
            // colours and the inner one re-supplied them, so the two could
            // disagree after a theme change.
            AppRoot(
                onFindMyChange = { send(it) },
                onNavMirrorChange = { send(it) },
                onScan = { openScanner() },
                onDisconnect = { send(RideService.ACTION_DISCONNECT) },
                onOpenNotificationAccess = { openNotificationAccess() },
                // Re-read on every recomposition: the user grants notification
                // access in system settings and comes back, so a value sampled
                // once in onCreate is permanently stale.
                hasNotificationAccess = notificationAccess,
            )
        }
    }

    @Composable
    private fun AppRoot(
        onFindMyChange: (String) -> Unit,
        onNavMirrorChange: (String) -> Unit,
        onScan: () -> Unit,
        onDisconnect: () -> Unit,
        onOpenNotificationAccess: () -> Unit,
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
        var navMirroring by rememberSaveable { mutableStateOf(true) }

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
        LaunchedEffect(connection.state) {
            if (navMirroring && connection.state == ConnectionState.CONNECTED) {
                onNavMirrorChange(RideService.ACTION_START_NAV)
            }
        }
        val parked by Session.parked.collectAsStateWithLifecycle()
        val rideMode by Session.rideMode.collectAsStateWithLifecycle()

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
                            onPick = { address, model ->
                                scanning.value = false
                                RidePrefs.load(this@MainActivity)
                                    .updateLastConnection(address, model.name)
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
                            notificationAccessGranted = hasNotificationAccess,
                            onOpenNotificationAccess = onOpenNotificationAccess,
                            onScan = onScan,
                            onDisconnect = onDisconnect,
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
     * Scan, or connect to the remembered cluster once permission is in hand.
     *
     * The callback used to be granted-permission-and-forget: it checked the
     * dialog result, showed a toast if anything was denied, and dropped the
     * grant on the floor. Nothing ever connected, so `connect()` in the service
     * was unreachable. There is still no scan screen, so this reconnects to the
     * last known cluster — the nearest honest thing to "Scan".
     */
    /**
 * Ask for caller-ID permissions, on request, never at launch.
 *
 * These three are the most invasive thing this app can hold — the call log and
 * the contact list. They are deliberately **not** bundled into the Bluetooth
 * request: a rider tapping Connect to pair a scooter should not be shown a
 * prompt about their phonebook, and a denial of any of them must not block
 * connecting. Failure here only costs caller ID and SMS display; navigation
 * and telemetry keep working.
 *
 * Returns the ones actually granted, so the caller can say what is missing
 * rather than guessing.
 */
private fun requestCallerIdPermissions(onResult: (List<String>) -> Unit) {
    val wanted = listOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
    )
    val missing = wanted.filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }
    if (missing.isEmpty()) {
        onResult(wanted)
        return
    }
    callerIdLauncher.launch(missing.toTypedArray())
}

private val callerIdLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val have = granted.filterValues { it }.keys.toList()
        Log.i("MainActivity", "caller-ID permissions granted: $have")
        // Denial is not fatal and must not be reported as a failure: nav and
        // telemetry are unaffected, only the cluster's caller display is.
        if (have.isEmpty()) {
            Toast.makeText(
                this,
                "Caller ID needs Contacts and Phone access — navigation still works",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

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
            ?: VehicleModel.NTORQ
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

    override fun onResume() {
        super.onResume()
        notificationAccess = hasNotificationAccess()
        // Once, on the first resume only. Reconnecting on every resume would
        // fight the user for the GATT link while they are still reading the
        // screen.
        if (!autoConnectTried) {
            autoConnectTried = true
            autoConnectOnLaunch()
        }
    }

    private var autoConnectTried = false

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
        scanner.start()
        scanning.value = true
    }

    /**
     * Reconnect on launch — no scan, no user action.
     *
     * This is the point of pairing. Once a MAC is stored the cluster need not
     * be advertising at all: TVS's manual says it advertises only in BT pair
     * mode, which is a battery disconnect/reconnect away. So `connectGatt` on the
     * stored address is both faster and, most of the time, the only thing that
     * works. Scanning on every launch would be slower and often fruitless.
     */
    private fun autoConnectOnLaunch() {
        if (!hasBlePermission()) {
            Log.i("MainActivity", "auto-connect skipped: Bluetooth not granted")
            return
        }
        connectToRememberedCluster()
    }

    private fun hasNotificationAccess(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true

    private fun openNotificationAccess() {
        val i = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        runCatching { startActivity(i) }
            .onFailure {
                Toast.makeText(this, "Open notification access from system settings", Toast.LENGTH_LONG).show()
            }
    }
}