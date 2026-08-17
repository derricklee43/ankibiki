package com.derricklee.ankidict

private val TONE_MARKS: Map<Char, CharArray> = mapOf(
    'a' to charArrayOf('a', 'ā', 'á', 'ǎ', 'à'),
    'e' to charArrayOf('e', 'ē', 'é', 'ě', 'è'),
    'i' to charArrayOf('i', 'ī', 'í', 'ǐ', 'ì'),
    'o' to charArrayOf('o', 'ō', 'ó', 'ǒ', 'ò'),
    'u' to charArrayOf('u', 'ū', 'ú', 'ǔ', 'ù'),
    'ü' to charArrayOf('ü', 'ǖ', 'ǘ', 'ǚ', 'ǜ'),
)

private val SYLLABLE_REGEX = Regex("^([a-zA-Zü]+)([1-5])$")

/**
 * CC-CEDICT's numbered-tone pinyin (e.g. "nu:3 hao3", its own ASCII-safe way of writing ü) into
 * standard diacritic form (e.g. "nǚ hǎo") -- matching how pinyin is already written everywhere
 * else in this collection (e.g. 一's own Pinyin field is "yī", not "yi1"). Tone 5 (neutral) and
 * any syllable that doesn't parse are left with just the digit dropped/untouched respectively.
 */
fun toDiacriticPinyin(numbered: String): String =
    numbered.split(" ").filter { it.isNotEmpty() }.joinToString(" ") { toneMarkSyllable(it) }

private fun toneMarkSyllable(rawSyllable: String): String {
    val syllable = rawSyllable.replace("u:", "ü")
    val match = SYLLABLE_REGEX.find(syllable) ?: return syllable
    val (letters, toneStr) = match.destructured
    val tone = toneStr.toInt()
    if (tone == 5) return letters

    // Standard placement rule: a/e always take the mark; else "ou" marks the o; else it's the
    // last vowel in the cluster (correctly handles both "iu" -> u and "ui" -> i this way).
    val lower = letters.lowercase()
    val targetIndex = when {
        'a' in lower -> lower.indexOf('a')
        'e' in lower -> lower.indexOf('e')
        "ou" in lower -> lower.indexOf('o')
        else -> lower.indexOfLast { it in TONE_MARKS }
    }
    if (targetIndex < 0) return letters

    val marks = TONE_MARKS.getValue(lower[targetIndex])
    return letters.substring(0, targetIndex) + marks[tone] + letters.substring(targetIndex + 1)
}
