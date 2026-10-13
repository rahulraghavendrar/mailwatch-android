package com.rahul.mailwatch.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rahul.mailwatch.Account
import com.rahul.mailwatch.Alarms
import com.rahul.mailwatch.BuildConfig
import com.rahul.mailwatch.Gemini
import com.rahul.mailwatch.GmailImap
import com.rahul.mailwatch.Outlook
import com.rahul.mailwatch.Scheduler
import com.rahul.mailwatch.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

const val GUIDE_URL = "https://github.com/rahulraghavendrar/mailwatch-android#readme"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            SectionTitle("Inboxes", "The mail accounts MailWatch checks.")
            AccountsSection()
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SectionTitle("Alarm")
            AlarmSection()
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SectionTitle("Make sure the alarm can reach you")
            PermissionsSection()
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SectionTitle("Gemini")
            GeminiKeySection(onSaved = {})
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { Store.clearChat() }) { Text("Clear chat") }
                TextButton(onClick = { openUrl(ctx, GUIDE_URL) }) { Text("Setup guide") }
            }
            Text(
                "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, bottom = 32.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------- Gemini key
@Composable
fun GeminiKeySection(onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by Store.settings.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf(settings.geminiKey) }
    var model by remember { mutableStateOf(settings.geminiModel) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val saved = settings.geminiKey.isNotBlank() && key == settings.geminiKey && model == settings.geminiModel

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = key, onValueChange = { key = it.trim(); error = null },
            label = { Text("Gemini API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model, onValueChange = { model = it.trim() },
            label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = key.isNotBlank() && !busy && !saved,
                onClick = {
                    busy = true
                    scope.launch {
                        val err = withContext(Dispatchers.IO) { Gemini.ping(key, model) }
                        busy = false
                        if (err == null) {
                            Store.updateSettings { it.copy(geminiKey = key, geminiModel = model.ifBlank { "gemini-3.5-flash-lite" }) }
                            toast(ctx, "Gemini key works")
                            onSaved()
                        } else error = err.take(200)
                    }
                },
            ) { Text(if (busy) "Checking…" else if (saved) "Saved" else "Check and save") }
            TextButton(onClick = { openUrl(ctx, "https://aistudio.google.com/apikey") }) { Text("Get a free key") }
        }
    }
}

// ---------------------------------------------------------------------------- inboxes
private sealed class OutlookUi {
    data object Idle : OutlookUi()
    data object Starting : OutlookUi()
    data class Code(val code: String, val url: String) : OutlookUi()
    data class Error(val msg: String) : OutlookUi()
}

@Composable
fun AccountsSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val accounts by Store.accounts.collectAsStateWithLifecycle()
    val settings by Store.settings.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf<String?>(if (accounts.isEmpty()) "gmail" else null) }

    // Gmail form
    var address by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var gmailBusy by remember { mutableStateOf(false) }
    var gmailError by remember { mutableStateOf<String?>(null) }

    // Outlook
    var clientId by remember { mutableStateOf(settings.msClientId) }
    var outlook by remember { mutableStateOf<OutlookUi>(OutlookUi.Idle) }
    var outlookJob by remember { mutableStateOf<Job?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        accounts.forEach { a -> AccountRow(a) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { adding = if (adding == "gmail") null else "gmail" }) { Text("Add Gmail") }
            FilledTonalButton(onClick = { adding = if (adding == "outlook") null else "outlook" }) { Text("Add Outlook") }
        }

        if (adding == "gmail") {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Gmail needs an app password (16 letters), not your normal password. You need 2-Step Verification on.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { openUrl(ctx, "https://myaccount.google.com/apppasswords") }) { Text("Create an app password") }
                    OutlinedTextField(
                        value = address, onValueChange = { address = it.trim(); gmailError = null },
                        label = { Text("Gmail address") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it; gmailError = null },
                        label = { Text("App password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    gmailError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(
                        enabled = address.contains("@") && password.replace(" ", "").length >= 12 && !gmailBusy,
                        onClick = {
                            gmailBusy = true
                            scope.launch {
                                val err = withContext(Dispatchers.IO) {
                                    runCatching { GmailImap.testLogin(address, password) }.exceptionOrNull()
                                }
                                gmailBusy = false
                                if (err == null) {
                                    Store.upsertAccount(
                                        Account(UUID.randomUUID().toString(), "gmail", address.lowercase(), password.replace(" ", "")),
                                    )
                                    address = ""; password = ""; adding = null
                                    toast(ctx, "Gmail connected")
                                    if (Store.settings.value.onboarded) Scheduler.checkNow(ctx)
                                } else gmailError = err.message ?: "Couldn't connect to Gmail"
                            }
                        },
                    ) { Text(if (gmailBusy) "Connecting…" else "Connect Gmail") }
                }
            }
        }

        if (adding == "outlook") {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Outlook needs a free Microsoft app ID. It takes about 10 minutes the first time; the guide shows how. " +
                            "If your college blocks it, forward your college mail to Gmail instead.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { openUrl(ctx, "$GUIDE_URL") }) { Text("Open the guide") }
                    OutlinedTextField(
                        value = clientId, onValueChange = { clientId = it.trim() },
                        label = { Text("Microsoft app (client) ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    when (val o = outlook) {
                        is OutlookUi.Code -> {
                            Text("Open the link below, enter this code, and sign in with your Outlook account:")
                            Text(o.code, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    clipboard.setText(AnnotatedString(o.code))
                                    openUrl(ctx, o.url)
                                }) { Text("Copy code and open") }
                                TextButton(onClick = { outlookJob?.cancel(); outlook = OutlookUi.Idle }) { Text("Cancel") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(8.dp))
                                Text("Waiting for you to sign in…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        else -> {
                            if (o is OutlookUi.Error) Text(o.msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            Button(
                                enabled = clientId.length > 10 && o !is OutlookUi.Starting,
                                onClick = {
                                    Store.updateSettings { it.copy(msClientId = clientId) }
                                    outlook = OutlookUi.Starting
                                    outlookJob = scope.launch {
                                        try {
                                            val dc = withContext(Dispatchers.IO) { Outlook.startDeviceFlow(clientId, settings.msTenant) }
                                            outlook = OutlookUi.Code(dc.userCode, dc.verificationUri)
                                            val (addr, refresh) = withContext(Dispatchers.IO) {
                                                Outlook.waitForSignIn(clientId, settings.msTenant, dc)
                                            }
                                            Store.upsertAccount(Account(UUID.randomUUID().toString(), "outlook", addr.lowercase(), refresh))
                                            outlook = OutlookUi.Idle
                                            adding = null
                                            toast(ctx, "Outlook connected: $addr")
                                            if (Store.settings.value.onboarded) Scheduler.checkNow(ctx)
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            outlook = OutlookUi.Error(e.message ?: "Sign-in failed")
                                        }
                                    }
                                },
                            ) { Text(if (o is OutlookUi.Starting) "Starting…" else "Connect Outlook") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountRow(a: Account) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a.address, fontWeight = FontWeight.SemiBold)
                val status = when {
                    !a.enabled -> "${a.label}, paused"
                    a.lastError != null -> a.lastError
                    a.lastChecked > 0 -> "${a.label}, checked ${ago(a.lastChecked).lowercase()}"
                    else -> "${a.label}, waiting for first check"
                }
                Text(
                    status, style = MaterialTheme.typography.bodySmall,
                    color = if (a.enabled && a.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = a.enabled, onCheckedChange = { on -> Store.updateAccount(a.id) { it.copy(enabled = on) } })
            IconButton(onClick = { Store.removeAccount(a.id) }) { Icon(Icons.Filled.Delete, contentDescription = "Remove ${a.address}") }
        }
    }
}

// ---------------------------------------------------------------------------- alarm settings
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlarmSection() {
    val ctx = LocalContext.current
    val s by Store.settings.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ChoiceRow("Check for new mail every", listOf(2, 5, 10, 15), s.checkMinutes, { "$it min" }) { v ->
            Store.updateSettings { it.copy(checkMinutes = v) }
            Scheduler.scheduleNext(ctx)
        }
        ChoiceRow("Each alarm rings for", listOf(1, 3, 5), s.ringMinutes, { "$it min" }) { v ->
            Store.updateSettings { it.copy(ringMinutes = v) }
        }
        ChoiceRow("If you don't stop it, ring", listOf(1, 3, 5, 10), s.rounds, { if (it == 1) "once" else "$it times" }) { v ->
            Store.updateSettings { it.copy(rounds = v) }
        }
        Text(
            "When the phone sits idle for a long time, Android may stretch checks to about every 10–15 minutes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = {
            Alarms.test(ctx, 10)
            toast(ctx, "Test alarm in 10 seconds. Lock your phone to see the full-screen alarm.")
        }) { Text("Test the alarm") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(label: String, options: List<Int>, selected: Int, text: (Int) -> String, onPick: (Int) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { v ->
                FilterChip(selected = v == selected, onClick = { onPick(v) }, label = { Text(text(v)) })
            }
        }
    }
}

// ---------------------------------------------------------------------------- permissions
@Composable
fun PermissionsSection() {
    val ctx = LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    val p = remember(lifecycleState, refresh) { permState(ctx) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PermRow("Notifications", "Needed for the alarm to appear at all.", p.notifications) {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else openNotificationSettings(ctx)
        }
        PermRow("Alarms over the lock screen", "Lets the alarm fill the screen like your clock app.", p.fullScreen) {
            openFullScreenSettings(ctx)
        }
        PermRow("Exact timing", "Lets MailWatch check mail on time.", p.exactAlarms) { openExactAlarmSettings(ctx) }
        PermRow("Unrestricted battery", "Stops Android from pausing MailWatch in the background.", p.battery) {
            openBatterySettings(ctx)
        }
    }
}

@Composable
private fun PermRow(title: String, why: String, ok: Boolean, fix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = if (ok) "Allowed" else "Not allowed",
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!ok) TextButton(onClick = fix) { Text("Allow") }
    }
}

@Composable
fun Gap(h: Int = 16) = Spacer(Modifier.height(h.dp))
