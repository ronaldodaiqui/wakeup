package app.wakeup

import android.Manifest
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import kotlin.math.sqrt
import kotlin.random.Random

val Mission.icon: ImageVector
    get() = when (this) {
        Mission.NONE -> Icons.Filled.NotificationsActive
        Mission.MATH -> Icons.Filled.Calculate
        Mission.TYPING -> Icons.Filled.Keyboard
        Mission.SHAKE -> Icons.Filled.Vibration
        Mission.MEMORY -> Icons.Filled.GridView
        Mission.STEPS -> Icons.Filled.DirectionsWalk
        Mission.BARCODE -> Icons.Filled.QrCodeScanner
    }

fun Alarm.missionSummary(): String = when (mission) {
    Mission.NONE -> "No mission"
    Mission.BARCODE -> "Scan your barcode"
    else -> buildString {
        append(mission.title)
        mission.levels.getOrNull(missionLevel)?.let { append(" · $it") }
        append(" · ${mission.amountLabel(missionAmount)}")
    }
}

/** How long you can stop interacting before the alarm gets loud again. */
fun Mission.idleLimitMs(): Long = when (this) {
    Mission.BARCODE -> 90_000L // time to walk to wherever the code lives
    Mission.STEPS -> 40_000L
    else -> 30_000L
}

/**
 * Runs the alarm's mission. [onActivity] is called whenever you make progress (keeps the alarm quiet).
 */
@Composable
fun MissionContent(alarm: Alarm, onActivity: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val amount = alarm.missionAmount.coerceAtLeast(1)
    when (alarm.mission) {
        Mission.MATH -> MathMission(alarm.missionLevel, amount, onActivity, onDone)
        Mission.TYPING -> TypingMission(amount, onActivity, onDone)
        Mission.SHAKE -> ShakeMission(amount, onActivity, onDone)
        Mission.MEMORY -> MemoryMission(alarm.missionLevel, amount, onActivity, onDone)
        Mission.STEPS -> StepsMission(amount, onActivity, onDone)
        Mission.BARCODE -> {
            if (alarm.barcode.isNotEmpty() && ctx.hasPermission(Manifest.permission.CAMERA)) {
                BarcodeMission(alarm.barcode, onActivity, onDone)
            } else {
                // No code registered or no camera access: never leave you with no way to turn it off.
                Column {
                    Text(
                        "Camera isn't available, so it's math instead.",
                        color = Dim, modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    MathMission(1, 3, onActivity, onDone)
                }
            }
        }
        Mission.NONE -> LaunchedEffect(Unit) { onDone() }
    }
}

@Composable
private fun MissionHeader(title: String, progress: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Dim)
        if (progress.isNotEmpty()) {
            Text(progress, style = MaterialTheme.typography.titleLarge, color = Lav, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---------------- Math ----------------

data class Problem(val text: String, val answer: Int)

fun makeProblem(level: Int, r: Random = Random): Problem = when (level) {
    0 -> {
        val a = r.nextInt(10, 100); val b = r.nextInt(2, 10)
        Problem("$a + $b", a + b)
    }
    1 -> {
        val a = r.nextInt(10, 100); val b = r.nextInt(10, 100)
        Problem("$a + $b", a + b)
    }
    2 -> {
        val a = r.nextInt(11, 100); val b = r.nextInt(3, 10); val c = r.nextInt(10, 100)
        Problem("$a × $b + $c", a * b + c)
    }
    else -> {
        val a = r.nextInt(11, 100); val b = r.nextInt(11, 100); val c = r.nextInt(100, 1000)
        Problem("$a × $b + $c", a * b + c)
    }
}

@Composable
fun MathMission(level: Int, count: Int, onActivity: () -> Unit, onDone: () -> Unit) {
    var solved by remember { mutableIntStateOf(0) }
    var problem by remember { mutableStateOf(makeProblem(level)) }
    var entry by remember { mutableStateOf("") }
    var wrong by remember { mutableIntStateOf(0) }
    val entryColor by animateColorAsState(if (wrong % 2 == 1) Bad else MaterialTheme.colorScheme.onBackground, label = "entry")

    LaunchedEffect(wrong) {
        if (wrong % 2 == 1) { delay(450); wrong++; entry = "" }
    }

    fun submit() {
        onActivity()
        if (entry.isEmpty()) return
        if (entry.toIntOrNull() == problem.answer) {
            solved++
            entry = ""
            if (solved >= count) onDone() else problem = makeProblem(level)
        } else {
            wrong++
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MissionHeader("Solve it", "${solved + 1} / $count")
        Spacer(Modifier.height(12.dp))
        Text(problem.text + " =", fontSize = 40.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(16.dp)).background(Card),
            contentAlignment = Alignment.Center,
        ) {
            Text(entry.ifEmpty { "?" }, fontSize = 40.sp, color = if (entry.isEmpty()) Dim else entryColor)
        }
        Spacer(Modifier.weight(1f))
        NumberPad(
            onDigit = { onActivity(); if (entry.length < 6) entry += it },
            onDelete = { onActivity(); entry = entry.dropLast(1) },
            onEnter = { submit() },
        )
        Spacer(Modifier.height(16.dp))
    }
}

// ---------------- Typing ----------------

private val PHRASES = listOf(
    "I am awake and ready for today",
    "Feet on the floor and eyes wide open",
    "My bed will still be here tonight",
    "Open the curtains and let the light in",
    "Get up and drink a glass of water",
    "No more snoozing, the day is waiting",
    "Small steps every morning add up",
    "A slow morning beats a rushed one",
    "Make the bed, then make the coffee",
    "Up now means more time later",
    "Today is a good day to start something",
    "Stretch, breathe, and stand up tall",
    "The early hours are quiet and all mine",
    "I chose to wake up at this time",
)

private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()

@Composable
fun TypingMission(count: Int, onActivity: () -> Unit, onDone: () -> Unit) {
    val picks = remember { PHRASES.shuffled().take(count.coerceAtMost(PHRASES.size)) }
    var index by remember { mutableIntStateOf(0) }
    var typed by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val phrase = picks[index]

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MissionHeader("Type it exactly", "${index + 1} / ${picks.size}")
        // Show how far you've matched.
        var matched = 0
        while (matched < phrase.length && matched < typed.length &&
            phrase[matched].lowercaseChar() == typed[matched].lowercaseChar()
        ) matched++
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Sun)) { append(phrase.substring(0, matched)) }
                append(phrase.substring(matched))
            },
            fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        OutlinedTextField(
            value = typed,
            onValueChange = { v ->
                typed = v
                onActivity()
                if (norm(v) == norm(phrase)) {
                    if (index + 1 >= picks.size) onDone() else { index++; typed = "" }
                }
            },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            textStyle = MaterialTheme.typography.titleLarge,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            isError = typed.isNotEmpty() && matched < typed.length,
            placeholder = { Text("Start typing…") },
        )
    }
}

// ---------------- Shake ----------------

@Composable
private fun SensorListener(type: Int, onEvent: (SensorEvent) -> Unit): Boolean {
    val ctx = LocalContext.current
    val latest by rememberUpdatedState(onEvent)
    val sm = remember { ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val sensor = remember { sm.getDefaultSensor(type) }
    DisposableEffect(sensor) {
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) = latest(e)
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(l) }
    }
    return sensor != null
}

@Composable
private fun BigCounter(done: Int, target: Int, label: String, icon: ImageVector) {
    val p by animateFloatAsState((done.toFloat() / target).coerceIn(0f, 1f), label = "progress")
    Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { 1f }, modifier = Modifier.fillMaxSize(), strokeWidth = 14.dp, color = Card,
        )
        CircularProgressIndicator(
            progress = { p }, modifier = Modifier.fillMaxSize(), strokeWidth = 14.dp, color = Sun,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = Dim, modifier = Modifier.size(32.dp))
            Text("${(target - done).coerceAtLeast(0)}", fontSize = 64.sp, fontWeight = FontWeight.Bold)
            Text(label, color = Dim)
        }
    }
}

@Composable
fun ShakeMission(target: Int, onActivity: () -> Unit, onDone: () -> Unit) {
    var count by remember { mutableIntStateOf(0) }
    val last = remember { LongArray(1) }
    var finished by remember { mutableStateOf(false) }
    val has = SensorListener(Sensor.TYPE_ACCELEROMETER) { e ->
        val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH
        val now = System.currentTimeMillis()
        // A real, hard shake: about 2.8× gravity, and at most ~3 per second.
        if (g > 2.8f && now - last[0] > 300 && !finished) {
            last[0] = now
            count++
            onActivity()
            if (count >= target) { finished = true; onDone() }
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MissionHeader("Shake your phone hard", "")
        Spacer(Modifier.weight(1f))
        BigCounter(count, target, "shakes left", Mission.SHAKE.icon)
        Spacer(Modifier.weight(1f))
        if (!has) Text("This phone has no motion sensor.", color = Bad)
    }
    if (!has) LaunchedEffect(Unit) { delay(1500); onDone() }
}

// ---------------- Steps ----------------

@Composable
fun StepsMission(target: Int, onActivity: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var count by remember { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf(false) }
    val canUseStepSensor = remember {
        (Build.VERSION.SDK_INT < 29 || ctx.hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) &&
            (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null
    }
    fun step() {
        if (finished) return
        count++
        onActivity()
        if (count >= target) { finished = true; onDone() }
    }
    if (canUseStepSensor) {
        SensorListener(Sensor.TYPE_STEP_DETECTOR) { step() }
    } else {
        // Fallback: spot steps in the raw accelerometer (a bounce up, then back down).
        val d = remember { StepDetector() }
        SensorListener(Sensor.TYPE_ACCELEROMETER) { e ->
            val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
            if (d.feed(m, System.currentTimeMillis())) step()
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MissionHeader("Get up and walk", "")
        Spacer(Modifier.weight(1f))
        BigCounter(count, target, "steps left", Mission.STEPS.icon)
        Spacer(Modifier.weight(1f))
        Text("Hold the phone and walk around.", color = Dim)
    }
}

/** Spots steps in raw acceleration magnitude: a bounce above gravity, then back below it. */
class StepDetector {
    private var smooth = SensorManager.GRAVITY_EARTH
    private var high = false
    private var last = 0L

    fun feed(magnitude: Float, now: Long): Boolean {
        smooth += (magnitude - smooth) * 0.25f
        if (!high && smooth > 11.2f) {
            high = true
        } else if (high && smooth < 9.4f) {
            high = false
            if (now - last > 300) {
                last = now
                return true
            }
        }
        return false
    }
}

// ---------------- Memory ----------------

private fun memoryShape(level: Int): Pair<Int, Int> = when (level) {
    0 -> 3 to 3
    1 -> 4 to 5
    else -> 5 to 7
}

@Composable
fun MemoryMission(level: Int, rounds: Int, onActivity: () -> Unit, onDone: () -> Unit) {
    val (n, tiles) = memoryShape(level)
    var round by remember { mutableIntStateOf(0) }
    var attempt by remember { mutableIntStateOf(0) }
    var pattern by remember { mutableStateOf(setOf<Int>()) }
    var showing by remember { mutableStateOf(true) }
    val found = remember { mutableStateListOf<Int>() }
    var miss by remember { mutableStateOf(-1) }

    LaunchedEffect(round, attempt) {
        pattern = (0 until n * n).shuffled().take(tiles).toSet()
        found.clear()
        miss = -1
        showing = true
        delay(1200L + 250L * tiles)
        showing = false
    }

    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MissionHeader(if (showing) "Remember the lit squares" else "Tap the squares that were lit", "${round + 1} / $rounds")
        Spacer(Modifier.weight(1f))
        Column(
            Modifier.fillMaxWidth().aspectRatio(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (r in 0 until n) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in 0 until n) {
                        val i = r * n + c
                        val lit = (showing && i in pattern) || i in found
                        val bg by animateColorAsState(
                            when {
                                i == miss -> Bad
                                lit -> Sun
                                else -> Card
                            },
                            label = "tile",
                        )
                        Box(
                            Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(12.dp)).background(bg)
                                .clickable(enabled = !showing && miss < 0 && i !in found) {
                                    onActivity()
                                    if (i in pattern) {
                                        found.add(i)
                                        if (found.size == pattern.size) {
                                            if (round + 1 >= rounds) onDone() else round++
                                        }
                                    } else {
                                        miss = i
                                    }
                                },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text(if (miss >= 0) "Wrong square — new pattern coming" else " ", color = Bad)
    }

    LaunchedEffect(miss) {
        if (miss >= 0) { delay(900); attempt++ }
    }
}

// ---------------- Barcode ----------------

/** Camera preview that reports every barcode / QR code it reads. Needs CAMERA permission. */
@OptIn(ExperimentalGetImage::class)
@Composable
fun BarcodeScanner(onCode: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onCode)
    val controller = remember { LifecycleCameraController(ctx) }
    DisposableEffect(owner) {
        val scanner = BarcodeScanning.getClient()
        val executor = ContextCompat.getMainExecutor(ctx)
        var busy = false
        controller.setImageAnalysisAnalyzer(executor) { proxy ->
            val media = proxy.image
            if (media == null || busy) {
                proxy.close()
                return@setImageAnalysisAnalyzer
            }
            busy = true
            val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
            scanner.process(input)
                .addOnSuccessListener { codes ->
                    codes.firstOrNull { !it.rawValue.isNullOrEmpty() }?.rawValue?.let { latest(it) }
                }
                .addOnCompleteListener {
                    busy = false
                    proxy.close()
                }
        }
        controller.bindToLifecycle(owner)
        onDispose {
            controller.unbind()
            controller.clearImageAnalysisAnalyzer()
            scanner.close()
        }
    }
    AndroidView(
        factory = { c ->
            PreviewView(c).apply {
                this.controller = controller
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        },
        modifier = modifier,
    )
}

@Composable
fun BarcodeMission(code: String, onActivity: () -> Unit, onDone: () -> Unit) {
    var wrongAt by remember { mutableStateOf(0L) }
    var finished by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(250); now = System.currentTimeMillis() } }

    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MissionHeader("Scan your registered code", "")
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.8f).clip(RoundedCornerShape(24.dp))
                .border(3.dp, if (now - wrongAt < 1500) Bad else Sun, RoundedCornerShape(24.dp)),
        ) {
            BarcodeScanner(
                onCode = { v ->
                    if (finished) return@BarcodeScanner
                    if (v == code) { finished = true; onDone() }
                    else if (System.currentTimeMillis() - wrongAt > 1500) { wrongAt = System.currentTimeMillis(); onActivity() }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            if (now - wrongAt < 1500) "That's a different code." else "Go to where you put it and point the camera at it.",
            color = if (now - wrongAt < 1500) Bad else Dim, textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun MissionBadge(m: Mission, modifier: Modifier = Modifier) {
    Box(
        modifier.size(28.dp).clip(CircleShape).background(CardHi),
        contentAlignment = Alignment.Center,
    ) { Icon(m.icon, null, tint = Lav, modifier = Modifier.size(16.dp)) }
}
