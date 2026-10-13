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
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tvsopen.protocol.VehicleModel
import dev.tvsopen.prefs.RidePrefs
import dev.tvsopen.transport.BondState
import dev.tvsopen.transport.Discovered
import dev.tvsopen.transport.ScooterPairing
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
    pairing: ScooterPairing,
    lastModelId: String?,
    onPick: (address: String, model: VehicleModel) -> Unit,
    onBack: () -> Unit,
) {
    val state by scanner.state.collectAsStateWithLifecycle()
    val bond by pairing.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var expanded by remember { mutableStateOf<String?>(null) }

    // Default to the rider's own model rather than whatever the advertised name
    // happens to decode to.
    //
    // `guessedModel` reads the BLE name, and this cluster advertises `TVSBT…`
    // with no model in it — so the guess was UNKNOWN, and connecting with that
    // selects a driver with no frame table, no nav and no caller ID. The screen
    // then looks like it worked while silently decoding nothing. The model is a
    // choice the rider makes, so preselecting the one this app was built against
    // is strictly better than preselecting UNKNOWN.
    //
    // A row the rider explicitly changes still wins: [modelChoice] is only the
    // fallback, so an override is never overwritten by a later discovery.
    val defaultModel = remember(lastModelId) {
        lastModelId?.let { runCatching { VehicleModel.valueOf(it) }.getOrNull() }
            ?: VehicleModel.NTORQ
    }
    var onlySmartXonnect by remember {
        mutableStateOf(RidePrefs.load(context).onlySmartXonnect)
    }
    var modelChoice by remember { mutableStateOf<Map<String, VehicleModel>>(emptyMap()) }

    // Registered for this screen's lifetime only: we need bond results while it
    // is open, and a manifest receiver would keep the process awake for the
    // duration of a PIN dialog nobody may be looking at.
    DisposableEffect(Unit) {
        pairing.start()
        onDispose {
            pairing.stop()
            scanner.stop()
        }
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

        PairModeCard(pairing.pairModeInstructions, bond)

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
                        // Kept short on purpose: a three-line wrap runs the
                        // text up against the switch and reads as clipped.
                        "Turn off if it never appears — flipping rescans",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = onlySmartXonnect,
                    onCheckedChange = { enabled ->
                        onlySmartXonnect = enabled
                        RidePrefs.load(context).onlySmartXonnect = enabled
                        scanner.setFilter(enabled)
                    },
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

            // The scan is time-boxed, so this is the normal resting state after
            // any successful-but-empty run. Without a button here the only way
            // to scan again is to back out and re-enter, which reads as a bug.
            state.devices.isEmpty() -> CentredNote(
                text = if (state.error != null) {
                    "The scan could not run."
                } else {
                    "Nothing found.\n\nScan again with the ignition on. If it " +
                        "still shows nothing, switch off \"Only SmartXonnect " +
                        "clusters\"."
                },
                action = {
                    Button(onClick = { scanner.start(onlySmartXonnect) }) {
                        Icon(
                            Icons.Filled.Bluetooth,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Scan again")
                    }
                },
            )

            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.devices, key = { it.address }) { d ->
                    // Explicit choice, then the default model, and only then the
                    // name-based guess — which for this cluster is UNKNOWN.
                    var savedChoice by remember(d.address) {
                        mutableStateOf(
                            RidePrefs.load(context).selectedModel(d.address)
                            ?.let { runCatching { VehicleModel.valueOf(it) }.getOrNull() }
                        )
                    }
                    val chosen = modelChoice[d.address]
                        ?: savedChoice
                        ?: if (d.isSmartXonnect) defaultModel else d.guessedModel
                    DeviceRow(
                        device = d,
                        expanded = expanded == d.address,
                        model = chosen,
                        bonded = pairing.isBonded(d.address),
                        onToggle = {
                            expanded = if (expanded == d.address) null else d.address
                        },
                        onModel = {
                            modelChoice = modelChoice + (d.address to it)
                            savedChoice = it
                            RidePrefs.load(context).updateSelectedModel(d.address, it.name)
                        },
                        onPair = { pairing.pair(d.address) },
                        onConnect = { onPick(d.address, chosen) },
                    )
                }
            }
        }
    }
}

/**
 * How to get the scooter into pair mode.
 *
 * These steps are on the *vehicle*, not the phone — TVS's manual is explicit
 * that the cluster enters BT pair mode by itself when the battery is
 * disconnected and reconnected, or a fuse is replaced, or the electric start is
 * done on a low battery. No phone-side action can force it. Publishing the
 * steps is therefore more useful than a "Pair" button that would fail
 * identically every time: the person can check whether they have actually put
 * the scooter in that state before retrying.
 */
@Composable
private fun PairModeCard(steps: List<String>, bond: BondState) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "First time? Put the scooter in pair mode",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            steps.forEach { step ->
                Text(
                    "• $step",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when (bond) {
                    is BondState.Pairing -> "Pairing…"
                    is BondState.Paired -> "Paired"
                    is BondState.Failed -> bond.reason
                    BondState.Idle -> "You only do this once. After pairing, the app connects on its own."
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * Empty/working state for the scan list.
 *
 * The horizontal padding is load-bearing: without it `Text` measures against
 * the full screen width and long lines overflow both edges rather than
 * wrapping, which is how this looked before.
 */
@Composable
private fun CentredNote(
    text: String,
    action: (@Composable () -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 28.dp),
        ) {
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
            if (action != null) {
                Spacer(Modifier.height(20.dp))
                action()
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: Discovered,
    expanded: Boolean,
    model: VehicleModel,
    bonded: Boolean,
    onToggle: () -> Unit,
    onModel: (VehicleModel) -> Unit,
    onPair: () -> Unit,
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
                        when {
                            device.byServiceUuid -> "SmartXonnect"
                            // Name-only. Saying "SmartXonnect" here would be a
                            // claim we cannot back: the service UUID was absent
                            // from the advertisement, so this is a guess.
                            device.byName -> "likely cluster (name only)"
                            else -> "not a cluster"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (expanded) {
                Spacer(Modifier.height(12.dp))
                if (!bonded) {
                    OutlinedButton(onClick = onPair, modifier = Modifier.fillMaxWidth()) {
                        Icon(
                            Icons.Filled.Link,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Pair with this scooter (first time only)")
                    }
                    Spacer(Modifier.height(10.dp))
                }
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
    // The owner's bike, corrected 2026-10-13 — it was previously absent and the
    // picker defaulted to the 150, so every connection named the wrong vehicle.
    VehicleModel.NTORQ_125_RACE,
    VehicleModel.NTORQ,
    VehicleModel.RAIDER_125,
    VehicleModel.RAIDER_180,
    VehicleModel.JUPITER_125,
    VehicleModel.APACHE_160,
    VehicleModel.IQUBE,
    VehicleModel.UNKNOWN,
)
