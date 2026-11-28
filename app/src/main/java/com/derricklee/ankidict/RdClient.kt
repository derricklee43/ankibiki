package com.derricklee.ankidict

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Real-Debrid REST 1.0 adapter (design §4.5 M9 + Stage 3 Step 1 link minting).
 * Rate posture: 250 req/min global; every link mint runs on-device so the phone
 * pulls bytes from its own IP (docs/stremio-auto-scrub-parked.md §0.4).
 */
class RdClient(
    private val transport: StremioTransport,
    private val base: String = "https://api.real-debrid.com/rest/1.0",
) {

    private fun auth(token: String) = mapOf("Authorization" to "Bearer $token")
    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

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
        val rows = JSONArray(transport.get("$base/downloads?limit=$limit", auth(token)))
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

    fun torrents(token: String, limit: Int = 100): List<RdTorrentSummary> {
        val rows = JSONArray(transport.get("$base/torrents?limit=$limit", auth(token)))
        val out = ArrayList<RdTorrentSummary>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val linksArr = row.optJSONArray("links") ?: JSONArray()
            val links = (0 until linksArr.length()).mapNotNull { linksArr.optString(it).takeIf { s -> s.isNotEmpty() } }
            out += RdTorrentSummary(
                id = row.optString("id", ""),
                filename = row.optString("filename", ""),
                hash = row.optString("hash", "").lowercase(),
                status = row.optString("status", ""),
                links = links,
            )
        }
        return out
    }

    fun torrentInfo(token: String, torrentId: String): RdTorrentInfo {
        val obj = JSONObject(transport.get("$base/torrents/info/$torrentId", auth(token)))
        val filesArr = obj.optJSONArray("files") ?: JSONArray()
        val files = ArrayList<RdTorrentFile>()
        for (i in 0 until filesArr.length()) {
            val f = filesArr.optJSONObject(i) ?: continue
            files += RdTorrentFile(
                id = f.optInt("id"),
                path = f.optString("path", ""),
                bytes = f.optLong("bytes", 0L),
                selected = f.optInt("selected", 0) == 1,
            )
        }
        val linksArr = obj.optJSONArray("links") ?: JSONArray()
        val links = (0 until linksArr.length()).mapNotNull { linksArr.optString(it).takeIf { s -> s.isNotEmpty() } }
        return RdTorrentInfo(
            id = obj.optString("id", torrentId),
            hash = obj.optString("hash", "").lowercase(),
            status = obj.optString("status", ""),
            files = files,
            links = links,
        )
    }

    fun addMagnet(token: String, infoHash: String): String {
        val magnet = "magnet:?xt=urn:btih:${infoHash.lowercase()}"
        val body = "magnet=${enc(magnet)}"
        val resp = JSONObject(
            transport.post("$base/torrents/addMagnet", body, FORM_URLENCODED, auth(token)),
        )
        return resp.optString("id").takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("RD addMagnet returned no torrent id")
    }

    fun selectFiles(token: String, torrentId: String, fileIdsCsv: String) {
        transport.post(
            "$base/torrents/selectFiles/$torrentId",
            "files=${enc(fileIdsCsv)}",
            FORM_URLENCODED,
            auth(token),
        )
    }

    fun deleteTorrent(token: String, torrentId: String) {
        transport.delete("$base/torrents/delete/$torrentId", auth(token))
    }

    fun unrestrictLink(token: String, hosterLink: String): RdUnrestrictedLink {
        val resp = JSONObject(
            transport.post("$base/unrestrict/link", "link=${enc(hosterLink)}", FORM_URLENCODED, auth(token)),
        )
        val download = resp.optString("download").takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("RD unrestrict returned no download URL")
        return RdUnrestrictedLink(
            id = resp.optString("id", ""),
            filename = resp.optString("filename", ""),
            mimeType = resp.optString("mimeType").takeIf { it.isNotEmpty() },
            filesize = resp.optLong("filesize", 0L),
            downloadUrl = download,
        )
    }

    /**
     * Mints a fresh direct HTTPS download URL for `(infoHash, fileIdx)` under the
     * device's own IP (Stage 3 Step 1). Reuses an already-downloaded torrent in `/torrents`
     * when present; otherwise adds magnet, selects target file, verifies instant-cache
     * (`status == "downloaded"` — deleting any uncached add so the user's cloud queue
     * stays clean), and unrestricts the hoster link.
     */
    fun mintDirectStreamUrl(
        token: String,
        infoHash: String,
        fileIdx: Int?,
        pollDelayMs: (Long) -> Unit = { Thread.sleep(it) },
    ): RdUnrestrictedLink {
        val normHash = infoHash.lowercase()
        val existing = torrents(token).firstOrNull {
            it.hash == normHash && it.status == "downloaded" && it.links.isNotEmpty()
        }
        val hosterLink = if (existing != null) {
            val info = torrentInfo(token, existing.id)
            pickHosterLink(info, fileIdx)
                ?: existing.links.first()
        } else {
            val addedId = addMagnet(token, normHash)
            val initialInfo = torrentInfo(token, addedId)
            val targetId = pickTargetFileId(initialInfo.files, fileIdx)
            selectFiles(token, addedId, targetId?.toString() ?: "all")
            var ready = torrentInfo(token, addedId)
            var attempts = 0
            while (ready.status != "downloaded" && attempts < 2) {
                pollDelayMs(500L)
                ready = torrentInfo(token, addedId)
                attempts++
            }
            if (ready.status != "downloaded" || ready.links.isEmpty()) {
                runCatching { deleteTorrent(token, addedId) }
                throw IllegalStateException("RD torrent not instant-cached (status=${ready.status})")
            }
            pickHosterLink(ready, fileIdx) ?: ready.links.first()
        }
        return unrestrictLink(token, hosterLink)
    }

    companion object {
        private const val FORM_URLENCODED = "application/x-www-form-urlencoded"
        private val VIDEO_EXTS = setOf("mkv", "mp4", "m4v", "webm", "avi", "ts", "m2ts")

        /** Maps 0-based Stremio `fileIdx` to RD 1-based `file.id`, falling back to largest video file. */
        fun pickTargetFileId(files: List<RdTorrentFile>, fileIdx: Int?): Int? {
            if (files.isEmpty()) return null
            if (fileIdx != null) {
                val expectedRdId = fileIdx + 1
                files.firstOrNull { it.id == expectedRdId }?.let { return it.id }
            }
            return files.filter { f ->
                val ext = f.path.substringAfterLast('.', "").lowercase()
                ext in VIDEO_EXTS
            }.maxByOrNull { it.bytes }?.id ?: files.maxByOrNull { it.bytes }?.id
        }

        /** Maps `fileIdx` into RD's `links[]` (which aligns 1:1 with `files.filter { it.selected }`). */
        fun pickHosterLink(info: RdTorrentInfo, fileIdx: Int?): String? {
            if (info.links.isEmpty()) return null
            if (info.links.size == 1) return info.links.first()
            val selected = info.files.filter { it.selected }.sortedBy { it.id }
            if (selected.size == info.links.size && fileIdx != null) {
                val expectedRdId = fileIdx + 1
                val pos = selected.indexOfFirst { it.id == expectedRdId }
                if (pos in info.links.indices) return info.links[pos]
            }
            // Fallback: pick largest selected video file if aligned, else first link.
            if (selected.size == info.links.size) {
                val largestPos = selected.withIndex()
                    .filter { it.value.path.substringAfterLast('.', "").lowercase() in VIDEO_EXTS }
                    .maxByOrNull { it.value.bytes }?.index
                if (largestPos != null && largestPos in info.links.indices) return info.links[largestPos]
            }
            return info.links.first()
        }

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
