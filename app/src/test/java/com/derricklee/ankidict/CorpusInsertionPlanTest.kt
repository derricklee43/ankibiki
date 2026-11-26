package com.derricklee.ankidict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM specs for CorpusInsertion.planInsertions (device freeze 2026-11-26): the Stremio
 * engine used to fire the per-candidate AnkiDroid ContentProvider round-trips and
 * dictionary scans INSIDE each cue-batch transaction, holding the single pooled
 * mining.db connection 60+s per batch (dumpsys dbinfo: TRANSACTION-EXCLUSIVE took
 * 60560ms; all SQL summed 366ms). Planning now happens outside any transaction, with a
 * per-episode memo; these specs pin the plan semantics (dedupe, IPC counting,
 * late-match adoption) that make apply-then-commit safe.
 */
class CorpusInsertionPlanTest {

    private fun cand(expr: String, reading: String? = null) =
        Pair(ItemType.WORD, NormalizedCandidate(expr, expr, reading))

    private class CountingLookup(
        private val byKey: (ItemType, String, String) -> AnkiCardMatch?,
    ) : AnkiMatchLookup {
        val calls = ArrayList<Triple<ItemType, String, String>>()
        override fun findMatch(
            languageTag: LanguageTag,
            itemType: ItemType,
            expression: String,
            reading: String,
        ): AnkiCardMatch? {
            calls += Triple(itemType, expression, reading)
            return byKey(itemType, expression, reading)
        }
    }

    private class FakeDict(private val table: Map<String, DictionaryMetadata>) : DictionaryProvider {
        var calls = 0
        override fun lookup(
            expression: String,
            itemType: ItemType,
            readingHint: String?,
        ): DictionaryMetadata? {
            calls++
            return table[expression]
        }
    }

    private class NoGrammar : GrammarAnalyzer {
        override fun extractGrammar(sentence: String) = emptyList<NormalizedCandidate>()
    }

    private fun config(
        dict: FakeDict,
        lookup: CountingLookup,
    ): PipelineConfig = PipelineConfig(
        tag = LanguageTag.JAPANESE,
        textExtractor = object : TextExtractor {
            override fun extract(image: PreparedImage, preflightDocument: OcrDocument?) =
                error("unused in plan")
        },
        duplicateDetector = object : DuplicatePhotoDetector {
            override fun checkDuplicate(
                extractedText: String,
                tag: LanguageTag,
                config: DuplicateDetectionConfig,
            ) = error("unused in plan")
        },
        sentenceParser = object : SentenceParser {
            override fun parseSentences(document: OcrDocument) = error("unused in plan")
        },
        wordAnalyzer = object : WordAnalyzer {
            override fun extractWords(sentence: String) = error("unused in plan")
        },
        grammarAnalyzer = NoGrammar(),
        dictionaryProviders = listOf(dict),
        ankiMatchLookup = lookup,
    )

    private val match = AnkiCardMatch("ankidroid-local", 7L, "JP", 1234L)

    @Test
    fun newExpressionsDedupeToOneUpsertWithOneIpcEach() {
        val dict = FakeDict(
            mapOf("学校" to DictionaryMetadata(kanji = "学校", kana = "がっこう", meaning = "school"))
        )
        val lookup = CountingLookup { _, expr, _ -> if (expr == "学校") match else null }
        val plan = CorpusInsertion.planInsertions(
            config(dict, lookup),
            listOf(
                "学校へ行く" to listOf(cand("学校")),
                "学校の前の" to listOf(cand("学校"), cand("前の")),
            ),
            hasItem = { _, _ -> false },
            hasLink = { _, _ -> false },
        )
        assertEquals(2, plan.items.size)
        assertEquals(3, plan.occurrences.size)
        assertEquals(
            CorpusInsertion.ItemUpsert(
                ItemType.WORD, "学校", "学校", "がっこう", "school", null, null, match,
            ),
            plan.items.first { it.expression == "学校" },
        )
        // dictionary looked up once per unique expression, NOT once per sighting
        assertEquals(2, dict.calls)
        // anki probe once per unique (type, expr, resolvedKana); negative result cached
        assertEquals(2, lookup.calls.size)
        assertEquals(
            CorpusInsertion.ItemUpsert(
                // 前 is an ideograph -> the LEGACY path sets kanji = expression when
                // the dictionary has no entry; plan mirrors it verbatim.
                ItemType.WORD, "前の", "前の", "前の", "—", null, null, null,
            ),
            plan.items.first { it.expression == "前の" },
        )
    }

    @Test
    fun existingLinkedItemsSkipAllSlowLookups() {
        val dict = FakeDict(emptyMap())
        val lookup = CountingLookup { _, _, _ -> match }
        val plan = CorpusInsertion.planInsertions(
            config(dict, lookup),
            listOf("学校へ" to listOf(cand("学校"))),
            hasItem = { _, _ -> true },
            hasLink = { _, _ -> true },
        )
        assertTrue(plan.items.isEmpty())
        assertTrue(plan.links.isEmpty())
        assertEquals(1, plan.occurrences.size)
        assertEquals(0, dict.calls)
        assertEquals(0, lookup.calls.size)
    }

    @Test
    fun existingUnlinkedItemsProduceOneDedupedLateLink() {
        val dict = FakeDict(emptyMap())
        val lookup = CountingLookup { _, expr, reading ->
            if (expr == "学校" && reading == "がっこう") match else null
        }
        val plan = CorpusInsertion.planInsertions(
            config(dict, lookup),
            listOf(
                "学校へ" to listOf(cand("学校", "がっこう")),
                "この学校" to listOf(cand("学校", "がっこう")),
            ),
            hasItem = { _, _ -> true },
            hasLink = { _, _ -> false },
        )
        assertEquals(1, plan.links.size)
        assertSame(match, plan.links.first().match)
        assertEquals(ItemType.WORD to "学校", plan.links.first().let { it.itemType to it.expression })
        // memo + dedupe: the second sighting must not re-probe Anki
        assertEquals(1, lookup.calls.size)
        assertEquals(2, plan.occurrences.size)
    }

    @Test
    fun unlinkedPlanEntryAdoptsLateMatchFromDivergentReading() {
        val dict = FakeDict(emptyMap())
        val lookup = CountingLookup { _, expr, reading ->
            if (expr == "簡単" && reading == "かんたん") match else null
        }
        val plan = CorpusInsertion.planInsertions(
            config(dict, lookup),
            listOf(
                "カンタンだ" to listOf(cand("簡単", "カンタン")),
                "簡単な問題" to listOf(cand("簡単", "かんたん")),
            ),
            hasItem = { _, _ -> false },
            hasLink = { _, _ -> false },
        )
        val item = plan.items.single { it.expression == "簡単" }
        assertSame("second sighting's reading found the card -> plan adopts it", match, item.ankiMatch)
    }

    @Test
    fun memoIsSharedAcrossBatchesOfOneJob() {
        val dict = FakeDict(mapOf("学校" to DictionaryMetadata("学校", "がっこう", "school")))
        val lookup = CountingLookup { _, expr, _ -> if (expr == "学校") match else null }
        val memo = CorpusInsertion.InsertionMemo()
        val cfg = config(dict, lookup)
        CorpusInsertion.planInsertions(
            cfg, listOf("学校へ" to listOf(cand("学校", "がっこう"))),
            hasItem = { _, _ -> false }, hasLink = { _, _ -> false }, memo = memo,
        )
        val second = CorpusInsertion.planInsertions(
            cfg, listOf("学校の前の学校" to listOf(cand("学校", "がっこう"), cand("前"))),
            hasItem = { _, _ -> false }, hasLink = { _, _ -> false }, memo = memo,
        )
        // 学校 already resolved -> only 前 sees the dictionary/Anki path in batch two
        assertEquals(1, second.items.count { it.expression == "学校" })
        assertEquals(2, dict.calls)
        assertEquals(2, lookup.calls.size)
        assertTrue(memo.dictionaries.containsKey(ItemType.WORD to "前"))
    }
}
