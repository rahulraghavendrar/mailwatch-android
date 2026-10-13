package com.rahul.mailwatch

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Sends what you type to Gemini and applies the reminders it decides on. */
object ChatEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()

    fun send(ctx: Context, text: String) {
        val msg = text.trim()
        if (msg.isEmpty() || _thinking.value) return
        val history = Store.chat.value
        Store.addChat(true, msg)
        _thinking.value = true
        val app = ctx.applicationContext
        scope.launch {
            try {
                val s = Store.settings.value
                val result = try {
                    Gemini.chat(s, history, Store.watches.value.filter { it.status != "done" }, Store.accounts.value, msg)
                } catch (e: Exception) {
                    fallback(msg, e)
                }
                val added = apply(result.actions, msg)
                Store.addChat(false, result.reply)
                for (w in added) {
                    val found = runCatching { Checker.catchUp(app, w) }.getOrDefault(0)
                    if (found > 0) Store.addChat(false, "Heads up: it's already in your inbox. Check the Arrived tab.")
                }
            } finally {
                _thinking.value = false
            }
        }
    }

    private fun apply(actions: List<Gemini.Action>, request: String): List<Watch> {
        val added = mutableListOf<Watch>()
        for (a in actions) {
            when (a.type) {
                "add_watch" -> if (a.title.isNotBlank()) {
                    added += Store.addWatch(
                        Watch(
                            id = Store.nextId(), request = request, title = a.title,
                            description = a.description.ifBlank { request }, keywords = a.keywords,
                            senders = a.senders, oneTime = a.oneTime,
                        )
                    )
                }
                "remove_watch" -> Store.removeWatch(a.id)
                "pause_watch" -> Store.updateWatch(a.id) { it.copy(status = "paused") }
                "resume_watch" -> Store.updateWatch(a.id) { it.copy(status = "active") }
            }
        }
        return added
    }

    /** Gemini unreachable: still set a simple keyword reminder if it sounds like one. */
    private fun fallback(msg: String, e: Exception): Gemini.ChatResult {
        val lower = msg.lowercase()
        val looksLikeReminder = listOf("remind", "when", "notify", "ring", "alert", "mail", "email", "tell me")
            .any { lower.contains(it) }
        if (!looksLikeReminder) {
            return Gemini.ChatResult("I couldn't reach Gemini (${e.message?.take(120)}). Check your key in Settings.", emptyList())
        }
        val stop = ("remind me when the a an of for to my mail email mails comes come arrives arrive notify tell if from " +
            "about is in on and or any that this it please i get gets ring alert me").split(" ").toSet()
        val words = Regex("[A-Za-z0-9.@&+-]{2,}").findAll(msg).map { it.value }.filter { it.lowercase() !in stop }.distinct().toList()
        val title = words.take(5).joinToString(" ").ifBlank { msg.take(40) }
        return Gemini.ChatResult(
            "Gemini isn't answering right now, so I set a simple keyword reminder for \"$title\". " +
                "It rings when a mail mentions ${words.take(4).joinToString(", ")}.",
            listOf(Gemini.Action(type = "add_watch", title = title, description = msg, keywords = words.take(8), oneTime = true)),
        )
    }
}
