package dev.tvsopen.nav

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dev.tvsopen.protocol.ManeuverResolver
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
    /** Best single line for a 17-character cluster display. */
    fun displayLine(maxChars: Int = 17): String {
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
        set(value) { field = value }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val pkg = n.packageName ?: return
        val extras = n.notification?.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val ongoing = n.isOngoing

        val looksLikeNav = pkg in allowedPackages ||
            (extras.containsKey("android.app.extra.MAPS_MODE") ||
                extras.containsKey("navigation.mode") ||
                (ongoing && title.isNotEmpty() && (text.isNotEmpty() || bigText.isNotEmpty()) &&
                    extractDistance(text, bigText) >= 0))

        if (!looksLikeNav) return

        val distance = extractDistance(text, bigText)
        if (distance < 0) {
            Log.d(TAG, "nav-shaped notification without a distance from $pkg: '$title' / '$text'")
        }

        val step = NavInstruction(
            title = title,
            street = text.ifBlank { bigText },
            distanceMeters = distance,
            etaMinutes = extractEtaMinutes(bigText, text),
            // Tier 2 resolution: the cluster icon table is closed, so fall back
            // to the words when the provider does not give us a usable icon.
            pictogram = ManeuverResolver.fromText("$title $text $bigText"),
            provider = pkg,
        )

        Log.i(TAG, "nav step from $pkg: title='${step.title}' street='${step.street}' " +
            "d=${step.distanceMeters}m pic=${step.pictogram}")
        _instruction.value = step
        _navigating.value = true
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName !in allowedPackages) return
        _navigating.value = false
        _instruction.value = null
    }

    companion object {
        private const val TAG = "MapsNavListener"

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

        /** `150 m`, `1.5 km`, `850m`, `2,3 km` — providers vary on punctuation. */
        private val DISTANCE_RE =
            Regex("""(\d+(?:[.,]\d+)?)\s*(m|km|mi|ft)""", RegexOption.IGNORE_CASE)

        private fun extractDistance(vararg texts: String?): Int {
            for (t in texts) {
                if (t.isNullOrBlank()) continue
                val m = DISTANCE_RE.find(t) ?: continue
                val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
                return when (m.groupValues[2].lowercase()) {
                    "km" -> (value * 1000).toInt()
                    "mi" -> (value * 1609).toInt()
                    "ft" -> (value * 0.3048).toInt()
                    else -> value.toInt()
                }
            }
            return -1
        }

        private val ETA_RE =
            Regex("""(\d+)\s*(min|mins|minute|minutes|hr|hrs|hour|hours)""", RegexOption.IGNORE_CASE)

        private fun extractEtaMinutes(vararg texts: String?): Int {
            for (t in texts) {
                if (t.isNullOrBlank()) continue
                val m = ETA_RE.find(t) ?: continue
                val n = m.groupValues[1].toIntOrNull() ?: continue
                return when (m.groupValues[2].lowercase()) {
                    "hr", "hrs", "hour", "hours" -> n * 60
                    else -> n
                }
            }
            return 0
        }
    }
}