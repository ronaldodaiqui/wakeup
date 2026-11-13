package app.wakeup

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.time.LocalTime

/** The page that shows over the lock screen while an alarm rings. */
class RingActivity : ComponentActivity() {

    /** True while the mission is on screen (alarm quiet). */
    private val inMission = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (AlarmService.state.value == null) {
            finish()
            return
        }
        setContent {
            WakeTheme {
                RingScreen(inMission = inMission.value, setInMission = { inMission.value = it }, close = { finish() })
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the alarm screen mid-mission? It gets loud again.
        if (AlarmService.state.value != null && !isFinishing) {
            inMission.value = false
            AlarmService.instance?.setMuted(false)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val volumeKey = keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        if (volumeKey && AlarmService.state.value != null) return true // volume buttons do nothing
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
private fun RingScreen(inMission: Boolean, setInMission: (Boolean) -> Unit, close: () -> Unit) {
    val state by AlarmService.state.collectAsStateWithLifecycle()
    val s = state
    LaunchedEffect(s == null) { if (s == null) close() }
    BackHandler(enabled = true) { /* no escaping */ }
    if (s == null) return

    val service = AlarmService.instance

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().imePadding(),
    ) {
        when {
            s.check -> CheckFace(s, onAwake = { service?.confirmAwake() })
            inMission -> MissionHost(
                alarm = s.alarm,
                onDone = { service?.dismiss() },
                onTimeout = {
                    setInMission(false)
                    service?.setMuted(false)
                },
            )
            else -> AlarmFace(
                s,
                onStart = {
                    if (s.alarm.mission == Mission.NONE) service?.dismiss()
                    else {
                        setInMission(true)
                        service?.setMuted(true)
                    }
                },
                onSnooze = { service?.snooze() },
            )
        }
    }
}

@Composable
private fun Clock() {
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = LocalTime.now(); delay(1000) } }
    val (t, ap) = timeParts(ctx, now.hour, now.minute)
    Row(verticalAlignment = Alignment.Bottom) {
        Text(t, fontSize = 88.sp, fontWeight = FontWeight.Bold, lineHeight = 88.sp)
        if (ap.isNotEmpty()) Text(" $ap", fontSize = 28.sp, color = Dim, modifier = Modifier.padding(bottom = 14.dp))
    }
}

@Composable
private fun Glow() {
    val t = rememberInfiniteTransition(label = "glow")
    val scale by t.animateFloat(
        0.85f, 1.15f,
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scale",
    )
    Box(
        Modifier.size(260.dp).scale(scale).clip(CircleShape)
            .background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Sun.copy(alpha = 0.45f), Sun.copy(alpha = 0f)))),
    )
}

@Composable
private fun AlarmFace(s: RingState, onStart: () -> Unit, onSnooze: () -> Unit) {
    val a = s.alarm
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Box(contentAlignment = Alignment.Center) {
            Glow()
            Clock()
        }
        if (a.label.isNotBlank()) {
            Text(a.label, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        }
        if (s.test) Text("Test alarm", color = Dim)
        Spacer(Modifier.weight(1f))
        if (a.mission != Mission.NONE) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MissionBadge(a.mission)
                Spacer(Modifier.width(8.dp))
                Text(a.missionSummary(), color = Dim)
            }
            Spacer(Modifier.height(16.dp))
        }
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().height(72.dp),
            shape = RoundedCornerShape(24.dp),
        ) {
            Text(if (a.mission == Mission.NONE) "Turn off" else "Start mission", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        if (s.canSnooze) {
            TextButton(onClick = onSnooze, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                val left = if (s.snoozesLeft < 0) "" else " · ${s.snoozesLeft} left"
                Text("Snooze ${a.snoozeMinutes} min$left", fontSize = 18.sp, color = Dim)
            }
        } else {
            Spacer(Modifier.height(56.dp))
        }
    }
}

@Composable
private fun MissionHost(alarm: Alarm, onDone: () -> Unit, onTimeout: () -> Unit) {
    val limit = alarm.mission.idleLimitMs()
    var lastActive by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var done by remember { mutableStateOf(false) }
    val poke = { lastActive = System.currentTimeMillis() }

    LaunchedEffect(Unit) {
        while (true) {
            delay(200)
            now = System.currentTimeMillis()
            if (!done && now - lastActive > limit) {
                onTimeout()
                break
            }
        }
    }

    Column(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                    lastActive = System.currentTimeMillis()
                }
            }
        },
    ) {
        val remaining = (1f - (now - lastActive).toFloat() / limit).coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { remaining },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = if (remaining < 0.3f) Bad else Sun,
            trackColor = Card,
            drawStopIndicator = {},
        )
        MissionContent(
            alarm = alarm,
            onActivity = poke,
            onDone = {
                if (!done) {
                    done = true
                    onDone()
                }
            },
        )
    }
}

@Composable
private fun CheckFace(s: RingState, onAwake: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(250) } }
    val secs = ((s.checkDeadline - now) / 1000).coerceAtLeast(0)
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Still awake?", fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("The alarm rings again in", color = Dim)
        Text("$secs", fontSize = 96.sp, fontWeight = FontWeight.Bold, color = Lav)
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onAwake,
            modifier = Modifier.fillMaxWidth().height(72.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Sun),
        ) {
            Text("I'm awake", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
