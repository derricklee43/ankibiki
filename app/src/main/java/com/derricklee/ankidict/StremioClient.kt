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

    /** Step 1 of the link flow: returns (code to show, key that later authenticates). */
    fun linkCreate(): Pair<String, String> {
        val body = transport.get("$linkApiUrl/api/v2/create?type=Create")
        val json = JSONObject(body)
        val code = json.getString("code")
        val key = json.getString("key")
        return code to key
    }

    /**
     * Step 2: poll until the user approves on any signed-in Stremio client.
     * Returns the authKey when ready, null while pending.
     */
    fun linkPoll(key: String): String? {
        val json = JSONObject(transport.get("$linkApiUrl/api/v2/read?key=$key"))
        // "response" is an object once approved, a plain string ("pending") before
        val response = json.opt("response") as? JSONObject ?: return null
        return response.optString("authKey").takeIf { it.isNotEmpty() }
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
        val rows = response.optJSONArray("events") ?: JSONArray()
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
