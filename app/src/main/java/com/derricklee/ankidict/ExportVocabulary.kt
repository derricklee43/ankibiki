package com.derricklee.ankidict

import org.json.JSONArray
import org.json.JSONObject

/**
 * The wizard's export vocabulary — the stable contract between AnkiBiki internals and saved
 * card templates (docs/card-creation-wizard-plan.md). Templates persist these labels, NEVER
 * internal column names; when internal models change, update DraftAdapter, not the enum,
 * and the contract test in testDebugUnitTest guards every mapping.
 */
enum class ExportField(val label: String) {
    TERM("term"),
    READING("reading"),
    MEANING("meaning"),
    SENTENCE("sentence"),
    SENTENCE_WORDS("sentence_words"),
    PHOTO("photo"),
    LANGUAGE("language"),
    LEVEL("level"),
    RANK("rank"),
    ;

    companion object {
        fun fromLabel(label: String?): ExportField? = entries.firstOrNull { it.label == label }
        val labels: List<String> get() = entries.map { it.label }
    }
}

/**
 * The reviewed draft handed to the wizard: one item + one explicitly selected sentence
 * (Sentence 1/2/3 dropdown, Q8). JSON round-trips through activity intents and is also
 * what preview/write screens compose from — the draft is the wizard's only data currency.
 */
data class CardDraft(
    val languageTag: String,
    val itemType: String,
    val expression: String,
    val reading: String,
    val meaning: String,
    val sentence: String,
    val sentenceWords: List<String>,
    val photoUri: String?,
    val languageLabel: String,
    val levelBadge: String?,
    val corpusRank: Int?,
) {
    fun toJson(): String = JSONObject().apply {
        put("languageTag", languageTag)
        put("itemType", itemType)
        put("expression", expression)
        put("reading", reading)
        put("meaning", meaning)
        put("sentence", sentence)
        put("sentenceWords", JSONArray(sentenceWords))
        put("photoUri", photoUri ?: JSONObject.NULL)
        put("languageLabel", languageLabel)
        put("levelBadge", levelBadge ?: JSONObject.NULL)
        put("corpusRank", corpusRank ?: JSONObject.NULL)
    }.toString()

    companion object {
        fun fromJson(json: String): CardDraft? = runCatching {
            val o = JSONObject(json)
            CardDraft(
                languageTag = o.getString("languageTag"),
                itemType = o.getString("itemType"),
                expression = o.getString("expression"),
                reading = o.getString("reading"),
                meaning = o.getString("meaning"),
                sentence = o.getString("sentence"),
                sentenceWords = o.getJSONArray("sentenceWords").let { a ->
                    (0 until a.length()).map { a.getString(it) }
                },
                photoUri = if (o.isNull("photoUri")) null else o.getString("photoUri"),
                languageLabel = o.getString("languageLabel"),
                levelBadge = if (o.isNull("levelBadge")) null else o.getString("levelBadge"),
                corpusRank = if (o.isNull("corpusRank")) null else o.getInt("corpusRank"),
            )
        }.getOrNull()

        /** Build from live data; [sentenceWords] comes from the repository's candidate query. */
        fun of(detail: ExtractedItemDetail, sentence: String, sentenceWords: List<String>): CardDraft =
            CardDraft(
                languageTag = detail.languageTag.name,
                itemType = detail.itemType.name,
                expression = detail.expression,
                reading = detail.kana.ifBlank { detail.kanji },
                meaning = detail.meaning,
                sentence = sentence,
                sentenceWords = sentenceWords,
                photoUri = sentencePhotoUri(detail, sentence),
                languageLabel = when (detail.languageTag) {
                    LanguageTag.JAPANESE -> "Japanese"
                    LanguageTag.CHINESE -> "Chinese"
                    else -> detail.languageTag.name
                },
                levelBadge = detail.levelBadge,
                corpusRank = detail.corpusRank,
            )

        private fun sentencePhotoUri(detail: ExtractedItemDetail, sentence: String): String? {
            val photoId = detail.matchedSentences.firstOrNull { it.sentence == sentence }?.photoId
                ?: detail.photos.firstOrNull()?.mediaStoreId ?: return null
            return detail.photos.firstOrNull { it.mediaStoreId == photoId }?.contentUri
        }
    }
}

/** Draft -> export values. The one tested seam between internal models and templates. */
object DraftAdapter {
    const val WORDS_JOINER = "、"

    fun resolve(draft: CardDraft): Map<ExportField, String> = mapOf(
        ExportField.TERM to draft.expression,
        ExportField.READING to draft.reading,
        ExportField.MEANING to draft.meaning,
        ExportField.SENTENCE to draft.sentence,
        ExportField.SENTENCE_WORDS to draft.sentenceWords.joinToString(WORDS_JOINER),
        // photo is materialized at write time (media import), not as plain text:
        ExportField.PHOTO to "",
        ExportField.LANGUAGE to draft.languageLabel,
        ExportField.LEVEL to draft.levelBadge.orEmpty(),
        ExportField.RANK to draft.corpusRank?.toString().orEmpty(),
    )

    /** Compose one Anki field: prefix + value + suffix, or empty for unbound rows (Q7). */
    fun compose(value: String, prefix: String, suffix: String): String = prefix + value + suffix
}

/** One persisted template row: which export field feeds which Anki ordinal (null = unbound). */
data class CardTemplateMapping(val ordinal: Int, val fieldLabel: String?) {
    companion object {
        fun jsonOf(rows: List<CardTemplateMapping>): String = JSONArray().apply {
            rows.forEach { put(JSONObject().put("ord", it.ordinal).put("field", it.fieldLabel ?: JSONObject.NULL)) }
        }.toString()

        fun parse(json: String?): List<CardTemplateMapping> = runCatching {
            val a = JSONArray(json ?: return emptyList())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                CardTemplateMapping(o.getInt("ord"), if (o.isNull("field")) null else o.getString("field"))
            }
        }.getOrDefault(emptyList())
    }
}
