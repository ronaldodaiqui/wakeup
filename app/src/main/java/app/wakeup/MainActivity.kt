package app.wakeup

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        AlarmStore.init(this)
        setContent { WakeTheme { AppRoot() } }
    }

    override fun onResume() {
        super.onResume()
        // While an alarm is ringing, the app only ever shows the alarm.
        if (AlarmService.state.value != null) {
            startActivity(Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

private sealed interface Screen {
    data object List : Screen
    data class Edit(val alarm: Alarm, val isNew: Boolean) : Screen
    data object Settings : Screen
    /** Permissions page; [back] is where Back goes. */
    data class Permissions(val back: Screen) : Screen
}

@Composable
private fun AppRoot() {
    var screen by remember {
        mutableStateOf<Screen>(if (AlarmStore.setupSeen) Screen.List else Screen.Permissions(back = Screen.List))
    }
    when (val s = screen) {
        Screen.List -> AlarmList(
            onNew = { screen = Screen.Edit(AlarmStore.template(), isNew = true) },
            onEdit = { screen = Screen.Edit(it, isNew = false) },
            onSettings = { screen = Screen.Settings },
            onPermissions = { screen = Screen.Permissions(back = Screen.List) },
        )
        is Screen.Edit -> {
            BackHandler { screen = Screen.List }
            AlarmEditor(s.alarm, s.isNew, close = { screen = Screen.List })
        }
        Screen.Settings -> {
            BackHandler { screen = Screen.List }
            SettingsScreen(
                back = { screen = Screen.List },
                onPermissions = { screen = Screen.Permissions(back = Screen.Settings) },
            )
        }
        is Screen.Permissions -> {
            val leave = { AlarmStore.setupSeen = true; screen = s.back }
            BackHandler { leave() }
            SetupScreen(done = leave)
        }
    }
}

/** Ticks every 20 s so "rings in …" text stays fresh. */
@Composable
fun rememberNow(periodMs: Long = 20_000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) { while (true) { delay(periodMs); now = System.currentTimeMillis() } }
    return now
}

fun ringsInToast(ctx: android.content.Context, a: Alarm) {
    val ms = a.nextTrigger().toMillis() - System.currentTimeMillis()
    Toast.makeText(ctx, "Rings in ${durationText(ms)}", Toast.LENGTH_SHORT).show()
}

@Composable
private fun AlarmList(onNew: () -> Unit, onEdit: (Alarm) -> Unit, onSettings: () -> Unit, onPermissions: () -> Unit) {
    val ctx = LocalContext.current
    val alarms by AlarmStore.alarms.collectAsStateWithLifecycle()
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    val now = rememberNow()
    val setupOk = remember(resumeTick) { Setup.essentialOk(ctx) }
    val next = remember(alarms, now) { Scheduler.nextRing(alarms) }
    var typingFor by remember { mutableStateOf<Alarm?>(null) }

    typingFor?.let { a ->
        TimeEntryDialog(
            hour = a.hour, minute = a.minute,
            onDone = { h, m ->
                Scheduler.cancelAll(ctx, a.id)
                AlarmStore.resetSnoozes(a.id)
                val updated = AlarmStore.put(a.copy(hour = h, minute = m, enabled = true))
                Scheduler.schedule(ctx, updated)
                ringsInToast(ctx, updated)
                typingFor = null
            },
            onDismiss = { typingFor = null },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("New alarm", fontWeight = FontWeight.SemiBold) },
                containerColor = Sun,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = pad.calculateTopPadding() + 8.dp,
                bottom = pad.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        Text(
                            if (next == null) "No alarms on" else "Next alarm in",
                            color = Dim, style = MaterialTheme.typography.titleMedium,
                        )
                        if (next != null) {
                            Text(
                                durationText(next.second - now),
                                fontSize = 34.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings", tint = Dim) }
                }
            }
            if (!setupOk) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .background(Bad.copy(alpha = 0.15f)).clickable(onClick = onPermissions).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Warning, null, tint = Bad)
                        Spacer(Modifier.width(12.dp))
                        Text("Alarms might not ring. Tap to finish setup.", color = Bad)
                    }
                }
            }
            if (alarms.isEmpty()) {
                item {
                    Text(
                        "Tap New alarm to make one.",
                        color = Dim, modifier = Modifier.padding(vertical = 32.dp).fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            items(alarms, key = { it.id }) { a ->
                AlarmCard(
                    a, now,
                    onClick = { onEdit(a) },
                    onTimeClick = { typingFor = a },
                    onToggle = { on ->
                        val updated = AlarmStore.put(a.copy(enabled = on))
                        if (on) {
                            Scheduler.schedule(ctx, updated)
                            ringsInToast(ctx, updated)
                        } else {
                            Scheduler.cancelAll(ctx, a.id)
                            AlarmStore.resetSnoozes(a.id)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun AlarmCard(a: Alarm, now: Long, onClick: () -> Unit, onTimeClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val (t, ap) = timeParts(ctx, a.hour, a.minute)
    val faded = if (a.enabled) 1f else 0.45f
    val snoozeAt = AlarmStore.snoozeUntil(a.id)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card)
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onTimeClick),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(t, fontSize = 40.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = faded))
                if (ap.isNotEmpty()) {
                    Text(" $ap", fontSize = 16.sp, color = Dim.copy(alpha = faded), modifier = Modifier.padding(bottom = 7.dp))
                }
            }
            val sub = buildString {
                append(daysSummary(a.days))
                if (a.label.isNotBlank()) append(" · ${a.label}")
            }
            Text(sub, color = Dim.copy(alpha = faded))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                MissionBadge(a.mission)
                Spacer(Modifier.width(6.dp))
                Text(a.missionSummary(), color = Dim.copy(alpha = faded), style = MaterialTheme.typography.bodySmall)
            }
            if (a.enabled && snoozeAt > now) {
                val z = millisToZoned(snoozeAt)
                Text(
                    "Snoozed until ${formatTime(ctx, z.hour, z.minute)}",
                    color = Sun, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Switch(
            checked = a.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedTrackColor = Sun),
        )
    }
}

// ---------------- Setup ----------------

@Composable
private fun SetupScreen(done: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    data class Item(val title: String, val why: String, val ok: Boolean, val required: Boolean, val fix: () -> Unit)

    val items = remember(tick) {
        listOf(
            Item(
                "Notifications", "Needed to show the alarm screen.",
                Setup.notificationsOk(ctx), true,
            ) {
                if (Build.VERSION.SDK_INT >= 33 && !ctx.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else ctx.launchSafely(Setup.notificationSettings(ctx))
            },
            Item(
                "Full-screen alarms", "Lets the alarm take over the lock screen.",
                Setup.fullScreenOk(ctx), true,
            ) { ctx.launchSafely(Setup.fullScreenSettings(ctx)) },
            Item(
                "Exact alarms", "Rings at exactly the right minute.",
                Setup.exactOk(ctx), true,
            ) { ctx.launchSafely(Setup.exactSettings(ctx)) },
            Item(
                "Ignore battery saving", "Stops the phone from putting Wakeup to sleep. Set battery to \"Unrestricted\" if asked.",
                Setup.batteryOk(ctx), false,
            ) { ctx.launchSafely(Setup.batterySettings(ctx)) },
            Item(
                "Display over other apps", "Opens the alarm straight away even if you're using your phone.",
                Setup.overlayOk(ctx), false,
            ) { ctx.launchSafely(Setup.overlaySettings(ctx)) },
        )
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = done) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Permissions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Text(
            "Android can block alarm apps in a few ways. Allow these once and Wakeup will ring every time.",
            color = Dim, modifier = Modifier.padding(vertical = 12.dp),
        )
        items.forEach { item ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape)
                        .background(if (item.ok) Good.copy(alpha = 0.2f) else CardHi),
                    contentAlignment = Alignment.Center,
                ) {
                    if (item.ok) Icon(Icons.Filled.CheckCircle, null, tint = Good, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title + if (item.required) "" else "  (recommended)", fontWeight = FontWeight.SemiBold)
                    Text(item.why, color = Dim, style = MaterialTheme.typography.bodySmall)
                }
                if (!item.ok) {
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = item.fix) { Text("Allow") }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        OutlinedButton(
            onClick = {
                AlarmStore.setTestAlarm(Alarm(mission = Mission.MATH, missionLevel = 0, missionAmount = 1, snoozeLimit = 0))
                AlarmService.start(ctx, AlarmStore.TEST_ID, Scheduler.KIND_RING)
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Try a test alarm now") }
        Spacer(Modifier.height(10.dp))
        Button(onClick = done, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Done", fontWeight = FontWeight.SemiBold)
        }
    }
}
