package com.rahul.mailwatch

import org.json.JSONArray
import org.json.JSONObject

data class Account(
    val id: String,
    val provider: String, // "gmail" | "outlook"
    val address: String,
    val secret: String, // Gmail app password, or Outlook refresh token
    val state: String = "{}", // sync position
    val enabled: Boolean = true,
    val lastError: String? = null,
    val lastChecked: Long = 0,
    val errorNotified: Boolean = false,
    val errorSince: Long = 0,
) {
    val label get() = if (provider == "gmail") "Gmail" else "Outlook"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("provider", provider).put("address", address).put("secret", secret)
        .put("state", state).put("enabled", enabled).put("lastError", lastError ?: JSONObject.NULL)
        .put("lastChecked", lastChecked).put("errorNotified", errorNotified).put("errorSince", errorSince)

    companion object {
        fun from(o: JSONObject) = Account(
            id = o.getString("id"),
            provider = o.getString("provider"),
            address = o.getString("address"),
            secret = o.optString("secret"),
            state = o.optString("state", "{}"),
            enabled = o.optBoolean("enabled", true),
            lastError = if (o.isNull("lastError")) null else o.optString("lastError"),
            lastChecked = o.optLong("lastChecked"),
            errorNotified = o.optBoolean("errorNotified"),
            errorSince = o.optLong("errorSince"),
        )
    }
}

data class Watch(
    val id: Long,
    val request: String,
    val title: String,
    val description: String,
    val keywords: List<String>,
    val senders: List<String>,
    val oneTime: Boolean,
    val status: String = "active", // active | paused | done
    val created: Long = System.currentTimeMillis(),
    val matchCount: Int = 0,
    val lastMatched: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("request", request).put("title", title).put("description", description)
        .put("keywords", JSONArray(keywords)).put("senders", JSONArray(senders)).put("oneTime", oneTime)
        .put("status", status).put("created", created).put("matchCount", matchCount).put("lastMatched", lastMatched)

    companion object {
        fun from(o: JSONObject) = Watch(
            id = o.getLong("id"),
            request = o.optString("request"),
            title = o.optString("title"),
            description = o.optString("description"),
            keywords = o.optJSONArray("keywords").strings(),
            senders = o.optJSONArray("senders").strings(),
            oneTime = o.optBoolean("oneTime", true),
            status = o.optString("status", "active"),
            created = o.optLong("created"),
            matchCount = o.optInt("matchCount"),
            lastMatched = o.optLong("lastMatched"),
        )
    }
}

data class Alert(
    val id: Long,
    val watchId: Long,
    val watchTitle: String,
    val account: String,
    val provider: String,
    val msgKey: String,
    val sender: String,
    val subject: String,
    val snippet: String,
    val link: String,
    val reason: String,
    val method: String, // ai | keywords | test
    val created: Long = System.currentTimeMillis(),
    val acked: Boolean = false,
    val round: Int = 0, // how many times it has rung
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("watchId", watchId).put("watchTitle", watchTitle).put("account", account)
        .put("provider", provider).put("msgKey", msgKey).put("sender", sender).put("subject", subject)
        .put("snippet", snippet).put("link", link).put("reason", reason).put("method", method)
        .put("created", created).put("acked", acked).put("round", round)

    companion object {
        fun from(o: JSONObject) = Alert(
            id = o.getLong("id"),
            watchId = o.optLong("watchId"),
            watchTitle = o.optString("watchTitle"),
            account = o.optString("account"),
            provider = o.optString("provider"),
            msgKey = o.optString("msgKey"),
            sender = o.optString("sender"),
            subject = o.optString("subject"),
            snippet = o.optString("snippet"),
            link = o.optString("link"),
            reason = o.optString("reason"),
            method = o.optString("method"),
            created = o.optLong("created"),
            acked = o.optBoolean("acked"),
            round = o.optInt("round"),
        )
    }
}

data class ChatMsg(val id: Long, val fromUser: Boolean, val text: String, val ts: Long = System.currentTimeMillis()) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("fromUser", fromUser).put("text", text).put("ts", ts)

    companion object {
        fun from(o: JSONObject) = ChatMsg(o.getLong("id"), o.optBoolean("fromUser"), o.optString("text"), o.optLong("ts"))
    }
}

data class Settings(
    val geminiKey: String = "",
    val geminiModel: String = "gemini-3.5-flash-lite",
    val fallbackModel: String = "gemini-3.1-flash-lite",
    val msClientId: String = "",
    val msTenant: String = "common",
    val checkMinutes: Int = 5,
    val ringMinutes: Int = 3, // how long one alarm rings
    val gapMinutes: Int = 3, // pause before it rings again
    val rounds: Int = 5, // how many times it rings if you don't stop it
    val threshold: Double = 0.6,
    val onboarded: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("geminiKey", geminiKey).put("geminiModel", geminiModel).put("fallbackModel", fallbackModel)
        .put("msClientId", msClientId).put("msTenant", msTenant).put("checkMinutes", checkMinutes)
        .put("ringMinutes", ringMinutes).put("gapMinutes", gapMinutes).put("rounds", rounds)
        .put("threshold", threshold).put("onboarded", onboarded)

    companion object {
        fun from(o: JSONObject) = Settings(
            geminiKey = o.optString("geminiKey"),
            geminiModel = o.optString("geminiModel").ifBlank { "gemini-3.5-flash-lite" },
            fallbackModel = o.optString("fallbackModel").ifBlank { "gemini-3.1-flash-lite" },
            msClientId = o.optString("msClientId"),
            msTenant = o.optString("msTenant").ifBlank { "common" },
            checkMinutes = o.optInt("checkMinutes", 5),
            ringMinutes = o.optInt("ringMinutes", 3),
            gapMinutes = o.optInt("gapMinutes", 3),
            rounds = o.optInt("rounds", 5),
            threshold = o.optDouble("threshold", 0.6),
            onboarded = o.optBoolean("onboarded"),
        )
    }
}

/** One email as read from Gmail or Outlook. */
data class Email(
    val key: String,
    val provider: String,
    val account: String,
    val folder: String,
    val sender: String,
    val subject: String,
    val date: String,
    val preview: String,
    val link: String,
)

fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
}
