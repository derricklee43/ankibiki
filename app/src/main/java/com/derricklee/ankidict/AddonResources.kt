package com.derricklee.ankidict

import org.json.JSONArray
import org.json.JSONObject

/**
 * Stremio addon resource calls over plain HTTP (design §4 D): /meta, /subtitles, /stream.
 * URL templates per the addon protocol; transportUrl comes from the user's own
 * addonCollectionGet, so their configured subtitle/stream addons (RD key included) are
 * replayed as-is — no hardcoding, and the phone mints links under its own IP.
 */
class AddonResources(private val transport: StremioTransport) {

    private fun resourcePath(addon: StremioAddon, resource: String, type: String, id: String): String {
        val base = addon.transportUrl.trimEnd('/', '.')
        return "$base/$resource/$type/$id.json"
    }

    /** videos[] from a meta addon for the series/movie; season/episode parsed from ids
     *  ("<imdb>:<season>:<episode>") with title fallback. */
    fun metaEpisodes(metaAddon: StremioAddon, type: String, baseId: String): List<MetaEpisode> {
        val root = JSONObject(transport.get(resourcePath(metaAddon, "meta", type, baseId)))
        val meta = root.optJSONObject("meta") ?: return emptyList()
        val videos = meta.optJSONArray("videos") ?: JSONArray()
        val out = ArrayList<MetaEpisode>()
        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            val id = v.optString("id").takeIf { it.isNotEmpty() } ?: continue
            val se = regexEpisodeRef(id)
            out += MetaEpisode(
                episodeId = id,
                title = v.optString("name", id),
                season = se?.season ?: v.optInt("season", -1).takeIf { it >= 0 },
                episode = se?.episode ?: v.optInt("episode", -1).takeIf { it >= 0 },
                released = v.optString("released").takeIf { it.isNotEmpty() },
            )
        }
        return out
    }

    /** subtitle list filtered by ISO language prefix (e.g. "jpn"/"eng"). */
    fun subtitles(subtitleAddon: StremioAddon, type: String, videoId: String, lang: String): List<SubtitleChoice> {
        val root = JSONObject(transport.get(resourcePath(subtitleAddon, "subtitles", type, videoId)))
        val rows = root.optJSONArray("subtitles") ?: JSONArray()
        val out = ArrayList<SubtitleChoice>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val url = row.optString("url").takeIf { it.isNotEmpty() } ?: continue
            val langCode = row.optString("lang", "").lowercase()
            if (lang.isNotEmpty() && langCode.isNotEmpty() && !langCode.startsWith(lang.lowercase())) continue
            out += SubtitleChoice(
                subId = row.optString("id", ""),
                lang = langCode,
                releaseName = row.optString("filename", row.optString("lang", "subtitle")),
                fileUrl = url,
                addonId = subtitleAddon.addonId,
            )
        }
        return out
    }

    /** raw subtitle file body (text). Downloaded once per run; hashed for identity (D8). */
    fun fetchSubtitleText(url: String): String = transport.get(url)

    /**
     * stream candidates for an episode; infoHash+fileIdx are §5.5 file identity when
     * present (addon protocol stream fields). externalUrl/magnet-less rows land with nulls.
     */
    fun streams(streamAddon: StremioAddon, type: String, videoId: String): List<StreamCandidate> {
        val root = JSONObject(transport.get(resourcePath(streamAddon, "stream", type, videoId)))
        val rows = root.optJSONArray("streams") ?: JSONArray()
        val out = ArrayList<StreamCandidate>()
        for (i in 0 until rows.length()) {
            val s = rows.optJSONObject(i) ?: continue
            val infoHash = s.optString("infoHash").takeIf { it.isNotEmpty() }?.lowercase()
            val fileIdx = if (s.has("fileIdx") && !s.isNull("fileIdx")) s.optInt("fileIdx") else null
            out += StreamCandidate(
                name = s.optString("name", ""),
                url = s.optString("url").takeIf { it.isNotEmpty() },
                infoHash = infoHash,
                fileIdx = fileIdx,
            )
        }
        return out
    }

    companion object {
        /** "<imdb>:<season>:<episode>" (Stremio video ids) -> S/E. */
        fun regexEpisodeRef(id: String): ParsedEpisodeRef? {
            val parts = id.split(":")
            if (parts.size >= 3) {
                val s = parts[parts.size - 2].toIntOrNull()
                val e = parts[parts.size - 1].toIntOrNull()
                if (s != null && e != null) return ParsedEpisodeRef(s, e)
            }
            return null
        }

        /**
         * S0-D2 filename heuristic for RD download-history candidates. PRE-CHECK HINT ONLY —
         * never authority (D9). Handles "S01E04", "1x04", bare "Episode 4", anime "[grp] Show
         * - 04 (1080p)". Returns null when nothing confident.
         */
        fun parseEpisodeFromFilename(filename: String): ParsedEpisodeRef? {
            Regex("(?i)[\\s._\\-\\[]S(\\d{1,2})\\s*[-_#]?\\s*E(\\d{1,3})").find(filename)?.let {
                return ParsedEpisodeRef(it.groupValues[1].toInt(), it.groupValues[2].toInt())
            }
            Regex("(?i)(\\d{1,2})x(\\d{1,3})").find(filename)?.let {
                return ParsedEpisodeRef(it.groupValues[1].toInt(), it.groupValues[2].toInt())
            }
            Regex("(?i)Episode[\\s._\\-#]?(\\d{1,3})").find(filename)?.let {
                return ParsedEpisodeRef(1, it.groupValues[1].toInt())
            }
            Regex("(?i)[-\\s] ?(\\d{2,3})(?:[\\s._\\-]|\\()").find(filename)?.let {
                return ParsedEpisodeRef(1, it.groupValues[1].toInt())
            }
            return null
        }
    }
}
