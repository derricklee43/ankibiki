package com.derricklee.ankidict

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Port of Stremio's watched bitfield format (stremio-watched-bitfield index.js and
 * lib/bitfield8.js, read 2026-11-23 — the JS reference the Rust core uses).
 *
 * Serialized: "{anchorVideoId}:{anchorLength}:{base64(zlib(bytes))}" where the
 * deflate'd bytes hold one bit per videoId POSITION (LSB-first within each byte),
 * sized to anchorLength bits at write time. The anchor video id re-aligns the bitmap
 * when the videos list shifts (old episodes pruned from the front). Unfound anchor ->
 * all-unwatched (same as upstream: return a blank field). This is DECODE-only (the
 * console never writes Stremio state; S0-B); encodeForTest exists solely so JVM specs
 * can generate cross-checked fixtures with real zlib output.
 */
class WatchedBitfield private constructor(
    private val bits: ByteArray,
    private val length: Int,
    private val videoIds: List<String>,
) {

    fun get(index: Int): Boolean {
        if (index < 0 || index >= length) return false
        val byte = bits.getOrNull(index / 8) ?: return false
        return (byte.toInt() and (1 shl (index % 8))) != 0
    }

    /** upstream getVideo: unknown videoId -> false, never fatal. */
    fun getVideo(videoId: String): Boolean = get(videoIds.indexOf(videoId))

    fun watchedCount(): Int = videoIds.indices.count { get(it) }

    companion object {

        /**
         * constructAndResize equivalent: decode `serialized` and re-align it onto the
         * CURRENT videoIds list via the anchor. Any malformed input degrades to an
         * all-unwatched field (design: hint only, D5 unopinionated — a corrupt blob
         * must never block the console).
         */
        fun decode(serialized: String?, videoIds: List<String>): WatchedBitfield {
            if (serialized.isNullOrEmpty() || videoIds.isEmpty()) return blank(videoIds)
            val components = serialized.split(":")
            if (components.size < 3) return blank(videoIds)
            val serializedBuf = components.last()
            val anchorLength = components[components.size - 2].toIntOrNull() ?: return blank(videoIds)
            val anchorVideoId = components.dropLast(2).joinToString(":")
            val anchorIdx = videoIds.indexOf(anchorVideoId)
            if (anchorLength < 1 || anchorIdx < 0) return blank(videoIds)
            val offset = (anchorLength - 1) - anchorIdx
            val packed = runCatching { inflate(base64Decoder(serializedBuf)) }
                .getOrNull() ?: return blank(videoIds)
            val bits = unpack(packed)
            val out = WatchedBitfield(ByteArray(videoIds.size / 8 + 1), videoIds.size, videoIds)
            for (i in videoIds.indices) {
                val src = i + offset
                if (src in 0 until bits.size) {
                    if (bits[src]) {
                        out.bits[i / 8] = (out.bits[i / 8].toInt() or (1 shl (i % 8))).toByte()
                    }
                }
            }
            return out
        }

        private fun blank(videoIds: List<String>) =
            WatchedBitfield(ByteArray(videoIds.size / 8 + 1), videoIds.size, videoIds)

        private fun base64Decoder(s: String): ByteArray = try {
            Base64.getDecoder().decode(s)
        } catch (e: IllegalArgumentException) {
            ByteArray(0)
        }

        private fun inflate(data: ByteArray): ByteArray {
            if (data.isEmpty()) return ByteArray(0)
            val inflater = Inflater()
            try {
                inflater.setInput(data)
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                while (!inflater.finished()) {
                    val n = try {
                        inflater.inflate(buf)
                    } catch (e: DataFormatException) {
                        break
                    }
                    if (n == 0 && inflater.needsInput()) break
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            } finally {
                inflater.end()
            }
        }

        /** byte array (LSB-first) -> boolean-per-bit view */
        private fun unpack(bytes: ByteArray): BooleanArray {
            val bits = BooleanArray(bytes.size * 8)
            for (i in bits.indices) {
                bits[i] = (bytes[i / 8].toInt() and (1 shl (i % 8))) != 0
            }
            return bits
        }

        /** spec-only: build a serialized blob for a watched set (mirrors JS deflate+anchor). */
        fun encodeForTest(watchedVideoIds: Set<String>, videoIds: List<String>): String {
            if (videoIds.isEmpty()) return ""
            val bytes = ByteArray((videoIds.size + 7) / 8)
            videoIds.forEachIndexed { i, id ->
                if (id in watchedVideoIds) {
                    bytes[i / 8] = (bytes[i / 8].toInt() or (1 shl (i % 8))).toByte()
                }
            }
            val deflated = java.util.zip.Deflater().let { d ->
                d.setInput(bytes)
                d.finish()
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                while (!d.finished()) {
                    val n = d.deflate(buf)
                    out.write(buf, 0, n)
                }
                d.end()
                out.toByteArray()
            }
            val anchor = videoIds.last()
            return "$anchor:${videoIds.size}:" + Base64.getEncoder().encodeToString(deflated)
        }
    }
}
