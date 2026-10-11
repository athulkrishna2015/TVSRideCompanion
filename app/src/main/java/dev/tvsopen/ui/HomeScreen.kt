package dev.tvsopen.ui

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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tvsopen.session.ConnectionState1
import dev.tvsopen.session.HudState
import dev.tvsopen.session.ParkedInfo
import dev.tvsopen.session.Telemetry
import dev.tvsopen.transport.ConnectionState
import dev.tvsopen.transport.label
import dev.tvsopen.ui.theme.LocalTilePalette
import dev.tvsopen.ui.theme.smallTile

/**
 * Dashboard.
 *
 * Layout adapted from `overclock98/JupiterRideCompanion` (CC BY-NC 4.0): two
 * large tiles over three small ones, then stacked action cards. The differences
 * are deliberate, not oversights:
 *
 *  - No TVS or Jupiter branding anywhere. That app's header carries the TVS logo
 *    and the model name; we keep the structure and substitute our own identity,
 *    because this project is unaffiliated and serves more than one model.
 *  - The navigation strip is ours and has no counterpart there.
 *  - Ride Mode and Last Parked are gated on a live link instead of being faked.
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
    rideMode: Boolean,
    findMyEnabled: Boolean,
    navMirroring: Boolean,
    onFindMyChange: (Boolean) -> Unit,
    onNavMirrorChange: (Boolean) -> Unit,
    onRideModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val connected = connection.state == ConnectionState.CONNECTED

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

        Spacer(Modifier.height(16.dp))
        SectionLabel("Telemetry")

        // Only values the cluster is documented to display. See ClusterTiles for
        // why range and fuel economy are absent.
        val tiles = ClusterTiles.forModel(connection.model)
        val large = tiles.take(2)
        // No `.take()` on the remainder: everything after the large pair is
        // rendered, wrapped onto as many rows as it needs. Truncating to three
        // silently dropped Service, which is a real cluster element.
        val small = tiles.drop(2)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            large.forEachIndexed { i, spec ->
                val (value, unit) = ClusterTiles.format(telemetry, spec.value)
                LargeTile(
                    Modifier.weight(1f),
                    spec.icon,
                    value,
                    unit,
                    spec.label,
                    if (i == 0) LocalTilePalette.current.largeLeft
                    else LocalTilePalette.current.largeRight,
                )
            }
        }

        if (small.isNotEmpty()) {
            // Chunk into rows of three rather than taking exactly three. A
            // silent `.take(3)` dropped the Service tile — a real cluster
            // element — purely because it happened to be sixth in the list.
            small.chunked(3).forEachIndexed { rowIndex, chunk ->
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    chunk.forEachIndexed { i, spec ->
                        val (value, unit) = ClusterTiles.format(telemetry, spec.value)
                        SmallTile(
                            Modifier.weight(1f), spec.icon, value, unit, spec.label,
                            rowIndex * 3 + i,
                        )
                    }
                    // Hold a partial row's tiles at the same width as a full
                    // row's instead of letting them stretch.
                    repeat(3 - chunk.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("Vehicle")

        MirrorNavCard(navMirroring, hud, onNavMirrorChange)
        Spacer(Modifier.height(12.dp))
        LastParkedCard(parked)
        Spacer(Modifier.height(12.dp))
        RideModeCard(rideMode, connected, onRideModeChange)
        Spacer(Modifier.height(12.dp))
        FindMyCard(findMyEnabled, onFindMyChange)

        Spacer(Modifier.height(20.dp))
        SectionLabel("Cluster packets")
        ClusterStatCard("Hud frames sent: ${hud.framesSent}")

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
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

@Composable
private fun LargeTile(
    modifier: Modifier,
    icon: ImageVector,
    value: String,
    unit: String,
    label: String,
    background: Color,
) {
    Surface(
        modifier.height(168.dp),
        shape = RoundedCornerShape(22.dp),
        color = background,
    ) {
        Column(Modifier.padding(16.dp)) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        unit,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                }
            }
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SmallTile(
    modifier: Modifier,
    icon: ImageVector,
    value: String,
    unit: String,
    label: String,
    index: Int,
) {
    Surface(
        modifier.height(130.dp),
        shape = RoundedCornerShape(20.dp),
        color = smallTile(index),
    ) {
        Column(Modifier.padding(12.dp)) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(3.dp))
                    Text(
                        unit,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
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
        trailing = { Switch(checked = enabled, onCheckedChange = onChange) },
    )
}

@Composable
private fun LastParkedCard(parked: ParkedInfo?) {
    // Recomputed only when the record changes, not on every recomposition.
    val ago = remember(parked) {
        parked?.let { relativeTime(System.currentTimeMillis() - it.atMillis) }
    }
    ActionCard(
        background = LocalTilePalette.current.neutral,
        icon = Icons.Filled.Place,
        title = "Last Parked",
        subtitle = parked?.label ?: "Not recorded on this device",
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

@Composable
private fun RideModeCard(enabled: Boolean, connected: Boolean, onChange: (Boolean) -> Unit) {
    ActionCard(
        background = LocalTilePalette.current.neutral,
        icon = Icons.AutoMirrored.Filled.DirectionsBike,
        title = "Ride Mode",
        subtitle = if (enabled) "On — keeps the app active"
        else "Off (auto-enables on ride)",
        trailing = {
            Switch(checked = enabled, onCheckedChange = onChange, enabled = connected)
        },
    )
}

@Composable
private fun FindMyCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    ActionCard(
        background = LocalTilePalette.current.accent,
        icon = Icons.Filled.NotificationsActive,
        title = "Find My Scooter",
        subtitle = "Flash lights & sound horn",
        trailing = { Switch(checked = enabled, onCheckedChange = onChange) },
    )
}

@Composable
private fun ActionCard(
    background: Color,
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = background,
    ) {
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
}

@Composable
private fun ClusterStatCard(text: String) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text,
            Modifier.padding(18.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NavigationStrip(hud: HudState) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                hud.instructionTitle ?: "Navigating",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            hud.street?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                "${hud.distanceMeters} m · ${hud.etaMinutes} min",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

private fun relativeTime(ms: Long): String {
    val min = (ms / 60_000).toInt()
    return when {
        min < 60 -> "${min}m"
        min < 60 * 24 -> "${min / 60}h"
        else -> "${min / (60 * 24)}d"
    }
}