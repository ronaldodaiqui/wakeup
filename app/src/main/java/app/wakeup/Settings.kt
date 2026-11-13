package app.wakeup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate

@Composable
fun SettingsScreen(back: () -> Unit, onPermissions: () -> Unit) {
    val ctx = LocalContext.current
    val alarms by AlarmStore.alarms.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    var nudgeOn by remember { mutableStateOf(Bedtime.enabled(ctx)) }
    var nudgeMin by remember { mutableIntStateOf(Bedtime.minutesBefore(ctx)) }
    val permsOk = remember(tick) { Setup.essentialOk(ctx) }
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {

            // ---- Permissions
            Heading("Permissions")
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card)
                    .clickable(onClick = onPermissions).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (permsOk) Icons.Filled.CheckCircle else Icons.Filled.Warning, null,
                    tint = if (permsOk) Good else Bad,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Make sure alarms ring", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (permsOk) "All set" else "Some permissions need allowing",
                        color = if (permsOk) Dim else Bad, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Dim)
            }

            // ---- Bedtime nudge
            Heading("Bedtime nudge")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable {
                        nudgeOn = !nudgeOn
                        Bedtime.setEnabled(ctx, nudgeOn)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Bedtime, null, tint = Lav)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Remind me to go to sleep", fontWeight = FontWeight.SemiBold)
                        Text(
                            "A quiet notification when it's time for bed.",
                            color = Dim, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = nudgeOn,
                        onCheckedChange = { nudgeOn = it; Bedtime.setEnabled(ctx, it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Sun),
                    )
                }
                if (nudgeOn) {
                    Text(
                        "How long before your alarm",
                        color = Dim, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Bedtime.choices.forEach { m ->
                            FilterChip(
                                selected = nudgeMin == m,
                                onClick = { nudgeMin = m; Bedtime.setMinutesBefore(ctx, m) },
                                label = { Text(Bedtime.label(m)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = SunSoft,
                                ),
                            )
                        }
                    }
                    val next = remember(alarms, nudgeMin) { Bedtime.nextNudge(alarms, nudgeMin.toLong()) }
                    Text(
                        if (next == null) "No alarms coming up to remind you about."
                        else "Next one: " + whenText(ctx, next.first),
                        color = Dim, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            // ---- Colors
            Heading("Colors")
            PaletteList()

            Spacer(Modifier.height(24.dp))
            Text(
                "Wakeup $version", color = Dim, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Heading(t: String) {
    Text(
        t, color = Dim, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

/** "tonight at 10:30 PM", "tomorrow at 9:00 PM", "Sat at 10:30 PM" */
private fun whenText(ctx: android.content.Context, ms: Long): String {
    val z = millisToZoned(ms)
    val today = LocalDate.now()
    val day = when (z.toLocalDate()) {
        today -> if (z.hour >= 17) "tonight" else "today"
        today.plusDays(1) -> "tomorrow"
        else -> z.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
    }
    return "$day at ${formatTime(ctx, z.hour, z.minute)}"
}
