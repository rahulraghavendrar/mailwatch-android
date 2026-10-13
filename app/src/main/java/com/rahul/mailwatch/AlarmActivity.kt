package com.rahul.mailwatch

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rahul.mailwatch.ui.Amber
import com.rahul.mailwatch.ui.AmberDeep
import com.rahul.mailwatch.ui.BellIcon
import com.rahul.mailwatch.ui.MailWatchTheme
import com.rahul.mailwatch.ui.openMailApp
import java.text.DateFormat
import java.util.Date

/** The full-screen alarm, shown over the lock screen like the clock app's alarm. */
class AlarmActivity : ComponentActivity() {
    private var alertId = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        show(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        show(intent)
    }

    private fun show(intent: Intent?) {
        alertId = intent?.getLongExtra(Alarms.EXTRA_ALERT, -1) ?: -1
        val alert = Store.alert(alertId)
        if (alert == null || alert.acked) {
            finish()
            return
        }
        Alarms.showRingingQuietly(this, alertId)
        AlarmSound.start(this, Store.settings.value.ringMinutes)
        setContent {
            MailWatchTheme {
                AlarmScreen(
                    alert = alert,
                    onStop = {
                        Alarms.stop(this, alertId)
                        finish()
                    },
                    onOpen = {
                        Alarms.stop(this, alertId)
                        openMailApp(this, alert)
                        finish()
                    },
                    onLater = {
                        // Silence now; it rings again after the pause unless you stop it.
                        Alarms.snooze(this, alertId)
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun AlarmScreen(alert: Alert, onStop: () -> Unit, onOpen: () -> Unit, onLater: () -> Unit) {
    Surface(color = Amber, contentColor = AmberDeep, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(32.dp))
                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()), fontSize = 20.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(28.dp))
                Icon(BellIcon, contentDescription = null, modifier = Modifier.size(72.dp))
                Spacer(Modifier.height(20.dp))
                Text(
                    alert.watchTitle,
                    fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center,
                )
                Text("just arrived", fontSize = 20.sp, textAlign = TextAlign.Center)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    alert.subject, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(alert.sender, fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onStop,
                    shape = RoundedCornerShape(36.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AmberDeep, contentColor = Amber),
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                ) { Text("Stop", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(12.dp))
                if (alert.provider.isNotBlank()) {
                    Button(
                        onClick = onOpen,
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.55f), contentColor = AmberDeep),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Text("Stop and open the email", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                }
                TextButton(onClick = onLater) { Text("Remind me again in a few minutes", color = AmberDeep) }
            }
        }
    }
}
