package com.derricklee.ankidict

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal injectable HTTP transport for the Stremio console adapters.
 * Same raw HttpURLConnection style as CloudVisionTextExtractor (repo carries zero new
 * runtime dependencies by choice). JVM specs swap in a fake; nothing above this layer
 * touches the network directly.
 */
interface StremioTransport {
    fun get(url: String, headers: Map<String, String> = emptyMap()): String
    fun post(url: String, body: String, contentType: String = "application/json",
             headers: Map<String, String> = emptyMap()): String
    fun delete(url: String, headers: Map<String, String> = emptyMap()): String =
        throw UnsupportedOperationException("DELETE not implemented on ${javaClass.simpleName}")
    /** Follows HTTP 3xx redirects without downloading the response body (GET Range: bytes=0-0). */
    fun resolveRedirectUrl(url: String, headers: Map<String, String> = emptyMap()): String = url
}

class UrlStremioTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : StremioTransport {

    private fun open(url: String, method: String, headers: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AnkiBiki/1.0")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

    private fun readFully(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            ?: throw StremioHttpException(conn.responseCode, "empty response for ${conn.url}")
        return stream.use { input ->
            val out = ByteArrayOutputStream()
            input.copyTo(out)
            out.toString("UTF-8")
        }
    }

    override fun get(url: String, headers: Map<String, String>): String {
        val conn = open(url, "GET", headers)
        val code = conn.responseCode
        val body = readFully(conn)
        if (code !in 200..299) throw StremioHttpException(code, "GET $url")
        return body
    }

    override fun post(url: String, body: String, contentType: String, headers: Map<String, String>): String {
        val conn = open(url, "POST", headers + ("Content-Type" to contentType))
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code == 204) return ""
        val response = readFully(conn)
        if (code !in 200..299) throw StremioHttpException(code, "POST $url")
        return response
    }

    override fun delete(url: String, headers: Map<String, String>): String {
        val conn = open(url, "DELETE", headers)
        val code = conn.responseCode
        if (code == 204) return ""
        val response = readFully(conn)
        if (code !in 200..299) throw StremioHttpException(code, "DELETE $url")
        return response
    }

    override fun resolveRedirectUrl(url: String, headers: Map<String, String>): String {
        var current = url
        repeat(6) {
            val conn = open(current, "GET", headers + ("Range" to "bytes=0-0")).apply {
                instanceFollowRedirects = false
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                        ?: throw StremioHttpException(code, "missing Location on redirect from $current")
                    current = URL(URL(current), loc).toString()
                } else if (code in 200..299) {
                    return current
                } else {
                    throw StremioHttpException(code, "RESOLVE $current")
                }
            } finally {
                conn.disconnect()
            }
        }
        return current
    }
}

class StremioHttpException(val status: Int, val context: String) : Exception("$context -> HTTP $status")
