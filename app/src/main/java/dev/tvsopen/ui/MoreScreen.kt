package dev.tvsopen.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import dev.tvsopen.ui.theme.AppSwitch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tvsopen.Nucleus
import dev.tvsopen.data.DataExport
import dev.tvsopen.prefs.RidePrefs
import dev.tvsopen.protocol.Confidence
import dev.tvsopen.protocol.Pictograms
import dev.tvsopen.session.Ride
import dev.tvsopen.session.Session
import dev.tvsopen.transport.label
import dev.tvsopen.ui.theme.ThemeMode
import dev.tvsopen.ui.theme.ThemePrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * More / settings screen.
 *
 * The transparency panel is the important part: it states exactly which
 * protocol facts are verified and which still await hardware confirmation, so
 * a user reading this app knows what they can trust.
 */
@Composable
fun MoreScreen(
    connection: dev.tvsopen.session.ConnectionState1,
    riderName: String?,
    onRiderNameChange: (String?) -> Unit,
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    autoConnect: Boolean,
    onAutoConnectChange: (Boolean) -> Unit,
    notificationAccessGranted: Boolean,
    onOpenNotificationAccess: () -> Unit,
    onScan: () -> Unit,
    onDisconnect: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("KEEP THE CONNECTION ALIVE", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item { DozeCard() }

        item {
            Text("APPEARANCE", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item { AppearanceCard() }

        item { RiderNameCard(name = riderName, onNameChange = onRiderNameChange) }

        item {
            Text("RIDES", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            // Collected here, not in MainActivity: ride distance changes with
            // telemetry, and hoisting it recomposed the entire More screen.
            val rides by Session.rides.collectAsStateWithLifecycle()
            val totalRides by Session.totalRides.collectAsStateWithLifecycle()
            val totalDistanceKm by Session.totalRideDistanceKm.collectAsStateWithLifecycle()
            RidesCard(rides = rides, totalRides = totalRides, totalDistanceKm = totalDistanceKm)
        }

        item {
            Text("YOUR DATA", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            val ctx = LocalContext.current
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Export rides, settings and the last parked spot as JSON. " +
                            "Everything stays on your device unless you share it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = {
                        val json = DataExport.buildJson(ctx)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_SUBJECT, "TVSRide export")
                            putExtra(Intent.EXTRA_TEXT, json)
                        }
                        ctx.startActivity(Intent.createChooser(intent, "Export data"))
                    }) {
                        Text("Export & share")
                    }
                }
            }
        }

        item {
            Text("CONNECTION", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(connection.model.displayName, style = MaterialTheme.typography.titleMedium)
                    // .label, not the enum name: this printed "CONNECTING".
                    Text("State: ${connection.state.label}")
                    connection.address?.let { Text("MAC: $it") }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onScan, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Bluetooth, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Scan")
                        }
                        OutlinedButton(onClick = onDisconnect, modifier = Modifier.weight(1f)) {
                            Text("Disconnect")
                        }
                    }
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Auto-connect near vehicle",
                                style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Android 12+: connect when the paired cluster becomes " +
                                    "reachable, without opening the app.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AppSwitch(checked = autoConnect, onCheckedChange = onAutoConnectChange)
                    }
                }
            }
        }

        item {
            Text("PERMISSIONS", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (notificationAccessGranted)
                            "Notification access: granted"
                        else
                            "Notification access: required to read navigation, calls and messages",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!notificationAccessGranted) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onOpenNotificationAccess) {
                            Icon(Icons.Filled.NotificationsActive, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Open notification access")
                        }
                    }
                }
            }
        }

        item {
            val ctx = LocalContext.current
            val prefs = RidePrefs.load(ctx)
            var extras by remember { mutableStateOf(prefs.extraMapsPackages) }
            ExtraPackageCard(
                title = "Extra maps apps",
                description = "Turn-by-turn from these apps is mirrored too.",
                packages = extras,
                hints = listOf("map", "nav", "waze", "osm", "here", "tomtom", "route"),
                onAdd = { extras = extras + it; prefs.extraMapsPackages = extras },
                onRemove = { extras = extras - it; prefs.extraMapsPackages = extras },
            )
        }

        item {
            Text("TESTING", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item { KeepScreenOnCard(enabled = keepScreenOn, onChange = onKeepScreenOnChange) }

        item {
            Text("ALERTS", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            val ctx = LocalContext.current
            val prefs = remember { RidePrefs.load(ctx) }
            var mirrorCalls by remember { mutableStateOf(prefs.mirrorPhoneCalls) }
            var mirrorMessages by remember { mutableStateOf(prefs.mirrorMessages) }
            var mirrorText by remember { mutableStateOf(prefs.mirrorMessageText) }
            var callDetails by remember { mutableStateOf(prefs.mirrorCallDetails) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Phone & app calls", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Show incoming calls (phone, WhatsApp, Telegram, etc.) on the cluster.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AppSwitch(
                            checked = mirrorCalls,
                            onCheckedChange = {
                                mirrorCalls = it
                                prefs.mirrorPhoneCalls = it
                            },
                        )
                    }
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Messages", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Show sender and app (WhatsApp, Telegram, SMS, etc.). Message text is never sent.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AppSwitch(
                            checked = mirrorMessages,
                            onCheckedChange = {
                                mirrorMessages = it
                                prefs.mirrorMessages = it
                            },
                        )
                    }
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Mirror any app's notifications",
                                style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Show any app's notification on the cluster as a message. " +
                                    "System notifications are skipped; the Messages switch still applies.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AppSwitch(
                            checked = prefs.mirrorAnyAppNotifications,
                            onCheckedChange = { prefs.mirrorAnyAppNotifications = it },
                        )
                    }
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Show SMS text", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Put the message body on the cluster instead of just the " +
                                    "sender and count. The text is sanitised and clipped to " +
                                    "the cluster row; nothing leaves the phone.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AppSwitch(
                            checked = mirrorText,
                            onCheckedChange = {
                                mirrorText = it
                                prefs.mirrorMessageText = it
                            },
                        )
                    }
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Show call type", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Prefix the caller with Missed or Out when the notification says so.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AppSwitch(
                            checked = callDetails,
                            onCheckedChange = {
                                callDetails = it
                                prefs.mirrorCallDetails = it
                            },
                        )
                    }
                }
            }
        }

        item {
            val ctx = LocalContext.current
            val prefs = RidePrefs.load(ctx)
            var extras by remember { mutableStateOf(prefs.extraMessagePackages) }
            ExtraPackageCard(
                title = "Extra message apps",
                description = "Notifications from these apps are shown as messages.",
                packages = extras,
                hints = listOf("whatsapp", "telegram", "message", "messenger", "signal",
                    "discord", "slack", "chat", "sms"),
                onAdd = { extras = extras + it; prefs.extraMessagePackages = extras },
                onRemove = { extras = extras - it; prefs.extraMessagePackages = extras },
            )
        }

        item {
            val ctx = LocalContext.current
            val prefs = RidePrefs.load(ctx)
            var extras by remember { mutableStateOf(prefs.extraCallPackages) }
            ExtraPackageCard(
                title = "Extra call apps",
                description = "Incoming-call notifications from these apps are shown on the cluster.",
                packages = extras,
                hints = listOf("whatsapp", "telegram", "call", "phone", "dialer", "meet", "duo"),
                onAdd = { extras = extras + it; prefs.extraCallPackages = extras },
                onRemove = { extras = extras - it; prefs.extraCallPackages = extras },
            )
        }

        item {
            Text("PROTOCOL TRANSPARENCY", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "What is verified, and what still needs your scooter",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Nucleus.describe().forEachIndexed { index, (model, facts) ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Text(
                            model.displayName,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        facts.forEach { (fact, confidence) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    when (confidence) {
                                        Confidence.VERIFIED_FROM_APK -> "●"
                                        Confidence.INHERITED_UNVERIFIED -> "◐"
                                        Confidence.NEEDS_CAPTURE -> "○"
                                    },
                                    color = when (confidence) {
                                        Confidence.VERIFIED_FROM_APK -> MaterialTheme.colorScheme.primary
                                        Confidence.INHERITED_UNVERIFIED -> MaterialTheme.colorScheme.tertiary
                                        Confidence.NEEDS_CAPTURE -> MaterialTheme.colorScheme.outline
                                    },
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                Text(fact, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "● verified from the official APK   " +
                            "◐ inherited, unverified   ○ needs capture",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Pictograms (${Pictograms.ids.size})",
                        style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Official app table. Two tables disagree upstream — " +
                            "see docs/NAVIGATION.md if an arrow renders wrong.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Navigation display", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Every instruction is compressed before it is sent: " +
                            "Turn left onto Example Road becomes <- LFT to Example Rd.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "The arrow prefix marks the turn at a glance " +
                            "(<- left, -> right, ^ straight, U U-turn, O roundabout).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Text wider than the row scrolls one window per send " +
                            "instead of clipping its tail.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Both cluster lines go out together (0x4F + 0x50, rows = 2); " +
                            "a single alternating line when the second has nothing to show.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Compression, arrows and scrolling run on the phone and are " +
                            "unit-tested. Whether the Ntorq renders both lines at once " +
                            "still needs your scooter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Science, null)
                    Text("TVSRide", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "No account. No TVS server. INTERNET is used for map tiles only.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** An installed app the rider can pick as a navigation source. */
private data class InstalledApp(val label: String, val packageName: String)

/** Compact entry point; the heavy picker only opens on demand. */
@Composable
private fun ExtraPackageCard(
    title: String,
    description: String,
    packages: Set<String>,
    hints: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (packages.isEmpty()) description
                else "Added: ${packages.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { open = true }) { Text("Manage") }
        }
    }

    if (open) {
        PackagePickerDialog(
            title = title,
            current = packages,
            hints = hints,
            onAdd = onAdd,
            onRemove = onRemove,
            onDismiss = { open = false },
        )
    }
}

/** On-demand picker. Package enumeration is I/O and runs off the main thread. */
@Composable
private fun PackagePickerDialog(
    title: String,
    current: Set<String>,
    hints: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var installed by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var input by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.getInstalledPackages(0)
                .mapNotNull { info ->
                    val label = runCatching {
                        info.applicationInfo?.loadLabel(pm)?.toString()
                    }.getOrNull() ?: return@mapNotNull null
                    InstalledApp(label, info.packageName)
                }
                .filter { it.packageName != ctx.packageName }
                .sortedBy { it.label.lowercase() }
        }
        installed = apps
        loading = false
    }

    val query = input.trim().lowercase()
    val matches = remember(query, installed) {
        val pool = if (query.isEmpty()) {
            installed.filter { app ->
                val text = "${app.label} ${app.packageName}".lowercase()
                // The "nav" hint also matches Android's own "Navigation Bar"
                // system components, which cannot post a useful notification.
                !app.packageName.startsWith("com.android.internal") &&
                    !text.contains("navigation bar") &&
                    !text.contains("navbar") &&
                    hints.any { h -> text.contains(h) }
            }
        } else {
            installed.filter {
                it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query)
            }
        }
        pool.take(8)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Added", style = MaterialTheme.typography.bodyMedium)
                if (current.isEmpty()) {
                    Text(
                        "None yet.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    current.forEach { pkg ->
                        val label = installed.firstOrNull { it.packageName == pkg }?.label
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                if (label != null) {
                                    Text(label, style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    pkg, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(onClick = { onRemove(pkg) }) { Text("Remove") }
                        }
                    }
                }

                HorizontalDivider()
                Text("Installed apps", style = MaterialTheme.typography.bodyMedium)
                when {
                    loading -> Text(
                        "Loading installed apps…", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    matches.isEmpty() -> Text(
                        if (query.isEmpty()) "No likely matches — type a package name below."
                        else "No installed app matches — add the package manually below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> {
                        matches.forEach { app ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        app.packageName, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                OutlinedButton(onClick = { onAdd(app.packageName) }) { Text("Add") }
                            }
                        }
                        if (query.isNotEmpty()) {
                            Text(
                                "Showing the first ${matches.size} matches.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("Filter apps / package name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(6.dp))
                    Button(onClick = {
                        val pkg = input.trim()
                        if (pkg.isNotEmpty()) {
                            onAdd(pkg)
                            input = ""
                        }
                    }) { Text("Add") }
                }
            }
        },
        confirmButton = { OutlinedButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Testing aid: keeps the screen awake while the app is open. */
@Composable
private fun KeepScreenOnCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Keep screen on", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "For testing only. The screen stays awake while this app is " +
                            "open, which uses more battery.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AppSwitch(checked = enabled, onCheckedChange = onChange)
            }
        }
    }
}

@Composable
private fun RiderNameCard(name: String?, onNameChange: (String?) -> Unit) {
    var editing by remember(name) { mutableStateOf(name.orEmpty()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Console greeting", style = MaterialTheme.typography.titleMedium)
            Text(
                "Shown by the cluster on ignition. Saving sends it at once when " +
                    "connected, otherwise on the next connection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = editing,
                onValueChange = { if (it.length <= 17) editing = it },
                label = { Text("Rider name") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("${editing.length}/17") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val trimmed = editing.trim().takeIf { it.isNotEmpty() }
                    onNameChange(trimmed)
                    editing = trimmed.orEmpty()
                }) { Text("Save") }
                OutlinedButton(onClick = {
                    editing = ""
                    onNameChange(null)
                }) { Text("Clear") }
            }
            Text(
                if (name.isNullOrBlank()) "Cluster shows HELLO RIDER"
                else "Saved: $name — sent now if connected, else on next connection",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}


/**
 * Doze exemption.
 *
 * This is the difference between a working app and one that dies mid-ride. Doze
 * suspends the GATT client within minutes of the screen going off, the
 * presence packet stops, and the cluster drops the link. The working Jupiter
 * companion asks for the same exemption.
 *
 * Android has no runtime dialog for this one — it is granted in system settings
 * or via the request intent — so the card states the state plainly and links
 * out, rather than pretending a button can do it.
 */
@Composable
private fun DozeCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var exempt by remember { mutableStateOf(isDozeExempt(ctx)) }
    val prefs = remember { RidePrefs.load(ctx) }

    // Re-read on every resume, not once. The grant happens in system settings,
    // so `remember` alone kept reporting "not exempt" until the process died.
    LifecycleResumeEffect(Unit) {
        exempt = isDozeExempt(ctx)
        onPauseOrDispose { }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (exempt) "Battery optimisation: exempt"
                else "Battery optimisation: not exempt",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Without this the phone sleeps mid-ride and the cluster drops the " +
                    "connection. It does not give the app network access — it only " +
                    "stops Android suspending it.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            if (!exempt) {
                OutlinedButton(onClick = {
                    runCatching {
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${ctx.packageName}"),
                            ),
                        )
                    }.onFailure {
                        runCatching {
                            ctx.startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                            )
                        }
                    }
                    // Re-read after the settings round-trip.
                    scope.launch {
                        delay(1500)
                        exempt = isDozeExempt(ctx)
                    }
                }) { Text("Open battery settings") }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppSwitch(
                    checked = prefs.resumeOnBoot,
                    onCheckedChange = { prefs.updateResumeOnBoot(it) },
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    "Reconnect automatically after a reboot",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun RidesCard(rides: List<Ride>, totalRides: Int, totalDistanceKm: Double) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "$totalRides rides · ${String.format(Locale.US, "%.1f", totalDistanceKm)} km total",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "Recorded on this phone from the cluster's odometer. No account, no cloud.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (rides.isEmpty()) {
                Text(
                    "No rides yet. Connect to the scooter and ride.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                rides.take(5).forEach { ride ->
                    val df = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
                    val whenText = df.format(Date(ride.startedAt))
                    val dist = String.format(Locale.US, "%.1f km", ride.distanceKm)
                    val dur = if (ride.inProgress) {
                        "In progress · ${ride.durationMinutes()} min"
                    } else {
                        "${ride.durationMinutes()} min"
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                whenText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                dur,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(dist, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** True when this package is on the battery-optimisation allowlist. */
private fun isDozeExempt(ctx: Context): Boolean {
    val pm = ctx.getSystemService(android.os.PowerManager::class.java) ?: return false
    return runCatching { pm.isIgnoringBatteryOptimizations(ctx.packageName) }
        .getOrDefault(false)
}

/**
 * Light / Dark / Auto.
 *
 * A segmented row rather than a switch: the middle option is "follow the
 * system", and a boolean cannot express three states without lying about one
 * of them.
 */
@Composable
private fun AppearanceCard() {
    val ctx = LocalContext.current
    val current = ThemePrefs.mode

    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = current == mode,
                    onClick = { ThemePrefs.set(ctx, mode) },
                    label = { Text(mode.label, textAlign = TextAlign.Center) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
