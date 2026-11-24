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
        val response = readFully(conn)
        if (code !in 200..299) throw StremioHttpException(code, "POST $url")
        return response
    }
}

class StremioHttpException(val status: Int, val context: String) : Exception("$context -> HTTP $status")
