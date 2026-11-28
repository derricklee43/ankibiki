package com.derricklee.ankidict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM specs for retroactive user-dictionary reparsing
 * (docs/user-dictionary-reparse-design.md §6, RULED R1a–R7a):
 * pure candidate reconciliation, strict-substring IGNORE no-op,
 * manual-row protection, duplicate merge priority, USER_DICT provenance,
 * and single-IPC memoization across matched sentences.
 */
class UserDictionaryReparseTest {

    private fun occRow(
        id: Long,
        expr: String,
        type: String = ItemType.WORD.name,
        state: String = OccurrenceState.UNSEEN.name,
        createdAt: Long = 1000L,
        isManual: Boolean = false,
    ) = MiningRepository.OccurrenceRow(
        id = id,
        itemType = type,
        expression = expr,
        state = state,
        createdAt = createdAt,
        modifiedByUserFlag = false,
        isManual = isManual,
    )

    private fun cand(expr: String, reading: String? = null, type: ItemType = ItemType.WORD) =
        type to NormalizedCandidate(expr, expr, reading)

    private class LayoutWordAnalyzer(
        private val layouts: Map<String, List<Triple<String, String?, String?>>>,
    ) : WordAnalyzer {
        override fun extractWords(sentence: String): List<NormalizedCandidate> =
            extractTokens(sentence).mapNotNull { it.candidate }

        override fun extractTokens(sentence: String): List<ExtractedToken> {
            val toks = layouts[sentence] ?: return emptyList()
            var cursor = 0
            return toks.map { (surface, expr, reading) ->
                val start = cursor
                cursor += surface.length
                ExtractedToken(
                    surface,
                    start..(start + surface.length - 1),
                    expr?.let { NormalizedCandidate(it, surface, reading) },
                )
            }
        }
    }

    private class CountingAnkiLookup(
        private val match: AnkiCardMatch? = null,
    ) : AnkiMatchLookup {
        val calls = ArrayList<Triple<ItemType, String, String>>()
        override fun findMatch(
            languageTag: LanguageTag,
            itemType: ItemType,
            expression: String,
            reading: String,
        ): AnkiCardMatch? {
            calls += Triple(itemType, expression, reading)
            return match
        }
    }

    private fun testConfig(
        wordAnalyzer: WordAnalyzer,
        providers: List<DictionaryProvider>,
        ankiLookup: AnkiMatchLookup? = null,
    ): PipelineConfig = PipelineConfig(
        tag = LanguageTag.JAPANESE,
        textExtractor = object : TextExtractor {
            override fun extract(image: PreparedImage, preflightDocument: OcrDocument?) =
                error("unused in reparse")
        },
        duplicateDetector = object : DuplicatePhotoDetector {
            override fun checkDuplicate(
                extractedText: String,
                tag: LanguageTag,
                config: DuplicateDetectionConfig,
            ) = error("unused in reparse")
        },
        sentenceParser = CjkSentenceParser(),
        wordAnalyzer = wordAnalyzer,
        grammarAnalyzer = NoOpGrammarAnalyzer(),
        dictionaryProviders = providers,
        ankiMatchLookup = ankiLookup,
    )

    @Test
    fun reconcile_replacesFragmentsWithUserWord_keepsSurvivingTriage() {
        val existing = listOf(
            occRow(1L, "竈門", state = OccurrenceState.UNSEEN.name),
            occRow(2L, "炭", state = OccurrenceState.SEEN.name),
            occRow(3L, "治郎", state = OccurrenceState.UNSEEN.name),
            occRow(4L, "少年", state = OccurrenceState.SEEN.name),
        )
        val candidates = listOf(
            cand("竈門炭治郎", "かまどたんじろう"),
            cand("少年", "しょうねん"),
        )
        val diff = UserDictionaryReparse.reconcileOccurrenceRows(existing, candidates)
        assertTrue(diff.hasChanges)
        assertEquals(1, diff.keptCount)
        assertEquals(setOf(1L, 2L, 3L), diff.deleteIds.toSet())
        assertEquals(listOf("竈門炭治郎"), diff.missingCandidates.map { it.second.expression })
        assertTrue(diff.mergedDuplicateIds.isEmpty())
    }

    @Test
    fun reconcile_protectsManualRowsEvenWhenAbsentFromNewParse() {
        val existing = listOf(
            occRow(10L, "竈門", isManual = false),
            occRow(11L, "鬼殺隊", isManual = true), // user hand-added in word editor
        )
        val candidates = listOf(cand("竈門炭治郎", "かまどたんじろう"))
        val diff = UserDictionaryReparse.reconcileOccurrenceRows(existing, candidates)
        assertEquals(listOf(10L), diff.deleteIds)
        assertEquals(listOf("竈門炭治郎"), diff.missingCandidates.map { it.second.expression })
    }

    @Test
    fun reconcile_mergesDuplicatesKeepingHighestStateRank() {
        val existing = listOf(
            occRow(20L, "少年", state = OccurrenceState.UNSEEN.name, createdAt = 300L),
            occRow(21L, "少年", state = OccurrenceState.DELETED.name, createdAt = 100L),
            occRow(22L, "少年", state = OccurrenceState.SEEN.name, createdAt = 200L),
        )
        val candidates = listOf(cand("少年", "しょうねん"))
        val diff = UserDictionaryReparse.reconcileOccurrenceRows(existing, candidates)
        assertEquals(1, diff.keptCount)
        assertTrue(diff.deleteIds.isEmpty())
        assertTrue(diff.missingCandidates.isEmpty())
        // 21L (DELETED) survives; 20L and 22L merge out
        assertEquals(setOf(20L, 22L), diff.mergedDuplicateIds.toSet())
    }

    @Test
    fun planReparse_strictSubstringIgnoreProducesNoOp() {
        // 累 is registered, but the sentence contains 累計 (one legit token) ->
        // UserDictionaryWordAnalyzer ignores the strict substring -> zero changes.
        val sentence = "累計を見る"
        val base = LayoutWordAnalyzer(
            mapOf(
                sentence to listOf(
                    Triple("累計", "累計", "るいけい"),
                    Triple("を", null, null),
                    Triple("見る", "見る", "みる"),
                ),
            ),
        )
        val userWords = listOf(UserWord("累", "るい", "Rui"))
        val cfg = testConfig(
            wordAnalyzer = UserDictionaryWordAnalyzer(base, { userWords }),
            providers = listOf(UserDictionaryProvider { userWords }),
        )
        val scope = SentenceScope(mediaId = 5L, photoId = 100L, sentence = sentence, existingCreatedAt = 500L)
        val existing = listOf(
            occRow(1L, "累計"),
            occRow(2L, "見る"),
        )
        val plan = UserDictionaryReparse.planReparse(
            config = cfg,
            scopes = listOf(scope),
            rowsForScope = { existing },
            hasItem = { _, _ -> true },
            hasLink = { _, _ -> true },
        )
        assertEquals(1, plan.matchedSentences)
        assertTrue(plan.changedScopes.isEmpty())
        assertTrue(plan.insertionPlan.items.isEmpty())
        assertTrue(plan.insertionPlan.occurrences.isEmpty())
    }

    @Test
    fun planReparse_registersUserDictSourceAndDedupesAnkiIpcAcrossSentences() {
        val s1 = "竈門炭治郎が走る"
        val s2 = "竈門炭治郎の剣"
        val base = LayoutWordAnalyzer(
            mapOf(
                s1 to listOf(
                    Triple("竈門", "竈門", "かまど"),
                    Triple("炭", "炭", "すみ"),
                    Triple("治郎", "治郎", "じろう"),
                    Triple("が", null, null),
                    Triple("走る", "走る", "はしる"),
                ),
                s2 to listOf(
                    Triple("竈門", "竈門", "かまど"),
                    Triple("炭", "炭", "すみ"),
                    Triple("治郎", "治郎", "じろう"),
                    Triple("の", null, null),
                    Triple("剣", "剣", "けん"),
                ),
            ),
        )
        val userWords = listOf(UserWord("竈門炭治郎", "かまどたんじろう", "Tanjiro"))
        val anki = CountingAnkiLookup(match = null)
        val cfg = testConfig(
            wordAnalyzer = UserDictionaryWordAnalyzer(base, { userWords }),
            providers = listOf(UserDictionaryProvider { userWords }),
            ankiLookup = anki,
        )
        val scope1 = SentenceScope(mediaId = 1L, photoId = 10L, sentence = s1, existingCreatedAt = 100L)
        val scope2 = SentenceScope(mediaId = 2L, photoId = null, sentence = s2, existingCreatedAt = 200L)
        val existingBySentence = mapOf(
            s1 to listOf(occRow(1L, "竈門"), occRow(2L, "炭"), occRow(3L, "治郎"), occRow(4L, "走る")),
            s2 to listOf(occRow(5L, "竈門"), occRow(6L, "炭"), occRow(7L, "治郎"), occRow(8L, "剣")),
        )
        val plan = UserDictionaryReparse.planReparse(
            config = cfg,
            scopes = listOf(scope1, scope2),
            rowsForScope = { existingBySentence[it.sentence].orEmpty() },
            hasItem = { _, expr -> expr != "竈門炭治郎" },
            hasLink = { _, _ -> false },
        )
        assertEquals(2, plan.matchedSentences)
        assertEquals(2, plan.changedScopes.size)
        val item = plan.insertionPlan.items.single()
        assertEquals("竈門炭治郎", item.expression)
        assertEquals("かまどたんじろう", item.kana)
        assertEquals("Tanjiro", item.meaning)
        assertEquals(DefinitionSource.USER_DICT, item.source)
        // InsertionMemo ensures AnkiDroid IPC ran ONCE for 竈門炭治郎 across both sentences.
        assertEquals(1, anki.calls.size)
        assertFalse(plan.changedScopes.any { it.diff.deleteIds.size != 3 })
    }
}
