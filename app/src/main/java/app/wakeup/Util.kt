package app.wakeup

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

// ---------------- Look ----------------

/** One color set for the whole app. All four are dark with soft accents. */
data class Palette(
    val key: String,
    val name: String,
    val credit: String,
    val bg: Color,
    val card: Color,
    val cardHi: Color,
    val ink: Color,       // main text
    val dim: Color,       // secondary text
    val accent: Color,    // buttons, selected things, the alarm glow
    val accentSoft: Color,
    val second: Color,    // missions and smaller accents
    val onAccent: Color,  // text on accent
    val bad: Color,
    val good: Color,
)

object Palettes {
    val all = listOf(
        Palette(
            "sunrise", "Sunrise", "Wakeup's own peach and lavender",
            bg = Color(0xFF15141C), card = Color(0xFF1F1E29), cardHi = Color(0xFF2A2936),
            ink = Color(0xFFECE9F5), dim = Color(0xFFA29EB4),
            accent = Color(0xFFFFBFA3), accentSoft = Color(0xFFFFE3D6), second = Color(0xFFC9B8FF),
            onAccent = Color(0xFF33202C), bad = Color(0xFFFF9DAE), good = Color(0xFFA6E3C6),
        ),
        // catppuccin.com: Mocha flavor
        Palette(
            "mocha", "Catppuccin Mocha", "Soft pastels on deep blue-grey",
            bg = Color(0xFF1E1E2E), card = Color(0xFF313244), cardHi = Color(0xFF45475A),
            ink = Color(0xFFCDD6F4), dim = Color(0xFFA6ADC8),
            accent = Color(0xFFFAB387), accentSoft = Color(0xFFF5E0DC), second = Color(0xFFB4BEFE),
            onAccent = Color(0xFF11111B), bad = Color(0xFFF38BA8), good = Color(0xFFA6E3A1),
        ),
        // rosepinetheme.com: main variant
        Palette(
            "rosepine", "Rosé Pine", "Dusty rose and iris on plum",
            bg = Color(0xFF191724), card = Color(0xFF26233A), cardHi = Color(0xFF403D52),
            ink = Color(0xFFE0DEF4), dim = Color(0xFF908CAA),
            accent = Color(0xFFEBBCBA), accentSoft = Color(0xFFF6E3E2), second = Color(0xFFC4A7E7),
            onAccent = Color(0xFF191724), bad = Color(0xFFEB6F92), good = Color(0xFF9CCFD8),
        ),
        // nordtheme.com: Polar Night, Snow Storm, Frost, Aurora
        Palette(
            "nord", "Nord", "Cool arctic blues, calm and quiet",
            bg = Color(0xFF2E3440), card = Color(0xFF3B4252), cardHi = Color(0xFF4C566A),
            ink = Color(0xFFECEFF4), dim = Color(0xFFB4BCCB),
            accent = Color(0xFF88C0D0), accentSoft = Color(0xFFD8DEE9), second = Color(0xFFB48EAD),
            onAccent = Color(0xFF2E3440), bad = Color(0xFFBF616A), good = Color(0xFFA3BE8C),
        ),
    )

    private val state = mutableStateOf(all[0])
    val current: Palette get() = state.value

    fun load(ctx: Context) {
        val key = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("palette", null)
        state.value = all.firstOrNull { it.key == key } ?: all[0]
    }

    fun set(ctx: Context, p: Palette) {
        state.value = p
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("palette", p.key).apply()
    }
}

// Shortcuts used everywhere. They follow the chosen palette, and screens redraw when it changes.
val Sun: Color get() = Palettes.current.accent
val SunSoft: Color get() = Palettes.current.accentSoft
val Lav: Color get() = Palettes.current.second
val Night: Color get() = Palettes.current.bg
val Card: Color get() = Palettes.current.card
val CardHi: Color get() = Palettes.current.cardHi
val Ink: Color get() = Palettes.current.ink
val Dim: Color get() = Palettes.current.dim
val OnAccent: Color get() = Palettes.current.onAccent
val Bad: Color get() = Palettes.current.bad
val Good: Color get() = Palettes.current.good

@Composable
fun WakeTheme(content: @Composable () -> Unit) {
    val p = Palettes.current
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = p.accent,
            onPrimary = p.onAccent,
            primaryContainer = lerp(p.bg, p.accent, 0.22f),
            onPrimaryContainer = p.accentSoft,
            secondary = p.second,
            onSecondary = p.onAccent,
            secondaryContainer = lerp(p.bg, p.second, 0.22f),
            onSecondaryContainer = p.ink,
            tertiary = p.good,
            background = p.bg,
            onBackground = p.ink,
            surface = p.bg,
            onSurface = p.ink,
            surfaceVariant = p.card,
            onSurfaceVariant = p.dim,
            surfaceContainer = p.card,
            surfaceContainerHigh = p.cardHi,
            surfaceContainerHighest = p.cardHi,
            surfaceContainerLow = p.card,
            surfaceContainerLowest = p.bg,
            inverseSurface = p.ink,
            inverseOnSurface = p.bg,
            outline = lerp(p.cardHi, p.dim, 0.25f),
            outlineVariant = p.cardHi,
            error = p.bad,
            onError = p.onAccent,
        ),
    ) {
        // Without a Surface, plain Text defaults to black. This makes every screen light-on-dark.
        Surface(Modifier.fillMaxSize(), color = p.bg, contentColor = p.ink, content = content)
    }
}

// ---------------- Time text ----------------

fun is24h(ctx: Context) = DateFormat.is24HourFormat(ctx)

/** ("7:05", "AM") or ("19:05", "") */
fun timeParts(ctx: Context, hour: Int, minute: Int): Pair<String, String> =
    if (is24h(ctx)) "%d:%02d".format(hour, minute) to ""
    else {
        val h = if (hour % 12 == 0) 12 else hour % 12
        "%d:%02d".format(h, minute) to if (hour < 12) "AM" else "PM"
    }

fun formatTime(ctx: Context, hour: Int, minute: Int): String {
    val (t, ap) = timeParts(ctx, hour, minute)
    return if (ap.isEmpty()) t else "$t $ap"
}

fun weekOrder(): List<DayOfWeek> {
    val first = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    return (0 until 7).map { first.plus(it.toLong()) }
}

fun daysSummary(days: Int): String = when (days) {
    0 -> "Once"
    0b1111111 -> "Every day"
    0b0011111 -> "Weekdays"
    0b1100000 -> "Weekends"
    else -> weekOrder()
        .filter { days and (1 shl (it.value - 1)) != 0 }
        .joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
}

// ---------------- Setup checks ----------------

object Setup {
    fun notificationsOk(ctx: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    fun fullScreenOk(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun exactOk(ctx: Context) = Scheduler.canScheduleExact(ctx)

    fun batteryOk(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    fun overlayOk(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /** The ones an alarm can't reliably ring without. */
    fun essentialOk(ctx: Context) = notificationsOk(ctx) && fullScreenOk(ctx) && exactOk(ctx)

    private fun pkg(ctx: Context) = Uri.parse("package:${ctx.packageName}")

    fun notificationSettings(ctx: Context) =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)

    fun fullScreenSettings(ctx: Context) =
        if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg(ctx))
        else notificationSettings(ctx)

    fun exactSettings(ctx: Context) =
        if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg(ctx))
        else appSettings(ctx)

    @Suppress("BatteryLife")
    fun batterySettings(ctx: Context) = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg(ctx))

    fun overlaySettings(ctx: Context) = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg(ctx))

    fun appSettings(ctx: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg(ctx))
}

fun Context.launchSafely(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        runCatching { startActivity(Setup.appSettings(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

fun Context.hasPermission(p: String) =
    ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
