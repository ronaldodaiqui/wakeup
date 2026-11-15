package app.wakeup

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlarmEditor(initial: Alarm, isNew: Boolean, close: () -> Unit) {
    val ctx = LocalContext.current
    var a by remember { mutableStateOf(initial) }
    var scanning by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    val now = rememberNow()

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scanning = true
        else Toast.makeText(ctx, "The barcode mission needs the camera.", Toast.LENGTH_SHORT).show()
    }
    val stepsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val soundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val uri = r.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
            a = a.copy(sound = uri?.toString() ?: "")
        }
    }

    fun startScan() {
        if (ctx.hasPermission(Manifest.permission.CAMERA)) scanning = true
        else cameraLauncher.launch(Manifest.permission.CAMERA)
    }

    fun save() {
        if (a.mission == Mission.BARCODE && a.barcode.isEmpty()) {
            Toast.makeText(ctx, "Scan a code for the barcode mission first.", Toast.LENGTH_SHORT).show()
            startScan()
            return
        }
        Scheduler.cancelAll(ctx, a.id)
        AlarmStore.resetSnoozes(a.id)
        val saved = AlarmStore.put(a.copy(enabled = true))
        Scheduler.schedule(ctx, saved)
        AlarmStore.rememberTemplate(saved)
        ringsInToast(ctx, saved)
        close()
    }

    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
    Column(Modifier.fillMaxSize()) {
        // Top bar
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel") }
            Text(
                if (isNew) "New alarm" else "Edit alarm",
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f),
            )
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            val ringsIn = remember(a.hour, a.minute, a.days, now) {
                durationText(a.copy(enabled = true).nextTrigger().toMillis() - System.currentTimeMillis())
            }
            Text(
                "Rings in $ringsIn", color = Sun, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                style = MaterialTheme.typography.titleMedium,
            )

            TimeWheels(a.hour, a.minute, onTap = { typing = true }) { h, m -> a = a.copy(hour = h, minute = m) }
            Row(
                Modifier.fillMaxWidth().clickable { typing = true }.padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Dialpad, null, tint = Lav, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Tap the time to type it", color = Dim, style = MaterialTheme.typography.bodySmall)
            }

            // Quick picks: "in 8 h" etc. (from right now, rings once)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val quick = listOf("10 min" to 10, "30 min" to 30, "6 h" to 360, "7 h" to 420, "7½ h" to 450, "8 h" to 480, "9 h" to 540)
                items(quick) { (label, mins) ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            val t = LocalDateTime.now().plusMinutes(mins.toLong())
                            a = a.copy(hour = t.hour, minute = t.minute, days = 0)
                        },
                        label = { Text("In $label") },
                    )
                }
            }

            SectionTitle("Repeat")
            DaysRow(a) { a = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                SmallChip("Once", a.days == 0) { a = a.copy(days = 0) }
                SmallChip("Weekdays", a.days == 0b0011111) { a = a.copy(days = 0b0011111) }
                SmallChip("Every day", a.days == 0b1111111) { a = a.copy(days = 0b1111111) }
            }

            SectionTitle("Mission")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mission.entries.forEach { m ->
                    FilterChip(
                        selected = a.mission == m,
                        onClick = {
                            if (a.mission != m) {
                                a = a.copy(mission = m, missionAmount = m.defaultAmount, missionLevel = 1)
                                if (m == Mission.STEPS && Build.VERSION.SDK_INT >= 29 &&
                                    !ctx.hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)
                                ) stepsLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                                if (m == Mission.BARCODE && a.barcode.isEmpty()) startScan()
                            }
                        },
                        label = { Text(m.title) },
                        leadingIcon = { Icon(m.icon, null, modifier = Modifier.size(18.dp)) },
                        colors = chipColors(),
                    )
                }
            }
            MissionOptions(a, onChange = { a = it }, onScan = ::startScan)

            SectionTitle("Sound")
            val soundName = remember(a.sound) {
                if (a.sound.isEmpty()) "Phone's alarm sound"
                else runCatching { RingtoneManager.getRingtone(ctx, Uri.parse(a.sound))?.getTitle(ctx) }.getOrNull() ?: "Custom sound"
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card)
                    .clickable {
                        val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarm sound")
                            .putExtra(
                                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                if (a.sound.isEmpty()) android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI else Uri.parse(a.sound),
                            )
                        runCatching { soundLauncher.launch(i) }
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.MusicNote, null, tint = Lav)
                Spacer(Modifier.width(12.dp))
                Text(soundName, modifier = Modifier.weight(1f))
                Text("Change", color = Dim)
            }
            ToggleRow("Vibrate", null, a.vibrate) { a = a.copy(vibrate = it) }
            ToggleRow(
                "Gentle start",
                "Starts quiet and gets louder over 30 seconds.",
                a.gentle,
            ) { a = a.copy(gentle = it) }

            SectionTitle("Snooze")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Off", 1 to "1×", 2 to "2×", 3 to "3×", -1 to "No limit").forEach { (v, l) ->
                    SmallChip(l, a.snoozeLimit == v) { a = a.copy(snoozeLimit = v) }
                }
            }
            if (a.snoozeLimit != 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    listOf(3, 5, 10, 15).forEach { m ->
                        SmallChip("$m min", a.snoozeMinutes == m) { a = a.copy(snoozeMinutes = m) }
                    }
                }
            }

            SectionTitle("Extra")
            ToggleRow(
                "Wake-up check",
                "5 minutes after you turn it off, tap \"I'm awake\" within a minute or it rings again.",
                a.wakeCheck,
            ) { a = a.copy(wakeCheck = it) }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = a.label,
                onValueChange = { a = a.copy(label = it.take(40)) },
                label = { Text("Label (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))
            OutlinedButton(
                onClick = {
                    if (a.mission == Mission.BARCODE && a.barcode.isEmpty()) {
                        startScan()
                    } else {
                        AlarmStore.setTestAlarm(a)
                        AlarmService.start(ctx, AlarmStore.TEST_ID, Scheduler.KIND_RING)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Test it now") }
            if (!isNew) {
                TextButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 4.dp),
                ) { Text("Delete alarm", color = Bad) }
            }
            Spacer(Modifier.height(104.dp)) // room to scroll past the Save button
        }
    }

        // Save sits bottom-right, where your thumb is.
        ExtendedFloatingActionButton(
            onClick = ::save,
            icon = { Icon(Icons.Filled.Check, null) },
            text = { Text("Save", fontWeight = FontWeight.SemiBold, fontSize = 18.sp) },
            containerColor = Sun,
            contentColor = OnAccent,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
    }

    if (scanning) {
        RegisterCodeDialog(
            onCode = { code ->
                a = a.copy(barcode = code, mission = Mission.BARCODE)
                scanning = false
                Toast.makeText(ctx, "Code saved", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { scanning = false },
        )
    }

    if (typing) {
        TimeEntryDialog(
            hour = a.hour, minute = a.minute,
            onDone = { h, m -> a = a.copy(hour = h, minute = m); typing = false },
            onDismiss = { typing = false },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this alarm?") },
            confirmButton = {
                TextButton(onClick = {
                    Scheduler.cancelAll(ctx, a.id)
                    AlarmStore.delete(a.id)
                    confirmDelete = false
                    close()
                }) { Text("Delete", color = Bad) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = SunSoft,
    selectedLeadingIconColor = Sun,
)

@Composable
private fun SectionTitle(t: String) {
    Text(
        t, color = Dim, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 22.dp, bottom = 8.dp),
    )
}

@Composable
private fun SmallChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, colors = chipColors())
}

@Composable
private fun ToggleRow(title: String, desc: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (desc != null) Text(desc, color = Dim, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = value, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Sun))
    }
}

@Composable
private fun DaysRow(a: Alarm, onChange: (Alarm) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        weekOrder().forEach { d ->
            val on = a.hasDay(d)
            Box(
                Modifier.size(42.dp).clip(CircleShape)
                    .background(if (on) Sun else Card)
                    .clickable { onChange(a.withDay(d, !on)) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    d.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun MissionOptions(a: Alarm, onChange: (Alarm) -> Unit, onScan: () -> Unit) {
    val m = a.mission
    if (m.levels.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            m.levels.forEachIndexed { i, l -> SmallChip(l, a.missionLevel == i) { onChange(a.copy(missionLevel = i)) } }
        }
        if (m == Mission.MATH) {
            Text(
                "Example: " + makeProblem(a.missionLevel, kotlin.random.Random(7)).text,
                color = Dim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    if (m.amounts.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            m.amounts.forEach { n -> SmallChip(m.amountLabel(n), a.missionAmount == n) { onChange(a.copy(missionAmount = n)) } }
        }
    }
    when (m) {
        Mission.BARCODE -> Row(
            Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(Card).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (a.barcode.isEmpty()) "No code yet" else "Code saved", fontWeight = FontWeight.SemiBold)
                Text(
                    if (a.barcode.isEmpty()) "Scan any barcode or QR code, like your toothpaste or shampoo. To turn the alarm off you'll have to go scan it again."
                    else a.barcode.take(32),
                    color = Dim, style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onScan) { Text(if (a.barcode.isEmpty()) "Scan" else "Rescan") }
        }
        Mission.NONE -> Text(
            "Just a Turn off button. Easy to sleep through!",
            color = Dim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
        )
        else -> {}
    }
}

@Composable
private fun RegisterCodeDialog(onCode: (String) -> Unit, onDismiss: () -> Unit) {
    var got by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            BarcodeScanner(
                onCode = { if (!got) { got = true; onCode(it) } },
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.6f))
                    .navigationBarsPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Point at a barcode or QR code", fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(
                    "Pick something that stays in another room, like the bathroom.",
                    color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center,
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Filled.Close, "Close", tint = Color.White)
            }
        }
    }
}

// ---------------- Time wheels ----------------

@Composable
private fun TimeWheels(hour: Int, minute: Int, onTap: () -> Unit, onChange: (Int, Int) -> Unit) {
    val ctx = LocalContext.current
    val h24 = remember { is24h(ctx) }
    val itemH = 64.dp
    val latestHour by rememberUpdatedState(hour)
    val latestMinute by rememberUpdatedState(minute)

    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        // Selection band
        Box(Modifier.fillMaxWidth().height(itemH).clip(RoundedCornerShape(18.dp)).background(Card))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (h24) {
                WheelPicker(24, hour, { onChange(it, latestMinute) }, { "%02d".format(it) }, itemH, 96.dp, onTap)
            } else {
                WheelPicker(
                    12, hour % 12,
                    { v -> onChange(v + if (latestHour >= 12) 12 else 0, latestMinute) },
                    { if (it == 0) "12" else "$it" }, itemH, 96.dp, onTap,
                )
            }
            Text(":", fontSize = 44.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
            WheelPicker(60, minute, { onChange(latestHour, it) }, { "%02d".format(it) }, itemH, 96.dp, onTap)
            if (!h24) {
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("AM" to false, "PM" to true).forEach { (l, pm) ->
                        val sel = (hour >= 12) == pm
                        Box(
                            Modifier.width(60.dp).height(40.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (sel) Sun else CardHi)
                                .clickable { onChange(hour % 12 + if (pm) 12 else 0, minute) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                l, fontWeight = FontWeight.SemiBold,
                                color = if (sel) MaterialTheme.colorScheme.onPrimary else Dim,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A looping, snapping number wheel. Shows 3 rows; the middle one is the value. */
@Composable
private fun WheelPicker(
    count: Int,
    value: Int,
    onValue: (Int) -> Unit,
    label: (Int) -> String,
    itemH: Dp,
    width: Dp,
    onTap: () -> Unit,
) {
    val loops = 400
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (loops / 2) * count + value - 1)
    val fling = rememberSnapFlingBehavior(lazyListState = state)
    val halfPx = with(LocalDensity.current) { itemH.toPx() } / 2f
    val latestOnValue by rememberUpdatedState(onValue)

    val centerIndex by remember {
        derivedStateOf {
            state.firstVisibleItemIndex + (if (state.firstVisibleItemScrollOffset > halfPx) 1 else 0) + 1
        }
    }

    // Report the value under the band as you scroll.
    LaunchedEffect(state) {
        snapshotFlow { centerIndex.mod(count) }
            .distinctUntilChanged()
            .collect { latestOnValue(it) }
    }

    // Jump when the value is changed from outside (quick picks, AM/PM).
    LaunchedEffect(value) {
        val cur = centerIndex.mod(count)
        if (cur != value && !state.isScrollInProgress) {
            var target = centerIndex - cur + value
            if (value - cur > count / 2) target -= count
            if (cur - value > count / 2) target += count
            state.scrollToItem(target - 1)
        }
    }

    LazyColumn(
        state = state,
        flingBehavior = fling,
        modifier = Modifier.width(width).height(itemH * 3),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(count * loops) { i ->
            val selected = i == centerIndex
            Box(
                Modifier.height(itemH).fillMaxWidth().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onTap,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(i.mod(count)),
                    fontSize = if (selected) 48.sp else 34.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onBackground else Dim.copy(alpha = 0.5f),
                )
            }
        }
    }
}
