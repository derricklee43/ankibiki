package com.derricklee.ankidict

import java.net.URLEncoder
import org.json.JSONArray

/**
 * Builtin Jimaku subtitle source (spikes/007 §16 verdict; ruling D4a: joins the
 * mining candidate loop as the rung AFTER the synced Stremio addons).
 *
 * Jimaku is a Japanese-subtitle archive with an official free-key REST API.
 * Live-verified response shapes (probe 2026-11-23, spikes/007 README §1/§16 —
 * pinned by JVM fixtures, not guessed):
 * - GET /api/entries/search?query=<name>  -> top-level JSON ARRAY of entries
 *   (fields used: id, name). 401 body {"error":"unauthorized","code":7} w/o key.
 * - GET /api/entries/{id}/files?episode=E -> top-level JSON ARRAY of FileEntry
 *   {url, name, size, last_modified}. `episode=` demonstrably extracts the single
 *   episode out of season-pack entries (prediction #4 CONFIRMED).
 *
 * Constraints the probe forced into this class (each cited in spikes/007 §16):
 * - C1: tmdb_id search is DEAD for anime (0/4 hits; entries are AniList-backed).
 *   Resolution is query-based and hits are VALIDATED, never taken blindly.
 * - C3: file lists are NOT format-safe (a 2.9 MB image-sub .zip led Sakamoto's
 *   list) — only .ass/.srt/.vtt rows are offered.
 * - D3a: per-show entry cache (prefs-backed; prefs are outside every bundle
 *   contract by the dated StremioPrefs rule) so mining sessions never re-search:
 *   a cached entry self-heals on an empty/invalid episode result.
 * - Etiquette: docs ask a descriptive User-Agent (UrlStremioTransport sends
 *   "AnkiBiki/1.0"); volume is structurally capped by the serial pipeline lane.
 *
 * Validation ladder per search hit (max 3 tried):
 *   1. entry name must share the show's leading title tokens (cheap gate);
 *   2. its files for THIS episode must contain >=1 text-format row whose name
 *      parses (via the shipped, bugfixed parseEpisodeFromFilename) to the
 *      requested episode number — OR, when no name parses (Japanese-only
 *      filenames), the entry name gate alone accepts (C2 territory).
 * First validated hit wins and is cached; 401/auth errors propagate as
 * StremioHttpException so the engine's tried-list tail carries the status.
 */
class JimakuClient(
    private val transport: StremioTransport,
    private val apiKey: () -> String?,
    private val entryCache: JimakuEntryCache? = null,
    private val apiBase: String = "https://jimaku.cc",
) {

    interface JimakuEntryCache {
        fun getEntryId(baseId: String): Long?
        fun putEntryId(baseId: String, entryId: Long)
        fun clearEntryId(baseId: String)
    }

    class Failure(reason: String) : Exception(reason)

    data class JmFile(val url: String, val name: String, val size: Long)

    companion object {
        const val ADDON_ID = "builtin.jimaku"
        private val TEXT_EXT = listOf(".ass", ".srt", ".vtt")

        /** leading title tokens (<=2, lowercased, "the"-dropped) must all appear */
        fun nameSharesTitle(showName: String?, entryName: String): Boolean {
            val tokens = showName?.lowercase()?.split(Regex("[^a-z0-9']+"))
                ?.filter { it.isNotEmpty() && it != "the" }
                ?: return true // no show name known: gate cannot run
            if (tokens.isEmpty()) return true
            val lower = entryName.lowercase()
            return tokens.take(2).all { lower.contains(it) }
        }

        fun isTextFile(name: String): Boolean {
            val clean = name.substringBefore('?')
            return TEXT_EXT.any { clean.lowercase().endsWith(it) }
        }

        fun parseFiles(rows: JSONArray): List<JmFile> {
            val out = ArrayList<JmFile>()
            for (i in 0 until rows.length()) {
                val o = rows.optJSONObject(i) ?: continue
                val url = o.optString("url")
                if (url.isEmpty()) continue
                out += JmFile(url, o.optString("name", url.substringAfterLast('/')),
                    o.optLong("size", 0L))
            }
            return out
        }

        /** validated iff: has text files AND (episode parses to E on some row OR
         *  no row parses but the entry title gate held) — movie shape (E=null):
         *  any text file. */
        fun episodeValidated(files: List<JmFile>, ep: Int?, entryName: String, showName: String?): Boolean {
            val text = files.filter { isTextFile(it.name) }
            if (text.isEmpty()) return false
            if (ep == null) return true
            val parsed = text.mapNotNull { AddonResources.parseEpisodeFromFilename(it.name) }
            if (parsed.any { it.episode == ep }) return true
            if (parsed.isEmpty()) return nameSharesTitle(showName, entryName)
            return false
        }
    }

    /**
     * Returns choices (possibly empty). Throws [Failure] for human-actionable
     * reasons (no key, no show name, nothing validated) and StremioHttpException
     * for transport/auth status — the engine renders either into its tried-list.
     */
    fun subtitlesFor(
        showName: String?,
        metaType: String,
        videoId: String,
        lang: String,
    ): List<SubtitleChoice> {
        val l = lang.lowercase()
        if (!(l.startsWith("jpn") || l.startsWith("ja"))) return emptyList() // not applicable, not a failure
        val key = apiKey() ?: throw Failure("jimaku API key not set (console: Jimaku button)")
        if (showName.isNullOrBlank()) throw Failure("show name unknown for $videoId")
        val authHeaders = mapOf("Authorization" to key)
        val ref = AddonResources.regexEpisodeRef(videoId)
        val ep = if (metaType == "movie") null else ref?.episode
        // strip ONLY the trailing :season:episode numeric tails (regexEpisodeRef's
        // own contract); substringBeforeLast would strand the season segment.
        val baseId = videoId.replace(Regex("(:\\d+)+$"), "")

        // rung 1: cached entry (D3a) — files only; self-heal on invalidation
        val cached = entryCache?.getEntryId(baseId)
        if (cached != null) {
            val files = filesOf(cached, ep, authHeaders)
            if (episodeValidated(files, ep, entryName = showName, showName = showName)) {
                return choices(files)
            }
            entryCache.clearEntryId(baseId) // stale/wrong cache: fall through to a fresh validated search
        }

        // rung 2: fuzzy query + validated ladder (C1 — never js[0] blindly)
        val query = URLEncoder.encode(showName, "UTF-8")
        val hits = JSONArray(transport.get("$apiBase/api/entries/search?query=$query", authHeaders))
        var lastReason = "search returned 0 entries"
        var triedHits = 0
        for (i in 0 until hits.length()) {
            if (triedHits >= 3) break
            val hit = hits.optJSONObject(i) ?: continue
            val id = hit.optLong("id", -1L).takeIf { it > 0 } ?: continue
            val name = hit.optString("name", hit.optString("title", ""))
            if (!nameSharesTitle(showName, name)) { lastReason = "no title-token overlap"; continue }
            triedHits++
            val files = filesOf(id, ep, authHeaders)
            if (episodeValidated(files, ep, name, showName)) {
                entryCache?.putEntryId(baseId, id)
                return choices(files)
            }
            lastReason = if (files.isEmpty()) "entry $name: 0 files for episode ${ep ?: "movie"}"
                else "entry $name: files did not validate episode ${ep ?: ""}"
        }
        throw Failure(lastReason)
    }

    private fun filesOf(entryId: Long, ep: Int?, authHeaders: Map<String, String>): List<JmFile> {
        val url = if (ep == null) "$apiBase/api/entries/$entryId/files"
            else "$apiBase/api/entries/$entryId/files?episode=$ep"
        return parseFiles(JSONArray(transport.get(url, authHeaders)))
    }

    private fun choices(files: List<JmFile>): List<SubtitleChoice> =
        files.filter { isTextFile(it.name) }.map {
            SubtitleChoice(
                subId = it.name,
                lang = "jpn",
                releaseName = it.name,
                fileUrl = it.url,
                addonId = ADDON_ID,
            )
        }
}

/** prefs-backed JimakuEntryCache; baseId maps to the Jimaku entry id it validated
 *  against. Prefs sit outside the bundle contract (StremioPrefs header rule). */
class PrefsJimakuEntryCache(private val prefs: StremioPrefs) : JimakuClient.JimakuEntryCache {
    override fun getEntryId(baseId: String): Long? = prefs.jimakuEntryId(baseId)
    override fun putEntryId(baseId: String, entryId: Long) = prefs.setJimakuEntryId(baseId, entryId)
    override fun clearEntryId(baseId: String) = prefs.clearJimakuEntryId(baseId)
}
