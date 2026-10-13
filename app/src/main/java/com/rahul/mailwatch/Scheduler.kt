package com.rahul.mailwatch

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Two layers so a check is never skipped for long:
 *  1. an exact alarm every N minutes (fast; Android may stretch it to ~10 min while the phone sleeps)
 *  2. a WorkManager job every 15 minutes (backstop that survives reboots and app updates)
 */
object Scheduler {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun start(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "backstop",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CheckWorker>(15, TimeUnit.MINUTES).setConstraints(network).build(),
        )
        scheduleNext(ctx)
    }

    fun scheduleNext(ctx: Context) {
        val minutes = Store.settings.value.checkMinutes.coerceIn(1, 60)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, CheckReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val at = System.currentTimeMillis() + minutes * 60_000L
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun checkNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "check-now",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CheckWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(network)
                .build(),
        )
    }
}

class CheckReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        Scheduler.scheduleNext(ctx)
        Scheduler.checkNow(ctx)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        Scheduler.start(ctx)
        // alarms that were still ringing before the restart
        Store.alerts.value.filter { Alarms.isRinging(it) }.forEach { Alarms.scheduleReRing(ctx, it.id, System.currentTimeMillis() + 30_000) }
    }
}

class CheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Store.init(applicationContext)
        return try {
            Checker.checkOnce(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        // Only used on Android 11 and older for expedited work.
        val n = NotificationCompat.Builder(applicationContext, Notify.CH_WORK)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle("Checking mail")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        return ForegroundInfo(4242, n)
    }
}
