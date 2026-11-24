package com.derricklee.ankidict

import org.json.JSONObject

/**
 * Real-Debrid REST adapter (design §4.5, module M9). READ-ONLY by design: link
 * minting/unrestriction belongs to the media phase (parked doc §C), not the console.
 * Rate posture: 250 req/min global, ETag honored — the console calls /user on sync
 * and /downloads sparingly (S0-D2), never in a hot loop.
 */
class RdClient(
    private val transport: StremioTransport,
    private val base: String = "https://api.real-debrid.com/rest/1.0",
) {

    private fun auth(token: String) = mapOf("Authorization" to "Bearer $token")

    fun user(token: String): RdUser {
        val json = JSONObject(transport.get("$base/user", auth(token)))
        return RdUser(
            username = json.optString("username", ""),
            type = json.optString("type", ""),
            expiration = json.optString("expiration").takeIf { it.isNotEmpty() },
        )
    }

    /** Recent unrestricted/stream history — candidate hints only (D9). */
    fun downloads(token: String, limit: Int = 100): List<RdDownloadEntry> {
        val rows = org.json.JSONArray(transport.get("$base/downloads?limit=$limit", auth(token)))
        val out = ArrayList<RdDownloadEntry>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            out += RdDownloadEntry(
                fileId = row.optString("ID", row.optString("id", "")),
                filename = row.optString("Filename", row.optString("filename", "")),
                filesize = row.optLong("Size", row.optLong("filesize", 0L)),
                type = row.optString("Type", row.optString("type", "")),
                link = row.optString("Link", row.optString("link", "")),
                downloadUrl = row.optString("Download", row.optString("download", "")),
                host = row.optString("Host", row.optString("host", "")),
                submitted = row.optString("submitted").takeIf { it.isNotEmpty() },
                completed = row.optString("completed").takeIf { it.isNotEmpty() },
            )
        }
        return out
    }

    companion object {
        /** Premium validity check from /user.expiration (ISO json date string). */
        fun isPremiumActive(user: RdUser, nowMillis: Long): Boolean {
            val exp = user.expiration ?: return false
            val parsed = runCatching {
                java.time.Instant.parse(exp).toEpochMilli()
            }.recoverCatching {
                java.time.OffsetDateTime.parse(exp).toInstant().toEpochMilli()
            }.getOrNull() ?: return false
            return parsed > nowMillis
        }
    }
}
