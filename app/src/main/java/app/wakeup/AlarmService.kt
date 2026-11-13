package app.wakeup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class RingState(
    val alarm: Alarm,
    /** True while it's a quiet "are you awake?" check rather than a full alarm. */
    val check: Boolean,
    val checkDeadline: Long = 0L,
    /** -1 = unlimited */
    val snoozesLeft: Int,
) {
    val test: Boolean get() = alarm.id == AlarmStore.TEST_ID
    val canSnooze: Boolean get() = !check && !test && snoozesLeft != 0
}

object Notifications {
    const val CHANNEL_RING = "ringing"
    const val RING_ID = 7

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_RING, "Ringing alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Shows the alarm screen when an alarm goes off"
            setSound(null, null) // the app plays the alarm sound itself
            enableVibration(false)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    fun ring(ctx: Context, s: RingState): Notification {
        val open = PendingIntent.getActivity(
            ctx, 2,
            Intent(ctx, RingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val a = s.alarm
        val title = if (s.check) "Are you awake?" else
            formatTime(ctx, a.hour, a.minute) + if (a.label.isNotBlank()) " · ${a.label}" else ""
        val text = when {
            s.check -> "Tap to confirm, or the alarm rings again"
            a.mission == Mission.NONE -> "Tap to turn off"
            else -> "Finish the ${a.mission.title.lowercase()} mission to turn it off"
        }
        return NotificationCompat.Builder(ctx, CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}

/**
 * Rings an alarm: loud looping sound on the alarm stream, vibration, a full-screen alarm page,
 * and a volume lock. It keeps going until the mission is done (or you snooze).
 */
class AlarmService : Service() {

    companion object {
        private const val ACTION_FIRE = "app.wakeup.FIRE"
        const val CHECK_WINDOW_MS = 60_000L

        private val _state = MutableStateFlow<RingState?>(null)
        val state: StateFlow<RingState?> = _state

        @Volatile
        var instance: AlarmService? = null
            private set

        fun start(ctx: Context, id: Int, kind: String) {
            startServiceCompat(
                ctx,
                Intent(ctx, AlarmService::class.java)
                    .setAction(ACTION_FIRE)
                    .putExtra("id", id)
                    .putExtra("kind", kind),
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var savedVolume = -1
    private var sounding = false
    private var muted = false
    /** 0..1 loudness from gentle start (1 = full). */
    private var level = 1f
    private var rampStart = 0L

    private val audio by lazy { getSystemService(AudioManager::class.java) }

    private val alarmAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** Puts the alarm volume back to max whenever something turns it down. */
    private val volumeGuard = object : Runnable {
        override fun run() {
            if (sounding && !muted) forceMaxVolume()
            handler.postDelayed(this, 400)
        }
    }

    /** Gentle start: quiet at first, full volume after GENTLE_MS. Squared curve so it feels even. */
    private val ramp = object : Runnable {
        override fun run() {
            if (!sounding) return
            val f = ((System.currentTimeMillis() - rampStart).toFloat() / GENTLE_MS).coerceIn(0f, 1f)
            level = 0.04f + 0.96f * f * f
            applyMute()
            if (f < 1f) handler.postDelayed(this, 200)
        }
    }

    private val toneLoop = object : Runnable {
        override fun run() {
            if (sounding && !muted) runCatching { tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 700) }
            handler.postDelayed(this, 1000)
        }
    }

    private val escalate = Runnable {
        val s = _state.value ?: return@Runnable
        if (!s.check) return@Runnable
        val ringing = s.copy(check = false)
        _state.value = ringing
        notifyState(ringing)
        startSound(ringing.alarm, gentle = false) // missed the wake-up check: no easing in
        openRingScreen()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        AlarmStore.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getIntExtra("id", -1) ?: -1
        val kind = intent?.getStringExtra("kind") ?: Scheduler.KIND_RING
        val current = _state.value

        if (current != null) {
            // Already ringing: keep the current alarm going. (The new one was already rescheduled.)
            goForeground(current)
            return START_REDELIVER_INTENT
        }

        val alarm = AlarmStore.get(id)
        if (alarm == null) {
            goForeground(RingState(Alarm(), check = false, snoozesLeft = 0))
            stopEverything()
            return START_NOT_STICKY
        }
        begin(alarm, check = kind == Scheduler.KIND_CHECK)
        return START_REDELIVER_INTENT
    }

    private fun begin(alarm: Alarm, check: Boolean) {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wakeup:ring")
            .apply { acquire(60 * 60_000L) }

        val left = if (alarm.snoozeLimit < 0) -1 else maxOf(0, alarm.snoozeLimit - AlarmStore.snoozesUsed(alarm.id))
        val s = RingState(
            alarm = alarm,
            check = check,
            checkDeadline = if (check) System.currentTimeMillis() + CHECK_WINDOW_MS else 0L,
            snoozesLeft = left,
        )
        _state.value = s
        goForeground(s)

        if (check) {
            buzzOnce()
            handler.postDelayed(escalate, CHECK_WINDOW_MS)
        } else {
            startSound(alarm, gentle = alarm.gentle)
        }
        openRingScreen()
    }

    private fun goForeground(s: RingState) {
        ServiceCompat.startForeground(
            this, Notifications.RING_ID, Notifications.ring(this, s),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
    }

    private fun notifyState(s: RingState) {
        getSystemService(NotificationManager::class.java).notify(Notifications.RING_ID, Notifications.ring(this, s))
    }

    /** Works directly when allowed (e.g. "display over other apps"); otherwise the full-screen notification does it. */
    private fun openRingScreen() {
        runCatching {
            startActivity(
                Intent(this, RingActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            )
        }
    }

    // ---------------- Sound ----------------

    private fun startSound(a: Alarm, gentle: Boolean) {
        if (sounding) return
        sounding = true
        level = if (gentle) 0.04f else 1f
        rampStart = System.currentTimeMillis()
        savedVolume = runCatching { audio.getStreamVolume(AudioManager.STREAM_ALARM) }.getOrDefault(-1)
        forceMaxVolume()

        val candidates = listOfNotNull(
            a.sound.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) },
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM),
            Settings.System.DEFAULT_ALARM_ALERT_URI,
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE),
        )
        val ok = candidates.any { tryPlay(it) }
        if (!ok) {
            tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull()
            handler.post(toneLoop)
        }
        handler.post(volumeGuard)
        applyMute()
        if (gentle) handler.post(ramp)
    }

    private fun tryPlay(uri: Uri): Boolean = try {
        player = MediaPlayer().apply {
            setAudioAttributes(alarmAttrs)
            setDataSource(this@AlarmService, uri)
            isLooping = true
            prepare()
            start()
        }
        true
    } catch (e: Exception) {
        runCatching { player?.release() }
        player = null
        false
    }

    private fun forceMaxVolume() {
        runCatching {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (audio.getStreamVolume(AudioManager.STREAM_ALARM) < max) {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            }
        }
    }

    private fun stopSound() {
        if (!sounding) return
        sounding = false
        handler.removeCallbacks(volumeGuard)
        handler.removeCallbacks(toneLoop)
        handler.removeCallbacks(ramp)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { tone?.release() }
        tone = null
        stopVibrating()
        if (savedVolume >= 0) runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) }
        savedVolume = -1
    }

    /** Quiet while you're doing the mission; loud again if you stop. */
    fun setMuted(m: Boolean) {
        if (muted == m) return
        muted = m
        applyMute()
    }

    private fun applyMute() {
        if (!sounding) return
        val v = if (muted) 0f else level
        runCatching { player?.setVolume(v, v) }
        if (muted) stopVibrating()
        else if (!vibrating && level > 0.25f && _state.value?.alarm?.vibrate == true) vibrateLoop() // gentle: buzz joins partway in
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private fun vibrate(effect: VibrationEffect) {
        val v = vibrator() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, alarmAttrs)
        }
    }

    private var vibrating = false

    private fun vibrateLoop() {
        vibrating = true
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 900, 600), 0))
    }

    private fun stopVibrating() {
        vibrating = false
        vibrator()?.cancel()
    }

    private fun buzzOnce() = vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 250, 400), -1))

    // ---------------- Ending ----------------

    /** Mission complete. */
    fun dismiss() {
        val s = _state.value ?: return
        val a = s.alarm
        if (!s.test) {
            AlarmStore.resetSnoozes(a.id)
            Scheduler.cancelSnooze(this, a.id)
            if (!a.repeats && a.enabled) {
                AlarmStore.put(a.copy(enabled = false))
                Scheduler.cancelRing(this, a.id)
            }
            if (!s.check && a.wakeCheck) Scheduler.scheduleWakeCheck(this, a)
        }
        stopEverything()
    }

    fun snooze() {
        val s = _state.value ?: return
        if (!s.canSnooze) return
        AlarmStore.addSnooze(s.alarm.id)
        Scheduler.snooze(this, s.alarm)
        stopEverything()
    }

    /** "I'm awake" on the wake-up check. */
    fun confirmAwake() {
        val s = _state.value ?: return
        if (s.check) stopEverything()
    }

    private fun stopEverything() {
        handler.removeCallbacks(escalate)
        stopSound()
        muted = false
        _state.value = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopSound()
        _state.value = null
        wakeLock?.let { if (it.isHeld) it.release() }
        if (instance === this) instance = null
        super.onDestroy()
    }
}
