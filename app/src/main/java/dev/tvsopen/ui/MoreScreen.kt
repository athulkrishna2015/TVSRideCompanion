package dev.tvsopen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.tvsopen.Nucleus
import dev.tvsopen.protocol.Confidence
import dev.tvsopen.protocol.Pictograms

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
            Text("Connection", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(connection.model.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("State: ${connection.state}")
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
                }
            }
        }

        item {
            Text("Permissions", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (notificationAccessGranted)
                            "Notification access: granted"
                        else
                            "Notification access: required to read navigation",
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
            Text("Protocol transparency", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
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
                    Nucleus.describe().forEach { (model, facts) ->
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
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Science, null)
                    Text("TVSRide", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "No account. No TVS server. No INTERNET permission.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}