package com.derricklee.ankidict

import org.json.JSONArray
import org.json.JSONObject

/**
 * Stremio cloud client (docs/stremio-mining-console-design.md §4). Endpoints and field
 * names verified against stremio-core src/types/api/request.rs + constants.rs
 * (API_URL=https://api.strem.io, collection name "libraryItem", addonCollectionGet)
 * on 2026-11-23. The cloud API is unofficial: every parser must be exercised against
 * fixtures and fail loudly on shape drift (S0-A live pin pending).
 */
class StremioClient(
    private val transport: StremioTransport,
    private val apiUrl: String = "https://api.strem.io",
    private val linkApiUrl: String = "https://link.stremio.com",
) {

    /**
     * Step 1 of the link flow. LIVE-VERIFIED envelope 2026-11-23:
     * {"result":{"success":true,"code":"XXXX","link":...,"qrcode":...}}.
     * Returns the human-entered code (poll handle) and the pairing link for display.
     */
    fun linkCreate(): Pair<String, String> {
        val body = JSONObject(transport.get("$linkApiUrl/api/v2/create?type=Create"))
        val result = body.optJSONObject("result")
            ?: throw StremioHttpException(-1, "link create: no result envelope: $body")
        val code = result.optString("code").takeIf { it.isNotEmpty() }
            ?: throw StremioHttpException(-1, "link create: no code in result")
        return code to result.optString("link", "")
    }

    /**
     * Step 2: poll with the CODE (query "type=Read&code=..." per LinkRequest serde).
     * Pending/denied arrives as an error envelope or 4xx (observed: error code 101)
     * -- both read as "keep waiting"; only {"result":{"authKey":...}} resolves.
     */
    fun linkPoll(code: String): String? = try {
        JSONObject(transport.get("$linkApiUrl/api/v2/read?type=Read&code=$code"))
            .optJSONObject("result")
            ?.optString("authKey")
            ?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null // not approved yet (or transient); the caller's loop keeps polling
    }

    /**
     * Full library (shows/movies/live). Rows missing _id/name are skipped, never fatal:
     * the console lists what it can parse and S0-A records the diff.
     */
    fun fetchLibrary(authKey: String): List<StremioLibraryItem> {
        val request = JSONArray().apply {
            put(JSONObject()
                .put("type", "datastore")
                .put("auth", authKey)
                .put("body", JSONObject()
                    .put("api_version", "1.1")
                    .put("type", "libraryItem")
                    .put("auth", authKey)))
        }
        val response = JSONObject(
            transport.post("$apiUrl/api/datastoreGet", request.toString()),
        )
        val out = ArrayList<StremioLibraryItem>()
        // v1 datastoreGet answers {"events":[...]}; tolerate a bare "result" array too
        // (which spelling is real is part of what S0-A's live capture pins).
        val rows = response.optJSONArray("events")
            ?: response.optJSONArray("result") ?: JSONArray()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val baseId = row.optString("_id").takeIf { it.isNotEmpty() } ?: continue
            if (row.optBoolean("removed", false)) continue
            val state = row.optJSONObject("state")
            out += StremioLibraryItem(
                baseId = baseId,
                type = row.optString("type", "series"),
                name = row.optString("name", baseId),
                poster = row.optString("poster").takeIf { it.isNotEmpty() },
                state = state?.let { s ->
                    StremioItemState(
                        // serde camelCase says "videoId"; some clients write "video_id".
                        // S0-A pins the authoritative spelling for each device; parse both.
                        videoId = s.optString("videoId").takeIf { it.isNotEmpty() }
                            ?: s.optString("video_id").takeIf { it.isNotEmpty() },
                        timeOffset = s.optLong("timeOffset", s.optLong("time_offset", 0L)),
                        duration = s.optLong("duration", 0L),
                        lastWatched = s.optString("lastWatched").takeIf { it.isNotEmpty() },
                        timesWatched = s.optInt("timesWatched", 0),
                        flaggedWatched = s.optInt("flaggedWatched", 0),
                        watchedBlob = s.optString("watched").takeIf { it.isNotEmpty() },
                    )
                },
            )
        }
        return out
    }

    /**
     * The user's installed addons WITH config URLs (torrentio etc. embed keys there).
     * Response shape per stremio-core AddonCollectionGet: { addons: [manifest...] } where
     * manifests carry id/name/version/transportUrl/resources[{name,types}].
     */
    fun fetchAddonCollection(authKey: String): List<StremioAddon> {
        val request = JSONArray().apply {
            put(JSONObject()
                .put("type", "addons")
                .put("auth", authKey)
                .put("body", JSONObject()
                    .put("api_version", "1.1")
                    .put("auth", authKey)
                    .put("metaTemplate", "")))
        }
        val response = JSONObject(
            transport.post("$apiUrl/api/addonCollectionGet", request.toString()),
        )
        val out = ArrayList<StremioAddon>()
        val addons = response.optJSONArray("addons") ?: JSONArray()
        for (i in 0 until addons.length()) {
            val manifest = addons.optJSONObject(i) ?: continue
            val id = manifest.optString("id").takeIf { it.isNotEmpty() } ?: continue
            val transportUrl = manifest.optString("transportUrl")
                .takeIf { it.isNotEmpty() } ?: continue
            val resources = HashSet<String>()
            manifest.optJSONArray("resources").let { arr ->
                if (arr != null) for (r in 0 until arr.length()) {
                    val res = arr.opt(r)
                    when (res) {
                        is String -> resources += res.lowercase()
                        is JSONObject -> {
                            // only the resource NAME enters resourceTypes; the "types"
                            // array lists media types (series/movie), not resources
                            res.optString("name").takeIf { it.isNotEmpty() }
                                ?.let { resources += it.lowercase() }
                        }
                    }
                }
            }
            out += StremioAddon(
                addonId = id,
                name = manifest.optString("name", id),
                version = manifest.optString("version", ""),
                transportUrl = transportUrl,
                resourceTypes = resources,
            )
        }
        return out
    }
}
