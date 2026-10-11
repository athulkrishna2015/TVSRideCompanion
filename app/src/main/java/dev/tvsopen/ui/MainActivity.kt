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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import dev.tvsopen.Nucleus
import dev.tvsopen.transport.RideService
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

        // Placeholder until a scooter is connected; the service owns the live
        // flows once it is running.
        val telemetry = remember { Telemetry() }
        val hud = remember { HudSnapshot() }
        val connection = remember { ConnectionUi() }

        MaterialTheme {
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
                    }
                },
            ) { padding ->
                androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                    if (tab == 0) {
                        HomeScreen(
                            telemetry = telemetry,
                            hud = hud,
                            connection = connection,
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
                        )
                    } else {
                        MoreScreen(
                            connection = connection,
                            notificationAccessGranted = hasNotificationAccess,
                            onOpenNotificationAccess = onOpenNotificationAccess,
                            onScan = onScan,
                            onDisconnect = onDisconnect,
                        )
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