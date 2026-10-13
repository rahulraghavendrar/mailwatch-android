package com.rahul.mailwatch

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal blocking HTTP helpers (always call from a background thread). */
object Http {
    class Response(val code: Int, val body: String)

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun request(
        url: String,
        method: String = "GET",
        body: String? = null,
        contentType: String? = null,
        headers: List<Pair<String, String>> = emptyList(),
        timeoutMs: Int = 30_000,
    ): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 20_000
            conn.readTimeout = timeoutMs
            headers.forEach { (k, v) -> conn.addRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Response(code, text)
        } finally {
            conn.disconnect()
        }
    }

    fun postForm(url: String, fields: Map<String, String>): Response =
        request(
            url, "POST",
            body = fields.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" },
            contentType = "application/x-www-form-urlencoded",
        )
}
