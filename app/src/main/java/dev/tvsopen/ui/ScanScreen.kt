package dev.tvsopen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tvsopen.protocol.VehicleModel
import dev.tvsopen.transport.Discovered
import dev.tvsopen.transport.ScooterScanner

/**
 * Pick-a-scooter.
 *
 * The first screen that can end with a connection. Choosing a row persists the
 * address and starts the ride service — the code path that was unreachable
 * until now, because nothing ever supplied an address.
 *
 * Tap a row to expand it, then choose the model. The model matters more than it
 * looks: it selects the driver, and therefore the inbound frame table. A wrong
 * guess decodes telemetry that looks plausible and is wrong, so the advertised
 * name is only a hint and the choice stays with the user.
 */
@Composable
fun ScanScreen(
    scanner: ScooterScanner,
    onPick: (address: String, model: VehicleModel) -> Unit,
    onBack: () -> Unit,
) {
    val state by scanner.state.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf<String?>(null) }
    var modelChoice by remember { mutableStateOf<Map<String, VehicleModel>>(emptyMap()) }

    // Stop scanning when leaving. A scan with no visible UI is the fastest way
    // to drain the battery.
    DisposableEffect(Unit) {
        onDispose { scanner.stop() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text("Find your scooter", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (state.scanning) "Scanning — turn the ignition on"
                    else "Scan stopped",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.scanning) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }

        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Only SmartXonnect clusters",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Turn off if your scooter never appears",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.onlySmartXonnect,
                    onCheckedChange = scanner::setFilter,
                )
            }
        }

        state.error?.let { err ->
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Text(
                    err,
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        when {
            state.devices.isEmpty() && state.scanning -> CentredNote(
                "Looking for clusters…\n\nTurn the ignition on and make sure " +
                    "Bluetooth is visible to other devices.",
            )

            state.devices.isEmpty() -> CentredNote(
                "Nothing found.\n\nScan again with the ignition on. If it still " +
                    "shows nothing, switch off \"Only SmartXonnect clusters\".",
            )

            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.devices, key = { it.address }) { d ->
                    val chosen = modelChoice[d.address] ?: d.guessedModel
                    DeviceRow(
                        device = d,
                        expanded = expanded == d.address,
                        model = chosen,
                        onToggle = {
                            expanded = if (expanded == d.address) null else d.address
                        },
                        onModel = { modelChoice = modelChoice + (d.address to it) },
                        onConnect = { onPick(d.address, chosen) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CentredNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.AutoMirrored.Filled.BluetoothSearching,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DeviceRow(
    device: Discovered,
    expanded: Boolean,
    model: VehicleModel,
    onToggle: () -> Unit,
    onModel: (VehicleModel) -> Unit,
    onConnect: () -> Unit,
) {
    Surface(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = if (device.isSmartXonnect) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Bluetooth,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (device.isSmartXonnect) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        device.name ?: "Unnamed device",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        device.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (device.isSmartXonnect) "SmartXonnect" else "not a cluster",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (expanded) {
                Spacer(Modifier.height(12.dp))
                Text("Which model is this?", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Wrong model means the wrong frame table.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CHOICES.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { m ->
                                FilterChip(
                                    selected = model == m,
                                    onClick = { onModel(m) },
                                    label = {
                                        Text(
                                            m.displayName,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                    Text("Connect as ${model.displayName}")
                }
            }
        }
    }
}

/**
 * A shortlist, not the whole enum.
 *
 * `VehicleModel` has eleven values; the ones with no transcribed frame table
 * would connect and then decode nothing useful, so offering them is offering a
 * dead end. UNKNOWN stays because a cluster that advertises no name lands there,
 * and GenericDriver still streams raw frames for capture.
 */
private val CHOICES = listOf(
    VehicleModel.NTORQ,
    VehicleModel.RAIDER_125,
    VehicleModel.RAIDER_180,
    VehicleModel.JUPITER_125,
    VehicleModel.APACHE_160,
    VehicleModel.IQUBE,
    VehicleModel.UNKNOWN,
)