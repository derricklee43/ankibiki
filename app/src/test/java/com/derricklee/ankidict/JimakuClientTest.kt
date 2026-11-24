package com.derricklee.ankidict

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Jimaku builtin source contract (spikes/007 §16 verdict → production rules).
 * Fixtures are REAL captured shapes from the 2026-11-23 probe (top-level arrays,
 * FileEntry fields, actual release filenames incl. the Sakamoto zip row), not
 * invented ones — see spikes/007-direct-subtitle-sources/README.md §1 and §16.
 */
class JimakuClientTest {

    private class RecordingTransport(private val routes: (String) -> String?) : StremioTransport {
        val calls = ArrayList<Pair<String, Map<String, String>>>()
        override fun get(url: String, headers: Map<String, String>): String {
            calls += url to headers
            return routes(url) ?: throw StremioHttpException(404, "GET $url unmapped")
        }
        override fun post(url: String, body: String, contentType: String,
                          headers: Map<String, String>): String = "{}"
    }

    private class MapCache : JimakuClient.JimakuEntryCache {
        val map = HashMap<String, Long>()
        var clears = 0
        override fun getEntryId(baseId: String) = map[baseId]
        override fun putEntryId(baseId: String, entryId: Long) { map[baseId] = entryId }
        override fun clearEntryId(baseId: String) { map.remove(baseId); clears++ }
    }

    // ---- live-captured shapes (2026-11-23) ----

    private val gachiSearch =
        """[{"id":9898,"name":"Gachiakuta"},{"id":5001,"name":"Gachiakuta Movie Special"}]"""

    private val gachiFilesE4 =
        """[{"url":"https://jimaku.cc/entry/9898/download/nanako.ass","name":"[NanakoRaws] Gachiakuta - 04 (TBS 1920x1080 x265 AAC).ass","size":52854,"last_modified":"2025-08-08T05:00:00Z"},
{"url":"https://jimaku.cc/entry/9898/download/nanako.srt","name":"[NanakoRaws] Gachiakuta - 04 (TBS 1920x1080 x265 AAC).srt","size":23876,"last_modified":"2025-08-08T05:00:00Z"},
{"url":"https://jimaku.cc/entry/9898/download/netflix.srt","name":"[Netflix Retimed] ガチアクタ.S01E04.掃除屋本部.srt","size":24036,"last_modified":"2025-08-08T05:00:00Z"}]"""

    // the Sakamoto E01 list that motivated constraint C3: zip row leads the list
    private val sakamotoFilesE1 =
        """[{"url":"https://jimaku.cc/entry/8450/download/images.zip","name":"SAKAMOTO.DAYS.S01E01.伝説の殺し屋.WEBRip.Netflix (Images).zip","size":2916285,"last_modified":"2025-01-11T00:00:00Z"},
{"url":"https://jimaku.cc/entry/8450/download/cc.srt","name":"SAKAMOTO.DAYS.S01E01.伝説の殺し屋.WEBRip.Netflix.ja[cc].srt","size":23581,"last_modified":"2025-01-11T00:00:00Z"}]"""

    private fun client(routes: (String) -> String?, key: String? = "K",
                       cache: MapCache? = null): Pair<JimakuClient, RecordingTransport> {
        val t = RecordingTransport(routes)
        return JimakuClient(t, { key }, cache) to t
    }

    @Test
    fun happyPath_validatesQueryHit_filtersZip_andSendsAuth() {
        val cache = MapCache()
        val (c, t) = client({ url -> when {
            url.contains("/entries/search?query=Gachiakuta") -> gachiSearch
            url.contains("/entries/9898/files?episode=4") -> gachiFilesE4
            else -> null
        } }, cache = cache)
        val choices = c.subtitlesFor("Gachiakuta", "series", "tt32612521:1:4", "jpn")
        assertEquals(3, choices.size)
        assertTrue("no zip rows offered (C3)", choices.none { it.fileUrl.endsWith(".zip") })
        assertTrue(choices.all { it.lang == "jpn" && it.addonId == JimakuClient.ADDON_ID })
        assertEquals("validated entry cached", 9898L, cache.map["tt32612521"])
        // headers on EVERY jimaku call; UA handled at UrlStremioTransport level
        assertTrue(t.calls.all { it.second["Authorization"] == "K" })
        // C1 discipline: the dead tmdb_id tier must not appear at all
        assertTrue("no tmdb_id tier", t.calls.none { it.first.contains("tmdb_id") })
    }

    @Test
    fun titleGateSkipsUnrelatedHits_withoutFilesCall() {
        val (c, t) = client({ url -> when {
            url.contains("/entries/search?query=Gachiakuta") ->
                """[{"id":777,"name":"Zankyou no Terror"},{"id":9898,"name":"Gachiakuta"}]"""
            url.contains("/entries/9898/files?episode=4") -> gachiFilesE4
            else -> null
        } })
        val choices = c.subtitlesFor("Gachiakuta", "series", "tt32612521:1:4", "jpn")
        assertEquals(3, choices.size)
        assertTrue("hit 777 skipped before files", t.calls.none { it.first.contains("/entries/777/") })
    }

    @Test
    fun episodeValidation_ladderRejectsWrongEpisodeHit() {
        val cache = MapCache()
        val (c, t) = client({ url -> when {
            url.contains("/entries/search") ->
                """[{"id":1234,"name":"Gachiakuta Fan Compilation"},{"id":9898,"name":"Gachiakuta"}]"""
            url.contains("/entries/1234/files?episode=4") ->
                """[{"url":"https://jimaku.cc/entry/1234/x.srt","name":"[GRP] Gachiakuta - 11 (720p).srt","size":1,"last_modified":"2025-01-01T00:00:00Z"}]"""
            url.contains("/entries/9898/files?episode=4") -> gachiFilesE4
            else -> null
        } }, cache = cache)
        val choices = c.subtitlesFor("Gachiakuta", "series", "tt32612521:1:4", "jpn")
        assertEquals(3, choices.size)
        assertEquals("validated entry cached, not the wrong one", 9898L, cache.map["tt32612521"])
        // the wrong hit (files parse to episode 11) was tried, rejected, ladder advanced
        assertEquals(listOf("search", "1234", "9898"), t.calls.map {
            when {
                it.first.contains("search") -> "search"
                it.first.contains("/1234/") -> "1234"
                else -> "9898"
            } })
    }

    @Test
    fun cacheHit_skipsSearchEntirely() {
        val cache = MapCache().apply { map["tt32612521"] = 9898 }
        val (c, t) = client({ url -> when {
            url.contains("/entries/9898/files?episode=9") ->
                """[{"url":"https://jimaku.cc/entry/9898/e9.ass","name":"[NanakoRaws] Gachiakuta - 09 (TBS 1920x1080 x265 AAC).ass","size":48213,"last_modified":"2025-09-05T00:00:00Z"}]"""
            else -> null
        } }, cache = cache)
        val choices = c.subtitlesFor("Gachiakuta", "series", "tt32612521:1:9", "jpn")
        assertEquals(1, choices.size)
        assertTrue("no search on cache hit (D3a)", t.calls.none { it.first.contains("search") })
    }

    @Test
    fun cacheSelfHeals_invalidEntryClearedThenResearched() {
        val cache = MapCache().apply { map["tt17069148"] = 4242 }
        val (c, t) = client({ url -> when {
            url.contains("/entries/4242/files?episode=1") -> "[]"
            url.contains("/entries/search?query=Sakamoto+Days") ->
                """[{"id":8450,"name":"SAKAMOTO DAYS"}]"""
            url.contains("/entries/8450/files?episode=1") -> sakamotoFilesE1
            else -> null
        } }, cache = cache)
        val choices = c.subtitlesFor("Sakamoto Days", "series", "tt17069148:1:1", "jpn")
        assertEquals(1, choices.size) // zip filtered out, srt kept
        assertTrue("zip row never offered", choices.none { it.fileUrl.endsWith(".zip") })
        assertEquals(1, cache.clears)
        assertEquals(8450L, cache.map["tt17069148"])
        assertTrue("search ran after self-heal", t.calls.any { it.first.contains("search") })
    }

    @Test
    fun nonJapaneseLang_shortCircuitsEmpty() {
        val (c, t) = client({ _ -> "{}" })
        assertTrue(c.subtitlesFor("Gachiakuta", "series", "tt32612521:1:4", "eng").isEmpty())
        assertTrue(c.subtitlesFor("Gachiakuta", "series", "tt32612521:1:4", "").isEmpty())
        assertTrue("no network for non-applicable langs", t.calls.isEmpty())
    }

    @Test
    fun missingKey_failsWithActionableMessage() {
        val (c, _) = client({ _ -> "{}" }, key = null)
        val e = assertThrows(JimakuClient.Failure::class.java) {
            c.subtitlesFor("Gachiakuta", "series", "tt32612521:1:4", "jpn")
        }
        assertTrue(e.message!!.contains("key not set"))
    }

    @Test
    fun unauthorized_propagatesStatusForLedger() {
        val (c, _) = client({ _ -> throw StremioHttpException(401, "GET jimaku") })
        val e = assertThrows(StremioHttpException::class.java) {
            c.subtitlesFor("Gachiakuta", "series", "tt32612521:1:4", "jpn")
        }
        assertEquals(401, e.status)
    }

    @Test
    fun movieShape_noEpisodeParam_titleOnlyValidation() {
        val (c, t) = client({ url -> when {
            url.contains("/entries/search?query=The+Age+of+Innocence") ->
                """[{"id":51,"name":"The Age of Innocence"}]"""
            url == "https://jimaku.cc/api/entries/51/files" ->
                """[{"url":"https://jimaku.cc/entry/51/m.srt","name":"The.Age.of.Innocence.1993.jpn.srt","size":90,"last_modified":"2024-01-01T00:00:00Z"}]"""
            else -> null
        } })
        val choices = c.subtitlesFor("The Age of Innocence", "movie", "tt0106226", "jpn")
        assertEquals(1, choices.size)
        assertTrue("no episode= on movies", t.calls.none { it.first.contains("episode=") })
    }

    @Test
    fun pureHelpers_tokenGateAndFormatFilter() {
        assertTrue(JimakuClient.nameSharesTitle("Mushoku Tensei: Jobless Reincarnation",
            "Mushoku Tensei II: Isekai Ittara Honki Dasu"))
        assertTrue(!JimakuClient.nameSharesTitle("Gachiakuta", "Cowboy Bebop"))
        assertTrue(JimakuClient.isTextFile("A.B.ass"))
        assertTrue(JimakuClient.isTextFile("x.srt?token=1"))
        assertTrue(!JimakuClient.isTextFile("images.zip"))
        val files = listOf(
            JimakuClient.JmFile("u", "[GRP] Show - 04 (1080p).srt", 1),
            JimakuClient.JmFile("u", "pack.rar", 2),
        )
        assertTrue(JimakuClient.episodeValidated(files, 4, "Show", "Show"))
        assertTrue("zip/rar-only list never validates",
            !JimakuClient.episodeValidated(listOf(JimakuClient.JmFile("u", "a.zip", 1)), 4, "Show", "Show"))
        assertNull(JSONArray("[]").optJSONObject(0))
    }
}
