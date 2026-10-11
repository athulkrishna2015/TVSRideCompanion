package dev.tvsopen.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.tvsopen.R
import dev.tvsopen.protocol.VehicleModel
import dev.tvsopen.transport.RideService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Minimal control surface.
 *
 * Deliberately thin: the value is in the protocol layer, and a heavier UI would
 * be built once the hardware questions in docs/CAPTURE.md are answered.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var navStatus: TextView

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val missing = granted.filterValues { !it }.keys
            if (missing.isEmpty()) connectOrExplain() else {
                status.text = "Missing permissions: ${missing.joinToString()}"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        navStatus = findViewById(R.id.nav_status)

        findViewById<Button>(R.id.connect).setOnClickListener { requestPermissionsThenConnect() }
        findViewById<Button>(R.id.find_my).setOnClickListener { send(RideService.ACTION_FIND_MY) }
        findViewById<Button>(R.id.stop_find_my).setOnClickListener { send(RideService.ACTION_STOP_FIND_MY) }
        findViewById<Button>(R.id.start_nav).setOnClickListener { send(RideService.ACTION_START_NAV) }
        findViewById<Button>(R.id.stop_nav).setOnClickListener { send(RideService.ACTION_STOP_NAV) }
        findViewById<Button>(R.id.disconnect).setOnClickListener { send(RideService.ACTION_DISCONNECT) }
        findViewById<Button>(R.id.notification_access).setOnClickListener { openNotificationAccess() }
    }

    private fun neededPermissions(): Array<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms.toTypedArray()
    }

    private fun requestPermissionsThenConnect() {
        val missing = neededPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) connectOrExplain() else permissionLauncher.launch(missing.toTypedArray())
    }

    /**
     * TODO: replace with a BLE scan + model picker once a scooter is in range.
     * Until then this explains what the app needs rather than pretending to
     * connect to something it cannot find.
     */
    private fun connectOrExplain() {
        if (!hasNotificationAccess()) {
            status.text = "Grant notification access, then reconnect."
            return
        }
        status.text = "Scanning for SmartXonnect clusters…\n" +
            "Service ${dev.tvsopen.protocol.Gatt.SERVICE}\n" +
            "Models: " + VehicleModel.entries
                .filter { it != VehicleModel.UNKNOWN }
                .joinToString { it.displayName }
    }

    private fun hasNotificationAccess(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true

    private fun openNotificationAccess() {
        val i = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        } else {
            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        }
        runCatching { startActivity(i) }
            .onFailure { Toast.makeText(this, "Open notification access manually", Toast.LENGTH_LONG).show() }
    }

    private fun send(action: String) {
        startForegroundService(Intent(this, RideService::class.java).setAction(action))
    }
}