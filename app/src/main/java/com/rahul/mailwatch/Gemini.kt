package com.rahul.mailwatch

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Gemini over REST. Every call returns parsed JSON. */
object Gemini {
    class GeminiError(msg: String) : Exception(msg)

    private const val API = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent"

    fun parseJson(text: String): JSONObject {
        val t = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try {
            JSONObject(t)
        } catch (e: Exception) {
            val start = t.indexOf('{')
            val end = t.lastIndexOf('}')
            if (start >= 0 && end > start) JSONObject(t.substring(start, end + 1))
            else throw GeminiError("Gemini didn't return JSON: ${t.take(150)}")
        }
    }

    fun call(s: Settings, prompt: String, temperature: Double = 0.1): JSONObject {
        if (s.geminiKey.isBlank()) throw GeminiError("Add your Gemini API key in Settings")
        val models = listOf(s.geminiModel, s.fallbackModel).filter { it.isNotBlank() }.distinct()
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("temperature", temperature).put("responseMimeType", "application/json"))
            .toString()
        var lastErr = "no response"
        for (model in models) {
            for (attempt in 0 until 3) {
                val r = try {
                    Http.request(
                        API.format(model), "POST", body, "application/json",
                        headers = listOf("x-goog-api-key" to s.geminiKey), timeoutMs = 60_000,
                    )
                } catch (e: Exception) {
                    lastErr = "network error: ${e.message}"
                    Thread.sleep(2000L * (attempt + 1))
                    continue
                }
                if (r.code == 200) {
                    val parts = JSONObject(r.body).optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")
                    val text = buildString {
                        for (i in 0 until (parts?.length() ?: 0)) {
                            val p = parts!!.getJSONObject(i)
                            if (!p.optBoolean("thought")) append(p.optString("text"))
                        }
                    }
                    if (text.isBlank()) { lastErr = "empty answer from $model"; break }
                    return parseJson(text)
                }
                lastErr = "$model: HTTP ${r.code} ${r.body.take(200)}"
                if ((r.code == 400 || r.code == 403) && r.body.contains("API key", true)) {
                    throw GeminiError("Gemini rejected the API key. Check it in Settings.")
                }
                if (r.code >= 500 && attempt < 2) { Thread.sleep(3000L * (attempt + 1)); continue }
                break // 404 model unknown / 429 quota → try the fallback model
            }
        }
        throw GeminiError(lastErr)
    }

    /** Quick check used when you paste the key. */
    fun ping(key: String, model: String): String? = try {
        call(Settings(geminiKey = key, geminiModel = model, fallbackModel = ""), "Return {\"ok\": true}")
        null
    } catch (e: Exception) {
        e.message ?: "Unknown error"
    }

    // ------------------------------------------------------------------ chat
    data class Action(
        val type: String,
        val id: Long = 0,
        val title: String = "",
        val description: String = "",
        val keywords: List<String> = emptyList(),
        val senders: List<String> = emptyList(),
        val oneTime: Boolean = true,
    )

    data class ChatResult(val reply: String, val actions: List<Action>)

    private const val PLACEMENT_HINT =
        "The user is a college student in India. Emails about jobs, internships and interviews often come " +
            "through the college placement / training & placement cell, a careers portal or a recruiting platform " +
            "(forwarded mails, Superset, HirePro, Unstop, Naukri), not only from the company's own domain."

    fun chat(s: Settings, history: List<ChatMsg>, watches: List<Watch>, accounts: List<Account>, userText: String): ChatResult {
        val today = SimpleDateFormat("EEEE d MMMM yyyy", Locale.ENGLISH).format(Date())
        val inboxes = accounts.joinToString { "${it.address} (${it.label})" }.ifBlank { "none yet" }
        val current = if (watches.isEmpty()) "none" else watches.joinToString("\n") {
            "  [${it.id}] ${it.title} (${it.status}) — ${it.description}"
        }
        val convo = history.takeLast(12).joinToString("\n") { (if (it.fromUser) "User: " else "You: ") + it.text.take(500) }
        val prompt = """
You are MailWatch, a friendly assistant inside an Android app. The app watches the user's email inboxes and rings
a loud full-screen alarm on their phone when an email they're waiting for arrives. The user tells you, in chat,
what to watch for. $PLACEMENT_HINT

Today: $today
Inboxes being watched: $inboxes
Current reminders:
$current

Conversation so far:
$convo
User: $userText

Decide what to do and answer in JSON only:
{
  "reply": "your message to the user: 1-3 short sentences, plain text, no markdown",
  "actions": [
    {"type": "add_watch", "title": "max 6 words", "description": "1-2 sentences: which emails should ring the alarm and which look-alikes should NOT (ads, newsletters, job-alert digests)", "keywords": ["3-10 likely words/phrases incl. variants, abbreviations and domains"], "senders": ["likely sender names or domains"], "one_time": true},
    {"type": "remove_watch", "id": 12},
    {"type": "pause_watch", "id": 12},
    {"type": "resume_watch", "id": 12}
  ]
}
Rules:
- add_watch when the user wants to be alerted about an email. one_time = true for one specific expected email,
  false for "every mail from/about ...". When you add one, say in the reply what you'll ring for.
- Use the ids above for remove/pause/resume. "actions": [] when nothing should change.
- If the request is too vague to watch for (e.g. "remind me about the mail"), ask one short question and add nothing.
- You cannot read, search, summarise or send emails, and cannot set time-based reminders. Say so briefly if asked.
""".trimIndent()
        val o = call(s, prompt, temperature = 0.3)
        val actions = mutableListOf<Action>()
        val arr = o.optJSONArray("actions") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            actions += Action(
                type = a.optString("type"),
                id = a.optLong("id"),
                title = a.optString("title").take(60),
                description = a.optString("description").take(600),
                keywords = a.optJSONArray("keywords").strings().take(12),
                senders = a.optJSONArray("senders").strings().take(8),
                oneTime = a.optBoolean("one_time", true),
            )
        }
        val reply = o.optString("reply").ifBlank { if (actions.isEmpty()) "Okay." else "Done." }
        return ChatResult(reply, actions)
    }

    // ------------------------------------------------------------------ matching
    data class Match(val emailIndex: Int, val watchId: Long, val confidence: Double, val reason: String)

    fun matchEmails(s: Settings, watches: List<Watch>, emails: List<Email>): List<Match> {
        val rules = watches.joinToString("\n") {
            "[W${it.id}] ${it.title}\n  Looking for: ${it.description}\n" +
                "  Keyword hints: ${it.keywords.joinToString().ifBlank { "-" }}\n  Likely senders: ${it.senders.joinToString().ifBlank { "-" }}"
        }
        val mails = emails.mapIndexed { i, e ->
            "[E$i] Folder: ${e.folder} | From: ${e.sender} | Subject: ${e.subject}\n  Preview: ${e.preview.replace('\n', ' ').take(700)}"
        }.joinToString("\n")
        val prompt = """
You are an email triage assistant. Decide which NEW emails match any of the person's watch rules.
$PLACEMENT_HINT

WATCH RULES:
$rules

NEW EMAILS:
$mails

How to decide:
- Match only when the email really is what the rule describes. Keyword overlap alone is not enough.
- Ads, newsletters, promotions and job-alert digests that merely mention the company do NOT match unless the rule asks for them.
- A forwarded mail or a placement-cell / recruiting-platform mail about the described event DOES match.
- If unsure but plausible, still report it with a lower confidence (a false alarm is better than a missed email).

Return JSON only:
{"matches": [{"email": "E0", "watch": "W3", "confidence": 0.0, "reason": "max 15 words"}]}
Return {"matches": []} if nothing matches.
""".trimIndent()
        val o = call(s, prompt, temperature = 0.0)
        val ids = watches.map { it.id }.toSet()
        val out = mutableListOf<Match>()
        val arr = o.optJSONArray("matches") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val ei = m.optString("email").trimStart('E', 'e').toIntOrNull() ?: continue
            val wi = m.optString("watch").trimStart('W', 'w').toLongOrNull() ?: continue
            if (ei in emails.indices && wi in ids) {
                out += Match(ei, wi, m.optDouble("confidence", 0.0), m.optString("reason").take(200))
            }
        }
        return out
    }
}
