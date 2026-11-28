package com.derricklee.ankidict

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Stage 3 Step 2 (S3.1) Media3 single-pass extractor + hardware MediaCodec slicer.
 *
 * Replaces AOSP `MediaExtractor`'s broken MKV HTTP seeker (which pulled 447 MB / 40.6s
 * scanning linearly from byte 0 on Demon Slayer 1080p HDR MKV) with Google's pure-Java
 * Media3 `MatroskaExtractor` / `DefaultExtractorsFactory`:
 *  - Phase 1 (Probe): HTTP open #1 (byte 0 headers + SeekHead) -> HTTP open #2 (EOF Cues
 *    SeekMap) — ~200-500 KB total.
 *  - Phase 2 (Slice): ONE HTTP open #3 at `seekMap.getSeekPoints(targetUs).first.position`
 *    demuxing BOTH the single video GOP (to `frameAtUs`) AND the 2-4s audio window
 *    simultaneously in one continuous stream (~3-6 MB), then closing HTTP immediately
 *    and running local hardware frame extract + local AAC remux/transcode.
 */
@OptIn(UnstableApi::class)
object Media3SliceEngine {

    private const val MAX_SLICE_READ_BYTES = 45L * 1024L * 1024L // 45 MB hard ceiling (never stalls)
    private const val MAX_PROBE_READ_BYTES = 12L * 1024L * 1024L // 12 MB probe cap
    private const val MAX_PCM_BYTES = 6 * 1024 * 1024            // ~15s 48kHz stereo safety cap

    data class DemuxedSample(
        val ptsUs: Long,
        val flags: Int,
        val data: ByteArray,
    )

    data class SliceOutcome(
        val imageFile: File,
        val audioFile: File,
        val frameMs: Long,
        val audioMs: Long,
        val demuxMs: Long,
        val totalMs: Long,
        val rxBytes: Long,
        val httpOpens: Long,
        val seekable: Boolean,
        val seekBytePos: Long,
        val seekedPtsMs: Long,
        val imageBytes: Long,
        val audioBytes: Long,
        val audioMode: String,
        val selectedTrack: AudioTrackInfo,
        val window: MediaSliceLogic.SliceWindow,
    )

    internal class CountingDataSource(
        private val delegate: DataSource,
    ) : DataSource {
        val bytesRead = AtomicLong(0L)
        val opens = AtomicLong(0L)

        override fun addTransferListener(transferListener: TransferListener) =
            delegate.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            opens.incrementAndGet()
            return delegate.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val n = delegate.read(buffer, offset, length)
            if (n > 0) bytesRead.addAndGet(n.toLong())
            return n
        }

        override fun getUri(): Uri? = delegate.uri
        override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders
        override fun close() = delegate.close()
    }

    internal class CollectingTrackOutput(
        val trackId: Int,
        val trackType: Int,
    ) : TrackOutput {
        @Volatile var format: Format? = null
        @Volatile var captureEnabled: Boolean = false
        @Volatile var minKeepPtsUs: Long = Long.MIN_VALUE
        @Volatile var maxKeepPtsUs: Long = Long.MAX_VALUE
        @Volatile var requireInitialKeyFrame: Boolean = false

        private var seenKeyFrame = false
        private val currentSampleBuf = ByteArrayOutputStream(64 * 1024)
        val samples = ArrayList<DemuxedSample>()
        @Volatile var maxSeenPtsUs: Long = Long.MIN_VALUE

        fun resetCapture(
            enabled: Boolean,
            minPtsUs: Long = Long.MIN_VALUE,
            maxPtsUs: Long = Long.MAX_VALUE,
            requireKeyFrame: Boolean = false,
        ) {
            captureEnabled = enabled
            minKeepPtsUs = minPtsUs
            maxKeepPtsUs = maxPtsUs
            requireInitialKeyFrame = requireKeyFrame
            seenKeyFrame = false
            currentSampleBuf.reset()
            samples.clear()
            maxSeenPtsUs = Long.MIN_VALUE
        }

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int {
            val tmp = ByteArray(minOf(length, 64 * 1024))
            val read = input.read(tmp, 0, tmp.size)
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw java.io.EOFException("unexpected EOF in track $trackId")
            }
            if (captureEnabled && read > 0) {
                currentSampleBuf.write(tmp, 0, read)
            }
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            if (captureEnabled && length > 0) {
                val tmp = ByteArray(length)
                data.readBytes(tmp, 0, length)
                currentSampleBuf.write(tmp)
            } else {
                data.skipBytes(length)
            }
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            if (!captureEnabled) {
                currentSampleBuf.reset()
                return
            }
            val raw = currentSampleBuf.toByteArray()
            currentSampleBuf.reset()
            if (timeUs > maxSeenPtsUs) maxSeenPtsUs = timeUs
            val isKey = (flags and C.BUFFER_FLAG_KEY_FRAME) != 0
            if (requireInitialKeyFrame && !seenKeyFrame) {
                if (!isKey) return
                seenKeyFrame = true
            }
            if (timeUs in minKeepPtsUs..maxKeepPtsUs && raw.isNotEmpty()) {
                // `offset` is trailing bytes after sample end; trim to [raw.size - offset - size, raw.size - offset)
                val endIdx = (raw.size - offset).coerceIn(0, raw.size)
                val startIdx = (endIdx - size).coerceIn(0, endIdx)
                val slice = if (startIdx == 0 && endIdx == raw.size) raw else raw.copyOfRange(startIdx, endIdx)
                samples += DemuxedSample(timeUs, flags, slice)
            }
        }
    }

    internal class SessionExtractorOutput : ExtractorOutput {
        val tracks = LinkedHashMap<Int, CollectingTrackOutput>()
        @Volatile var tracksEnded = false
        @Volatile var seekMap: SeekMap? = null

        override fun track(id: Int, type: Int): TrackOutput =
            tracks.getOrPut(id) { CollectingTrackOutput(id, type) }

        override fun endTracks() {
            tracksEnded = true
        }

        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }
    }

    /** Live probed session keeping the Media3 Extractor + SeekMap warm for immediate slicing. */
    class Session internal constructor(
        private val uri: Uri,
        private val dataSource: CountingDataSource,
        private val extractor: Extractor,
        private val output: SessionExtractorOutput,
        private val totalFileBytes: Long,
        val probe: MediaProbeResult,
        private val videoTrackId: Int?,
    ) : Closeable {

        private val sessionLock = Any()

        val totalHttpOpens: Long get() = dataSource.opens.get()
        val totalBytesRead: Long get() = dataSource.bytesRead.get()

        fun extractSlice(
            track: AudioTrackInfo,
            window: MediaSliceLogic.SliceWindow,
            outputDir: File,
            filePrefix: String,
        ): SliceOutcome = synchronized(sessionLock) {
            outputDir.mkdirs()
            val imageFile = File(outputDir, "$filePrefix.webp")
            val audioFile = File(outputDir, "$filePrefix.m4a")

            val rx0 = dataSource.bytesRead.get()
            val opens0 = dataSource.opens.get()
            val tTotal0 = SystemClock.elapsedRealtime()

            val seekMap = output.seekMap
                ?: throw IllegalStateException("extractor produced no SeekMap")
            val seekTargetUs = minOf(window.startMs, window.frameAtMs) * 1000L
            val seekPoints = seekMap.getSeekPoints(seekTargetUs)
            val bytePos = if (seekMap.isSeekable) {
                seekPoints.first.position.coerceAtLeast(0L)
            } else if (totalFileBytes > 0L && probe.durationMs > 0L) {
                ((totalFileBytes.toDouble() * seekTargetUs / (probe.durationMs * 1000.0)).toLong())
                    .coerceIn(0L, totalFileBytes - 1L)
            } else 0L
            val seekedPtsMs = seekPoints.first.timeUs / 1000L

            // Configure single-pass capture for BOTH video (1 GOP up to frameAtUs + 600ms) and audio.
            val frameAtUs = window.frameAtMs * 1000L
            val videoEndUs = frameAtUs + 600_000L
            val audioStartUs = window.startMs * 1000L
            val audioEndUs = window.endMs * 1000L
            val audioStopUs = audioEndUs + 250_000L

            for (t in output.tracks.values) t.resetCapture(enabled = false)
            val videoOut = videoTrackId?.let { output.tracks[it] }
            videoOut?.resetCapture(
                enabled = true,
                minPtsUs = Long.MIN_VALUE,
                maxPtsUs = videoEndUs,
                requireKeyFrame = true,
            )
            val audioOut = output.tracks[track.containerTrackId]
                ?: throw IllegalStateException("audio track #${track.trackIndex} (id=${track.containerTrackId}) missing in session")
            audioOut.resetCapture(
                enabled = true,
                minPtsUs = audioStartUs - 250_000L,
                maxPtsUs = audioStopUs,
                requireKeyFrame = false,
            )

            // Phase 2A: ONE continuous HTTP stream at bytePos demuxing BOTH tracks simultaneously!
            val tDemux0 = SystemClock.elapsedRealtime()
            extractor.seek(bytePos, seekTargetUs)
            val posHolder = PositionHolder()
            var currentPos = bytePos
            var input = openInputAt(dataSource, uri, currentPos, totalFileBytes)
            try {
                while (true) {
                    val videoDone = videoOut == null || (videoOut.samples.isNotEmpty() && videoOut.maxSeenPtsUs >= videoEndUs)
                    val audioDone = audioOut.samples.isNotEmpty() && audioOut.maxSeenPtsUs >= audioStopUs
                    if (videoDone && audioDone) break
                    if (dataSource.bytesRead.get() - rx0 > MAX_SLICE_READ_BYTES) break

                    val res = extractor.read(input, posHolder)
                    when (res) {
                        Extractor.RESULT_CONTINUE -> Unit
                        Extractor.RESULT_SEEK -> {
                            currentPos = posHolder.position
                            runCatching { dataSource.close() }
                            input = openInputAt(dataSource, uri, currentPos, totalFileBytes)
                        }
                        Extractor.RESULT_END_OF_INPUT -> break
                    }
                }
            } finally {
                runCatching { dataSource.close() }
            }
            val demuxMs = SystemClock.elapsedRealtime() - tDemux0
            val rxDelta = (dataSource.bytesRead.get() - rx0).coerceAtLeast(0L)
            val opensDelta = (dataSource.opens.get() - opens0).coerceAtLeast(0L)

            val vFmt = videoOut?.format
            if (videoOut == null || vFmt == null || videoOut.samples.isEmpty()) {
                throw IllegalStateException("demuxed 0 video samples at bytePos=$bytePos (${videoOut?.samples?.size ?: 0})")
            }
            val aFmt = audioOut.format
                ?: throw IllegalStateException("audio track ${track.trackIndex} has no Format")
            if (audioOut.samples.isEmpty()) {
                throw IllegalStateException("demuxed 0 audio samples for track ${track.trackIndex} in ${window.startMs}..${window.endMs}ms")
            }

            // Phase 2B + 2C: Run local frame decode and local audio remux/transcode IN PARALLEL
            // (both operate purely on in-memory demuxed samples; hides audioMs behind frameMs).
            val videoSamplesSnapshot = videoOut.samples.toList()
            val audioSamplesSnapshot = audioOut.samples.toList()
            val audioFuture = AppIo.io.submit<Pair<String, Long>> {
                val tA0 = SystemClock.elapsedRealtime()
                val mode = encodeAudioSamples(aFmt, audioSamplesSnapshot, audioStartUs, audioEndUs, audioFile)
                mode to (SystemClock.elapsedRealtime() - tA0)
            }
            val tFrame0 = SystemClock.elapsedRealtime()
            val frameResult = runCatching {
                extractFrameFromSamples(vFmt, videoSamplesSnapshot, frameAtUs, outputDir, filePrefix, imageFile)
            }
            val frameMs = SystemClock.elapsedRealtime() - tFrame0
            val (audioMode, audioMs) = try {
                audioFuture.get()
            } catch (e: java.util.concurrent.ExecutionException) {
                throw (e.cause ?: e)
            }
            frameResult.getOrThrow()

            val totalMs = SystemClock.elapsedRealtime() - tTotal0
            SliceOutcome(
                imageFile = imageFile,
                audioFile = audioFile,
                frameMs = frameMs,
                audioMs = audioMs,
                demuxMs = demuxMs,
                totalMs = totalMs,
                rxBytes = rxDelta,
                httpOpens = opensDelta,
                seekable = seekMap.isSeekable,
                seekBytePos = bytePos,
                seekedPtsMs = seekedPtsMs,
                imageBytes = imageFile.length(),
                audioBytes = audioFile.length(),
                audioMode = audioMode,
                selectedTrack = track,
                window = window,
            )
        }

        override fun close() = synchronized(sessionLock) {
            runCatching { dataSource.close() }
            runCatching { extractor.release() }
            Unit
        }
    }

    /** Opens the remote URL with Media3, probes tracks + SeekMap in ~2 HTTP Range requests. */
    fun openAndProbe(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Session {
        val uri = Uri.parse(url)
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("AnkiBiki/1.0")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        if (headers.isNotEmpty()) httpFactory.setDefaultRequestProperties(headers)
        val dataSource = CountingDataSource(httpFactory.createDataSource())

        val t0 = SystemClock.elapsedRealtime()
        val rx0 = dataSource.bytesRead.get()
        val openLen = dataSource.open(DataSpec(uri, 0L, C.LENGTH_UNSET.toLong()))
        val totalBytes = if (openLen == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else openLen
        var input = DefaultExtractorInput(dataSource, 0L, totalBytes)

        val candidates = DefaultExtractorsFactory()
            .createExtractors(uri, dataSource.responseHeaders)
        var extractor: Extractor? = null
        for (cand in candidates) {
            val matched = runCatching { cand.sniff(input) }.getOrDefault(false)
            input.resetPeekPosition()
            if (matched) {
                extractor = cand
                break
            }
        }
        val chosenExtractor = extractor ?: run {
            runCatching { dataSource.close() }
            throw IllegalStateException("Media3 could not sniff container format")
        }

        val output = SessionExtractorOutput()
        chosenExtractor.init(output)
        val posHolder = PositionHolder()
        try {
            while (!(output.tracksEnded && output.seekMap != null)) {
                if (dataSource.bytesRead.get() - rx0 > MAX_PROBE_READ_BYTES) break
                val res = chosenExtractor.read(input, posHolder)
                when (res) {
                    Extractor.RESULT_CONTINUE -> Unit
                    Extractor.RESULT_SEEK -> {
                        runCatching { dataSource.close() }
                        input = openInputAt(dataSource, uri, posHolder.position, totalBytes)
                    }
                    Extractor.RESULT_END_OF_INPUT -> break
                }
            }
        } finally {
            runCatching { dataSource.close() }
        }

        val probeMs = SystemClock.elapsedRealtime() - t0
        val probeRx = (dataSource.bytesRead.get() - rx0).coerceAtLeast(0L)

        var videoTrackId: Int? = null
        var videoMime: String? = null
        var videoWidth = 0
        var videoHeight = 0
        val audioTracks = ArrayList<AudioTrackInfo>()

        for ((id, trackOut) in output.tracks) {
            val fmt = trackOut.format ?: continue
            val mime = fmt.sampleMimeType ?: continue
            when {
                mime.startsWith("video/") && videoTrackId == null -> {
                    videoTrackId = id
                    videoMime = mime
                    videoWidth = fmt.width.coerceAtLeast(0)
                    videoHeight = fmt.height.coerceAtLeast(0)
                }
                mime.startsWith("audio/") -> {
                    audioTracks += AudioTrackInfo(
                        trackIndex = audioTracks.size,
                        mime = mime,
                        language = fmt.language,
                        channelCount = fmt.channelCount.takeIf { it > 0 } ?: 2,
                        sampleRate = fmt.sampleRate.takeIf { it > 0 } ?: 48000,
                        isDefault = (fmt.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0,
                        containerTrackId = id,
                    )
                }
            }
        }
        val durationMs = output.seekMap?.durationUs?.takeIf { it != C.TIME_UNSET }?.div(1000L) ?: 0L
        val probe = MediaProbeResult(
            audioTracks = audioTracks,
            videoMime = videoMime,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            durationMs = durationMs,
            probeMs = probeMs,
            rxBytes = probeRx,
        )
        return Session(
            uri = uri,
            dataSource = dataSource,
            extractor = chosenExtractor,
            output = output,
            totalFileBytes = totalBytes,
            probe = probe,
            videoTrackId = videoTrackId,
        )
    }

    private fun openInputAt(
        dataSource: CountingDataSource,
        uri: Uri,
        position: Long,
        knownTotalBytes: Long,
    ): DefaultExtractorInput {
        val remaining = dataSource.open(DataSpec(uri, position, C.LENGTH_UNSET.toLong()))
        val total = when {
            knownTotalBytes > 0L -> knownTotalBytes
            remaining != C.LENGTH_UNSET.toLong() -> position + remaining
            else -> C.LENGTH_UNSET.toLong()
        }
        return DefaultExtractorInput(dataSource, position, total)
    }

    private fun toAndroidVideoFormat(fmt: Format): MediaFormat {
        val mime = fmt.sampleMimeType ?: throw IllegalStateException("video format missing mime")
        return MediaFormat.createVideoFormat(
            mime,
            fmt.width.takeIf { it > 0 } ?: 1920,
            fmt.height.takeIf { it > 0 } ?: 1080,
        ).apply {
            fmt.initializationData.forEachIndexed { i, bytes ->
                setByteBuffer("csd-$i", ByteBuffer.wrap(bytes))
            }
            if (fmt.frameRate > 0f) setFloat(MediaFormat.KEY_FRAME_RATE, fmt.frameRate)
        }
    }

    private fun toAndroidAudioFormat(fmt: Format): MediaFormat {
        val mime = fmt.sampleMimeType ?: throw IllegalStateException("audio format missing mime")
        val sr = fmt.sampleRate.takeIf { it > 0 } ?: 48000
        val ch = fmt.channelCount.takeIf { it > 0 } ?: 2
        return MediaFormat.createAudioFormat(mime, sr, ch).apply {
            fmt.initializationData.forEachIndexed { i, bytes ->
                setByteBuffer("csd-$i", ByteBuffer.wrap(bytes))
            }
        }
    }

    /**
     * Muxes the tiny demuxed 1-GOP video slice into a local temp container and runs
     * `MediaMetadataRetriever.getScaledFrameAtTime` locally (~100ms, handles 10-bit HDR
     * tone-mapping and scaling in hardware cleanly).
     */
    private fun extractFrameFromSamples(
        videoFormat: Format,
        samples: List<DemuxedSample>,
        frameAtUs: Long,
        tempDir: File,
        filePrefix: String,
        outWebp: File,
    ) {
        val mime = videoFormat.sampleMimeType ?: ""
        val useWebm = mime == "video/x-vnd.on2.vp8" || mime == "video/x-vnd.on2.vp9"
        val tempFile = File(tempDir, "${filePrefix}_tmp.${if (useWebm) "webm" else "mp4"}")
        val minPtsUs = samples.minOf { it.ptsUs }
        val muxer = MediaMuxer(
            tempFile.absolutePath,
            if (useWebm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )
        var started = false
        try {
            val trackIdx = muxer.addTrack(toAndroidVideoFormat(videoFormat))
            muxer.start()
            started = true
            val info = MediaCodec.BufferInfo()
            for (s in samples) {
                info.offset = 0
                info.size = s.data.size
                info.presentationTimeUs = (s.ptsUs - minPtsUs).coerceAtLeast(0L)
                info.flags = if ((s.flags and C.BUFFER_FLAG_KEY_FRAME) != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else 0
                muxer.writeSampleData(trackIdx, ByteBuffer.wrap(s.data), info)
            }
        } finally {
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }

        val retriever = MediaMetadataRetriever()
        var bitmap: Bitmap? = null
        try {
            retriever.setDataSource(tempFile.absolutePath)
            val relTargetUs = (frameAtUs - minPtsUs).coerceAtLeast(0L)
            bitmap = retriever.getScaledFrameAtTime(
                relTargetUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                1280,
                720,
            ) ?: retriever.getScaledFrameAtTime(
                relTargetUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                1280,
                720,
            ) ?: retriever.getScaledFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                1280,
                720,
            ) ?: throw IllegalStateException("local MediaMetadataRetriever returned null frame")

            FileOutputStream(outWebp).use { fos ->
                if (!bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 82, fos)) {
                    throw IllegalStateException("WebP compress failed")
                }
            }
        } finally {
            bitmap?.recycle()
            runCatching { retriever.release() }
            runCatching { tempFile.delete() }
        }
    }

    private fun encodeAudioSamples(
        audioFormat: Format,
        samples: List<DemuxedSample>,
        startUs: Long,
        endUs: Long,
        outM4a: File,
    ): String {
        val androidFormat = toAndroidAudioFormat(audioFormat)
        val mime = audioFormat.sampleMimeType ?: throw IllegalStateException("audio format missing mime")
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            remuxAacSamples(androidFormat, samples, startUs, endUs, outM4a)
            return "remux-aac"
        }
        val decoded = decodeSamplesToPcm(androidFormat, mime, samples, startUs, endUs)
        encodePcmToM4a(decoded.pcm, decoded.sampleRate, decoded.channelCount, outM4a)
        return "transcode-${mime.substringAfter("audio/")}->aac"
    }

    private fun remuxAacSamples(
        format: MediaFormat,
        samples: List<DemuxedSample>,
        startUs: Long,
        endUs: Long,
        outFile: File,
    ) {
        val windowSamples = samples.filter { it.ptsUs in startUs..endUs }.ifEmpty { samples }
        val basePtsUs = windowSamples.minOf { it.ptsUs }
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val trackIdx = muxer.addTrack(format)
            muxer.start()
            started = true
            val info = MediaCodec.BufferInfo()
            for (s in windowSamples) {
                info.offset = 0
                info.size = s.data.size
                info.presentationTimeUs = (s.ptsUs - basePtsUs).coerceAtLeast(0L)
                info.flags = if ((s.flags and C.BUFFER_FLAG_KEY_FRAME) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(trackIdx, ByteBuffer.wrap(s.data), info)
            }
        } finally {
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private data class DecodedPcm(val pcm: ByteArray, val sampleRate: Int, val channelCount: Int)

    private fun decodeSamplesToPcm(
        inputFormat: MediaFormat,
        inputMime: String,
        samples: List<DemuxedSample>,
        startUs: Long,
        endUs: Long,
    ): DecodedPcm {
        val decoder = MediaCodec.createDecoderByType(inputMime)
        var outSampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else 48000
        var outChannels = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else 2
        val pcmOut = ByteArrayOutputStream(256 * 1024)
        var started = false
        try {
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            started = true

            val info = MediaCodec.BufferInfo()
            var sampleIdx = 0
            var inputDone = false
            var outputDone = false
            var idleLoops = 0

            while (!outputDone && idleLoops < 150) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(2_000L)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        if (sampleIdx >= samples.size) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val s = samples[sampleIdx++]
                            val len = minOf(s.data.size, inBuf.capacity())
                            inBuf.put(s.data, 0, len)
                            decoder.queueInputBuffer(inIdx, 0, len, s.ptsUs, 0)
                        }
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(info, 2_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = decoder.outputFormat
                        if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            outSampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            outChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                    outIdx >= 0 -> {
                        idleLoops = 0
                        val outBuf = decoder.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0 && info.presentationTimeUs in startUs..endUs) {
                            if (pcmOut.size() < MAX_PCM_BYTES) {
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                val chunk = ByteArray(info.size)
                                outBuf.get(chunk)
                                pcmOut.write(chunk)
                            }
                        }
                        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        decoder.releaseOutputBuffer(outIdx, false)
                        if (eos || pcmOut.size() >= MAX_PCM_BYTES) outputDone = true
                    }
                    else -> {
                        idleLoops++
                        if (inputDone && idleLoops > 20) outputDone = true
                    }
                }
            }
            if (pcmOut.size() == 0) {
                throw IllegalStateException("decoded 0 PCM bytes for $inputMime (${samples.size} packets)")
            }
            val raw = pcmOut.toByteArray()
            val finalChannels = outChannels.coerceAtMost(2)
            val finalPcm = MediaSliceLogic.downmixToStereo16Bit(raw, raw.size, outChannels)
            return DecodedPcm(finalPcm, outSampleRate, finalChannels)
        } finally {
            if (started) runCatching { decoder.stop() }
            runCatching { decoder.release() }
        }
    }

    private fun encodePcmToM4a(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int,
        outFile: File,
    ) {
        val encFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var encoderStarted = false
        var muxerStarted = false
        var muxTrack = -1
        try {
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            encoderStarted = true

            val info = MediaCodec.BufferInfo()
            val bytesPerSec = sampleRate * channels * 2.0
            var offset = 0
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = encoder.dequeueInputBuffer(2_000L)
                    if (inIdx >= 0) {
                        val inBuf = encoder.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        if (offset >= pcm.size) {
                            val ptsUs = (offset / bytesPerSec * 1_000_000.0).toLong()
                            encoder.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val chunkSize = minOf(inBuf.capacity(), pcm.size - offset)
                            inBuf.put(pcm, offset, chunkSize)
                            val ptsUs = (offset / bytesPerSec * 1_000_000.0).toLong()
                            encoder.queueInputBuffer(inIdx, 0, chunkSize, ptsUs, 0)
                            offset += chunkSize
                        }
                    }
                }
                val outIdx = encoder.dequeueOutputBuffer(info, 2_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            muxTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = encoder.getOutputBuffer(outIdx)
                        val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (!isConfig && info.size > 0 && muxerStarted && outBuf != null) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            muxer.writeSampleData(muxTrack, outBuf, info)
                        }
                        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        encoder.releaseOutputBuffer(outIdx, false)
                        if (eos) outputDone = true
                    }
                }
            }
        } finally {
            if (encoderStarted) runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }
}
