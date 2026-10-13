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

    /**
     * Last link the service established, for reconnect-after-reboot. Plain
     * getters, not observable state: only MainActivity reads them once at
     * startup, and SharedPreferences is the source of truth.
     */
    val lastAddress: String?
        get() = sp.getString(KEY_LAST_ADDRESS, null)

    val lastModelId: String?
        get() = sp.getString(KEY_LAST_MODEL, null)

    fun updateLastConnection(address: String, modelId: String) {
        sp.edit()
            .putString(KEY_LAST_ADDRESS, address)
            .putString(KEY_LAST_MODEL, modelId)
            .apply()
    }

    /** Explicit user's selection from the scan row; survives later rediscovery. */
    fun updateSelectedModel(address: String, modelId: String) {
        sp.edit().putString(modelKey(address), modelId).apply()
    }

    fun selectedModel(address: String): String? = sp.getString(modelKey(address), null)

    /** Scan preference; default is unfiltered because the Ntorq advertises no UUID. */
    var onlySmartXonnect: Boolean
        get() = sp.getBoolean(KEY_ONLY_SMART_XONNECT, false)
        set(value) { sp.edit().putBoolean(KEY_ONLY_SMART_XONNECT, value).apply() }

    /** Navigation mirroring is the product default; preserve an explicit opt-out. */
    var navigationMirroring: Boolean
        get() = sp.getBoolean(KEY_NAVIGATION_MIRRORING, true)
        set(value) { sp.edit().putBoolean(KEY_NAVIGATION_MIRRORING, value).apply() }

    /** Local profile name; UI can edit it without sending an unverified packet. */
    var riderName: String?
        get() = sp.getString(KEY_RIDER_NAME, null)
        set(value) {
            val trimmed = value?.trim()?.takeIf { it.isNotEmpty() }
            sp.edit().apply {
                if (trimmed == null) remove(KEY_RIDER_NAME) else putString(KEY_RIDER_NAME, trimmed)
            }.apply()
        }

    /** Explicit user's selection from the scan row; survives later rediscovery. */
    fun updateSelectedModel(address: String, modelId: String) {
        sp.edit().putString(modelKey(address), modelId).apply()
    }

    fun selectedModel(address: String): String? = sp.getString(modelKey(address), null)

    companion object {
        private const val NAME = "tvsride"
        private const val KEY_RIDE_MODE = "ride_mode"
        private const val KEY_RESUME_BOOT = "resume_on_boot"
        private const val KEY_LAST_ADDRESS = "last_address"
        private const val KEY_LAST_MODEL = "last_model"
        private const val KEY_RIDER_NAME = "rider_name"
        private const val KEY_ONLY_SMART_XONNECT = "only_smart_xonnect"
        private const val KEY_NAVIGATION_MIRRORING = "navigation_mirroring"
        private const val KEY_SELECTED_MODEL_PREFIX = "selected_model_"

        private fun modelKey(address: String) = "$KEY_SELECTED_MODEL_PREFIX$address"

        @Volatile
        private var instance: RidePrefs? = null

        fun load(context: Context): RidePrefs =
            instance ?: synchronized(this) {
                instance ?: RidePrefs(context.applicationContext)
                    .also { instance = it }
            }
    }
}
