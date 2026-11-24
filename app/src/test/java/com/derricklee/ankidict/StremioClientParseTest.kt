package com.derricklee.ankidict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cloud-shape parsing specs against fixtures. NOTE: the JSON bodies here are transcribed
 * from stremio-core's serde models (source-verified 2026-11-23), NOT yet from a live
 * account — S0-A replaces them with captured fixtures; specs must stay green unchanged.
 */
class StremioClientParseTest {

    private class FakeTransport(private val routes: Map<String, String>) : StremioTransport {
        var lastPostUrl: String? = null
        var lastPostBody: String? = null
        var lastGetUrl: String? = null
        override fun get(url: String, headers: Map<String, String>): String {
            lastGetUrl = url
            return routes.entries.first { url.startsWith(it.key) }.value
        }
        override fun post(url: String, body: String, contentType: String,
                          headers: Map<String, String>): String {
            lastPostUrl = url; lastPostBody = body
            return routes.entries.first { url.startsWith(it.key) }.value
        }
    }

    @Test
    fun linkCreateAndPollParsesLiveEnvelope() {
        // shapes captured from the live endpoints 2026-11-23 (curl create/read probes)
        val t = FakeTransport(
            mapOf(
                "https://link.stremio.com/api/v2/create" to
                    """{"result":{"success":true,"code":"1Z1S",
                       "link":"https://link.stremio.com/1Z1S",
                       "qrcode":"https://link.stremio.com/qr?data=x"}}""",
                "https://link.stremio.com/api/v2/read" to
                    """{"result":{"authKey":"AUTHKEY123"}}""",
            ),
        )
        val client = StremioClient(t)
        assertEquals("1Z1S" to "https://link.stremio.com/1Z1S", client.linkCreate())
        assertEquals("AUTHKEY123", client.linkPoll("1Z1S"))
    }

    @Test
    fun linkPollPendingIsErrorEnvelopeOrHttpExceptionNotCrash() {
        // live pending read returns HTTP 4xx with {"error":{"code":101,...}}
        val t = FakeTransport(
            mapOf("https://link.stremio.com/api/v2/read" to
                """{"error":{"code":101,"message":"Invalid or expired token"}}"""),
        )
        assertNull(StremioClient(t).linkPoll("ABCD"))
        val throwing = object : StremioTransport {
            override fun get(url: String, headers: Map<String, String>) =
                throw StremioHttpException(401, "GET $url")
            override fun post(url: String, body: String, contentType: String,
                              headers: Map<String, String>) = "{}"
        }
        assertNull(StremioClient(throwing).linkPoll("ABCD"))
    }

    @Test
    fun linkPollUsesCodeQueryNotKey() {
        val t = FakeTransport(
            mapOf("https://link.stremio.com/api/v2/read" to
                """{"result":{"authKey":"K"}}"""),
        )
        StremioClient(t).linkPoll("WXYZ")
        // LinkRequest serde: internally-tagged enum -> query carries BOTH type and code
        assertEquals(
            "https://link.stremio.com/api/v2/read?type=Read&code=WXYZ",
            t.lastGetUrl,
        )
    }

    @Test
    fun libraryParsesBothFieldSpellingsAndSkipsRemoved() {
        val events = """
            {"events":[
              {"_id":"tt111","type":"series","name":"Show One",
               "state":{"videoId":"tt111:1:2","timeOffset":95000,"duration":1400,"timesWatched":1,"flaggedWatched":0,"watched":"BLOB"}},
              {"_id":"tt222","type":"movie","name":"Movie Two","removed":true,
               "state":{"timeOffset":5}},
              {"_id":"tt333","type":"series","name":"Show Three",
               "state":{"video_id":"tt333:2:1","time_offset":61,"duration":100}}
            ]}
        """.trimIndent()
        val t = FakeTransport(mapOf("https://api.strem.io/api/datastoreGet" to events))
        val items = StremioClient(t).fetchLibrary("auth")
        assertEquals(2, items.size) // removed row skipped
        val first = items.first { it.baseId == "tt111" }
        assertEquals("tt111:1:2", first.state?.videoId)
        assertEquals(95_000L, first.state?.timeOffset)
        assertEquals("BLOB", first.state?.watchedBlob)
        val third = items.first { it.baseId == "tt333" }
        assertEquals("tt333:2:1", third.state?.videoId) // snake_case fallback parsed
        assertEquals(61L, third.state?.timeOffset)
    }

    @Test
    fun addonCollectionParsesObjectAndStringResourceForms() {
        val response = """
            {"addons":[
              {"id":"torrentio","name":"Torrentio","version":"1.0",
               "transportUrl":"https://torrentio.strem.fun/xx/configure/manifest.json",
               "resources":[{"name":"stream","types":["series"]},{"name":"subtitle","types":[]}]},
              {"id":"opensubs","name":"OpenSubtitles V3","version":"0.1",
               "transportUrl":"https://opensubtitles-v3.strem.io/manifest.json",
               "resources":["meta","subtitles"]}
            ]}
        """.trimIndent()
        val t = FakeTransport(mapOf("https://api.strem.io/api/addonCollectionGet" to response))
        val addons = StremioClient(t).fetchAddonCollection("auth")
        assertEquals(2, addons.size)
        val torrentio = addons.first { it.addonId == "torrentio" }
        assertEquals(setOf("stream", "subtitle"), torrentio.resourceTypes)
        val subs = addons.first { it.addonId == "opensubs" }
        assertEquals(setOf("meta", "subtitles"), subs.resourceTypes)
        // addon finder helpers match plural/singular uniformly
        assertEquals("torrentio", addons.firstSubtitleAddon()?.addonId)
        assertEquals("opensubs", addons.firstMetaAddon()?.addonId)
    }
}
