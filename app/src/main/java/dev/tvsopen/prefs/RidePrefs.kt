// Small persisted settings that outlive the process.
//
// Separate from ui.theme.ThemePrefs because these affect behaviour rather than
// appearance, and the ride state in particular is read by a BroadcastReceiver
// that runs with no UI and no Activity.

package dev.tvsopen.prefs

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class RidePrefs private constructor(private val ctx: Context) {

    private val sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Observable for Compose. */
    var rideMode by mutableStateOf(sp.getBoolean(KEY_RIDE_MODE, false))
        private set

    /** True when the user opted into reconnecting after a reboot. */
    var resumeOnBoot by mutableStateOf(sp.getBoolean(KEY_RESUME_BOOT, false))
        private set

    /**
     * Named `updateX`, not `setX`: a `private set` on a delegated `var` already
     * generates a `setX(Z)V` method, so a `fun setRideMode(Boolean)` collides
     * with it at the JVM signature level and fails the build.
     */
    fun updateRideMode(on: Boolean) {
        rideMode = on
        sp.edit().putBoolean(KEY_RIDE_MODE, on).apply()
    }

    fun updateResumeOnBoot(on: Boolean) {
        resumeOnBoot = on
        sp.edit().putBoolean(KEY_RESUME_BOOT, on).apply()
    }

    companion object {
        private const val NAME = "tvsride"
        private const val KEY_RIDE_MODE = "ride_mode"
        private const val KEY_RESUME_BOOT = "resume_on_boot"

        @Volatile
        private var instance: RidePrefs? = null

        fun load(context: Context): RidePrefs =
            instance ?: synchronized(this) {
                instance ?: RidePrefs(context.applicationContext)
                    .also { instance = it }
            }
    }
}