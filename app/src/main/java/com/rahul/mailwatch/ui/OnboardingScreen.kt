package com.rahul.mailwatch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rahul.mailwatch.Store

/** First launch: Gemini key → inboxes → permissions. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val settings by Store.settings.collectAsStateWithLifecycle()
    val accounts by Store.accounts.collectAsStateWithLifecycle()

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(BellIcon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.size(10.dp))
                Text("MailWatch", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(progress = { (step + 1) / 3f }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))

            when (step) {
                0 -> {
                    Heading("Connect Gemini", "Gemini reads your reminders and decides which new emails match them. The free key is enough.")
                    GeminiKeySection(onSaved = { step = 1 })
                    NavRow(canNext = settings.geminiKey.isNotBlank(), onNext = { step = 1 })
                }
                1 -> {
                    Heading(
                        "Which inboxes should I watch?",
                        "Add every account where the email might land. MailWatch only reads mail and never marks it as read.",
                    )
                    AccountsSection()
                    NavRow(
                        canNext = accounts.isNotEmpty(),
                        nextLabel = "Next",
                        onBack = { step = 0 },
                        onNext = { step = 2 },
                        skip = if (accounts.isEmpty()) ({ step = 2 }) else null,
                    )
                }
                else -> {
                    Heading(
                        "Let the alarm reach you",
                        "Allow these so the alarm can ring over the lock screen and in silent mode, like your clock app.",
                    )
                    PermissionsSection()
                    Spacer(Modifier.height(12.dp))
                    AlarmSection()
                    NavRow(canNext = true, nextLabel = "Start watching", onBack = { step = 1 }, onNext = onDone)
                }
            }
        }
    }
}

@Composable
private fun Heading(title: String, sub: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Text(sub, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun NavRow(
    canNext: Boolean,
    onNext: () -> Unit,
    nextLabel: String = "Next",
    onBack: (() -> Unit)? = null,
    skip: (() -> Unit)? = null,
) {
    Spacer(Modifier.height(24.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) TextButton(onClick = onBack) { Text("Back") } else Spacer(Modifier.size(1.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (skip != null) TextButton(onClick = skip) { Text("Skip for now") }
            Button(onClick = onNext, enabled = canNext) { Text(nextLabel) }
        }
    }
    Spacer(Modifier.height(24.dp))
}
