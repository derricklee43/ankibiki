package com.derricklee.ankidict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offline JVM specs for Stage 3 Step 1 + Step 2:
 *  - Torrentio RD resolve URL (infoHash, fileIdx) extraction
 *  - streamAddonCandidates ranking (non-local before org.stremio.local)
 *  - RdClient direct link minting (cache reuse, magnet add+select+unrestrict, uncached cleanup)
 *  - StreamUrlResolver fallback ladder
 *  - MediaSliceLogic track selection, slice windowing, and 5.1 -> stereo PCM downmix
 */
class Stage3MediaSliceTest {

    private class ScriptedTransport(
        private val gets: MutableMap<String, ArrayDeque<String>> = LinkedHashMap(),
        private val posts: MutableMap<String, ArrayDeque<String>> = LinkedHashMap(),
        private val redirects: Map<String, String> = emptyMap(),
    ) : StremioTransport {
        val log = ArrayList<String>()

        fun onGet(urlSubstring: String, vararg responses: String) {
            gets.getOrPut(urlSubstring) { ArrayDeque() }.addAll(responses)
        }

        fun onPost(urlSubstring: String, vararg responses: String) {
            posts.getOrPut(urlSubstring) { ArrayDeque() }.addAll(responses)
        }

        override fun get(url: String, headers: Map<String, String>): String {
            log += "GET $url"
            val q = gets.entries.firstOrNull { url.contains(it.key) }?.value
                ?: error("unexpected GET $url")
            return q.removeFirst()
        }

        override fun post(url: String, body: String, contentType: String, headers: Map<String, String>): String {
            log += "POST $url [$body]"
            val q = posts.entries.firstOrNull { url.contains(it.key) }?.value
                ?: error("unexpected POST $url")
            return q.removeFirst()
        }

        override fun delete(url: String, headers: Map<String, String>): String {
            log += "DELETE $url"
            return ""
        }

        override fun resolveRedirectUrl(url: String, headers: Map<String, String>): String {
            log += "RESOLVE $url"
            return redirects[url] ?: url
        }
    }

    @Test
    fun extractInfoHashFromUrl_parsesTorrentioResolveAndBareHash() {
        val hash = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678"
        val torrentio = "https://torrentio.strem.fun/resolve/realdebrid/SECRET/$hash/null/5/Demon.Slayer.S01E06.mkv"
        assertEquals(hash to 5, AddonResources.extractInfoHashFromUrl(torrentio))

        val bare = "https://example.org/streams/${hash.uppercase()}/video.mkv"
        assertEquals(hash to null, AddonResources.extractInfoHashFromUrl(bare))

        assertNull(AddonResources.extractInfoHashFromUrl("https://example.org/plain/video.mp4"))
    }

    @Test
    fun streams_extractsIdentityFromTorrentioUrlWhenTopLevelHashMissing() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val transport = ScriptedTransport().apply {
            onGet(
                "/stream/series/tt9335498:1:1.json",
                """{"streams":[{"name":"Torrentio\n1080p [RD+]","title":"Episode 1","url":"https://torrentio.strem.fun/resolve/realdebrid/K/$hash/null/2/ep1.mkv","behaviorHints":{"filename":"ep1.mkv"}}]}""",
            )
        }
        val addon = StremioAddon("com.stremio.torrentio.addon", "Torrentio RD", "0.0.14", "https://torrentio.strem.fun/manifest.json", setOf("stream"))
        val got = AddonResources(transport).streams(addon, "series", "tt9335498:1:1")
        assertEquals(1, got.size)
        assertEquals(hash, got.single().infoHash)
        assertEquals(2, got.single().fileIdx)
        assertEquals("ep1.mkv", got.single().filename)
    }

    @Test
    fun streamAddonCandidates_ranksRemoteBeforeLocal() {
        val local = StremioAddon("org.stremio.local", "Local Files", "1", "http://127.0.0.1/manifest.json", setOf("stream"))
        val torrentio = StremioAddon("com.stremio.torrentio.addon", "Torrentio RD", "1", "https://t/manifest.json", setOf("stream"))
        val ranked = listOf(local, torrentio).streamAddonCandidates()
        assertEquals(listOf("com.stremio.torrentio.addon", "org.stremio.local"), ranked.map { it.addonId })
    }

    @Test
    fun rdMint_reusesExistingDownloadedTorrentWithoutAddMagnet() {
        val hash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val transport = ScriptedTransport().apply {
            onGet("/torrents?limit=100", """[{"id":"T1","filename":"Pack","hash":"$hash","status":"downloaded","links":["https://rd/h1","https://rd/h2"]}]""")
            onGet(
                "/torrents/info/T1",
                """{"id":"T1","hash":"$hash","status":"downloaded","files":[{"id":1,"path":"/ep1.mkv","bytes":100,"selected":1},{"id":2,"path":"/ep2.mkv","bytes":200,"selected":1}],"links":["https://rd/h1","https://rd/h2"]}""",
            )
            onPost("/unrestrict/link", """{"id":"U1","filename":"ep2.mkv","mimeType":"video/x-matroska","filesize":200,"download":"https://cdn.real-debrid.com/d/ep2.mkv"}""")
        }
        val link = RdClient(transport).mintDirectStreamUrl("TOK", hash, fileIdx = 1)
        assertEquals("https://cdn.real-debrid.com/d/ep2.mkv", link.downloadUrl)
        assertTrue(transport.log.none { it.contains("addMagnet") })
        // fileIdx=1 -> 1-based RD file id=2 -> second hoster link https://rd/h2
        assertTrue(transport.log.any { it.contains("unrestrict/link") && it.contains("h2") })
    }

    @Test
    fun rdMint_addsMagnetSelectsTargetFileAndUnrestricts() {
        val hash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val transport = ScriptedTransport().apply {
            onGet("/torrents?limit=100", "[]")
            onPost("/torrents/addMagnet", """{"id":"T9","uri":"https://rd/t/T9"}""")
            onGet(
                "/torrents/info/T9",
                """{"id":"T9","hash":"$hash","status":"waiting_files_selection","files":[{"id":1,"path":"/nfo.txt","bytes":5,"selected":0},{"id":2,"path":"/ep.mkv","bytes":500,"selected":0}],"links":[]}""",
                """{"id":"T9","hash":"$hash","status":"downloaded","files":[{"id":1,"path":"/nfo.txt","bytes":5,"selected":0},{"id":2,"path":"/ep.mkv","bytes":500,"selected":1}],"links":["https://rd/hoster9"]}""",
            )
            onPost("/torrents/selectFiles/T9", "")
            onPost("/unrestrict/link", """{"id":"U9","filename":"ep.mkv","mimeType":"video/x-matroska","filesize":500,"download":"https://cdn.real-debrid.com/d/ep.mkv"}""")
        }
        val link = RdClient(transport).mintDirectStreamUrl("TOK", hash, fileIdx = 1, pollDelayMs = {})
        assertEquals("https://cdn.real-debrid.com/d/ep.mkv", link.downloadUrl)
        assertTrue(transport.log.any { it.contains("selectFiles/T9 [files=2]") })
    }

    @Test
    fun rdMint_deletesUncachedAddedTorrentAndFailsCleanly() {
        val hash = "cccccccccccccccccccccccccccccccccccccccc"
        val transport = ScriptedTransport().apply {
            onGet("/torrents?limit=100", "[]")
            onPost("/torrents/addMagnet", """{"id":"T7","uri":"x"}""")
            onGet(
                "/torrents/info/T7",
                """{"id":"T7","hash":"$hash","status":"waiting_files_selection","files":[{"id":1,"path":"/ep.mkv","bytes":500,"selected":0}],"links":[]}""",
                """{"id":"T7","hash":"$hash","status":"downloading","files":[{"id":1,"path":"/ep.mkv","bytes":500,"selected":1}],"links":[]}""",
                """{"id":"T7","hash":"$hash","status":"downloading","files":[{"id":1,"path":"/ep.mkv","bytes":500,"selected":1}],"links":[]}""",
                """{"id":"T7","hash":"$hash","status":"downloading","files":[{"id":1,"path":"/ep.mkv","bytes":500,"selected":1}],"links":[]}""",
            )
            onPost("/torrents/selectFiles/T7", "")
        }
        try {
            RdClient(transport).mintDirectStreamUrl("TOK", hash, fileIdx = 0, pollDelayMs = {})
            fail("expected uncached failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("not instant-cached"))
            assertTrue("uncached torrent must be deleted", transport.log.any { it.contains("DELETE") && it.contains("/torrents/delete/T7") })
        }
    }

    @Test
    fun streamUrlResolver_fallsBackToAddonRedirectWhenNoRdToken() {
        val resolveUrl = "https://torrentio.strem.fun/resolve/realdebrid/K/dddddddddddddddddddddddddddddddddddddddd/null/0/ep.mkv"
        val cdnUrl = "https://cdn42.download.real-debrid.com/d/ABC/ep.mkv"
        val transport = ScriptedTransport(redirects = mapOf(resolveUrl to cdnUrl)).apply {
            onGet("/stream/series/tt1:1:1.json", """{"streams":[{"name":"Torrentio [RD+]","url":"$resolveUrl"}]}""")
        }
        val addon = StremioAddon("com.stremio.torrentio.addon", "Torrentio RD", "1", "https://t/manifest.json", setOf("stream"))
        val resolved = StreamUrlResolver(transport).resolve(
            videoId = "tt1:1:1", metaType = "series", storedInfoHash = null, storedFileIdx = null,
            addons = listOf(addon), rdToken = null,
        )
        assertEquals(cdnUrl, resolved.directUrl)
        assertEquals("addon-redirect", resolved.source)
        assertEquals("dddddddddddddddddddddddddddddddddddddddd", resolved.infoHash)
        assertEquals(0, resolved.fileIdx)
    }

    @Test
    fun selectAudioTrack_prioritizesExplicitIndexThenLanguageAliasesThenDefault() {
        val tracks = listOf(
            AudioTrackInfo(1, "audio/mp4a-latm", "eng", 2, 48000, isDefault = true),
            AudioTrackInfo(2, "audio/opus", "ja", 2, 48000, isDefault = false),
        )
        assertEquals(1, MediaSliceLogic.selectAudioTrack(tracks, preferredTrackIndex = 1, preferredLang = "jpn")?.trackIndex)
        assertEquals(2, MediaSliceLogic.selectAudioTrack(tracks, preferredTrackIndex = null, preferredLang = "jpn")?.trackIndex)
        assertEquals(1, MediaSliceLogic.selectAudioTrack(tracks, preferredTrackIndex = null, preferredLang = "zho")?.trackIndex)
    }

    @Test
    fun computeSliceWindow_anchorsFrameInsideShiftedCueAndPadsAudio() {
        val w = MediaSliceLogic.computeSliceWindow(cueStartMs = 10_000L, cueEndMs = 12_000L, offsetMs = 500)
        assertEquals(11_500L, w.frameAtMs) // midpoint of 10500..12500
        assertEquals(10_350L, w.startMs)   // -150ms pad
        assertEquals(12_700L, w.endMs)     // +200ms pad
    }

    @Test
    fun downmixToStereo16Bit_convertsSixChannel51ToClampedStereo() {
        // 1 frame of 6ch 16-bit LE PCM: L=10000, R=2000, C=10000, LFE=0, Ls=4000, Rs=0
        val inBytes = ByteArray(12)
        val bb = ByteBuffer.wrap(inBytes).order(ByteOrder.LITTLE_ENDIAN)
        listOf(10000, 2000, 10000, 0, 4000, 0).forEach { bb.putShort(it.toShort()) }
        val stereo = MediaSliceLogic.downmixToStereo16Bit(inBytes, inBytes.size, channels = 6)
        assertEquals(4, stereo.size)
        val out = ByteBuffer.wrap(stereo).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(19070, out.short.toInt()) // 10000 + 7070 + 2000
        assertEquals(9070, out.short.toInt())  // 2000 + 7070 + 0
    }

    @Test
    fun selectAudioTrack_outOfRangeSavedPinFallsBackToLanguageMatch() {
        // 0-based audio ordinals (#0 en, #1 ja); stale pin=2 falls back cleanly to jpn -> #1 ja
        val tracks = listOf(
            AudioTrackInfo(0, "audio/flac", "en", 2, 48000, isDefault = true, containerTrackId = 2),
            AudioTrackInfo(1, "audio/flac", "ja", 2, 48000, isDefault = false, containerTrackId = 3),
        )
        val chosen = MediaSliceLogic.selectAudioTrack(tracks, preferredTrackIndex = 2, preferredLang = "jpn")
        assertEquals(1, chosen?.trackIndex)
        assertEquals(3, chosen?.containerTrackId)
    }

    @Test
    fun sliceDiskCache_lookupRequiresBothFilesAndPrunesOldestOverLimit() {
        val dir = java.nio.file.Files.createTempDirectory("slice_cache_test").toFile()
        try {
            val p1 = SliceDiskCache.cachePrefix(5L, 1000L, 3000L, audioTrack = 1, offsetMs = -500)
            assertEquals("doc5_1000_3000_t1_o-500", p1)
            assertNull(SliceDiskCache.lookup(dir, p1))

            val (img1, aud1) = SliceDiskCache.filesFor(dir, p1)
            img1.writeBytes(ByteArray(60))
            assertNull("missing audio file -> cache miss", SliceDiskCache.lookup(dir, p1))
            aud1.writeBytes(ByteArray(40))
            img1.setLastModified(1000L)
            aud1.setLastModified(1000L)

            val hit = SliceDiskCache.lookup(dir, p1, nowMs = 2000L)
            assertEquals(img1 to aud1, hit)

            // Add a newer second slice and prune with maxBytes = 100 -> oldest files drop first
            val p2 = SliceDiskCache.cachePrefix(5L, 4000L, 6000L, audioTrack = null, offsetMs = 0)
            val (img2, aud2) = SliceDiskCache.filesFor(dir, p2)
            img2.writeBytes(ByteArray(50))
            aud2.writeBytes(ByteArray(40))
            img2.setLastModified(5000L)
            aud2.setLastModified(5000L)

            val removed = SliceDiskCache.pruneIfNeeded(dir, maxBytes = 100L)
            assertTrue(removed >= 2)
            assertNull("oldest slice evicted", SliceDiskCache.lookup(dir, p1))
            assertEquals(img2 to aud2, SliceDiskCache.lookup(dir, p2))
        } finally {
            dir.deleteRecursively()
        }
    }
}
