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
import androidx.compose.material3.MaterialTheme
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

val Sun = Color(0xFFFFA24C)
val SunSoft = Color(0xFFFFD9A8)
val Night = Color(0xFF121014)
val Card = Color(0xFF1E1A22)
val CardHi = Color(0xFF2A2430)
val Dim = Color(0xFF9A93A3)
val Bad = Color(0xFFFF6B6B)
val Good = Color(0xFF6BD49A)

@Composable
fun WakeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Sun,
            onPrimary = Color(0xFF2A1600),
            primaryContainer = Color(0xFF4A2C10),
            onPrimaryContainer = SunSoft,
            secondary = SunSoft,
            background = Night,
            onBackground = Color(0xFFEDE8F2),
            surface = Night,
            onSurface = Color(0xFFEDE8F2),
            surfaceVariant = Card,
            onSurfaceVariant = Dim,
            surfaceContainer = Card,
            surfaceContainerHigh = CardHi,
            surfaceContainerHighest = CardHi,
            surfaceContainerLow = Card,
            outline = Color(0xFF4A4352),
            outlineVariant = Color(0xFF332D3A),
            error = Bad,
        ),
        content = content,
    )
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
