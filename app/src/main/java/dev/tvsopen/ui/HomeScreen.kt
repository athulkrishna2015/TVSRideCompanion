package dev.tvsopen.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import dev.tvsopen.ui.theme.AppSwitch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import dev.tvsopen.prefs.RidePrefs
import dev.tvsopen.protocol.VehicleModel
import dev.tvsopen.session.ConnectionState1
import dev.tvsopen.session.HudState
import dev.tvsopen.session.ParkedInfo
import dev.tvsopen.session.Telemetry
import dev.tvsopen.transport.ConnectionState
import dev.tvsopen.transport.label
import dev.tvsopen.ui.theme.LocalTilePalette
import dev.tvsopen.ui.theme.NumberStyle
import kotlinx.coroutines.delay

/**
 * Dashboard.
 *
 * Card navigation rather than a wall of values: connect, then the two places
 * there is something to read. Mirrors the official TVS app's Ntorq dashboard,
 * minus everything that is cloud-backed (ride history, ride modes, the server
 * "last parked" pin) because this app has no account and no network.
 *
 * Layout shape adapted from `overclock98/JupiterRideCompanion` (CC BY-NC 4.0),
 * with no TVS or Jupiter branding — that project is the Jupiter's companion,
 * this one is unaffiliated and model-universal.
 *
 * Every absent value renders as an em dash. A `0` would be a lie: it would mean
 * the UI had invented a reading rather than reporting that nothing arrived.
 */
@Composable
fun HomeScreen(
    telemetry: Telemetry,
    hud: HudState,
    connection: ConnectionState1,
    parked: ParkedInfo?,
    findMyEnabled: Boolean,
    vehicleModel: VehicleModel,
    navMirroring: Boolean,
    rideMode: Boolean,
    onFindMyChange: (Boolean) -> Unit,
    onNavMirrorChange: (Boolean) -> Unit,
    onRideModeChange: (Boolean) -> Unit,
    onOpenOverview: () -> Unit,
    onOpenParked: () -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connected = connection.state == ConnectionState.CONNECTED
    val ctx0 = LocalContext.current
    val visibleCards = HomeCard.supported(vehicleModel).let { supported ->
        val override = RidePrefs.load(ctx0).homeCardsOverride
            ?.let { HomeCard.fromKeys(it) }
            ?: HomeCard.defaults(vehicleModel)
        override intersect supported
    }
    fun shows(card: HomeCard) = card in visibleCards

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Header(connection, connected)

        if (hud.active) {
            Spacer(Modifier.height(10.dp))
            NavigationStrip(hud)
        }

        val ctx = LocalContext.current

        if (shows(HomeCard.SHORTCUTS)) {
            Spacer(Modifier.height(20.dp))
            SectionLabel("Your scooter")
            // Card navigation rather than a value grid, mirroring the official
            // app's dashboard. Values live on the Overview tab.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NavCard(Modifier.weight(1f), Icons.Filled.Speed, "Overview", onOpenOverview)
                NavCard(Modifier.weight(1f), Icons.Filled.Place, "Last parked") {
                    if (!openParkedLocation(ctx, parked)) onOpenParked()
                }
            }
        }
        if (shows(HomeCard.CONNECT)) {
            Spacer(Modifier.height(12.dp))
            ConnectCard(connected, connection.state.label, onConnect)
        }
        // BootReceiver reads RidePrefs.rideMode to decide whether to re-attach
        // after a reboot; hiding this card does not change that behaviour.
        if (shows(HomeCard.RIDE_MODE)) {
            Spacer(Modifier.height(12.dp))
            RideModeCard(rideMode, onRideModeChange)
        }

        if (shows(HomeCard.MIRROR_NAV) || shows(HomeCard.FIND_MY) || shows(HomeCard.PARKED)) {
            Spacer(Modifier.height(20.dp))
            SectionLabel("While riding")
        }
        if (shows(HomeCard.MIRROR_NAV)) MirrorNavCard(navMirroring, hud, onNavMirrorChange)
        if (shows(HomeCard.FIND_MY)) {
            Spacer(Modifier.height(12.dp))
            FindMyCard(findMyEnabled, onFindMyChange)
        }
        if (shows(HomeCard.PARKED)) {
            Spacer(Modifier.height(12.dp))
            LastParkedCard(parked) { openParkedLocation(ctx, parked) }
        }

        // Telemetry freshness is the number that actually tells you whether the
        // link is alive. The frame counter belongs in the Log tab, not here.
        if (shows(HomeCard.LINK)) {
            Spacer(Modifier.height(20.dp))
            SectionLabel("Link")
            LinkCard(telemetry)
        }

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun NavCard(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.height(104.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        onClick = onClick,
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * The connection card.
 *
 * Honest about state: says "Not connected" rather than pretending a scan is
 * running, because there is no scan screen yet and the button reconnects to
 * the last known cluster.
 */
@Composable
private fun ConnectCard(connected: Boolean, stateLabel: String, onClick: () -> Unit) {
    // Both named, neither positional: the clickable Surface overload takes
    // `onClick` first, so a leading positional Modifier binds to it and no
    // overload matches — which then cascades into "invocations can only happen
    // from the context of a @Composable function" on the content lambda.
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = LocalTilePalette.current.accent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Bluetooth,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (connected) "Connected" else "Connect",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (connected) "Using Bluetooth" else stateLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
/**
 * Eyebrow section label, per `DESIGN.md` (`badge-pill` / `caption-strong`:
 * small uppercase muted label). Text-only: on a dense telemetry screen a
 * filled pill behind every heading would be noise.
 */
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
private fun Header(connection: ConnectionState1, connected: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("TVSRide", style = MaterialTheme.typography.headlineLarge)
            Text(
                connection.model.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Pill(
            if (connected) "Connected" else connection.state.label,
            connected,
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(36.dp)
                .background(
                    if (connected) LocalTilePalette.current.connected
                    else MaterialTheme.colorScheme.outline,
                    CircleShape,
                ),
        )
    }
}

@Composable
private fun Pill(text: String, on: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (on) LocalTilePalette.current.accent
        else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Bluetooth,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}



/**
 * The headline feature, so it sits above the rest of the vehicle cards and
 * reports where instructions are coming from — a user needs to know which maps
 * app the cluster is currently being driven by, and that it is their own app,
 * not a TVS server.
 */
@Composable
private fun MirrorNavCard(enabled: Boolean, hud: HudState, onChange: (Boolean) -> Unit) {
    ActionCard(
        background = LocalTilePalette.current.accent,
        icon = Icons.Filled.Navigation,
        title = "Mirror navigation",
        subtitle = when {
            !enabled -> "Off — turn-by-turn is not being sent"
            hud.active -> "Live from ${hud.provider ?: "your maps app"}"
            else -> "On — waiting for a navigation step"
        },
        trailing = { AppSwitch(checked = enabled, onCheckedChange = onChange) },
    )
}

@Composable
private fun LastParkedCard(parked: ParkedInfo?, onClick: () -> Unit = {}) {
    val tick = rememberTicker()
    val ago = remember(parked, tick) {
        parked?.let { relativeTime(System.currentTimeMillis() - it.atMillis) }
    }
    val clickable = parked?.label?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }?.size == 2
    ActionCard(
        background = LocalTilePalette.current.neutral,
        icon = Icons.Filled.Place,
        title = "Last Parked",
        subtitle = parked?.label ?: "Not recorded on this device",
        onClick = if (clickable) onClick else null,
        trailing = {
            Text(
                if (ago != null) "$ago ago" else "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Only accent the distance when there is one. A blue em dash reads
            // as a stray fragment rather than as "no data".
            if (parked?.distanceMeters != null) {
                Spacer(Modifier.width(10.dp))
                Text(
                    "${parked.distanceMeters}m away",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}

private fun openParkedLocation(context: android.content.Context, parked: ParkedInfo?): Boolean {
    val label = parked?.label ?: return false
    val parts = label.split(",").mapNotNull { it.trim().toDoubleOrNull() }
    if (parts.size != 2) return false
    val lat = parts[0]
    val lon = parts[1]
    val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(Parked scooter)")
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}


@Composable
private fun FindMyCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    ActionCard(
        background = LocalTilePalette.current.accent,
        icon = Icons.Filled.NotificationsActive,
        title = "Find My Scooter",
        subtitle = "Flash lights & sound horn",
        trailing = { AppSwitch(checked = enabled, onCheckedChange = onChange) },
    )
}

@Composable
private fun ActionCard(
    background: Color,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            trailing()
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = background,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            content = content,
        )
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = background,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            content = content,
        )
    }
}

/**
 * A 1 Hz tick so age labels keep advancing while no flow emits. A `remember`
 * keyed only on a timestamp froze "Telemetry live" on screen forever after a
 * link died, because no more frames arrived to change the key.
 */
@Composable
private fun rememberTicker(): Long {
    val tick = remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick.value++
        }
    }
    return tick.value
}

/**
 * Telemetry freshness.
 *
 * The single most useful "is this thing actually working" signal. Dashboards
 * fill with values from the last frame received, so without this the screen
 * looks identical whether the link is streaming or died a minute ago.
 */
@Composable
private fun LinkCard(telemetry: Telemetry) {
    val tick = rememberTicker()
    val age = remember(telemetry.lastFrameAt, tick) {
        if (telemetry.lastFrameAt == 0L) null
        else System.currentTimeMillis() - telemetry.lastFrameAt
    }
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                freshnessLabel(age),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NavigationStrip(hud: HudState) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(14.dp)) {
            // Both fields are skipped when blank rather than defaulted. Google
            // Maps puts the distance in the title, so the title is legitimately
            // empty once that is moved to the distance row — rendering "?"
            // for it, or an empty line above the street, both look broken.
            hud.instructionTitle?.takeIf { it.isNotBlank() }?.let {
                val arrow = dev.tvsopen.nav.NavShortCodes.uiArrow(hud.pictogramId)
                Text(
                    if (arrow.isNotEmpty()) "$arrow $it" else it,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            hud.street?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            // -1 is the "no distance in the notification" sentinel, so never
            // render it as a figure: it reached the cluster as 0 and the strip
            // used to say "-1 m" while the dash said 0.
            val distance = hud.distanceMeters
            Text(
                if (distance >= 0) "$distance m · ${hud.etaMinutes} min"
                else "${hud.etaMinutes} min to arrival",
                style = NumberStyle,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

internal fun relativeTime(ms: Long): String {
    val min = (ms / 60_000).toInt().coerceAtLeast(0)
    return when {
        min < 60 -> "${min}m"
        min < 60 * 24 -> "${min / 60}h"
        else -> "${min / (60 * 24)}d"
    }
}

/** Pure freshness label; negative clock skew is treated as "live". */
internal fun freshnessLabel(ageMs: Long?): String = when {
    ageMs == null -> "No telemetry received yet"
    ageMs < 3_000L -> "Telemetry live"
    ageMs < Telemetry.STALE_AFTER_MS -> "Telemetry ${ageMs / 1000}s old"
    else -> "Telemetry stale — ${ageMs / 1000}s since last frame"
}

@Composable
private fun RideModeCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    ActionCard(
        background = LocalTilePalette.current.neutral,
        icon = Icons.AutoMirrored.Filled.DirectionsBike,
        title = "Ride Mode",
        subtitle = if (enabled) "On \u2014 keeps the app active across a reboot"
        else "Off (auto-enables on ride)",
        trailing = {
            // Deliberately NOT gated on `connected`. It used to be, which made
            // it unreachable in the only state a new install can reach — no
            // scooter, and no scan screen yet to find one — and BootReceiver
            // decides the post-reboot resume from RidePrefs.rideMode. A toggle
            // that cannot be flipped cannot set that flag, so the resume path
            // was dead code dressed up as reachable UI.
            AppSwitch(checked = enabled, onCheckedChange = onChange)
        },
    )
}
