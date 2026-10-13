package com.rahul.mailwatch.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.rahul.mailwatch.Alert

// ---------------------------------------------------------------- icons (drawn here to avoid a big icon library)
private fun icon(name: String, path: String): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(pathData = addPathNodes(path), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.Black))
        .build()

val ChatIcon = icon(
    "Chat",
    "M6,3h12a2,2 0 0 1 2,2v10a2,2 0 0 1 -2,2H8l-4,4V5a2,2 0 0 1 2,-2z M7,7v2h10V7H7z M7,11v2h7v-2H7z",
)
val InboxIcon = icon(
    "Inbox",
    "M19,3H5C3.9,3 3,3.9 3,5v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5C21,3.9 20.1,3 19,3z M19,15h-4c0,1.66 -1.34,3 -3,3s-3,-1.34 -3,-3H5V5h14V15z",
)
val BellIcon = icon(
    "Bell",
    "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.9,2 2,2z M18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1L18,16z",
)

// ---------------------------------------------------------------- small helpers
fun ago(t: Long): String =
    if (t <= 0) "never"
    else DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

fun openUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        toast(ctx, "No app can open this link")
    }
}

/** Opens the Gmail / Outlook app (the new mail is at the top), or the web link if the app isn't installed. */
fun openMailApp(ctx: Context, a: Alert) {
    val pkg = when (a.provider) {
        "gmail" -> "com.google.android.gm"
        "outlook" -> "com.microsoft.office.outlook"
        else -> null
    }
    val launch = pkg?.let { ctx.packageManager.getLaunchIntentForPackage(it) }
    if (launch != null) {
        ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } else if (a.link.isNotBlank()) {
        openUrl(ctx, a.link)
    }
}

// ---------------------------------------------------------------- permissions
data class PermState(val notifications: Boolean, val fullScreen: Boolean, val exactAlarms: Boolean, val battery: Boolean) {
    val allGood get() = notifications && fullScreen && exactAlarms && battery
}

fun permState(ctx: Context): PermState {
    val notifications = if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    val fullScreen = if (Build.VERSION.SDK_INT >= 34) {
        ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    } else true
    val exact = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() else true
    val battery = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    return PermState(notifications, fullScreen, exact, battery)
}

private fun tryStart(ctx: Context, vararg intents: Intent) {
    for (i in intents) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: Exception) {
        }
    }
}

fun openNotificationSettings(ctx: Context) = tryStart(
    ctx,
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
)

fun openFullScreenSettings(ctx: Context) {
    if (Build.VERSION.SDK_INT >= 34) {
        tryStart(
            ctx,
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
        )
    }
}

fun openExactAlarmSettings(ctx: Context) {
    if (Build.VERSION.SDK_INT >= 31) {
        tryStart(ctx, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
    }
}

@SuppressLint("BatteryLife")
fun openBatterySettings(ctx: Context) = tryStart(
    ctx,
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
)

@Composable
fun SectionTitle(text: String, sub: String? = null) {
    Column(Modifier.padding(top = 8.dp, bottom = 8.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
