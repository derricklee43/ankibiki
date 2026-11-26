package com.derricklee.ankidict

/**
 * User dictionary (docs/user-dictionary-design.md, RULED 2026-11-26): words —
 * primarily names — the user registers so parsing extracts them whole and
 * registration carries the user's own gloss.
 *
 * Two seams, no engine surgery:
 *  - [UserDictionaryWordAnalyzer] decorates the jpn analyzer with span-forcing
 *    (§3.2, D1a): a user surface found in a sentence becomes exactly one
 *    candidate with the user's reading; fragments whose token span falls inside
 *    the match are dropped; a user word that only ever occurs strictly inside a
 *    legitimate token never fires (names must not fragment other words);
 *  - [UserDictionaryProvider] prepends the dictionaryProviders list so a
 *    registered expression registers with the user's meaning (source
 *    USER_DICT, docs/definition-model-design.md §3.1).
 *
 * Both take a LIVE supplier (the repo query) rather than a snapshot — a name
 * added in the same session applies to the next sentence parsed, and
 * MiningPipelineHolder's process-wide configs stay valid because the supplier
 * closes over the repository, not its data. The supplier read is wrapped: a
 * partial harness DB without user_words degrades to no-op (schema-versioning
 * rule 8).
 */

data class UserWord(
    val expression: String,
    val readingKana: String?,
    val meaning: String,
    val notes: String? = null,
)

class UserDictionaryWordAnalyzer(
    private val base: WordAnalyzer,
    private val userWords: () -> List<UserWord>,
) : WordAnalyzer {

    override fun extractWords(sentence: String): List<NormalizedCandidate> {
        val words = runCatching { userWords() }.getOrDefault(emptyList())
            .filter { it.expression.isNotBlank() }
        val baseOut = base.extractWords(sentence)
        if (words.isEmpty() || sentence.isBlank()) return baseOut

        val tokens = base.extractTokens(sentence)
        // candidate expression -> token span. Kuromoji dedups candidate
        // expressions per sentence, so expression is a safe join key; unknown
        // spans (default extractTokens) degrade conservatively: containment
        // unprovable -> no IGNORE, no fragment suppression.
        val spansByExpression = HashMap<String, IntRange?>()
        for (token in tokens) {
            val expr = token.candidate?.expression ?: continue
            spansByExpression.putIfAbsent(expr, token.range)
        }
        val tokenRanges = tokens.mapNotNull { it.range }

        // slots preserves base order; user additions take the slot of their
        // earliest suppressed fragment (else appended)
        val slots = baseOut.toMutableList()
        var appended = ArrayList<NormalizedCandidate>()
        val placed = HashSet<String>()
        // spans already claimed by a longer user entry: a shorter registered name
        // occurring ONLY inside one is the longer name's letters, not its own hit
        val coveredSpans = ArrayList<IntRange>()

        // longest first: overlapping user names resolve to the more specific entry
        for (word in words.sortedByDescending { it.expression.length }) {
            if (word.expression in placed) continue
            val forceSpans = ArrayList<IntRange>()
            var from = 0
            while (true) {
                val at = sentence.indexOf(word.expression, from)
                if (at < 0) break
                from = at + 1
                val span = at..(at + word.expression.length - 1)
                if (coveredSpans.any { span.first >= it.first && span.last <= it.last }) continue
                val host = tokenRanges.firstOrNull { span.first >= it.first && span.last <= it.last }
                if (host != null && (host.first != span.first || host.last != span.last)) continue
                forceSpans.add(span) // token-equal or crossing >=2 tokens (or unprovable)
            }
            if (forceSpans.isEmpty()) continue
            coveredSpans += forceSpans

            val userCandidate = NormalizedCandidate(
                expression = word.expression,
                surfaceForm = sentence.substring(forceSpans.first()),
                readingKana = word.readingKana,
            )
            val existingIndex = slots.indexOfFirst { it.expression == word.expression }
            if (existingIndex >= 0) {
                slots[existingIndex] = userCandidate // token-equal replace (rule 2)
            } else {
                // fragment suppression (rule 3): candidates fully inside a forced
                // span drop; the first dropped slot position keeps sentence order
                val dropIndices = slots.withIndex()
                    .filter { (_, cand) ->
                        cand.expression != word.expression &&
                            spansByExpression[cand.expression]?.let { cs ->
                                forceSpans.any { fs -> cs.first >= fs.first && cs.last <= fs.last }
                            } == true
                    }
                    .map { it.index }
                if (dropIndices.isNotEmpty()) {
                    dropIndices.reversed().forEach { slots.removeAt(it) }
                    slots.add(dropIndices.first(), userCandidate)
                } else {
                    appended.add(userCandidate)
                }
            }
            placed += word.expression
        }
        return slots + appended
    }
}

class UserDictionaryProvider(
    private val userWords: () -> List<UserWord>,
) : DictionaryProvider {

    override fun lookup(
        expression: String,
        itemType: ItemType,
        readingHint: String?,
    ): DictionaryMetadata? {
        if (itemType != ItemType.WORD) return null // names are words; grammar excluded (§4 non-goal)
        val word = runCatching { userWords() }.getOrDefault(emptyList())
            .firstOrNull { it.expression == expression } ?: return null
        val hasKanji = expression.any { MlKitImageTagger.isCjkIdeograph(it) }
        return DictionaryMetadata(
            kanji = if (hasKanji) expression else "",
            kana = word.readingKana ?: expression,
            meaning = word.meaning,
            source = DefinitionSource.USER_DICT,
        )
    }
}
