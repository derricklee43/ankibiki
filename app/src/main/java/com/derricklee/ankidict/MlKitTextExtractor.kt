package com.derricklee.ankidict

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.util.concurrent.TimeUnit

class MlKitTextExtractor(
    private val languageTag: LanguageTag,
) : TextExtractor {

    private val recognizer: TextRecognizer by lazy {
        when (languageTag) {
            LanguageTag.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        }
    }

    override fun extract(
        image: PreparedImage,
        preflightDocument: OcrDocument?,
    ): OcrDocument {
        if (preflightDocument != null && preflightDocument.fullText.isNotBlank()) {
            return preflightDocument
        }
        return runCatching {
            val inputImage = InputImage.fromBitmap(image.bitmap, 0)
            val result = Tasks.await(recognizer.process(inputImage), 15, TimeUnit.SECONDS)
            result.toOcrDocument()
        }.getOrElse {
            OcrDocument(
                fullText = "",
                blocks = emptyList(),
                note = "On-device recognition failed (${it.javaClass.simpleName})",
            )
        }
    }

    companion object {
        fun Text.toOcrDocument(): OcrDocument {
            val blocks = textBlocks.map { block ->
                val lines = block.lines.map { line ->
                    OcrLine(
                        text = line.text,
                        boundingBoxHeightPx = line.boundingBox?.height()?.takeIf { it > 0 },
                    )
                }
                OcrBlock(
                    text = block.text,
                    lines = lines,
                    bounds = block.boundingBox?.let { box ->
                        OcrBounds(box.left, box.top, box.right, box.bottom)
                    },
                )
            }
            return OcrDocument(
                fullText = text,
                blocks = blocks,
            )
        }
    }
}
