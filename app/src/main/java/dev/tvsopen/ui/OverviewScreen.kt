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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tvsopen.session.Telemetry
import dev.tvsopen.ui.theme.LocalTilePalette
import dev.tvsopen.ui.theme.NumberStyle

/**
 * Vehicle Overview.
 *
 * Laid out from what TVS's own app shows for an Ntorq: top speed, top RPM, best
 * 0-60, the fuel gauge, then distance travelled.
 *
 * ## Why these and not the old tile grid
 *
 * The previous dashboard put six values in a 2+3 tile grid. Those are the same
 * six quantities — they are real cluster display elements, per TVS's Ntorq 125
 * manual (odometer 1a, fuel 1c, speedometer 1d, engine temperature 1e, service
 * 1h, trip A/B/F 1k) — but a glanceable tile is the wrong shape for them. This
 * screen groups them the way the hardware presents them and adds the two the
 * official app shows that we can also produce locally.
 *
 * ## What is absent, and why
 *
 * The official Overview has a **Ride mode** selector (Race / Race x Street /
 * Street). Ride modes are server-side in TVS's app, tied to an account and a
 * ride history. This project has neither, so there is no mode to select. A
 * control that cannot do anything is worse than an absent one.
 *
 * "Total Rides" and the ride list are also cloud data and are not shown.
 */
@Composable
fun OverviewScreen(
    telemetry: Telemetry,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        Text("Vehicle Overview", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Live from the instrument cluster",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))

        // The three headline figures, as the official app shows them flanking
        // the cluster graphic.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigStat(
                Modifier.weight(1f),
                Icons.Filled.Speed,
                telemetry.topSpeedKmh?.toString() ?: "—",
                "km/h",
                "Top Speed",
                LocalTilePalette.current.largeLeft,
            )
            BigStat(
                Modifier.weight(1f),
                Icons.Filled.Bolt,
                telemetry.engineRpm?.toString() ?: "—",
                "rpm",
                "Top RPM",
                LocalTilePalette.current.largeRight,
            )
        }

        Spacer(Modifier.height(12.dp))
        BigStat(
            Modifier.fillMaxWidth(),
            Icons.Filled.Timer,
            telemetry.timeTo60Sec?.let { String.format("%.1f", it) } ?: "—",
            "sec",
            "Best 0–60",
            LocalTilePalette.current.neutral,
        )

        Spacer(Modifier.height(20.dp))
        SectionLabel("Fuel")
        FuelGauge(telemetry.fuelBars)

        Spacer(Modifier.height(20.dp))
        SectionLabel("Distance Travelled")

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DistanceStat(
                Modifier.weight(1f),
                Icons.Filled.Route,
                telemetry.odometerKm?.let { String.format("%.1f", it) } ?: "—",
                "Total distance",
            )
            DistanceStat(
                Modifier.weight(1f),
                Icons.Filled.Route,
                telemetry.connectedDistanceKm()
                    ?.let { String.format("%.1f", it) } ?: "—",
                "This connection",
            )
        }

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
/**
 * Eyebrow section label, per `DESIGN.md` (`badge-pill` / `caption-strong`:
 * small uppercase muted label). Text-only: on a dense telemetry screen a
 * filled pill behind every heading would be noise.
 */
@Composable
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
private fun BigStat(
    modifier: Modifier,
    icon: ImageVector,
    value: String,
    unit: String,
    label: String,
    background: Color,
) {
    Surface(
        modifier.height(140.dp),
        shape = RoundedCornerShape(24.dp),
        color = background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    style = NumberStyle,
                    fontSize = 28.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    unit,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
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
private fun DistanceStat(
    modifier: Modifier,
    icon: ImageVector,
    value: String,
    label: String,
) {
    Surface(
        modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(16.dp)) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                value,
                style = NumberStyle,
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Six-segment fuel gauge.
 *
 * Six, not five, because TVS's manual says six for this cluster — and a
 * clamp at five silently discarded a full tank. Segments fill from the E side,
 * matching the official app's E…F bar. Renders as dashes rather than a filled
 * bar so "no data" and "empty" cannot be confused.
 */
@Composable
private fun FuelGauge(bars: Int?) {
    val filled = bars ?: 0
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "E",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    repeat(Telemetry.FUEL_BAR_MAX) { i ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(22.dp)
                                .background(
                                    if (bars != null && i < filled)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline
                                        .copy(alpha = 0.35f),
                                    RoundedCornerShape(4.dp),
                                ),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "F",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                // Three states, not two: a real level, no frame at all yet, and
                // a frame whose value is outside the six-bar gauge and so cannot
                // be a bar count. Collapsing the third into the second would
                // report a level the cluster never sent.
                if (bars != null) "$filled of ${Telemetry.FUEL_BAR_MAX} bars"
                else "No usable reading",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
