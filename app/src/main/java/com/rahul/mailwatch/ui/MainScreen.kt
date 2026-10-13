package com.rahul.mailwatch.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rahul.mailwatch.Alarms
import com.rahul.mailwatch.Alert
import com.rahul.mailwatch.ChatEngine
import com.rahul.mailwatch.ChatMsg
import com.rahul.mailwatch.Scheduler
import com.rahul.mailwatch.Store
import com.rahul.mailwatch.Watch
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(startOnArrived: Boolean, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(if (startOnArrived) 1 else 0) }
    val alerts by Store.alerts.collectAsStateWithLifecycle()
    val accounts by Store.accounts.collectAsStateWithLifecycle()
    val lastCheck by Store.lastCheck.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick++ } } // keeps "checked x min ago" fresh

    val ringing = alerts.count { Alarms.isRinging(it) }
    val broken = accounts.firstOrNull { it.enabled && it.lastError != null }
    val status = when {
        accounts.isEmpty() -> "No inbox connected"
        broken != null -> "${broken.address} needs attention"
        else -> "Watching ${accounts.count { it.enabled }} inbox${if (accounts.count { it.enabled } == 1) "" else "es"}, checked ${ago(lastCheck).lowercase()}"
    }.also { tick.hashCode() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MailWatch", fontWeight = FontWeight.Bold)
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (broken != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(enabled = broken != null || accounts.isEmpty()) { onOpenSettings() },
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { Scheduler.checkNow(ctx); toast(ctx, "Checking your inboxes…") }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Check mail now")
                    }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(ChatIcon, contentDescription = null) }, label = { Text("Chat") },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = {
                        BadgedBox(badge = { if (ringing > 0) Badge(containerColor = Amber, contentColor = AmberDeep) { Text("$ringing") } }) {
                            Icon(InboxIcon, contentDescription = null)
                        }
                    },
                    label = { Text("Arrived") },
                )
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize()) {
            if (tab == 0) ChatScreen() else ArrivedScreen(alerts)
        }
    }
}

// ============================================================================ Chat
private val examples = listOf(
    "Remind me when the HCL interview call mail comes",
    "Ring me for every mail from the placement cell about a new company drive",
    "Tell me when my internship offer letter arrives",
)

@Composable
fun ChatScreen() {
    val ctx = LocalContext.current
    val chat by Store.chat.collectAsStateWithLifecycle()
    val watches by Store.watches.collectAsStateWithLifecycle()
    val thinking by ChatEngine.thinking.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var openWatch by remember { mutableStateOf<Watch?>(null) }
    val listState = rememberLazyListState()
    val visibleWatches = watches.filter { it.status != "done" }

    LaunchedEffect(chat.size, thinking) { if (chat.isNotEmpty()) listState.animateScrollToItem(0) }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (visibleWatches.isNotEmpty()) {
            Text(
                "Watching for",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visibleWatches, key = { it.id }) { w -> WatchChip(w) { openWatch = w } }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (chat.isEmpty() && !thinking) {
                EmptyChat(onPick = { input = it })
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (thinking) item(key = "thinking") { Bubble(ChatMsg(-1, false, "Thinking…"), faded = true) }
                    items(chat.reversed(), key = { it.id }) { Bubble(it) }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("What mail should I watch for?") },
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            FilledIconButton(
                onClick = { ChatEngine.send(ctx, input); input = "" },
                enabled = input.isNotBlank() && !thinking,
                modifier = Modifier.size(52.dp),
            ) { Icon(Icons.Filled.Send, contentDescription = "Send") }
        }
    }

    openWatch?.let { w -> WatchDialog(w, onClose = { openWatch = null }) }
}

@Composable
private fun EmptyChat(onPick: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("What are you waiting for?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Tell me in your own words. When that email arrives, your phone rings like an alarm until you stop it.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        examples.forEach { e ->
            Surface(
                onClick = { onPick(e) },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) { Text(e, Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
        }
    }
}

@Composable
private fun Bubble(m: ChatMsg, faded: Boolean = false) {
    val mine = m.fromUser
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp,
            ),
            color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
            contentColor = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                m.text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                color = if (faded) MaterialTheme.colorScheme.onSurfaceVariant else androidx.compose.ui.graphics.Color.Unspecified,
            )
        }
    }
}

@Composable
private fun WatchChip(w: Watch, onClick: () -> Unit) {
    val paused = w.status == "paused"
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (paused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(8.dp).background(
                    if (paused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary, CircleShape,
                ),
            )
            Spacer(Modifier.size(8.dp))
            Text(if (paused) "${w.title} (paused)" else w.title, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun WatchDialog(w: Watch, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(w.title) },
        text = {
            Column {
                Text(w.description)
                if (w.keywords.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Keywords: " + w.keywords.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (w.oneTime) "Stops watching after the first match." else "Rings for every matching mail.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Store.updateWatch(w.id) { it.copy(status = if (it.status == "active") "paused" else "active") }
                onClose()
            }) { Text(if (w.status == "active") "Pause" else "Resume") }
        },
        dismissButton = {
            TextButton(onClick = { Store.removeWatch(w.id); onClose() }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

// ============================================================================ Arrived
@Composable
fun ArrivedScreen(alerts: List<Alert>) {
    val sorted = alerts.sortedWith(compareByDescending<Alert> { Alarms.isRinging(it) }.thenByDescending { it.created })
    if (sorted.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(InboxIcon, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text("Nothing has arrived yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Emails that match your reminders show up here, and your phone rings at the same moment.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(sorted, key = { it.id }) { a -> AlertCard(a) }
        if (sorted.any { !Alarms.isRinging(it) }) {
            item(key = "clear") {
                TextButton(onClick = { Store.clearAlerts() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Clear stopped alarms")
                }
            }
        }
    }
}

@Composable
private fun AlertCard(a: Alert) {
    val ctx = LocalContext.current
    val live = Alarms.isRinging(a)
    var open by rememberSaveable(a.id) { mutableStateOf(live) }
    Card(
        onClick = { open = !open },
        colors = CardDefaults.cardColors(
            containerColor = if (live) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (live) {
                    Icon(BellIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                }
                Text(
                    a.watchTitle + if (live) ", ringing" else "",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (live) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(ago(a.created), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Text(a.subject, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                a.sender + if (a.account.isNotBlank()) "  →  ${a.account}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (open) {
                if (a.reason.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        a.reason + if (a.method == "keywords") " (keyword match, Gemini was unavailable)" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (a.snippet.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        a.snippet, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 8, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (live) {
                    Button(
                        onClick = { Alarms.stop(ctx, a.id) },
                        colors = ButtonDefaults.buttonColors(containerColor = AmberDeep, contentColor = Amber),
                    ) { Text("Stop alarm") }
                }
                if (a.provider.isNotBlank()) {
                    OutlinedButton(onClick = { openMailApp(ctx, a) }) {
                        Text(if (a.provider == "gmail") "Open Gmail" else "Open Outlook")
                    }
                }
                if (a.link.isNotBlank()) TextButton(onClick = { openUrl(ctx, a.link) }) { Text("Web") }
            }
        }
    }
}
