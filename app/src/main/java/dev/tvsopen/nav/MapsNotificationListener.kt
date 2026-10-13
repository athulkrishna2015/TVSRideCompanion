package dev.tvsopen.nav

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.tvsopen.log.Log
import dev.tvsopen.session.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One turn-by-turn instruction, normalised across map providers.
 */
data class NavInstruction(
    /** "Turn left", "Roundabout, exit 2", … — the maneuver. */
    val title: String,
    /** "Onto NH44" — the street. */
    val street: String,
    /** Metres to the maneuver; -1 when unknown. */
    val distanceMeters: Int,
    val etaMinutes: Int = 0,
    /** Resolved cluster pictogram id, or null when unresolved. */
    val pictogram: Int?,
    val provider: String,
) {
    /**
     * Best single line for this cluster's display.
     *
     * Width is [RowChars.NTORQ], **not** the 17 the Jupiter payload implies.
     * TVS's own manual renders `DISTANCE REMAINING` as `DISTANCE REM` and
     * `STAY SAME ROAD` as `STAY SAME RD` — both cut at 12 — and no string it
     * documents is longer. A renderer that truncates at 12 and never exceeds it
     * is displaying a 12-cell row.
     *
     * The 17-byte payload is still sent; the bytes beyond 12 are zero-padded and
     * the `0xFF` terminator stays at index 19, because that is the frame shape
     * the cluster expects. Only the character count changed.
     */
    fun displayLine(maxChars: Int = RowChars.NTORQ): String {
        val combined = listOf(street, title)
            .firstOrNull { it.isNotBlank() } ?: "Navigating"
        return combined.take(maxChars)
    }
}

/**
 * Reads turn-by-turn out of *any* maps app.
 *
 * This is the same approach as the official app
 * (`com.tvs.ble.feature.NotificationListenerService`): the cluster has no idea
 * what a maps app is, so we take instructions from whatever app is foreground
 * and forward them over BLE. No map SDK, no API key, no account — which is
 * what makes "use Google Maps without a TVS account" work.
 *
 * ## Recognising a navigation notification
 *
 * There is no standard flag, so we accept a notification when it
 *  - comes from a known maps package, **or**
 *  - looks like navigation (has the navigation extras we use).
 *
 * A user-configurable package allowlist comes first so a new maps app works
 * without a code change.
 */
class MapsNotificationListener : NotificationListenerService() {

    private val _instruction = MutableStateFlow<NavInstruction?>(null)
    val instruction: StateFlow<NavInstruction?> = _instruction.asStateFlow()

    /** True when a maps app is currently navigating. */
    private val _navigating = MutableStateFlow(false)
    val navigating: StateFlow<Boolean> = _navigating.asStateFlow()

    /** Packages to trust in addition to structural detection. */
    @Volatile
    var allowedPackages: Set<String> = DEFAULT_MAPS_PACKAGES

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        // packageName is platform-defined non-null, but it is typed String? in
        // the SDK. `?: return` on a non-null type is a compile error, so it was
        // written as a pointless `n.packageName ?: return` elsewhere; bind it
        // plainly and let the next line filter.
        val pkg = n.packageName
        val extras = n.notification?.extras ?: return
        if (pkg.isBlank()) return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        val ongoing = n.isOngoing

        // Google Maps does not put the distance where most providers do, so the
        // distance is read across all four fields before deciding whether this
        // notification is navigation at all. See NavParsing for the observed
        // format and why the title has to be searched too.
        val parsed = NavParsing.parse(title, text, bigText, subText)
        val distance = parsed.distanceMeters

        val looksLikeNav = pkg in allowedPackages ||
            (extras.containsKey("android.app.extra.MAPS_MODE") ||
                extras.containsKey("navigation.mode") ||
                (ongoing && title.isNotEmpty() && (text.isNotEmpty() || bigText.isNotEmpty()) &&
                    distance >= 0))

        if (!looksLikeNav) return

        val step = NavInstruction(
            title = parsed.maneuver,
            street = parsed.street,
            distanceMeters = parsed.distanceMeters,
            etaMinutes = parsed.etaMinutes,
            pictogram = parsed.pictogram,
            provider = pkg,
        )

        Log.i(
            TAG,
            "nav from $pkg: '${step.title}' / '${step.street}' " +
                "d=${step.distanceMeters}m eta=${step.etaMinutes}m pic=${step.pictogram} " +
                "[raw title='$title' text='$text' big='$bigText' sub='$subText']",
        )
        Session.onHud {
            it.copy(
                active = true,
                instructionTitle = step.title,
                street = step.street,
                distanceMeters = step.distanceMeters,
                etaMinutes = step.etaMinutes,
                pictogramId = step.pictogram,
                provider = step.provider,
            )
        }
        // A repost cancels a pending "navigation ended", so a refresh that
        // arrives as remove-then-post keeps the HUD alive.
        pendingClear?.let { handler.removeCallbacks(it) }
        pendingClear = null
        _instruction.value = step
        _navigating.value = true
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Bind to a non-null local first: the safe-call form below would not
        // smart-cast, leaving sbn nullable at the log line.
        val removed = sbn ?: return
        if (removed.packageName !in allowedPackages) return

        // **Debounced, deliberately.** Observed on device: Google Maps posted a
        // navigation notification and then *cancelled it nine seconds later*
        // while still navigating — it re-creates the notification as it
        // refreshes. Clearing straight away blanked the cluster display for a
        // journey that was still under way, and the rider would have watched
        // directions vanish mid-turn.
        //
        // So a removal only ends navigation if nothing reposts within
        // [CLEAR_GRACE_MS]. A repost cancels this, which is the common case and
        // the one that used to be broken.
        pendingClear?.let { handler.removeCallbacks(it) }
        pendingClear = Runnable {
            Log.i(TAG, "navigation ended (${removed.packageName})")
            Session.onHud { it.copy(active = false) }
            _navigating.value = false
            _instruction.value = null
        }.also { handler.postDelayed(it, CLEAR_GRACE_MS) }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingClear: Runnable? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "listener connected; allowed: ${allowedPackages.joinToString()}")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "listener disconnected — navigation mirroring unavailable")
    }

    companion object {
        private const val TAG = "MapsNavListener"

        /**
         * How long a cancelled navigation notification is given to be replaced.
         *
         * Nine seconds was the gap actually observed, so this sits just above
         * it. Long enough to survive a refresh, short enough that genuinely
         * ending a route clears the cluster promptly.
         */
        private const val CLEAR_GRACE_MS = 12_000L

        val DEFAULT_MAPS_PACKAGES = setOf(
            "com.google.android.apps.maps",       // Google Maps
            "com.mappls.app",                     // MapMyIndia
            "com.mmi.android.mmaps",              // Mapmyne
            "org.organicmaps",                    // Organic Maps
            "com.here.app.maps",                  // HERE WeGo
            "com.waze",                           // Waze
            "com.tomtom.gotcha",                  // TomTom Go
            "com.transit.app.transitmaps",        // Transit
            "com.immobiletrack",
        )
    }
}
/**
 * How many characters the cluster's alpha-numeric row actually shows.
 *
 * The protocol's text payload is 17 bytes and the Jupiter app fills all 17, which
 * is where the original assumption came from. TVS's Ntorq 125 manual documents
 * what this cluster renders, and every one of its examples is 12 characters or
 * fewer — including two that are visibly cut:
 *
 * - `DISTANCE REMAINING` -> `DISTANCE REM`
 * - `STAY SAME ROAD` -> `STAY SAME RD`
 *
 * That is a 12-cell row. Sending 17 characters means the tail is either
 * clipped on the cluster or never read, and `"Meppayyur - Avalla"` lost its
 * destination half every time.
 */
object RowChars {
    /** Documented Ntorq 125 row width. See `docs/NAVIGATION.md`. */
    const val NTORQ = 12

    /**
     * The Jupiter's row, kept for the Jupiter driver.
     *
     * The 17-byte payload is the frame shape both clusters accept; only the
     * usable characters differ, so this changes how much text is *sent*, never
     * how the frame is laid out.
     */
    const val JUPITER = 17
}
