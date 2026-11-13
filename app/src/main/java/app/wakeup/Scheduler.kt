package app.wakeup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Talks to AlarmManager. Every ring goes through setAlarmClock(), the same call the stock
 * Clock app uses: exact, wakes the phone from Doze, and shows the alarm icon in the status bar.
 */
object Scheduler {
    const val KIND_RING = "ring"
    const val KIND_SNOOZE = "snooze"
    const val KIND_CHECK = "check"

    private const val WAKE_CHECK_DELAY_MS = 5 * 60_000L

    private fun am(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    private fun requestCode(id: Int, kind: String) = id * 4 + when (kind) {
        KIND_RING -> 0
        KIND_SNOOZE -> 1
        else -> 2
    }

    private fun firePending(ctx: Context, id: Int, kind: String, flags: Int): PendingIntent? {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .setAction("app.wakeup.FIRE.$kind.$id")
            .putExtra("id", id)
            .putExtra("kind", kind)
        return PendingIntent.getBroadcast(ctx, requestCode(id, kind), i, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun showPending(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun canScheduleExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am(ctx).canScheduleExactAlarms()

    private fun setAt(ctx: Context, id: Int, kind: String, atMillis: Long) {
        val op = firePending(ctx, id, kind, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val am = am(ctx)
        try {
            if (canScheduleExact(ctx)) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, showPending(ctx)), op)
            } else {
                // Shouldn't happen for an alarm clock app, but never silently drop an alarm.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, op)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, op)
        }
    }

    private fun cancelKind(ctx: Context, id: Int, kind: String) {
        firePending(ctx, id, kind, PendingIntent.FLAG_NO_CREATE)?.let {
            am(ctx).cancel(it)
            it.cancel()
        }
    }

    /** Schedules (or cancels, if off) the alarm's next regular ring. */
    fun schedule(ctx: Context, a: Alarm) {
        if (a.id == AlarmStore.TEST_ID) return
        if (!a.enabled) {
            cancelAll(ctx, a.id)
            return
        }
        setAt(ctx, a.id, KIND_RING, a.nextTrigger().toMillis())
    }

    /** Next regular ring of a repeating alarm, at least a minute from now (so it can't re-fire). */
    fun scheduleFollowing(ctx: Context, a: Alarm) {
        if (!a.enabled || !a.repeats || a.id == AlarmStore.TEST_ID) return
        val from = java.time.ZonedDateTime.now().plusSeconds(61)
        setAt(ctx, a.id, KIND_RING, a.nextTrigger(from).toMillis())
    }

    fun snooze(ctx: Context, a: Alarm): Long {
        val at = System.currentTimeMillis() + a.snoozeMinutes * 60_000L
        AlarmStore.setSnoozeUntil(a.id, at)
        setAt(ctx, a.id, KIND_SNOOZE, at)
        return at
    }

    fun cancelSnooze(ctx: Context, id: Int) {
        AlarmStore.setSnoozeUntil(id, 0)
        cancelKind(ctx, id, KIND_SNOOZE)
    }

    fun scheduleWakeCheck(ctx: Context, a: Alarm) {
        setAt(ctx, a.id, KIND_CHECK, System.currentTimeMillis() + WAKE_CHECK_DELAY_MS)
    }

    fun cancelRing(ctx: Context, id: Int) = cancelKind(ctx, id, KIND_RING)

    fun cancelAll(ctx: Context, id: Int) {
        cancelKind(ctx, id, KIND_RING)
        cancelSnooze(ctx, id)
        cancelKind(ctx, id, KIND_CHECK)
    }

    /** After reboot / time change / app update: put every alarm back. */
    fun rescheduleAll(ctx: Context) {
        AlarmStore.init(ctx)
        val now = System.currentTimeMillis()
        AlarmStore.alarms.value.forEach { a ->
            schedule(ctx, a)
            val snoozeAt = AlarmStore.snoozeUntil(a.id)
            if (snoozeAt > now) setAt(ctx, a.id, KIND_SNOOZE, snoozeAt)
            else if (snoozeAt != 0L) AlarmStore.setSnoozeUntil(a.id, 0)
        }
    }

    /** The soonest upcoming ring across all alarms (including snoozes), or null. */
    fun nextRing(alarms: List<Alarm>): Pair<Alarm, Long>? {
        val now = System.currentTimeMillis()
        return alarms.filter { it.enabled }.map { a ->
            val snoozeAt = AlarmStore.snoozeUntil(a.id)
            val regular = a.nextTrigger().toMillis()
            a to if (snoozeAt > now) minOf(snoozeAt, regular) else regular
        }.minByOrNull { it.second }
    }
}
