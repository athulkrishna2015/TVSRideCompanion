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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import dev.tvsopen.session.Session
import dev.tvsopen.transport.RideService
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
            if (granted.values.any { !it }) {
                Toast.makeText(this, "Some permissions were denied", Toast.LENGTH_LONG).show()
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
            TVSRideTheme {
                AppRoot(
                    onFindMyChange = { send(RideService.ACTION_FIND_MY) },
                    onNavMirrorChange = { send(RideService.ACTION_START_NAV) },
                    onScan = { requestBlePermissions() },
                    onDisconnect = { send(RideService.ACTION_DISCONNECT) },
                    onOpenNotificationAccess = { openNotificationAccess() },
                    hasNotificationAccess = hasNotificationAccess(),
                )
            }
        }
    }

    @Composable
    private fun AppRoot(
        onFindMyChange: (Boolean) -> Unit,
        onNavMirrorChange: (Boolean) -> Unit,
        onScan: () -> Unit,
        onDisconnect: () -> Unit,
        onOpenNotificationAccess: () -> Unit,
        hasNotificationAccess: Boolean,
    ) {
        var tab by rememberSaveable { mutableStateOf(0) }
        var findMy by rememberSaveable { mutableStateOf(false) }
        var navMirroring by rememberSaveable { mutableStateOf(false) }

        // Everything the UI shows comes from the process-scoped Session, which the
        // BLE service and the notification listener both write into. The
        // activity only observes.
        val telemetry by Session.telemetry.collectAsStateWithLifecycle()
        val hud by Session.hud.collectAsStateWithLifecycle()
        val connection by Session.connection.collectAsStateWithLifecycle()
        val parked by Session.parked.collectAsStateWithLifecycle()
        val rideMode by Session.rideMode.collectAsStateWithLifecycle()

        TVSRideTheme(mode = ThemePrefs.mode) {
            Scaffold(
                bottomBar = {
                    NavigationBar {
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
                            icon = { Icon(Icons.AutoMirrored.Filled.Article, contentDescription = "Log") },
                            label = { Text("Log") },
                        )
                    }
                },
            ) { padding ->
                androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                    if (tab == 0) {
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
                                onFindMyChange(on)
                            },
                            onNavMirrorChange = { on ->
                                navMirroring = on
                                onNavMirrorChange(on)
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
                        )
                    } else if (tab == 1) {
                        MoreScreen(
                            connection = connection,
                            notificationAccessGranted = hasNotificationAccess,
                            onOpenNotificationAccess = onOpenNotificationAccess,
                            onScan = onScan,
                            onDisconnect = onDisconnect,
                        )
                    } else {
                        LogScreen()
                    }
                }
            }
        }
    }

    private fun send(action: String) {
        runCatching { startForegroundService(Intent(this, RideService::class.java).setAction(action)) }
            .onFailure { Toast.makeText(this, "Ride service not running", Toast.LENGTH_LONG).show() }
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
            Toast.makeText(this, "Scanning for SmartXonnect clusters…", Toast.LENGTH_SHORT).show()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
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