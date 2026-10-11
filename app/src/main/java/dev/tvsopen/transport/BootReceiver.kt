package dev.tvsopen.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.tvsopen.log.Log
import dev.tvsopen.prefs.RidePrefs

/**
 * Re-attach after a reboot.
 *
 * Without this the app is useless immediately after a restart: the cluster
 * keeps advertising, the phone has no connection, and nothing re-tries. The
 * scooter is often the thing that gets turned back on first, which is exactly
 * when the link matters.
 *
 * Only resumes if the user left the app in a riding state. Quietly restarting a
 * BLE foreground service unprompted, for an app someone merely opened once, is
 * not a trade anyone should get by default.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val prefs = RidePrefs.load(context)
        if (!prefs.resumeOnBoot) {
            Log.i(TAG, "boot: not resuming, ride mode was off")
            return
        }

        Log.i(TAG, "boot: resuming ride service (sdk ${Build.VERSION.SDK_INT})")
        // startForegroundService, not startService: this is a background start
        // and the service must already be foreground by the time onCreate runs
        // or Android throws.
        runCatching {
            context.startForegroundService(
                Intent(context, RideService::class.java),
            )
        }.onFailure {
            Log.w(TAG, "boot: could not start ride service: ${it.message}")
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}