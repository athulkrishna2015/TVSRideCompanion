package dev.tvsopen.nav

import android.app.Notification
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.tvsopen.log.Log
import dev.tvsopen.prefs.RidePrefs
import dev.tvsopen.session.ClusterAlert
import dev.tvsopen.session.Session
import dev.tvsopen.transport.ConnectionState
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
     * The maneuver first, the street second. The provider that is actually in
     * use here (`com.tvsm.connect`) posts the maneuver as the title and trip
     * metrics (`"2.32 km • 3 min • 11:40 AM ETA"`) as the text — preferring the
     * street therefore showed an ETA fragment where the turn instruction
     * belongs, on both lines at once. The Google Maps shape is the mirror:
     * its title is distance-only (`"550 m"`), so [NavParsing] leaves the
     * maneuver empty and this falls through to the street, which is the road.
     *
     * The raw instruction is compressed to short codes ([NavShortCodes]) so a
     * 10/12-character row fits far more, prefixed with an ASCII arrow for
     * glanceability, and rolled ([RollingText]) when it still overflows so the
     * tail scrolls into view instead of being clipped. [tick] advances once
     * per bridge send; pass 0 for a static snapshot.
     *
     * The 17-byte payload is still sent; the bytes beyond the row are
     * zero-padded and the `0xFF` terminator stays at index 19, because that is
     * the frame shape the cluster expects. Only the character count changed.
     */
    fun displayLine(maxChars: Int = RowChars.NTORQ, tick: Int = 0): String {
        val combined = listOf(title, street)
            .firstOrNull { it.isNotBlank() } ?: "Navigating"
        val compressed = NavShortCodes.compress(combined)
        val arrow = NavShortCodes.asciiArrow(pictogram)
        val full = if (arrow.isNotEmpty()) "$arrow $compressed" else compressed
        return RollingText.window(full, maxChars, tick)
    }

    /** Line 1: the maneuver only, with its arrow. Place names belong on line 2. */
    fun maneuverLine(maxChars: Int = RowChars.NTORQ_LINE_1, tick: Int = 0): String {
        // Maps often posts only a distance as the title. Line 1 still has to
        // say something; never fall back to the street, that belongs on line 2.
        val maneuver = title.takeIf { it.isNotBlank() } ?: "GO"
        val compressed = NavShortCodes.compress(maneuver)
        val arrow = NavShortCodes.asciiArrow(pictogram)
        val full = if (arrow.isNotEmpty()) "$arrow $compressed".trim() else compressed
        return RollingText.window(full, maxChars, tick)
    }

    /** Line 2: the place/street, with ETA and distance when known. */
    fun streetLine(maxChars: Int = RowChars.NTORQ_LINE_2, tick: Int = 0): String {
        val raw = listOf(
            street,
            if (etaMinutes > 0) "ETA ${etaMinutes}m" else "",
            if (distanceMeters >= 1000)
                "${distanceMeters / 1000}Km ${distanceMeters % 1000}m"
            else if (distanceMeters > 0) "${distanceMeters}m" else "",
        ).filter { it.isNotEmpty() }.joinToString(" ")
        return RollingText.window(NavShortCodes.compress(raw), maxChars, tick)
    }

    /**
     * The ETA / distance line, in TVS's own rendering.
     *
     * `10Km 700m` for multi-kilometre distances (as the manual prints it),
     * plain metres below that. Truncated to the row: without the take, a
     * `"ETA 3m 2Km 320m"` line overran the 10-character maneuver row and the
     * cluster clipped the distance off the end. Rolls like [displayLine] when
     * it still overflows.
     */
    fun metricsLine(maxChars: Int = RowChars.NTORQ_LINE_1, tick: Int = 0): String {
        val d = when {
            // -1 is NavParsing's "unknown distance" sentinel. Never ship it as
            // "-1m", and never turn it into an invented "0m" on the cluster.
            distanceMeters < 0 -> ""
            distanceMeters >= 1000 -> "${distanceMeters / 1000}Km ${distanceMeters % 1000}m"
            else -> "${distanceMeters}m"
        }
        val raw = listOf(
            if (etaMinutes > 0) "ETA ${etaMinutes}m" else "",
            d,
        ).filter { it.isNotEmpty() }.joinToString(" ")
        return RollingText.window(NavShortCodes.compress(raw), maxChars, tick)
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

    /** Built-in maps apps plus the packages the rider added in More. */
    private fun allowedPackages(): Set<String> =
        DEFAULT_MAPS_PACKAGES + RidePrefs.load(applicationContext).extraMapsPackages

    /** Key of the notification that posted the current step, for teardown. */
    @Volatile
    private var activeNavKey: String? = null

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val pkg = n.packageName
        val extras = n.notification?.extras ?: return
        if (pkg.isBlank()) return
        // The listener sees *every* notification on the phone. With no scooter
        // connected there is nothing to mirror, and recording them for hours
        // would turn the log into a diary of unrelated personal notifications.
        if (Session.connection.value.state != ConnectionState.CONNECTED) return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        val ongoing = n.isOngoing

        val parsed = NavParsing.parse(title, text, bigText, subText)
        val distance = parsed.distanceMeters
        val category = n.notification?.category
        // Kept at DEBUG: the Log tab is the evidence base for why a given
        // notification did or did not reach the cluster.
        Log.d(TAG, "notification posted category=$category ongoing=$ongoing")

        // Trust known maps packages and explicit navigation extras. For unknown
        // packages, require CATEGORY_NAVIGATION as well as distance-like content:
        // the loose "ongoing + has text + distance >= 0" fallback misfires on
        // non-nav notifications whose text contains time units that look like
        // metres (e.g. a system charging notification "1 h 13 m until full"
        // parses "13 m" as 13 metres and was mirrored as a turn).
        val hasNavExtras = extras.containsKey("android.app.extra.MAPS_MODE") ||
            extras.containsKey("navigation.mode")
        val looksLikeNav = pkg in allowedPackages() || hasNavExtras ||
            (ongoing && category == Notification.CATEGORY_NAVIGATION &&
                title.isNotEmpty() && (text.isNotEmpty() || bigText.isNotEmpty()) &&
                distance >= 0)

        if (looksLikeNav) {
            postNav(pkg, parsed, n.key)
            return
        }

        val prefs = RidePrefs.load(this)
        val structuralCall = category == Notification.CATEGORY_CALL ||
            extras.containsKey("android.telecom.extra.INCOMING_CALL_EXTRAS") ||
            extras.containsKey("android.telecom.extra.INCOMING_CALL_ADDRESS")
        val selectedCall = pkg in prefs.extraCallPackages && looksLikeCall(title, text)
        // Any-app mode still needs a call-shaped notification; otherwise every
        // message from every app would be announced as an incoming call.
        val anyAppCall = prefs.mirrorAnyAppNotifications &&
            !isSystemNotification(pkg) && looksLikeCall(title, text)
        val isCall = structuralCall || selectedCall || anyAppCall
        if (isCall) {
            // Phone dialer calls follow the phone-calls toggle; VoIP/app calls
            // (WhatsApp, Telegram, …) are mirrored when phone calls are enabled,
            // since the cluster shows "who is calling" regardless of transport.
            if (prefs.mirrorPhoneCalls) {
                postCall(n.key, pkg, extras, title, text)
            }
            return
        }

        val hasMessageStyle = extractMessagingStyle(extras) != null
        val selectedMessage = pkg in prefs.extraMessagePackages
        val anyAppMessage = prefs.mirrorAnyAppNotifications &&
            !isSystemNotification(pkg) && !ongoing
        val isMessage = hasMessageStyle || category == Notification.CATEGORY_MESSAGE ||
            selectedMessage || anyAppMessage
        if (isMessage && prefs.mirrorMessages) {
            postMessage(n.key, pkg, extras, title)
        }
    }

    @Suppress("DEPRECATION")
    private fun hasLiveNavNotification(pkg: String): Boolean = runCatching {
        activeNotifications.any { sbn ->
            sbn.packageName == pkg && sbn.isOngoing &&
                sbn.notification?.category == Notification.CATEGORY_NAVIGATION
        }
    }.getOrDefault(false)

    /** Never mirror the app's own or the system UI's notifications as messages. */
    private fun isSystemNotification(pkg: String): Boolean =
        pkg == packageName || pkg == "com.android.systemui" ||
            pkg == "android" || pkg.startsWith("com.android.") ||
            pkg == "com.samsung.android.app.routines" ||
            pkg == "com.samsung.android.lool"

    /** Call-shaped wording, for apps that do not set the telecom extras. */
    private fun looksLikeCall(title: String, text: String): Boolean {
        val s = "$title $text".lowercase()
        return listOf(
            "incoming call", "voice call", "video call", "is calling",
            "calling...", "calling…", "ringing",
        ).any { s.contains(it) }
    }

    private fun postNav(pkg: String, parsed: ParsedNav, key: String) {
        // Maps reposts "Starting navigation…" with no distance mid-route.
        // Replacing a usable step with it blanks the cluster until the next
        // post; keep the live turn instead.
        if (parsed.distanceMeters < 0 && Session.hud.value.active) {
            Log.i(TAG, "ignoring distance-less nav repost")
            return
        }
        activeNavKey = key
        val step = NavInstruction(
            title = parsed.maneuver,
            street = parsed.street,
            distanceMeters = parsed.distanceMeters,
            etaMinutes = parsed.etaMinutes,
            pictogram = parsed.pictogram,
            provider = pkg,
        )
        // Maneuver text and street names are route data; never write them.
        Log.i(TAG, "nav step mirrored")
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
        pendingClear?.let { handler.removeCallbacks(it) }
        pendingClear = null
        _instruction.value = step
        _navigating.value = true
    }

    private fun postCall(key: String, pkg: String, extras: Bundle, title: String, text: String) {
        // Telecom puts a Uri here, not a String — getString() always returned
        // null and the number was taken from the notification text instead.
        val addr = extras.get("android.telecom.extra.INCOMING_CALL_ADDRESS")
        val number = (addr as? android.net.Uri)?.schemeSpecificPart
            ?: extras.getString("android.text")
            ?: text.takeIf { it.isNotBlank() }
            ?: ""
        val type = if (RidePrefs.load(this).mirrorCallDetails) {
            when {
                title.contains("missed", ignoreCase = true) -> "Missed "
                title.contains("outgoing", ignoreCase = true) ||
                    extras.getInt("android.telecom.extra.CALL_TYPE", -1) == 2 -> "Out "
                else -> ""
            }
        } else {
            ""
        }
        val name = "$type${title.ifBlank { number }.ifBlank { "Incoming call" }}"
        val label = appLabel(pkg).takeUnless { it == "Phone" } ?: ""
        // Caller name/number are personal data, and the log is exported.
        Log.i(TAG, "call mirrored")
        Session.onAlert(
            ClusterAlert(
                kind = ClusterAlert.Kind.CALL,
                title = name,
                subtitle = if (label.isNotBlank() && number.isNotBlank()) "$label · $number" else number.ifBlank { label },
                key = key,
            ),
        )
    }

    private fun postMessage(key: String, pkg: String, extras: Bundle, title: String) {
        val info = extractMessageInfo(extras, title)
        val app = appLabel(pkg)
        val withText = RidePrefs.load(this).mirrorMessageText && info.body.isNotBlank()
        val display = when {
            withText && app.isNotBlank() -> "${info.body} ($app)"
            withText -> info.body
            info.title.isNotBlank() && info.count > 1 -> "${info.title} ($app ${info.count})"
            info.title.isNotBlank() && app.isNotBlank() -> "${info.title} ($app)"
            info.title.isNotBlank() -> info.title
            info.count > 1 -> "$app: ${info.count} msgs"
            else -> app.ifBlank { "Message" }
        }
        // Never write a message body or notification title to the on-device
        // log: it is personal data and the file is exported by design.
        Log.i(TAG, "message mirrored")
        Session.onAlert(
            ClusterAlert(
                kind = ClusterAlert.Kind.MESSAGE,
                title = display,
                subtitle = app,
                key = key,
            ),
        )
    }

    private data class MessageInfo(val title: String, val count: Int, val body: String = "")

    private fun extractMessageInfo(extras: Bundle, fallbackTitle: String): MessageInfo {
        val style = extractMessagingStyle(extras)
        if (style != null) {
            val conv = style.conversationTitle?.toString()?.trim().orEmpty()
            val messages = style.messages
            val count = messages.size
            val newest = messages.lastOrNull()
            val sender = conv.ifBlank {
                newest?.senderPerson?.name?.toString()?.trim().orEmpty()
            }
            val body = newest?.text?.toString()?.trim().orEmpty()
            return MessageInfo(sender.ifBlank { fallbackTitle }, count.coerceAtLeast(1), body)
        }
        return MessageInfo(fallbackTitle, 1, fallbackTitle)
    }

    @Suppress("DEPRECATION")
    private fun extractMessagingStyle(extras: Bundle): Notification.MessagingStyle? {
        val p = extras.getParcelable<android.os.Parcelable>("android.messagingStyle")
            ?: extras.getParcelable<android.os.Parcelable>(EXTRA_MESSAGING_STYLE_KEY)
        return p as? Notification.MessagingStyle
    }

    private fun appLabel(pkg: String): String = when (pkg) {
        "com.whatsapp" -> "WhatsApp"
        "org.telegram.messenger", "org.telegram.messenger.web" -> "Telegram"
        "com.google.android.apps.messaging" -> "Messages"
        "com.facebook.orca" -> "Messenger"
        "com.instagram.android" -> "Instagram"
        "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer" -> "Phone"
        else -> pkg.substringAfterLast('.').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val removed = sbn ?: return
        val key = removed.key
        // Clear a call/message alert posted from this notification.
        Session.clearAlert(key)

        // Clear only when the notification that posted the active step goes
        // away. Maps posts several notifications during a ride, and a removal
        // of any of them from an allowlisted app used to schedule a full clear
        // mid-route. Tracking the key also covers apps accepted structurally
        // rather than by package — those could never clear at all.
        if (activeNavKey == null || removed.key != activeNavKey) return
        activeNavKey = null
        pendingClear?.let { handler.removeCallbacks(it) }
        pendingClear = Runnable {
            // Maps cancels and reposts its notification constantly. A removal
            // alone used to wipe the HUD mid-route; only clear when no other
            // ongoing nav notification from that app remains.
            if (hasLiveNavNotification(removed.packageName)) {
                Log.i(TAG, "nav notification replaced; keeping the step")
                return@Runnable
            }
            Log.i(TAG, "navigation ended")
            Session.onHud { it.copy(active = false) }
            _navigating.value = false
            _instruction.value = null
        }.also { handler.postDelayed(it, CLEAR_GRACE_MS) }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingClear: Runnable? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "listener connected; ${allowedPackages().size} allowed maps packages")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "listener disconnected — navigation mirroring unavailable")
        // An OEM can kill the listener without unbinding us. Rebind so mirroring
        // recovers without a reinstall or a toggle cycle.
        runCatching {
            requestRebind(ComponentName(this, MapsNotificationListener::class.java))
        }.onFailure { Log.w(TAG, "rebind request failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "MapsNavListener"

        // Known message/call packages are no longer hard-coded here. Notification
        // category, telecom extras, MessagingStyle and the rider's own selections
        // in More decide what gets mirrored.

        private const val EXTRA_MESSAGING_STYLE_KEY = "android.messagingStyle"

        /**
         * How long a cancelled navigation notification is given to be replaced.
         *
         * Fifteen seconds: long enough to survive a Maps repost (observed
         * around nine seconds), short enough that an ended route clears the
         * cluster promptly. The pending callback re-checks for a live nav
         * notification before clearing.
         */
        // Maps can drop and repost its notification with a new key at any
        // time. A short grace wiped a live route whenever the gap exceeded a
        // few seconds; only give up after a long silence.
        private const val CLEAR_GRACE_MS = 15_000L

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
 * How many characters each cluster text line actually shows.
 *
 * The protocol's text payload is 17 bytes and the Jupiter app fills all 17, which
 * is where the original assumption came from. TVS's Ntorq 125 manual documents
 * what this cluster renders. The rider confirms the display is asymmetric:
 * **10 characters on line 1, 12 on line 2**. The manual's line-2 examples
 * corroborate the longer limit with two visible cuts:
 *
 * - `DISTANCE REMAINING` -> `DISTANCE REM`
 * - `STAY SAME ROAD` -> `STAY SAME RD`
 *
 * Sending 17 characters means the tail is clipped on both rows. Line 1 must be
 * capped at 10; line 2 at 12. See [RowChars].
 */
object RowChars {
    /** Ntorq physical line 1, confirmed by the rider. */
    const val NTORQ_LINE_1 = 10

    /** Ntorq physical line 2, confirmed by the rider and shown in TVS's manual. */
    const val NTORQ_LINE_2 = 12

    /** Longest Ntorq row, retained for the legacy single-row path. */
    const val NTORQ = NTORQ_LINE_2

    /**
     * The Jupiter's row, kept for the Jupiter driver.
     *
     * The 17-byte payload is the frame shape both clusters accept; only the
     * usable characters differ, so this changes how much text is *sent*, never
     * how the frame is laid out.
     */
    const val JUPITER = 17
}
