package com.derricklee.ankidict

import org.json.JSONArray
import org.json.JSONObject

/**
 * Stremio cloud client (docs/stremio-mining-console-design.md §4).
 *
 * Request bodies pinned LIVE from this account-less box 2026-11-23: api.strem.io
 * expects SINGLE OBJECTS mirroring stremio-core serde (datastoreGet
 * {authKey, collection, all}, addonCollectionGet {authKey, update}); the legacy
 * JSON-array bodies are now REJECTED with "JSON unmarshal err", while object
 * bodies reach auth validation ("Session does not exist" with a fake key).
 * Responses arrive in {"result": ...} / {"error": {code, message}} envelopes.
 * Inner libraryItem field spellings are still unobserved without a real key:
 * row parsers accept camelCase AND snake_case; S0-A's on-device capture pins
 * which is real.
 */
class StremioClient(
    private val transport: StremioTransport,
    private val apiUrl: String = "https://api.strem.io",
    private val linkApiUrl: String = "https://link.stremio.com",
) {

    private fun checkError(response: JSONObject, context: String) {
        response.optJSONObject("error")?.let { err ->
            throw StremioHttpException(
                err.optInt("code", -1),
                "$context: ${err.optString("message")}",
            )
        }
    }

    /**
     * Step 1 of the link flow. LIVE-VERIFIED envelope 2026-11-23:
     * {"result":{"success":true,"code":"XXXX","link":...,"qrcode":...}}.
     * Returns the human-entered code (poll handle) and the pairing link for display.
     */
    fun linkCreate(): Pair<String, String> {
        val body = JSONObject(transport.get("$linkApiUrl/api/v2/create?type=Create"))
        checkError(body, "link create")
        val result = body.optJSONObject("result")
            ?: throw StremioHttpException(-1, "link create: no result envelope")
        val code = result.optString("code").takeIf { it.isNotEmpty() }
            ?: throw StremioHttpException(-1, "link create: no code in result")
        return code to result.optString("link", "")
    }

    /**
     * Step 2: poll with the CODE (query "type=Read&code=..." per LinkRequest serde).
     * Pending/denied arrives as an error envelope or 4xx (observed: code 101)
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
    fun fetchLibrary(
        authKey: String,
        diagnostics: (String) -> Unit = {},
    ): List<StremioLibraryItem> {
        val body = JSONObject()
            .put("authKey", authKey)
            .put("collection", "libraryItem")
            .put("all", true)
        val response = JSONObject(transport.post("$apiUrl/api/datastoreGet", body.toString()))
        checkError(response, "datastoreGet")
        diagnostics("datastoreGet top keys: ${response.keys().asSequence().toList()}")
        val rows = response.optJSONArray("result")
            ?: response.optJSONArray("events")
            ?: JSONArray()
        val out = ArrayList<StremioLibraryItem>()
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
        diagnostics("datastoreGet rows=${rows.length()} parsed=${out.size}")
        return out
    }

    /**
     * The user's installed addons WITH config URLs (torrentio etc. embed keys there).
     * core serde request {authKey, update}; response {result:{addons:[manifest...]}}
     * with legacy top-level addons still tolerated. Manifests carry
     * id/name/version/transportUrl/resources (string names OR {name,types} objects).
     *
     * [diagnostics] receives COUNTS AND KEY NAMES ONLY (never values/config URLs) --
     * surfaced to logcat so live response-shape drift is diagnosable without ever
     * handling the user's authKey outside the app.
     */
    fun fetchAddonCollection(
        authKey: String,
        diagnostics: (String) -> Unit = {},
    ): List<StremioAddon> {
        val body = JSONObject()
            .put("authKey", authKey)
            .put("update", true)
        val response = JSONObject(
            transport.post("$apiUrl/api/addonCollectionGet", body.toString()),
        )
        checkError(response, "addonCollectionGet")
        diagnostics("addonCollection top keys: ${response.keys().asSequence().toList()}")
        val result = response.opt("result")
        val addons = when (result) {
            is JSONObject -> result.optJSONArray("addons") ?: JSONArray()
            is JSONArray -> result // tolerated envelope variant
            else -> response.optJSONArray("addons") ?: JSONArray()
        }
        val out = ArrayList<StremioAddon>()
        var skipped = 0
        for (i in 0 until addons.length()) {
            val manifest = addons.optJSONObject(i) ?: continue
            val id = manifest.optString("id").takeIf { it.isNotEmpty() } ?: continue
            val transportUrl = manifest.optString("transportUrl")
                .takeIf { it.isNotEmpty() }
                ?: manifest.optString("manifestUrl").takeIf { it.isNotEmpty() }
            if (transportUrl == null) { skipped++; continue }
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
        diagnostics("addons rows=${addons.length()} parsed=${out.size} " +
            "no-transportUrl=$skipped ids=${out.map { it.addonId }}")
        return out
    }
}
