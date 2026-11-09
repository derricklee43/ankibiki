package com.derricklee.ankidict

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ichi2.anki.FlashCardsContract
import com.ichi2.anki.api.AddContentApi

enum class SemanticField {
    EXPRESSION,
    READING,
    MEANING,
    SENTENCE,
    AUDIO,
}

sealed class FieldSelector {
    data class ByName(
        val fieldName: String,
        val fallbackIndex: Int? = null,
    ) : FieldSelector()

    data class ByIndex(
        val index: Int,
    ) : FieldSelector()
}

data class AnkiDeckBinding(
    val bindingId: String,
    val accountId: String,
    val languageTag: LanguageTag,
    val itemType: ItemType,
    val deckId: Long,
    val fallbackDeckName: String,
    val modelId: Long,
    val fieldMap: Map<SemanticField, FieldSelector>,
) {
    fun resolveFieldIndex(
        semanticField: SemanticField,
        modelFieldNames: List<String>?,
    ): Int? {
        val selector = fieldMap[semanticField] ?: return null
        return when (selector) {
            is FieldSelector.ByIndex -> selector.index
            is FieldSelector.ByName -> {
                val matchedIdx = modelFieldNames
                    ?.indexOfFirst { it.equals(selector.fieldName, ignoreCase = true) }
                    ?.takeIf { it >= 0 }
                matchedIdx ?: selector.fallbackIndex
            }
        }
    }
}

data class AnkiRawNote(
    val noteId: Long,
    val modelId: Long,
    val fields: List<String>,
    val tags: String = "",
)

data class AnkiCardDraft(
    val languageTag: LanguageTag,
    val itemType: ItemType,
    val expression: String,
    val reading: String,
    val meaning: String,
    val sentences: List<String>,
)

/**
 * Layer 3: Swappable Anki account/collection backend (AnkiDroid local ContentProvider, AnkiConnect,
 * or an in-memory test double).
 */
interface AnkiAccountGateway {
    val accountId: String

    fun isAvailable(): Boolean

    fun getDeckName(deckId: Long): String?

    fun getDeckIdForNote(noteId: Long): Long?

    fun getModelList(): Map<Long, String>

    fun getModelFieldNames(modelId: Long): List<String>?

    fun findNotesByModel(modelId: Long, query: String): List<AnkiRawNote>
}

class AnkiDroidAccountGateway(
    context: Context,
    override val accountId: String = DEFAULT_ACCOUNT_ID,
) : AnkiAccountGateway {
    private val appContext = context.applicationContext
    private val addContentApi by lazy { AddContentApi(appContext) }

    override fun isAvailable(): Boolean =
        runCatching { AddContentApi.getAnkiDroidPackageName(appContext) != null }.getOrDefault(false)

    override fun getDeckName(deckId: Long): String? {
        if (!isAvailable() || deckId <= 0L) return null
        return runCatching {
            addContentApi.deckList?.get(deckId) ?: addContentApi.getDeckName(deckId)
        }.getOrNull()
    }

    override fun getDeckIdForNote(noteId: Long): Long? {
        if (!isAvailable() || noteId <= 0L) return null
        val cardsUri = Uri.withAppendedPath(
            FlashCardsContract.Note.CONTENT_URI,
            "$noteId/cards",
        )
        return runCatching {
            appContext.contentResolver.query(
                cardsUri,
                arrayOf(FlashCardsContract.Card.DECK_ID),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        }.getOrNull()
    }

    override fun getModelList(): Map<Long, String> {
        if (!isAvailable()) return emptyMap()
        return runCatching {
            addContentApi.modelList ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    override fun getModelFieldNames(modelId: Long): List<String>? {
        if (!isAvailable()) return null
        return runCatching {
            addContentApi.getFieldList(modelId)?.toList()
        }.getOrNull()
    }

    override fun findNotesByModel(modelId: Long, query: String): List<AnkiRawNote> {
        if (!isAvailable() || query.isBlank()) return emptyList()
        return runCatching {
            val selection = "flds LIKE ? AND mid = ?"
            val selectionArgs = arrayOf("%$query%", modelId.toString())
            val cursor = appContext.contentResolver.query(
                FlashCardsContract.Note.CONTENT_URI_V2,
                FlashCardsContract.Note.DEFAULT_PROJECTION,
                selection,
                selectionArgs,
                null,
            ) ?: return emptyList()

            val results = mutableListOf<AnkiRawNote>()
            cursor.use {
                val idCol = it.getColumnIndexOrThrow(FlashCardsContract.Note._ID)
                val midCol = it.getColumnIndexOrThrow(FlashCardsContract.Note.MID)
                val tagsCol = it.getColumnIndexOrThrow(FlashCardsContract.Note.TAGS)
                val fldsCol = it.getColumnIndexOrThrow(FlashCardsContract.Note.FLDS)
                while (it.moveToNext()) {
                    results.add(
                        AnkiRawNote(
                            noteId = it.getLong(idCol),
                            modelId = it.getLong(midCol),
                            fields = (it.getString(fldsCol) ?: "").split(FIELD_SEPARATOR),
                            tags = it.getString(tagsCol) ?: "",
                        ),
                    )
                }
            }
            results
        }.getOrDefault(emptyList())
    }

    companion object {
        const val DEFAULT_ACCOUNT_ID = "ankidroid-local"
        private const val FIELD_SEPARATOR = "\u001F"
    }
}

/**
 * Layer 1: Domain service for looking up existing Anki cards across swappable [AnkiAccountGateway]s
 * and [AnkiDeckBinding]s using immutable `deckId` and `modelId` keys.
 */
class AnkiCardService(
    private val gatewaysByAccountId: Map<String, AnkiAccountGateway>,
    private val bindings: List<AnkiDeckBinding>,
    private val enableDynamicModelDiscovery: Boolean = true,
) : AnkiMatchLookup {

    constructor(
        gateway: AnkiAccountGateway,
        bindings: List<AnkiDeckBinding> = defaultBindings(gateway.accountId),
        enableDynamicModelDiscovery: Boolean = true,
    ) : this(
        gatewaysByAccountId = mapOf(gateway.accountId to gateway),
        bindings = bindings,
        enableDynamicModelDiscovery = enableDynamicModelDiscovery,
    )

    override fun findMatch(
        languageTag: LanguageTag,
        itemType: ItemType,
        expression: String,
        reading: String,
    ): AnkiCardMatch? {
        if (expression.isBlank()) return null

        // 1. Search configured AnkiDeckBindings for (languageTag, itemType)
        val candidateBindings = bindings.filter {
            it.languageTag == languageTag && it.itemType == itemType
        }
        for (binding in candidateBindings) {
            val gateway = gatewaysByAccountId[binding.accountId] ?: continue
            if (!gateway.isAvailable()) continue
            val fieldNames = gateway.getModelFieldNames(binding.modelId)
            val exprIdx = binding.resolveFieldIndex(SemanticField.EXPRESSION, fieldNames) ?: continue
            val readingIdx = binding.resolveFieldIndex(SemanticField.READING, fieldNames)

            val notes = gateway.findNotesByModel(binding.modelId, expression)
            val matchedNote = notes.firstOrNull { note ->
                val rawExpr = cleanAnkiFieldValue(note.fields.getOrNull(exprIdx).orEmpty())
                if (rawExpr != expression) return@firstOrNull false
                if (readingIdx != null && reading.isNotBlank()) {
                    val rawReading = cleanAnkiFieldValue(note.fields.getOrNull(readingIdx).orEmpty())
                    rawReading.isEmpty() || rawReading.contains(reading) || reading.contains(rawReading)
                } else {
                    true
                }
            } ?: notes.firstOrNull { note ->
                cleanAnkiFieldValue(note.fields.getOrNull(exprIdx).orEmpty()) == expression
            }

            if (matchedNote != null) {
                val resolvedDeckId = if (binding.deckId > 0L) {
                    binding.deckId
                } else {
                    gateway.getDeckIdForNote(matchedNote.noteId) ?: binding.deckId
                }
                val resolvedDeckName = gateway.getDeckName(resolvedDeckId)
                    ?.takeIf { it.isNotBlank() }
                    ?: binding.fallbackDeckName

                return AnkiCardMatch(
                    accountId = gateway.accountId,
                    deckId = resolvedDeckId,
                    deckName = resolvedDeckName,
                    noteId = matchedNote.noteId,
                )
            }
        }

        // 2. Optional dynamic discovery across other models in the active gateway(s)
        if (enableDynamicModelDiscovery) {
            val boundModelIds = candidateBindings.map { it.modelId }.toSet()
            for (gateway in gatewaysByAccountId.values) {
                if (!gateway.isAvailable()) continue
                val allModels = gateway.getModelList()
                for ((modelId, modelName) in allModels) {
                    if (modelId in boundModelIds) continue
                    val fieldNames = gateway.getModelFieldNames(modelId).orEmpty()
                    val exprIdx = findDynamicExpressionFieldIndex(fieldNames) ?: continue
                    val notes = gateway.findNotesByModel(modelId, expression)
                    val matchedNote = notes.firstOrNull { note ->
                        cleanAnkiFieldValue(note.fields.getOrNull(exprIdx).orEmpty()) == expression
                    } ?: continue

                    val deckId = gateway.getDeckIdForNote(matchedNote.noteId) ?: 0L
                    val deckName = gateway.getDeckName(deckId)
                        ?.takeIf { it.isNotBlank() }
                        ?: modelName
                    return AnkiCardMatch(
                        accountId = gateway.accountId,
                        deckId = deckId,
                        deckName = deckName,
                        noteId = matchedNote.noteId,
                    )
                }
            }
        }

        return null
    }

    /**
     * Resolves the current display name for [deckId] from its [accountId] gateway, falling back to
     * [cachedDeckName] if the gateway is unavailable.
     */
    fun resolveDeckDisplayName(
        accountId: String?,
        deckId: Long?,
        cachedDeckName: String?,
    ): String? {
        if (deckId != null && deckId > 0L) {
            val gateway = if (accountId != null) {
                gatewaysByAccountId[accountId]
            } else {
                gatewaysByAccountId.values.firstOrNull()
            }
            val liveName = gateway?.getDeckName(deckId)?.takeIf { it.isNotBlank() }
            if (liveName != null) return liveName
        }
        return cachedDeckName
    }

    /**
     * Opens the existing card in the `引き` lookup view pre-populated with [detail]'s expression.
     */
    fun openExistingCard(context: Context, detail: ExtractedItemDetail) {
        val lookupIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_INITIAL_QUERY, detail.expression)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        context.startActivity(lookupIntent)
    }

    /**
     * Extension point for future Anki card creation design phase.
     */
    @Suppress("UNUSED_PARAMETER")
    fun createCard(
        draft: AnkiCardDraft,
        bindingId: String? = null,
    ): AnkiCardMatch? {
        throw UnsupportedOperationException("Anki card creation is deferred to a dedicated design phase.")
    }

    companion object {
        private val HTML_TAG_REGEX = Regex("<[^>]+>")
        private val FURIGANA_BRACKET_REGEX = Regex("\\[[^\\]]+\\]")

        private val DYNAMIC_EXPRESSION_FIELD_NAMES = setOf(
            "expression", "word", "vocab", "vocabulary", "kanji", "hanzi", "character",
            "grammar", "pattern", "target", "front", "単語", "表現", "語彙", "漢字", "文法",
        )

        fun cleanAnkiFieldValue(raw: String): String =
            raw.replace(HTML_TAG_REGEX, "")
                .replace(FURIGANA_BRACKET_REGEX, "")
                .replace("&nbsp;", " ")
                .trim()

        private fun findDynamicExpressionFieldIndex(fieldNames: List<String>): Int? {
            if (fieldNames.isEmpty()) return null
            val idx = fieldNames.indexOfFirst { it.trim().lowercase() in DYNAMIC_EXPRESSION_FIELD_NAMES }
            return if (idx >= 0) idx else 0
        }

        fun defaultBindings(
            accountId: String = AnkiDroidAccountGateway.DEFAULT_ACCOUNT_ID,
        ): List<AnkiDeckBinding> = listOf(
            AnkiDeckBinding(
                bindingId = "jp-nihongo-shark-kanji",
                accountId = accountId,
                languageTag = LanguageTag.JAPANESE,
                itemType = ItemType.WORD,
                deckId = 0L, // Resolved dynamically from matched card's DID via getDeckIdForNote
                fallbackDeckName = "🇯🇵 Kanji",
                modelId = NIHONGO_SHARK_KANJI_MODEL_ID,
                fieldMap = mapOf(
                    SemanticField.EXPRESSION to FieldSelector.ByName(
                        fieldName = "kanji",
                        fallbackIndex = CHARACTER_FIELD_INDEX[NIHONGO_SHARK_KANJI_MODEL_ID] ?: 4,
                    ),
                    SemanticField.MEANING to FieldSelector.ByName(
                        fieldName = "keyword",
                        fallbackIndex = MEANING_FIELD_INDEX[NIHONGO_SHARK_KANJI_MODEL_ID] ?: 3,
                    ),
                ),
            ),
            AnkiDeckBinding(
                bindingId = "zh-words",
                accountId = accountId,
                languageTag = LanguageTag.CHINESE,
                itemType = ItemType.WORD,
                deckId = CHINESE_WORD_DECK_ID,
                fallbackDeckName = "🇨🇳 Chinese Words",
                modelId = CHINESE_WORD_MODEL_ID,
                fieldMap = mapOf(
                    SemanticField.EXPRESSION to FieldSelector.ByName(
                        fieldName = "Word",
                        fallbackIndex = WORD_FIELD_INDEX,
                    ),
                    SemanticField.MEANING to FieldSelector.ByName(
                        fieldName = "Translation",
                        fallbackIndex = TRANSLATION_FIELD_INDEX,
                    ),
                    SemanticField.READING to FieldSelector.ByName(
                        fieldName = "Pinyin",
                        fallbackIndex = WORD_PINYIN_FIELD_INDEX,
                    ),
                ),
            ),
            AnkiDeckBinding(
                bindingId = "zh-hanzi-mnemonics",
                accountId = accountId,
                languageTag = LanguageTag.CHINESE,
                itemType = ItemType.WORD,
                deckId = CHINESE_HANZI_DECK_ID,
                fallbackDeckName = "🇨🇳 Hanzi",
                modelId = CHINESE_MNEMONICS_MODEL_ID,
                fieldMap = mapOf(
                    SemanticField.EXPRESSION to FieldSelector.ByName(
                        fieldName = "Hanzi",
                        fallbackIndex = CHARACTER_FIELD_INDEX[CHINESE_MNEMONICS_MODEL_ID] ?: 2,
                    ),
                    SemanticField.MEANING to FieldSelector.ByName(
                        fieldName = "Meaning",
                        fallbackIndex = MEANING_FIELD_INDEX[CHINESE_MNEMONICS_MODEL_ID] ?: 0,
                    ),
                    SemanticField.READING to FieldSelector.ByName(
                        fieldName = "Pinyin",
                        fallbackIndex = HANZI_PINYIN_FIELD_INDEX,
                    ),
                ),
            ),
        )
    }
}
