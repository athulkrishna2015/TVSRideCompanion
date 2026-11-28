package dev.tvsopen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tvsopen.log.Level
import dev.tvsopen.log.Log
import dev.tvsopen.log.LogRecord

/**
 * Live log viewer.
 *
 * This is a debugging surface, not decoration. When the app is pointed at a
 * real scooter, this screen — and the exported file behind it — is the evidence
 * that every protocol fix was based on something observed rather than assumed.
 */
@Composable
fun LogScreen(modifier: Modifier = Modifier) {

    val ctx = LocalContext.current
    val records by Log.records.collectAsStateWithLifecycle()
    val frameCount by Log.frameCount.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    // Default to INFO, not DEBUG. Raw hex frames are DEBUG records, and a live
    // link produces one per channel per second at best; opening the tab on
    // DEBUG buried the events that explain a session (service created,
    // connected, subscribed, decoded) under a wall of telemetry. The frames are
    // one tap away and the caption below says so.
    var minLevel by remember { mutableStateOf(Level.INFO) }
    var autoScroll by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // Let the rider pick where the bundle goes (Downloads, Drive, ...). The
    // app-scoped Android/data folder is hidden from most file managers, so a
    // file saved there was effectively lost to anyone without adb.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult // cancelled
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { Log.exportTo(it) } ?: false
                }.getOrDefault(false)
            }
            Toast.makeText(
                ctx,
                if (ok) "Log saved" else "Export failed",
                Toast.LENGTH_SHORT,
            ).show()
            // No URI in the log: it can carry a display name or path.
            if (ok) Log.i(TAG, "exported logs") else Log.w(TAG, "log export failed")
        }
    }

    val visible = remember(records, minLevel) {
        records.filter { it.level >= minLevel }
    }

    // Key on the tail record, not visible.size: snapshot() caps the list at
    // 500, so once full the size never changes and Follow silently stops.
    LaunchedEffect(visible.lastOrNull(), autoScroll) {
        if (autoScroll && visible.isNotEmpty()) {
            listState.animateScrollToItem(visible.lastIndex)
        }
    }

    Column(modifier.fillMaxSize()) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "${frameCount} frames · ${visible.size} shown" +
                        if (minLevel > Level.DEBUG) " · tap DEBUG for raw frames" else "",
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR).forEach { lvl ->
                        FilterChip(
                            selected = minLevel == lvl,
                            onClick = { minLevel = lvl },
                            label = { Text(lvl.name, fontSize = 11.sp) },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { autoScroll = !autoScroll }) {
                        Text(if (autoScroll) "Follow: on" else "Follow: off")
                    }
                    TextButton(onClick = { Log.clear() }) { Text("Clear") }
                    OutlinedButton(onClick = {
                        if (!Log.hasLogFile()) {
                            Toast.makeText(ctx, "No log file yet", Toast.LENGTH_SHORT).show()
                        } else {
                            exportLauncher.launch(Log.exportFileName("txt"))
                        }
                    }) { Text("Export…") }
                }
            }
        }

        HorizontalDivider()

        if (visible.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null)
                Text(
                    "No records yet. Connect to a scooter, or check the filter level.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 10.dp)) {
                items(visible) { r -> LogRow(r) }
            }
        }
    }
}

private const val TAG = "LogScreen"

@Composable
private fun LogRow(r: LogRecord) {
    val colour = when (r.level) {
        Level.ERROR -> MaterialTheme.colorScheme.error
        Level.WARN -> MaterialTheme.colorScheme.tertiary
        Level.INFO -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // BLE frames get their own colour: they are what you actually read.
    val isFrame = r.tag == "BLE"
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = if (isFrame) r.message else r.format(),
            color = if (isFrame) MaterialTheme.colorScheme.onSurface else colour,
            fontFamily = FontFamily.Monospace,
            fontSize = if (isFrame) 12.sp else 11.sp,
            fontWeight = if (isFrame) FontWeight.Medium else FontWeight.Normal,
        )
        r.throwable?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
    }
}