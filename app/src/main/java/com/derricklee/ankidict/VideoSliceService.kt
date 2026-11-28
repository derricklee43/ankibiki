package com.derricklee.ankidict

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * Pure, JVM-testable disk cache naming and LRU pruning for extracted video slices
 * (Stage 3 Step 3).
 */
internal object SliceDiskCache {

    const val DIR_NAME = "video_slices"
    const val MAX_CACHE_BYTES = 100L * 1024L * 1024L // 100 MB cap (~1,500 slices)

    fun cachePrefix(
        docId: Long,
        startMs: Long,
        endMs: Long,
        audioTrack: Int?,
        offsetMs: Int,
    ): String = "doc${docId}_${startMs}_${endMs}_t${audioTrack?.toString() ?: "auto"}_o${offsetMs}"

    fun filesFor(dir: File, prefix: String): Pair<File, File> =
        File(dir, "$prefix.webp") to File(dir, "$prefix.m4a")

    fun lookup(dir: File, prefix: String, nowMs: Long = System.currentTimeMillis()): Pair<File, File>? {
        val (img, aud) = filesFor(dir, prefix)
        if (!img.isFile || img.length() <= 0L || !aud.isFile || aud.length() <= 0L) return null
        // Touch mtime for LRU retention.
        runCatching { img.setLastModified(nowMs) }
        runCatching { aud.setLastModified(nowMs) }
        return img to aud
    }

    /** Evicts oldest files when total directory size exceeds [maxBytes]. */
    fun pruneIfNeeded(dir: File, maxBytes: Long = MAX_CACHE_BYTES): Int {
        val files = dir.listFiles()?.filter { it.isFile } ?: return 0
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return 0
        var deleted = 0
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            val len = f.length()
            if (f.delete()) {
                total -= len
                deleted++
            }
        }
        return deleted
    }
}

/**
 * Process-wide lazy video slice service (Stage 3 Step 3):
 *  - Disk cache (`cacheDir/video_slices/`) keyed by `(docId, startMs, endMs, audioTrack, offsetMs)`
 *    -> 0 ms on re-open.
 *  - Episode-level warm session cache (up to 3 episodes, 2h RD link TTL) keeping the minted
 *    RD URL + probed Media3 `SeekMap` + `AudioTrackInfo` list warm -> eliminates the ~5.4 s cold
 *    resolve+probe cost for every 2nd+ sentence in the same episode.
 *  - Automatic 1-retry re-mint if a cached RD link expired early.
 */
object VideoSliceService {

    private const val MAX_WARM_EPISODES = 3
    private const val RD_URL_TTL_MS = 2L * 60L * 60L * 1000L // 2 hours

    private data class WarmEpisode(
        val docId: Long,
        val resolved: StreamUrlResolver.ResolvedStream,
        val session: Media3SliceEngine.Session,
        val mintedAtMs: Long,
    )

    data class Result(
        val imageFile: File,
        val audioFile: File,
        val fromDiskCache: Boolean,
        val fromWarmSession: Boolean,
        val resolved: StreamUrlResolver.ResolvedStream?,
        val probe: MediaProbeResult?,
        val slice: Media3SliceEngine.SliceOutcome?,
        val selectedTrack: AudioTrackInfo?,
        val window: MediaSliceLogic.SliceWindow,
    )

    private val warmLock = Any()
    private val warmEpisodes = object : LinkedHashMap<Long, WarmEpisode>(MAX_WARM_EPISODES + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, WarmEpisode>?): Boolean {
            if (size > MAX_WARM_EPISODES) {
                eldest?.value?.session?.close()
                return true
            }
            return false
        }
    }

    /** Last known probed audio tracks per docId (survives session eviction for UI track pickers). */
    private val probedTracksCache = HashMap<Long, List<AudioTrackInfo>>()

    fun sliceDir(context: Context): File = File(context.applicationContext.cacheDir, SliceDiskCache.DIR_NAME)

    fun probedTracksFor(docId: Long): List<AudioTrackInfo> = synchronized(warmLock) {
        probedTracksCache[docId].orEmpty()
    }

    /** Fast local-only disk cache lookup (0 network). */
    fun getCachedSlice(
        context: Context,
        docId: Long,
        cue: SubtitleCue,
        settings: StremioSettingsRow,
    ): Pair<File, File>? {
        val prefix = SliceDiskCache.cachePrefix(docId, cue.startMs, cue.endMs, settings.audioTrack, settings.offsetMs)
        return SliceDiskCache.lookup(sliceDir(context), prefix)
    }

    /**
     * Returns `(imageFile, audioFile)` for [ctx], serving from disk cache when present,
     * reusing a warm per-episode Media3 `SeekMap` session when available, and automatically
     * re-minting once if a cached RD link expired.
     */
    fun sliceOrLoad(
        context: Context,
        repo: MiningRepository,
        prefs: StremioPrefs,
        ctx: VideoCueContext,
        settings: StremioSettingsRow,
        forceRefresh: Boolean = false,
        onStatus: (String) -> Unit = {},
    ): Result {
        val dir = sliceDir(context)
        val prefix = SliceDiskCache.cachePrefix(
            ctx.docId, ctx.cue.startMs, ctx.cue.endMs, settings.audioTrack, settings.offsetMs,
        )
        val window = MediaSliceLogic.computeSliceWindow(ctx.cue.startMs, ctx.cue.endMs, settings.offsetMs)

        if (!forceRefresh) {
            SliceDiskCache.lookup(dir, prefix)?.let { (img, aud) ->
                val tracks = probedTracksFor(ctx.docId)
                val chosen = MediaSliceLogic.selectAudioTrack(tracks, settings.audioTrack, settings.subtitleLang)
                return Result(
                    imageFile = img,
                    audioFile = aud,
                    fromDiskCache = true,
                    fromWarmSession = false,
                    resolved = null,
                    probe = null,
                    slice = null,
                    selectedTrack = chosen,
                    window = window,
                )
            }
        }

        var attempt = 0
        var lastError: Throwable? = null
        while (attempt < 2) {
            val forceCold = attempt > 0
            val (warm, wasWarm) = getOrOpenWarmEpisode(repo, prefs, ctx, forceCold, onStatus)
            val chosen = MediaSliceLogic.selectAudioTrack(
                tracks = warm.session.probe.audioTracks,
                preferredTrackIndex = settings.audioTrack,
                preferredLang = settings.subtitleLang,
            ) ?: throw IllegalStateException("no audio tracks found in stream")

            onStatus("切り出し中… (${chosen.displayLabel()})")
            val sliceRes = try {
                runCatching {
                    warm.session.extractSlice(
                        track = chosen,
                        window = window,
                        outputDir = dir,
                        filePrefix = prefix,
                    )
                }
            } finally {
                if (ctx.docId <= 0L) warm.session.close()
            }
            if (sliceRes.isSuccess) {
                val slice = sliceRes.getOrThrow()
                SliceDiskCache.pruneIfNeeded(dir)
                val report = String.format(
                    Locale.US,
                    "engine=media3 warm=%s video=%s file=%s source=%s resolveMs=%d probeMs=%d " +
                        "chosen=#%d(%s/%s) mode=%s seekByte=%d demuxMs=%d frameMs=%d(%dKB) " +
                        "audioMs=%d(%dKB) totalMs=%d sliceRxMB=%.2f",
                    wasWarm, ctx.videoId, warm.resolved.displayName, warm.resolved.source,
                    if (wasWarm) 0L else warm.resolved.resolveMs,
                    if (wasWarm) 0L else warm.session.probe.probeMs,
                    chosen.trackIndex, chosen.language ?: "und", chosen.mime,
                    slice.audioMode, slice.seekBytePos, slice.demuxMs,
                    slice.frameMs, slice.imageBytes / 1024,
                    slice.audioMs, slice.audioBytes / 1024,
                    slice.totalMs, slice.rxBytes / (1024.0 * 1024.0),
                )
                Log.i("Stage3Slice", report)
                runCatching { File(dir, "last_report.txt").writeText(report + "\n") }
                return Result(
                    imageFile = slice.imageFile,
                    audioFile = slice.audioFile,
                    fromDiskCache = false,
                    fromWarmSession = wasWarm,
                    resolved = warm.resolved,
                    probe = warm.session.probe,
                    slice = slice,
                    selectedTrack = chosen,
                    window = window,
                )
            }
            lastError = sliceRes.exceptionOrNull()
            // Evict potentially expired RD link and retry once if this was a warm session.
            evictEpisode(ctx.docId)
            if (!wasWarm) break
            attempt++
        }
        throw (lastError ?: IllegalStateException("video slice failed"))
    }

    private fun getOrOpenWarmEpisode(
        repo: MiningRepository,
        prefs: StremioPrefs,
        ctx: VideoCueContext,
        forceCold: Boolean,
        onStatus: (String) -> Unit,
    ): Pair<WarmEpisode, Boolean> {
        val now = System.currentTimeMillis()
        if (!forceCold && ctx.docId > 0L) {
            synchronized(warmLock) {
                val existing = warmEpisodes[ctx.docId]
                if (existing != null && (now - existing.mintedAtMs) < RD_URL_TTL_MS) {
                    return existing to true
                }
                if (existing != null) {
                    warmEpisodes.remove(ctx.docId)?.session?.close()
                }
            }
        }

        onStatus("ストリームを取得中…")
        val addons = repo.loadStremioAddons()
        val resolver = StreamUrlResolver(UrlStremioTransport())
        val resolved = resolver.resolve(
            videoId = ctx.videoId,
            metaType = ctx.metaType,
            storedInfoHash = ctx.infoHash,
            storedFileIdx = ctx.fileIdx,
            addons = addons,
            rdToken = prefs.rdToken,
        )
        if (ctx.docId > 0L && resolved.infoHash != null) {
            repo.updateStremioDocIdentity(ctx.docId, resolved.infoHash, resolved.fileIdx)
        }

        onStatus("動画インデックスを読み込み中…")
        val session = Media3SliceEngine.openAndProbe(resolved.directUrl)
        val warm = WarmEpisode(ctx.docId, resolved, session, System.currentTimeMillis())
        if (ctx.docId > 0L) {
            synchronized(warmLock) {
                warmEpisodes.put(ctx.docId, warm)?.session?.close()
                probedTracksCache[ctx.docId] = session.probe.audioTracks
            }
        }
        return warm to false
    }

    fun evictEpisode(docId: Long) {
        synchronized(warmLock) {
            warmEpisodes.remove(docId)?.session?.close()
        }
    }
}
