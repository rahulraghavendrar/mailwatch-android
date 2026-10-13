package com.rahul.mailwatch

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Notification channels + plain notifications. */
object Notify {
    const val CH_ALARM = "alarm_v1"
    const val CH_RINGING = "ringing"
    const val CH_INFO = "info"
    const val CH_WORK = "work"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val alarm = NotificationChannel(CH_ALARM, "Mail alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rings when an email you're waiting for arrives"
            setSound(
                alarmSound,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 500, 800, 500, 800)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        val ringing = NotificationChannel(CH_RINGING, "Alarm ringing", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while an alarm is ringing on screen"
            setSound(null, null)
        }
        val info = NotificationChannel(CH_INFO, "Problems with an inbox", NotificationManager.IMPORTANCE_DEFAULT)
        val work = NotificationChannel(CH_WORK, "Checking mail", NotificationManager.IMPORTANCE_MIN)
        nm.createNotificationChannels(listOf(alarm, ringing, info, work))
    }

    @SuppressLint("MissingPermission")
    fun post(ctx: Context, id: Int, n: Notification) {
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    fun info(ctx: Context, title: String, text: String) {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CH_INFO)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        post(ctx, title.hashCode(), n)
    }
}

/**
 * The alarm: a full-screen notification (opens [AlarmActivity] over the lock screen) that keeps
 * sounding on the alarm channel. If you don't stop it, it rings again after a pause, a few times.
 */
object Alarms {
    const val EXTRA_ALERT = "alert_id"
    const val ACTION_STOP = "com.rahul.mailwatch.STOP"
    const val ACTION_RERING = "com.rahul.mailwatch.RERING"

    fun notifId(alertId: Long) = 10_000 + (alertId % 100_000).toInt()
    private fun ringingId(alertId: Long) = 200_000 + (alertId % 100_000).toInt()

    private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun alarmActivityIntent(ctx: Context, alertId: Long): PendingIntent = PendingIntent.getActivity(
        ctx, notifId(alertId),
        Intent(ctx, AlarmActivity::class.java)
            .putExtra(EXTRA_ALERT, alertId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        flags(),
    )

    private fun actionIntent(ctx: Context, action: String, alertId: Long): PendingIntent = PendingIntent.getBroadcast(
        ctx, notifId(alertId) + action.hashCode() % 1000,
        Intent(ctx, AlarmActionReceiver::class.java).setAction(action).putExtra(EXTRA_ALERT, alertId),
        flags(),
    )

    fun ring(ctx: Context, alertId: Long) {
        val alert = Store.alert(alertId) ?: return
        if (alert.acked) return
        val s = Store.settings.value
        val text = "From: ${alert.sender}\n${alert.subject}"
        val n = NotificationCompat.Builder(ctx, Notify.CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle("${alert.watchTitle}: it's here")
            .setContentText(alert.subject)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(alarmActivityIntent(ctx, alertId), true)
            .setContentIntent(alarmActivityIntent(ctx, alertId))
            .addAction(0, "Stop alarm", actionIntent(ctx, ACTION_STOP, alertId))
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(s.ringMinutes * 60_000L)
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT // keep sounding until stopped
        Notify.post(ctx, notifId(alertId), n)
        val round = alert.round + 1
        Store.updateAlert(alertId) { it.copy(round = round) }
        if (round < s.rounds) {
            scheduleReRing(ctx, alertId, System.currentTimeMillis() + (s.ringMinutes + s.gapMinutes) * 60_000L)
        }
    }

    fun scheduleReRing(ctx: Context, alertId: Long, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = actionIntent(ctx, ACTION_RERING, alertId)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Called when the alarm screen is open: the screen plays the sound, the notification goes quiet. */
    fun showRingingQuietly(ctx: Context, alertId: Long) {
        val alert = Store.alert(alertId) ?: return
        NotificationManagerCompat.from(ctx).cancel(notifId(alertId))
        val n = NotificationCompat.Builder(ctx, Notify.CH_RINGING)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle("Ringing: ${alert.watchTitle}")
            .setContentText(alert.subject)
            .setContentIntent(alarmActivityIntent(ctx, alertId))
            .addAction(0, "Stop alarm", actionIntent(ctx, ACTION_STOP, alertId))
            .setOngoing(true)
            .setTimeoutAfter(Store.settings.value.ringMinutes * 60_000L)
            .build()
        Notify.post(ctx, ringingId(alertId), n)
    }

    fun stop(ctx: Context, alertId: Long) {
        Store.updateAlert(alertId) { it.copy(acked = true) }
        AlarmSound.stop()
        val nm = NotificationManagerCompat.from(ctx)
        nm.cancel(notifId(alertId))
        nm.cancel(ringingId(alertId))
        ctx.getSystemService(AlarmManager::class.java).cancel(actionIntent(ctx, ACTION_RERING, alertId))
    }

    /** Silence now and ring again after the pause. */
    fun snooze(ctx: Context, alertId: Long) {
        AlarmSound.stop()
        NotificationManagerCompat.from(ctx).cancel(ringingId(alertId))
        NotificationManagerCompat.from(ctx).cancel(notifId(alertId))
        scheduleReRing(ctx, alertId, System.currentTimeMillis() + Store.settings.value.gapMinutes * 60_000L)
    }

    fun stopAll(ctx: Context) {
        Store.alerts.value.filter { !it.acked }.forEach { stop(ctx, it.id) }
    }

    /** Lets you try the alarm: rings in [delaySeconds] so you can lock the phone first. */
    fun test(ctx: Context, delaySeconds: Int = 10) {
        val a = Store.addAlert(
            Alert(
                id = Store.nextId(), watchId = -1, watchTitle = "Test alarm", account = "", provider = "",
                msgKey = "test:${System.currentTimeMillis()}", sender = "MailWatch",
                subject = "This is how a real alarm will look and sound", snippet = "", link = "",
                reason = "", method = "test", round = Store.settings.value.rounds - 1,
            )
        ) ?: return
        scheduleReRing(ctx, a.id, System.currentTimeMillis() + delaySeconds * 1000L)
    }

    fun isRinging(a: Alert) = !a.acked && a.round > 0 &&
        System.currentTimeMillis() - a.created < Store.settings.value.let { (it.ringMinutes + it.gapMinutes) * it.rounds } * 60_000L
}

/** Handles "Stop alarm" from the notification and the re-ring timer. */
class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        val id = intent.getLongExtra(Alarms.EXTRA_ALERT, -1)
        if (id < 0) return
        when (intent.action) {
            Alarms.ACTION_STOP -> Alarms.stop(ctx, id)
            Alarms.ACTION_RERING -> Alarms.ring(ctx, id)
        }
    }
}

/** Loud looping alarm sound + vibration, played on the alarm stream (works in silent mode). */
object AlarmSound {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopper = Runnable { stop() }

    @Synchronized
    fun start(ctx: Context, minutes: Int) {
        if (player != null) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                setDataSource(ctx.applicationContext, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {
            player = null
        }
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0))
        handler.removeCallbacks(stopper)
        handler.postDelayed(stopper, minutes * 60_000L)
    }

    @Synchronized
    fun stop() {
        handler.removeCallbacks(stopper)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    val isPlaying get() = player != null
}
