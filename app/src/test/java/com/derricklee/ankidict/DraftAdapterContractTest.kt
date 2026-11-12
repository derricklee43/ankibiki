package com.derricklee.ankidict

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contract gate for the wizard's export vocabulary (docs/card-creation-wizard-plan.md):
 * every ExportField's value extraction is pinned here. Internal model changes must pass
 * through DraftAdapter — if a mapping changes, this test must change deliberately, never
 * silently, because saved user templates reference these labels.
 */
class DraftAdapterContractTest {
    private val draft = CardDraft(
        languageTag = LanguageTag.JAPANESE.name,
        itemType = ItemType.WORD.name,
        expression = "溺れる",
        reading = "おぼれる",
        meaning = "to drown",
        sentence = "彼は湖で溺れた。",
        sentenceWords = listOf("彼", "湖", "溺れる"),
        photoUri = "content://media/external/images/media/42",
        languageLabel = "Japanese",
        levelBadge = "N2",
        corpusRank = 812,
    )

    @Test
    fun everyField_resolvesPerContract() {
        val v = DraftAdapter.resolve(draft)
        assertEquals("溺れる", v[ExportField.TERM])
        assertEquals("おぼれる", v[ExportField.READING])
        assertEquals("to drown", v[ExportField.MEANING])
        assertEquals("彼は湖で溺れた。", v[ExportField.SENTENCE])
        assertEquals("彼、湖、溺れる", v[ExportField.SENTENCE_WORDS])
        assertEquals("", v[ExportField.PHOTO]) // materialized at write time, never plain text
        assertEquals("Japanese", v[ExportField.LANGUAGE])
        assertEquals("N2", v[ExportField.LEVEL])
        assertEquals("812", v[ExportField.RANK])
    }

    @Test
    fun enumLabels_areStableNames() {
        // These strings are persisted in card_templates; renaming one breaks saved templates.
        assertEquals(
            listOf("term", "reading", "meaning", "sentence", "sentence_words",
                "photo", "language", "level", "rank"),
            ExportField.entries.map { it.label },
        )
        assertEquals(ExportField.TERM, ExportField.fromLabel("term"))
        assertEquals(null, ExportField.fromLabel("nonsense"))
        assertEquals(null, ExportField.fromLabel(null))
    }

    @Test
    fun compose_wrapsValueWithAffixes() {
        assertEquals("【溺れる】", DraftAdapter.compose("溺れる", "【", "】"))
        assertEquals("溺れる", DraftAdapter.compose("溺れる", "", ""))
        assertEquals("", DraftAdapter.compose("", "", ""))
    }

    @Test
    fun missingOptionalValues_resolveEmpty() {
        val sparse = draft.copy(levelBadge = null, corpusRank = null, photoUri = null, sentenceWords = emptyList())
        val v = DraftAdapter.resolve(sparse)
        assertEquals("", v[ExportField.LEVEL])
        assertEquals("", v[ExportField.RANK])
        assertEquals("", v[ExportField.SENTENCE_WORDS])
    }

    @Test
    fun draftJson_roundTrips() {
        val back = CardDraft.fromJson(draft.toJson())
        assertEquals(draft, back)
        assertEquals(null, CardDraft.fromJson("{not json"))
    }
}
