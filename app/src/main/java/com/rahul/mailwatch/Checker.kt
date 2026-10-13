package com.rahul.mailwatch

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Reads new mail from every inbox, asks Gemini which mails match, and starts alarms. */
object Checker {
    private val mutex = Mutex()

    data class Result(val inboxes: Int, val newMails: Int, val matches: Int)

    suspend fun checkOnce(ctx: Context): Result = withContext(Dispatchers.IO) {
        mutex.withLock {
            val s = Store.settings.value
            val accounts = Store.accounts.value.filter { it.enabled }
            val fresh = mutableListOf<Email>()
            for (acc in accounts) {
                try {
                    fresh += fetchAccount(acc, s).filter { Store.markSeen(it.key) }
                } catch (e: Exception) {
                    accountFailed(ctx, acc, e)
                }
            }
            val watches = Store.activeWatches()
            var created = 0
            if (fresh.isNotEmpty() && watches.isNotEmpty()) {
                for (m in findMatches(s, watches, fresh)) {
                    if (createAlert(ctx, m.email, m.watch, m.reason, m.method) != null) created++
                }
            }
            Store.setLastCheck(System.currentTimeMillis())
            Result(accounts.size, fresh.size, created)
        }
    }

    private fun fetchAccount(acc: Account, s: Settings): List<Email> {
        val emails: List<Email>
        if (acc.provider == "gmail") {
            val (list, state) = GmailImap.fetchNew(acc.address, acc.secret, acc.state)
            Store.updateAccount(acc.id) { it.copy(state = state) }
            emails = list
        } else {
            val r = Outlook.fetchNew(acc, s)
            Store.updateAccount(acc.id) { it.copy(state = r.state) }
            if (r.baseline) r.emails.forEach { Store.markSeen(it.key) }
            emails = if (r.baseline) emptyList() else r.emails
        }
        Store.updateAccount(acc.id) {
            it.copy(lastChecked = System.currentTimeMillis(), lastError = null, errorNotified = false, errorSince = 0)
        }
        return emails
    }

    private fun accountFailed(ctx: Context, acc: Account, e: Exception) {
        val isAuth = e is AuthError
        val msg = if (isAuth) e.message.orEmpty() else "Couldn't connect (${e.message ?: e.javaClass.simpleName}). Retrying."
        val now = System.currentTimeMillis()
        val since = if (acc.errorSince > 0) acc.errorSince else now
        Store.updateAccount(acc.id) { it.copy(lastError = msg.take(300), lastChecked = now, errorSince = since) }
        // Sign-in problems: tell you right away. Network blips: only if they last 30 minutes.
        if (!acc.errorNotified && (isAuth || now - since > 30 * 60_000L)) {
            Notify.info(ctx, "MailWatch can't read ${acc.address}", "$msg Open MailWatch → Settings to fix it.")
            Store.updateAccount(acc.id) { it.copy(errorNotified = true) }
        }
    }

    data class Found(val email: Email, val watch: Watch, val reason: String, val method: String)

    fun findMatches(s: Settings, watches: List<Watch>, emails: List<Email>): List<Found> {
        val byId = watches.associateBy { it.id }
        val out = mutableListOf<Found>()
        for (chunk in emails.chunked(20)) {
            try {
                for (m in Gemini.matchEmails(s, watches, chunk)) {
                    if (m.confidence < s.threshold) continue
                    val w = byId[m.watchId] ?: continue
                    out += Found(chunk[m.emailIndex], w, m.reason, "ai")
                }
            } catch (e: Exception) {
                // Gemini down or out of quota: fall back to keywords so the mail isn't missed.
                out += keywordMatch(watches, chunk)
            }
        }
        return out
    }

    fun keywordMatch(watches: List<Watch>, emails: List<Email>): List<Found> {
        val out = mutableListOf<Found>()
        for (e in emails) {
            val subject = e.subject.lowercase()
            val sender = e.sender.lowercase()
            val text = "$subject $sender ${e.preview.lowercase()}"
            for (w in watches) {
                val hits = w.keywords.map { it.lowercase() }.filter { it.length >= 2 }.filter { k ->
                    Regex("(?<![a-z0-9])" + Regex.escape(k) + "(?![a-z0-9])").containsMatchIn(text)
                }
                val inSubject = hits.any { subject.contains(it) }
                val senderHit = w.senders.any { it.length >= 3 && sender.contains(it.lowercase()) }
                if ((senderHit && hits.isNotEmpty()) || (hits.size >= 2 && inSubject)) {
                    out += Found(e, w, "Keywords: " + hits.take(4).joinToString(), "keywords")
                }
            }
        }
        return out
    }

    fun createAlert(ctx: Context, e: Email, w: Watch, reason: String, method: String, ring: Boolean = true): Alert? {
        val alert = Store.addAlert(
            Alert(
                id = Store.nextId(), watchId = w.id, watchTitle = w.title, account = e.account, provider = e.provider,
                msgKey = e.key, sender = e.sender, subject = e.subject, snippet = e.preview.take(600), link = e.link,
                reason = reason, method = method,
            )
        ) ?: return null
        if (method != "test") {
            Store.updateWatch(w.id) {
                it.copy(
                    matchCount = it.matchCount + 1,
                    lastMatched = System.currentTimeMillis(),
                    status = if (it.oneTime) "done" else it.status,
                )
            }
            Store.addChat(false, "It's here! \"${e.subject}\" from ${e.sender.substringBefore(" <")}.")
        }
        if (ring) Alarms.ring(ctx, alert.id)
        return alert
    }

    /** When you add a reminder, also look at the last [days] days in case it already came. */
    suspend fun catchUp(ctx: Context, watch: Watch, days: Int = 2): Int = withContext(Dispatchers.IO) {
        val s = Store.settings.value
        val emails = mutableListOf<Email>()
        for (acc in Store.accounts.value.filter { it.enabled }) {
            try {
                emails += if (acc.provider == "gmail") GmailImap.fetchRecent(acc.address, acc.secret, days)
                else Outlook.fetchRecent(acc, s, days)
            } catch (_: Exception) {
            }
        }
        var found = 0
        for (m in findMatches(s, listOf(watch), emails)) {
            if (createAlert(ctx, m.email, m.watch, m.reason, m.method) != null) found++
        }
        found
    }
}
