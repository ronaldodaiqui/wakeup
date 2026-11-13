package app.wakeup

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class WakeupApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AlarmStore.init(this)
        Notifications.createChannels(this)
    }
}

/** AlarmManager fires this; it hands off to AlarmService, which does the ringing. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmStore.init(context)
        val id = intent.getIntExtra("id", -1)
        val kind = intent.getStringExtra("kind") ?: Scheduler.KIND_RING
        val alarm = AlarmStore.get(id) ?: return
        // A wake-up check still runs after a one-time alarm has switched itself off.
        if (!alarm.enabled && kind != Scheduler.KIND_CHECK) return

        when (kind) {
            Scheduler.KIND_RING -> Scheduler.scheduleFollowing(context, alarm)
            Scheduler.KIND_SNOOZE -> AlarmStore.setSnoozeUntil(id, 0)
        }
        AlarmService.start(context, id, kind)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.rescheduleAll(context)
    }
}

fun startServiceCompat(context: Context, intent: Intent) {
    ContextCompat.startForegroundService(context, intent)
}
