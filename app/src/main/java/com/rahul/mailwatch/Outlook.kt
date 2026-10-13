package com.rahul.mailwatch

import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Outlook / Microsoft 365 through Microsoft Graph (read-only Mail.Read).
 * Sign-in uses the device-code flow: you get a short code, type it at microsoft.com/devicelogin
 * and sign in there. The refresh token is kept in the account's secret and renewed as it's used.
 */
object Outlook {
    private const val GRAPH = "https://graph.microsoft.com/v1.0"
    private const val SCOPES = "offline_access https://graph.microsoft.com/Mail.Read https://graph.microsoft.com/User.Read"
    private const val OVERLAP_MS = 15 * 60_000L
    private val FOLDERS = listOf("inbox" to "Inbox", "junkemail" to "Junk")

    private fun authority(tenant: String) = "https://login.microsoftonline.com/${tenant.ifBlank { "common" }}/oauth2/v2.0"

    data class DeviceCode(val userCode: String, val verificationUri: String, val deviceCode: String, val interval: Int, val expiresAt: Long)
    data class Tokens(val access: String, val refresh: String, val expiresAt: Long)

    private val accessCache = HashMap<String, Tokens>()

    fun startDeviceFlow(clientId: String, tenant: String): DeviceCode {
        if (clientId.isBlank()) throw AuthError("Add your Microsoft app ID first (see the guide).")
        val r = Http.postForm("${authority(tenant)}/devicecode", mapOf("client_id" to clientId, "scope" to SCOPES))
        val o = runCatching { JSONObject(r.body) }.getOrDefault(JSONObject())
        if (r.code != 200) throw AuthError(o.optString("error_description").ifBlank { "Microsoft sign-in failed (${r.code})" }.lineSequence().first())
        return DeviceCode(
            userCode = o.getString("user_code"),
            verificationUri = o.optString("verification_uri", "https://microsoft.com/devicelogin"),
            deviceCode = o.getString("device_code"),
            interval = o.optInt("interval", 5),
            expiresAt = System.currentTimeMillis() + o.optInt("expires_in", 900) * 1000L,
        )
    }

    /** Waits until you finish signing in. Returns (address, refresh token). */
    suspend fun waitForSignIn(clientId: String, tenant: String, dc: DeviceCode): Pair<String, String> {
        var interval = dc.interval.coerceAtLeast(3)
        while (System.currentTimeMillis() < dc.expiresAt) {
            delay(interval * 1000L)
            val r = Http.postForm(
                "${authority(tenant)}/token",
                mapOf(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "client_id" to clientId,
                    "device_code" to dc.deviceCode,
                ),
            )
            val o = runCatching { JSONObject(r.body) }.getOrDefault(JSONObject())
            if (r.code == 200) {
                val t = tokens(o, "")
                val address = me(t.access)
                accessCache[address.lowercase()] = t
                return address to t.refresh
            }
            when (o.optString("error")) {
                "authorization_pending" -> {}
                "slow_down" -> interval += 5
                else -> throw AuthError(o.optString("error_description").ifBlank { "Sign-in failed" }.lineSequence().first())
            }
        }
        throw AuthError("The code expired. Tap Connect Outlook to get a new one.")
    }

    private fun tokens(o: JSONObject, oldRefresh: String) = Tokens(
        access = o.getString("access_token"),
        refresh = o.optString("refresh_token").ifBlank { oldRefresh },
        expiresAt = System.currentTimeMillis() + (o.optInt("expires_in", 3600) - 120) * 1000L,
    )

    private fun me(access: String): String {
        val r = Http.request("$GRAPH/me?\$select=mail,userPrincipalName", headers = listOf("Authorization" to "Bearer $access"))
        val o = runCatching { JSONObject(r.body) }.getOrDefault(JSONObject())
        return o.optString("mail").takeIf { it.isNotBlank() && it != "null" } ?: o.optString("userPrincipalName", "Outlook account")
    }

    /** A valid access token; refreshes (and saves the rotated refresh token) when needed. */
    private fun accessToken(account: Account, s: Settings): String {
        val key = account.address.lowercase()
        accessCache[key]?.let { if (it.expiresAt > System.currentTimeMillis()) return it.access }
        if (s.msClientId.isBlank()) throw AuthError("Microsoft app ID is missing in Settings.")
        val r = Http.postForm(
            "${authority(s.msTenant)}/token",
            mapOf(
                "grant_type" to "refresh_token",
                "client_id" to s.msClientId,
                "refresh_token" to account.secret,
                "scope" to SCOPES,
            ),
        )
        val o = runCatching { JSONObject(r.body) }.getOrDefault(JSONObject())
        if (r.code != 200) {
            if (o.optString("error") == "invalid_grant") throw AuthError("Outlook sign-in expired. Reconnect ${account.address} in Settings.")
            throw Exception("Microsoft sign-in error ${r.code}: ${o.optString("error_description").lineSequence().firstOrNull().orEmpty()}")
        }
        val t = tokens(o, account.secret)
        accessCache[key] = t
        if (t.refresh != account.secret) Store.updateAccount(account.id) { it.copy(secret = t.refresh) }
        return t.access
    }

    private fun iso(t: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(t))
    }

    private fun list(account: Account, s: Settings, since: Long, top: Int): List<Email> {
        val access = accessToken(account, s)
        val out = mutableListOf<Email>()
        for ((folder, label) in FOLDERS) {
            val url = "$GRAPH/me/mailFolders/$folder/messages" +
                "?\$select=" + Http.enc("id,subject,from,body,bodyPreview,receivedDateTime,webLink") +
                "&\$filter=" + Http.enc("receivedDateTime ge ${iso(since)}") +
                "&\$orderby=" + Http.enc("receivedDateTime desc") +
                "&\$top=$top"
            val r = Http.request(
                url,
                headers = listOf(
                    "Authorization" to "Bearer $access",
                    "Prefer" to "outlook.body-content-type=\"text\"",
                    "Prefer" to "IdType=\"ImmutableId\"",
                ),
            )
            if (r.code == 401 || r.code == 403) throw AuthError("Outlook refused access (${r.code}). Reconnect ${account.address}.")
            if (r.code == 404 && folder != "inbox") continue
            if (r.code != 200) throw Exception("Outlook error ${r.code}: ${r.body.take(150)}")
            val arr = JSONObject(r.body).optJSONArray("value") ?: continue
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val from = m.optJSONObject("from")?.optJSONObject("emailAddress")
                val name = from?.optString("name").orEmpty()
                val addr = from?.optString("address").orEmpty()
                val body = m.optJSONObject("body")?.optString("content")?.takeIf { it.isNotBlank() } ?: m.optString("bodyPreview")
                out += Email(
                    key = "outlook:${account.address}:${m.getString("id")}",
                    provider = "outlook",
                    account = account.address,
                    folder = label,
                    sender = if (name.isNotBlank()) "$name <$addr>" else addr,
                    subject = m.optString("subject").ifBlank { "(no subject)" },
                    date = m.optString("receivedDateTime"),
                    preview = Text.clean(body).take(1500),
                    link = m.optString("webLink").ifBlank { "https://outlook.office.com/mail/" },
                )
            }
        }
        return out
    }

    data class FetchResult(val emails: List<Email>, val state: String, val baseline: Boolean)

    /** The first call only marks what's there as seen, so you don't get alarms for old mail. */
    fun fetchNew(account: Account, s: Settings): FetchResult {
        val state = runCatching { JSONObject(account.state) }.getOrDefault(JSONObject())
        val now = System.currentTimeMillis()
        val last = state.optLong("lastPoll", 0)
        val emails = list(account, s, (if (last > 0) last else now) - OVERLAP_MS, 50)
        state.put("lastPoll", now)
        return FetchResult(emails, state.toString(), last == 0L)
    }

    fun fetchRecent(account: Account, s: Settings, days: Int): List<Email> =
        list(account, s, System.currentTimeMillis() - days * 86_400_000L, 30)
}
