// Companion-device presence: android tells us when the associated scooter
// becomes reachable (typically when its ignition powers the cluster up), so the
// app can connect without waiting for the UI to be opened.
//
// Opt-in and Android 12+ only. The association itself is a system dialog owned
// by MainActivity; this service only reacts to presence events for the one
// remembered vehicle.

package dev.tvsopen.transport

import android.Manifest
import android.companion.CompanionDeviceService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.tvsopen.log.Log
import dev.tvsopen.prefs.RidePrefs

class VehiclePresenceService : CompanionDeviceService() {

    @Suppress("DEPRECATION")
    override fun onDeviceAppeared(address: String) {
        val prefs = RidePrefs.load(this)
        if (!prefs.autoConnect) {
            Log.i(TAG, "vehicle appeared; auto-connect is off")
            return
        }
        val saved = prefs.lastAddress
        if (saved.isNullOrBlank() || !saved.equals(address, ignoreCase = true)) {
            Log.i(TAG, "a different companion device appeared; ignoring")
            return
        }
        if (!hasBlePermission()) {
            Log.w(TAG, "vehicle appeared but Bluetooth permission is missing")
            return
        }
        // Per-address choice first, then the last model, then the rider's bike.
        val model = prefs.selectedModel(saved) ?: prefs.lastModelId ?: "NTORQ"
        Log.i(TAG, "vehicle appeared; connecting")
        runCatching {
            startForegroundService(
                Intent(this, RideService::class.java)
                    .setAction(RideService.ACTION_CONNECT)
                    .putExtra(RideService.EXTRA_ADDRESS, saved)
                    .putExtra(RideService.EXTRA_MODEL, model),
            )
        }.onFailure { Log.w(TAG, "could not start ride service: ${it.message}") }
    }

    @Suppress("DEPRECATION")
    override fun onDeviceDisappeared(address: String) {
        // Do not disconnect here: the link can survive the cluster leaving BLE
        // range briefly, and RideService owns reconnection when it really drops.
        Log.i(TAG, "vehicle out of range")
    }

    private fun hasBlePermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val TAG = "VehiclePresence"
    }
}
