package dev.tvsopen.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** How the app picks its palette. */
enum class ThemeMode(val label: String) {
    LIGHT("Light"),
    DARK("Dark"),
    AUTO("Auto"),
}

/**
 * Persisted appearance preference.
 *
 * Defaults to [ThemeMode.AUTO]: following the system is the least surprising
 * behaviour and costs nothing. The original port hardcoded light because the
 * design it was adapted from was light-only; that was a shortcut, not a
 * decision, and it meant the app fought anyone using dark mode.
 *
 * A process-scoped singleton rather than a ViewModel for the same reason as
 * `Session`: the activity is the only writer, but Compose needs observable
 * state above it.
 */
object ThemePrefs {

    private const val KEY = "theme_mode"

    /** Observable so the appearance card recomposes when it changes. */
    var mode by mutableStateOf(ThemeMode.AUTO)
        private set

    @Volatile
    private var loaded = false

    /** Idempotent. Safe to call on every activity creation. */
    fun load(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null)
            mode = ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.AUTO
            loaded = true
        }
    }

    fun set(ctx: Context, next: ThemeMode) {
        mode = next
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, next.name).apply()
    }

    /** Cycles Light -> Dark -> Auto. Used by the one-tap button. */
    fun cycle(ctx: Context): ThemeMode {
        val next = when (mode) {
            ThemeMode.LIGHT -> ThemeMode.DARK
            ThemeMode.DARK -> ThemeMode.AUTO
            ThemeMode.AUTO -> ThemeMode.LIGHT
        }
        set(ctx, next)
        return next
    }

    private const val PREFS = "tvsride"
}