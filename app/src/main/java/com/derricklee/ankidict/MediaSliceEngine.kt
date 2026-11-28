package com.derricklee.ankidict

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Pure, JVM-testable selection, windowing, and PCM helpers for Stage 3 media slicing
 * (docs/stremio-auto-scrub-parked.md §0.3 & §C; execution backed by [Media3SliceEngine]).
 */
object MediaSliceLogic {

    data class SliceWindow(
        val startMs: Long,
        val endMs: Long,
        val frameAtMs: Long,
    )

    private val LANG_ALIASES: Map<String, Set<String>> = mapOf(
        "jpn" to setOf("jpn", "ja", "jap", "japanese"),
        "ja" to setOf("jpn", "ja", "jap", "japanese"),
        "zho" to setOf("zho", "zh", "chi", "chn", "cmn", "chinese"),
        "zh" to setOf("zho", "zh", "chi", "chn", "cmn", "chinese"),
        "eng" to setOf("eng", "en", "english"),
        "en" to setOf("eng", "en", "english"),
    )

    fun languagesMatch(trackLang: String?, preferredLang: String?): Boolean {
        val t = trackLang?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "und" } ?: return false
        val p = preferredLang?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        if (t == p || t.startsWith(p) || p.startsWith(t)) return true
        val aliases = LANG_ALIASES[p] ?: return false
        return t in aliases
    }

    /** Priority: explicit 0-based audio trackIndex > language match > default track > first audio track. */
    fun selectAudioTrack(
        tracks: List<AudioTrackInfo>,
        preferredTrackIndex: Int?,
        preferredLang: String?,
    ): AudioTrackInfo? {
        if (tracks.isEmpty()) return null
        if (preferredTrackIndex != null) {
            tracks.firstOrNull { it.trackIndex == preferredTrackIndex }?.let { return it }
        }
        tracks.firstOrNull { languagesMatch(it.language, preferredLang) }?.let { return it }
        tracks.firstOrNull { it.isDefault }?.let { return it }
        return tracks.first()
    }

    /**
     * Applies [offsetMs], adds small speech-boundary padding, and anchors [SliceWindow.frameAtMs]
     * at the shifted cue midpoint (guaranteed inside the cue's shown window per the 2026-11-23 ruling).
     */
    fun computeSliceWindow(
        cueStartMs: Long,
        cueEndMs: Long,
        offsetMs: Int = 0,
        padBeforeMs: Long = 150L,
        padAfterMs: Long = 200L,
    ): SliceWindow {
        val shiftedStart = (cueStartMs + offsetMs).coerceAtLeast(0L)
        val shiftedEnd = (cueEndMs + offsetMs).coerceAtLeast(shiftedStart + 200L)
        val frameAt = shiftedStart + (shiftedEnd - shiftedStart) / 2L
        val audioStart = (shiftedStart - padBeforeMs).coerceAtLeast(0L)
        val audioEnd = (shiftedEnd + padAfterMs).coerceAtLeast(audioStart + 500L)
        return SliceWindow(startMs = audioStart, endMs = audioEnd, frameAtMs = frameAt)
    }

    /** Downmixes >2ch 16-bit LE PCM (e.g. 5.1 anime tracks) to stereo so AAC-LC stays universally playable. */
    fun downmixToStereo16Bit(pcm: ByteArray, length: Int, channels: Int): ByteArray {
        if (channels <= 2) return if (length == pcm.size) pcm else pcm.copyOf(length)
        val bytesPerFrame = channels * 2
        val frames = length / bytesPerFrame
        val out = ByteArray(frames * 4)
        val inBuf = ByteBuffer.wrap(pcm, 0, frames * bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)
        val outBuf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until frames) {
            val samples = ShortArray(channels) { inBuf.short }
            val (l, r) = if (channels == 6) {
                // ITU 5.1 layout: L(0), R(1), C(2), LFE(3), Ls(4), Rs(5)
                val c = samples[2] * 0.707
                val left = (samples[0] + c + samples[4] * 0.5).roundToInt()
                val right = (samples[1] + c + samples[5] * 0.5).roundToInt()
                left to right
            } else {
                var lSum = 0
                var rSum = 0
                var lCnt = 0
                var rCnt = 0
                for (ch in 0 until channels) {
                    if (ch % 2 == 0) { lSum += samples[ch]; lCnt++ }
                    else { rSum += samples[ch]; rCnt++ }
                }
                (lSum / lCnt.coerceAtLeast(1)) to (rSum / rCnt.coerceAtLeast(1))
            }
            outBuf.putShort(l.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            outBuf.putShort(r.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
        return out
    }
}
