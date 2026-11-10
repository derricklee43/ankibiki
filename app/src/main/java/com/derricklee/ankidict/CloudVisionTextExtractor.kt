package com.derricklee.ankidict

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class CloudVisionTextExtractor(
    private val languageTag: LanguageTag,
    private val apiKey: String = BuildConfig.CLOUD_VISION_API_KEY,
) : TextExtractor {

    fun isConfigured(): Boolean = apiKey.isNotBlank()

    override fun extract(
        image: PreparedImage,
        preflightDocument: OcrDocument?,
    ): OcrDocument {
        if (!isConfigured()) {
            return preflightDocument ?: OcrDocument(fullText = "", blocks = emptyList())
        }

        val base64Content = Base64.encodeToString(image.jpegBytes, Base64.NO_WRAP)
        val requestJson = buildRequestJson(base64Content, languageTag)
        val endpoint = URL("https://vision.googleapis.com/v1/images:annotate?key=$apiKey")
        val conn = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        return try {
            conn.outputStream.use { out ->
                out.write(requestJson.toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("Cloud Vision HTTP $code: $body")
            }
            parseResponseJson(body)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun buildRequestJson(base64Jpeg: String, languageTag: LanguageTag): String {
            val langHint = when (languageTag) {
                LanguageTag.CHINESE -> "zh"
                else -> "ja"
            }
            val feature = JSONObject()
                .put("type", "DOCUMENT_TEXT_DETECTION")
            val imageContext = JSONObject()
                .put("languageHints", JSONArray().put(langHint))
            val requestItem = JSONObject()
                .put("image", JSONObject().put("content", base64Jpeg))
                .put("features", JSONArray().put(feature))
                .put("imageContext", imageContext)
            return JSONObject()
                .put("requests", JSONArray().put(requestItem))
                .toString()
        }

        fun parseResponseJson(responseBody: String): OcrDocument {
            val root = JSONObject(responseBody)
            val responses = root.optJSONArray("responses") ?: return OcrDocument("", emptyList())
            if (responses.length() == 0) return OcrDocument("", emptyList())
            val firstResp = responses.getJSONObject(0)
            val fullAnnotation = firstResp.optJSONObject("fullTextAnnotation")
                ?: return OcrDocument("", emptyList())

            val fullText = fullAnnotation.optString("text", "")
            val pages = fullAnnotation.optJSONArray("pages") ?: JSONArray()
            val blocks = mutableListOf<OcrBlock>()

            for (pIdx in 0 until pages.length()) {
                val page = pages.getJSONObject(pIdx)
                val pageBlocks = page.optJSONArray("blocks") ?: continue
                for (bIdx in 0 until pageBlocks.length()) {
                    val blockObj = pageBlocks.getJSONObject(bIdx)
                    val paragraphs = blockObj.optJSONArray("paragraphs") ?: continue
                    val lines = mutableListOf<OcrLine>()
                    val blockTextBuilder = StringBuilder()

                    for (paraIdx in 0 until paragraphs.length()) {
                        val paraObj = paragraphs.getJSONObject(paraIdx)
                        val words = paraObj.optJSONArray("words") ?: continue
                        val currentLineText = StringBuilder()
                        val currentLineWordHeights = mutableListOf<Int>()

                        for (wIdx in 0 until words.length()) {
                            val wordObj = words.getJSONObject(wIdx)
                            val wordHeight = extractBoundingHeight(wordObj.optJSONObject("boundingBox"))
                            if (wordHeight != null && wordHeight > 0) {
                                currentLineWordHeights.add(wordHeight)
                            }
                            val symbols = wordObj.optJSONArray("symbols") ?: continue
                            for (sIdx in 0 until symbols.length()) {
                                val symObj = symbols.getJSONObject(sIdx)
                                currentLineText.append(symObj.optString("text", ""))
                                val breakType = symObj
                                    .optJSONObject("property")
                                    ?.optJSONObject("detectedBreak")
                                    ?.optString("type")
                                when (breakType) {
                                    "SPACE", "SURE_SPACE" -> currentLineText.append(' ')
                                    "EOL_SURE_SPACE", "LINE_BREAK" -> {
                                        val lineStr = currentLineText.toString().trim()
                                        if (lineStr.isNotEmpty()) {
                                            val height = medianInt(currentLineWordHeights)
                                            lines.add(OcrLine(text = lineStr, boundingBoxHeightPx = height))
                                            if (blockTextBuilder.isNotEmpty()) blockTextBuilder.append('\n')
                                            blockTextBuilder.append(lineStr)
                                        }
                                        currentLineText.setLength(0)
                                        currentLineWordHeights.clear()
                                    }
                                }
                            }
                        }

                        val trailing = currentLineText.toString().trim()
                        if (trailing.isNotEmpty()) {
                            val height = medianInt(currentLineWordHeights)
                            lines.add(OcrLine(text = trailing, boundingBoxHeightPx = height))
                            if (blockTextBuilder.isNotEmpty()) blockTextBuilder.append('\n')
                            blockTextBuilder.append(trailing)
                        }
                    }

                    if (lines.isNotEmpty()) {
                        blocks.add(
                            OcrBlock(
                                text = blockTextBuilder.toString(),
                                lines = lines,
                                bounds = extractBounds(blockObj.optJSONObject("boundingBox")),
                            ),
                        )
                    }
                }
            }

            if (blocks.isEmpty() && fullText.isNotBlank()) {
                val fallbackLines = fullText.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { OcrLine(text = it, boundingBoxHeightPx = null) }
                if (fallbackLines.isNotEmpty()) {
                    blocks.add(OcrBlock(text = fullText, lines = fallbackLines))
                }
            }

            return OcrDocument(
                fullText = fullText,
                blocks = blocks,
            )
        }

        private fun extractBounds(boundingBox: JSONObject?): OcrBounds? {
            val vertices = boundingBox?.optJSONArray("vertices") ?: return null
            if (vertices.length() == 0) return null
            val points = (0 until vertices.length()).map { vertices.getJSONObject(it) }
            val left = points.minOf { it.optInt("x", 0) }
            val top = points.minOf { it.optInt("y", 0) }
            val right = points.maxOf { it.optInt("x", 0) }
            val bottom = points.maxOf { it.optInt("y", 0) }
            return if (right > left && bottom > top) OcrBounds(left, top, right, bottom) else null
        }

        private fun extractBoundingHeight(boundingBox: JSONObject?): Int? {
            val vertices = boundingBox?.optJSONArray("vertices") ?: return null
            if (vertices.length() == 0) return null
            var minY = Int.MAX_VALUE
            var maxY = Int.MIN_VALUE
            for (i in 0 until vertices.length()) {
                val v = vertices.optJSONObject(i) ?: continue
                if (v.has("y")) {
                    val y = v.optInt("y")
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
            return if (maxY > minY && minY != Int.MAX_VALUE) (maxY - minY) else null
        }

        private fun medianInt(values: List<Int>): Int? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            return sorted[sorted.size / 2]
        }
    }
}

class CompositeTextExtractor(
    private val cloudExtractor: CloudVisionTextExtractor,
    private val fallbackExtractor: TextExtractor,
) : TextExtractor {
    override fun extract(
        image: PreparedImage,
        preflightDocument: OcrDocument?,
    ): OcrDocument {
        if (cloudExtractor.isConfigured()) {
            val cloudDoc = runCatching {
                cloudExtractor.extract(image, preflightDocument)
            }.getOrNull()
            if (cloudDoc != null && cloudDoc.fullText.isNotBlank()) {
                return cloudDoc
            }
        }
        return fallbackExtractor.extract(image, preflightDocument)
    }
}
