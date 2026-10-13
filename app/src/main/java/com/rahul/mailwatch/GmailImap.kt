package com.rahul.mailwatch

import com.sun.mail.imap.IMAPFolder
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Date
import java.util.Properties
import javax.mail.AuthenticationFailedException
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.ComparisonTerm
import javax.mail.search.ReceivedDateTerm
import javax.mail.Store as MailStore

class AuthError(msg: String) : Exception(msg)

/** Gmail over IMAP with an app password. Read-only: never marks mail as read. */
object GmailImap {
    private const val HOST = "imap.gmail.com"
    private const val MAX_PER_CHECK = 40

    private fun session(): Session {
        val p = Properties()
        p["mail.store.protocol"] = "imaps"
        p["mail.imaps.host"] = HOST
        p["mail.imaps.port"] = "993"
        p["mail.imaps.ssl.enable"] = "true"
        p["mail.imaps.connectiontimeout"] = "20000"
        p["mail.imaps.timeout"] = "40000"
        return Session.getInstance(p)
    }

    fun connect(address: String, appPassword: String): MailStore {
        val store = session().getStore("imaps")
        try {
            store.connect(HOST, 993, address, appPassword.replace(" ", ""))
        } catch (e: AuthenticationFailedException) {
            throw AuthError(
                "Gmail didn't accept the password. Use a 16-letter app password from " +
                    "myaccount.google.com/apppasswords, not your normal password."
            )
        }
        return store
    }

    fun testLogin(address: String, appPassword: String) {
        connect(address, appPassword).close()
    }

    /** Inbox + the Spam folder (its name depends on the account's language). */
    private fun folders(store: MailStore): List<Pair<IMAPFolder, String>> {
        val out = mutableListOf(store.getFolder("INBOX") as IMAPFolder to "Inbox")
        try {
            for (f in store.defaultFolder.list("*")) {
                val imf = f as? IMAPFolder ?: continue
                if (imf.attributes.any { it.equals("\\Junk", ignoreCase = true) }) {
                    out += imf to "Spam"
                    break
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** Mail that arrived since last time. The first call only remembers where the inbox is. */
    fun fetchNew(address: String, appPassword: String, stateJson: String): Pair<List<Email>, String> {
        val state = runCatching { JSONObject(stateJson) }.getOrDefault(JSONObject())
        val fstate = state.optJSONObject("folders") ?: JSONObject()
        val emails = mutableListOf<Email>()
        val store = connect(address, appPassword)
        try {
            for ((folder, label) in folders(store)) {
                folder.open(Folder.READ_ONLY)
                try {
                    val validity = folder.uidValidity
                    val uidNext = folder.uidNext
                    val st = fstate.optJSONObject(folder.fullName)
                    if (st == null || st.optLong("validity") != validity) {
                        val last = if (uidNext > 0) uidNext - 1
                        else if (folder.messageCount > 0) folder.getUID(folder.getMessage(folder.messageCount)) else 0L
                        fstate.put(folder.fullName, JSONObject().put("validity", validity).put("last", last))
                        continue
                    }
                    val last = st.optLong("last")
                    if (uidNext > 0 && uidNext - 1 <= last) continue
                    val msgs = folder.getMessagesByUID(last + 1, UIDFolder.LASTUID)
                        .filterNotNull()
                        .filter { folder.getUID(it) > last }
                    if (msgs.isEmpty()) continue
                    val recent = msgs.takeLast(MAX_PER_CHECK)
                    prefetch(folder, recent)
                    recent.forEach { m -> runCatching { emails += toEmail(m, folder, label, address) } }
                    fstate.put(folder.fullName, JSONObject().put("validity", validity).put("last", msgs.maxOf { folder.getUID(it) }))
                } finally {
                    runCatching { folder.close(false) }
                }
            }
        } finally {
            runCatching { store.close() }
        }
        state.put("folders", fstate)
        return emails to state.toString()
    }

    /** Mail from the last [days] days, used when a new reminder is added. */
    fun fetchRecent(address: String, appPassword: String, days: Int): List<Email> {
        val since = Date(System.currentTimeMillis() - days * 86_400_000L)
        val emails = mutableListOf<Email>()
        val store = connect(address, appPassword)
        try {
            for ((folder, label) in folders(store)) {
                folder.open(Folder.READ_ONLY)
                try {
                    val found = folder.search(ReceivedDateTerm(ComparisonTerm.GE, since))
                        .takeLast(if (label == "Inbox") 30 else 10)
                    prefetch(folder, found)
                    found.forEach { m -> runCatching { emails += toEmail(m, folder, label, address) } }
                } finally {
                    runCatching { folder.close(false) }
                }
            }
        } finally {
            runCatching { store.close() }
        }
        return emails
    }

    private fun prefetch(folder: IMAPFolder, msgs: List<Message>) {
        if (msgs.isEmpty()) return
        val fp = FetchProfile()
        fp.add(FetchProfile.Item.ENVELOPE)
        fp.add(UIDFolder.FetchProfileItem.UID)
        runCatching { folder.fetch(msgs.toTypedArray(), fp) }
    }

    private fun toEmail(m: Message, folder: IMAPFolder, label: String, address: String): Email {
        val first = m.from?.firstOrNull()
        val sender = (first as? InternetAddress)?.let { a ->
            if (a.personal.isNullOrBlank()) a.address else "${a.personal} <${a.address}>"
        } ?: first?.toString() ?: ""
        val msgId = (m as? MimeMessage)?.messageID?.trim()
        val body = runCatching { textOf(m, 0) }.getOrNull().orEmpty()
        val link = if (!msgId.isNullOrBlank()) {
            "https://mail.google.com/mail/u/?authuser=$address#search/rfc822msgid%3A" +
                URLEncoder.encode(msgId.trim('<', '>'), "UTF-8")
        } else "https://mail.google.com/mail/u/?authuser=$address"
        return Email(
            key = "gmail:$address:" + (msgId ?: "${folder.uidValidity}-${folder.getUID(m)}"),
            provider = "gmail",
            account = address,
            folder = label,
            sender = sender,
            subject = m.subject ?: "(no subject)",
            date = (m.receivedDate ?: m.sentDate)?.toString().orEmpty(),
            preview = Text.clean(body).take(1500),
            link = link,
        )
    }

    private fun textOf(p: Part, depth: Int): String? {
        if (depth > 6) return null
        if (Part.ATTACHMENT.equals(p.disposition, ignoreCase = true)) return null
        return when {
            p.isMimeType("text/plain") -> p.content as? String
            p.isMimeType("text/html") -> (p.content as? String)?.let { Text.htmlToText(it) }
            p.isMimeType("multipart/alternative") -> {
                val mp = p.content as? Multipart ?: return null
                var fallback: String? = null
                for (i in 0 until mp.count) {
                    val bp = mp.getBodyPart(i)
                    if (bp.isMimeType("text/plain")) return bp.content as? String
                    if (fallback == null) fallback = textOf(bp, depth + 1)
                }
                fallback
            }
            p.isMimeType("multipart/*") -> {
                val mp = p.content as? Multipart ?: return null
                for (i in 0 until mp.count) {
                    val t = textOf(mp.getBodyPart(i), depth + 1)
                    if (!t.isNullOrBlank()) return t
                }
                null
            }
            else -> null
        }
    }
}

object Text {
    fun htmlToText(html: String): String {
        var s = html.replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</tr>|</li>"), "\n")
        s = s.replace(Regex("<[^>]+>"), " ")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&rsquo;", "'").replace("&ndash;", "–")
        return s
    }

    fun clean(s: String): String =
        s.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n").trim()
}
