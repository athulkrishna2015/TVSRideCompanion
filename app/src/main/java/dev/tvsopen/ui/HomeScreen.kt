package dev.tvsopen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.tvsopen.protocol.Pictograms

/**
 * Home screen: live telemetry grid + navigation + find-my-scooter.
 *
 * Layout follows the third-party Jupiter companion's card grid (credited in
 * docs/UI_REFERENCE.md): a two-column grid of metric tiles, a prominent action
 * card for find-my, and a navigation strip when a maps app is directing.
 */
@Composable
fun HomeScreen(
    telemetry: Telemetry,
    hud: HudSnapshot,
    connection: ConnectionUi,
    findMyEnabled: Boolean,
    navMirroring: Boolean,
    onFindMyChange: (Boolean) -> Unit,
    onNavMirrorChange: (Boolean) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ConnectionHeader(connection) }

        if (hud.active && hud.instructionTitle != null) {
            item { NavigationStrip(hud) }
        }

        item {
            Text(
                "Telemetry",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(
                    "Speed", telemetry.speedKmh?.let { "$it" } ?: "—",
                    "km/h", Modifier.weight(1f), icon = Icons.Filled.Navigation,
                )
                MetricTile(
                    "Odometer",
                    telemetry.odometerKm?.let { "%.1f".format(it) } ?: "—",
                    "km", Modifier.weight(1f),
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(
                    "Fuel", telemetry.fuelBars?.let { "$it/5" } ?: "—",
                    "", Modifier.weight(1f),
                )
                MetricTile(
                    "Range", telemetry.distanceToEmptyKm?.let { "$it" } ?: "—",
                    "km", Modifier.weight(1f),
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(
                    "Economy", telemetry.avgEconomyKmpl?.let { "$it" } ?: "—",
                    "km/L", Modifier.weight(1f),
                )
                MetricTile(
                    "Engine", telemetry.engineRpm?.let { "$it" } ?: "—",
                    "rpm", Modifier.weight(1f),
                )
            }
        }

        item {
            ActionCard(
                title = "Find My Scooter",
                subtitle = "Flash lights and sound horn",
                checked = findMyEnabled,
                onCheckedChange = onFindMyChange,
            )
        }

        item {
            ActionCard(
                title = "Mirror navigation",
                subtitle = hud.provider?.let { "Reading from $it" }
                    ?: "Read turn-by-turn from any maps app",
                checked = navMirroring,
                onCheckedChange = onNavMirrorChange,
            )
        }

        item {
            Text(
                "Cluster packets",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Hud frames sent: ${hud.framesSent}")
                    hud.pictogramId?.let {
                        Text("Maneuver: #${it} ${Pictograms.label(it)}")
                    }
                    hud.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (!telemetry.isFresh() && telemetry.lastFrameAt > 0) {
                        Text(
                            "No recent frames from the cluster",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun ConnectionHeader(c: ConnectionUi) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (c.state == "Connected") Icons.Filled.Bluetooth
                else Icons.Filled.BluetoothDisabled,
                contentDescription = null,
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.modelName, style = MaterialTheme.typography.titleMedium)
                Text("${c.state}${c.address?.let { " · $it" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (c.state == "Connected") {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xFF2E7D32)),
                )
            }
        }
    }
}

@Composable
private fun NavigationStrip(hud: HudSnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                hud.instructionTitle ?: "",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            hud.street?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            val d = if (hud.distanceMeters >= 0) {
                if (hud.distanceMeters >= 1000) "%.1f km".format(hud.distanceMeters / 1000.0)
                else "${hud.distanceMeters} m"
            } else ""
            Text(
                listOf(d, if (hud.etaMinutes > 0) "${hud.etaMinutes} min" else "")
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun MetricTile(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                }
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (unit.isNotBlank()) {
                    Spacer(Modifier.size(4.dp))
                    Text(unit, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun ActionCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.NotificationsActive,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}