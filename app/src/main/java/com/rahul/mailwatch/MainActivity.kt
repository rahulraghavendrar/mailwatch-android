package com.rahul.mailwatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rahul.mailwatch.ui.MailWatchTheme
import com.rahul.mailwatch.ui.MainScreen
import com.rahul.mailwatch.ui.OnboardingScreen
import com.rahul.mailwatch.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val openArrived = intent?.getBooleanExtra("arrived", false) ?: false
        setContent {
            MailWatchTheme {
                val settings by Store.settings.collectAsStateWithLifecycle()
                var showSettings by rememberSaveable { mutableStateOf(false) }
                when {
                    !settings.onboarded -> OnboardingScreen(onDone = {
                        Store.updateSettings { it.copy(onboarded = true) }
                        Scheduler.start(applicationContext)
                        Scheduler.checkNow(applicationContext)
                    })
                    showSettings -> {
                        BackHandler { showSettings = false }
                        SettingsScreen(onBack = { showSettings = false })
                    }
                    else -> MainScreen(startOnArrived = openArrived, onOpenSettings = { showSettings = true })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Opening the app is a good moment to check (cheap, and makes the list feel fresh).
        if (Store.settings.value.onboarded) Scheduler.checkNow(applicationContext)
    }
}
