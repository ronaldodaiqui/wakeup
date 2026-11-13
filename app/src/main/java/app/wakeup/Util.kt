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
import androidx.compose.ui.graphics.Color
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

// ---------------- Look ----------------

// Dark, slightly lavender night with soft pastel accents.
val Sun = Color(0xFFFFBFA3)      // peach: the main accent
val SunSoft = Color(0xFFFFE3D6)  // pale peach
val Lav = Color(0xFFC9B8FF)      // lavender: missions and secondary accents
val Night = Color(0xFF15141C)
val Card = Color(0xFF1F1E29)
val CardHi = Color(0xFF2A2936)
val Ink = Color(0xFFECE9F5)      // main text
val Dim = Color(0xFFA29EB4)      // secondary text
val OnAccent = Color(0xFF33202C) // text on peach
val Bad = Color(0xFFFF9DAE)      // rose
val Good = Color(0xFFA6E3C6)     // mint

@Composable
fun WakeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Sun,
            onPrimary = OnAccent,
            primaryContainer = Color(0xFF3B2C38),
            onPrimaryContainer = SunSoft,
            secondary = Lav,
            onSecondary = Color(0xFF2A2140),
            secondaryContainer = Color(0xFF332D4A),
            onSecondaryContainer = Color(0xFFE6DEFF),
            tertiary = Good,
            background = Night,
            onBackground = Ink,
            surface = Night,
            onSurface = Ink,
            surfaceVariant = Card,
            onSurfaceVariant = Dim,
            surfaceContainer = Card,
            surfaceContainerHigh = CardHi,
            surfaceContainerHighest = CardHi,
            surfaceContainerLow = Card,
            surfaceContainerLowest = Night,
            inverseSurface = Ink,
            inverseOnSurface = Night,
            outline = Color(0xFF3E3C4C),
            outlineVariant = Color(0xFF2E2D3A),
            error = Bad,
            onError = OnAccent,
        ),
    ) {
        // Without a Surface, plain Text defaults to black. This makes every screen light-on-dark.
        Surface(Modifier.fillMaxSize(), color = Night, contentColor = Ink, content = content)
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
