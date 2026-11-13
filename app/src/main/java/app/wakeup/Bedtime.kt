package app.wakeup

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.ZonedDateTime

/**
 * "Your alarm is in 8 hours" reminder. There's only ever one pending: the next nudge across
 * all alarms. It's recalculated whenever alarms change, after it fires, and after a reboot.
 */
object Bedtime {
    private const val CHANNEL = "bedtime"
    private const val NOTIF_ID = 8
    private const val REQUEST = 9_999

    /** Choices for how long before the alarm, in minutes. */
    val choices = listOf(420, 450, 480, 510, 540)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("bedtimeOn", true)
    fun minutesBefore(ctx: Context) = prefs(ctx).getInt("bedtimeMin", 480)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("bedtimeOn", on).apply()
        refresh(ctx)
    }

    fun setMinutesBefore(ctx: Context, min: Int) {
        prefs(ctx).edit().putInt("bedtimeMin", min).apply()
        refresh(ctx)
    }

    fun label(min: Int): String = if (min % 60 == 0) "${min / 60} h" else "${min / 60}½ h"

    fun createChannel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL, "Bedtime reminder", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "A nudge to go to sleep so you get enough hours before your alarm"
            enableVibration(false)
        }
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun pending(ctx: Context, ringAt: Long, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            ctx, REQUEST,
            Intent(ctx, BedtimeReceiver::class.java).putExtra("ringAt", ringAt),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Earliest (nudgeTime, ringTime) still in the future, looking a few rings ahead per alarm. */
    fun nextNudge(alarms: List<Alarm>, before: Long, now: ZonedDateTime = ZonedDateTime.now()): Pair<Long, Long>? {
        val nowMs = now.toMillis()
        var best: Pair<Long, Long>? = null
        for (a in alarms) {
            if (!a.enabled || a.id == AlarmStore.TEST_ID) continue
            var t = a.nextTrigger(now)
            for (i in 0 until if (a.repeats) 8 else 1) {
                val ring = t.toMillis()
                val nudge = ring - before * 60_000L
                if (nudge > nowMs + 30_000L) {
                    val b = best
                    if (b == null || nudge < b.first) best = nudge to ring
                    break
                }
                t = a.nextTrigger(t)
            }
        }
        return best
    }

    fun refresh(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        pending(ctx, 0, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
        if (!enabled(ctx)) return
        val (at, ringAt) = nextNudge(AlarmStore.alarms.value, minutesBefore(ctx).toLong()) ?: return
        val pi = pending(ctx, ringAt, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        try {
            if (Scheduler.canScheduleExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun show(ctx: Context, ringAt: Long) {
        if (!Setup.notificationsOk(ctx)) return
        val z = millisToZoned(ringAt)
        val inText = durationText(ringAt - System.currentTimeMillis())
        val open = PendingIntent.getActivity(
            ctx, 3, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("Time to wind down")
            .setContentText("Your alarm is at ${formatTime(ctx, z.hour, z.minute)}, $inText from now.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(2 * 3_600_000L)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n) }
    }
}

class BedtimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmStore.init(context)
        val ringAt = intent.getLongExtra("ringAt", 0L)
        // Only nudge if that alarm is still on and still that far away.
        val stillOn = AlarmStore.alarms.value.any { it.enabled && it.nextTrigger().toMillis() == ringAt }
        if (Bedtime.enabled(context) && stillOn) Bedtime.show(context, ringAt)
        Bedtime.refresh(context)
    }
}
