package com.derricklee.ankidict

import java.text.Normalizer

/**
 * Sort keys for the Words (definition) tab — user spec 2026-11-13: "alphabetical according to
 * Japanese alphabet standards" = kana gojūon order; Chinese items sort by toneless pinyin
 * alphabet. Hand-rolled instead of java.text.Collator(Locale.JAPAN) because desktop-JDK and
 * Android-ICU collation tables differ at the edges — this table is deterministic on both, so
 * the JVM unit tests pin exactly what the device shows.
 *
 * Documented order choices (JIS X 4081 音順 conventions, simplified):
 * - plain columns sort before their voiced partners at the same slot (か < が < き < ぎ …),
 *   and は-row before ば before ぱ (は < ば < ぱ < ま);
 * - ぢ/づ sort as the voiced partners of ち/つ (their modern dictionary slots);
 * - small kana sort immediately after their full-size counterpart (あ < ぁ; and きゃ as
 *   きの-size-slot: や < ゃ so きのや precedes きのゃ… i.e. yōon fold to their ya-slot);
 * - katakana is weight-equivalent to the same hiragana reading;
 * - the prolonged-sound mark ー comes after the whole 五十音+ん block;
 * - spaces in a reading are ignored;
 * - anything unmappable (kanji fallback for a missing reading, rare kana, symbols) sorts
 *   after the kana block, by code point; final tie-break is always the raw expression.
 */
object ItemOrdering {

    fun compare(
        languageTag: LanguageTag,
        aKana: String?,
        aExpression: String,
        bKana: String?,
        bExpression: String,
    ): Int {
        val primary = when (languageTag) {
            LanguageTag.CHINESE -> comparePinyin(aKana, aExpression, bKana, bExpression)
            else -> compareKeys(kanaKey(aKana, aExpression), kanaKey(bKana, bExpression))
        }
        if (primary != 0) return primary
        return aExpression.compareTo(bExpression)
    }

    /** Reading if stored, else the expression itself (kanji then lands in the tail bucket). */
    private fun kanaKey(kana: String?, expression: String): List<Int> =
        toKanaKeys(kana?.takeIf { it.isNotBlank() } ?: expression)

    // ---- kana gojūon ----

    private const val UNKNOWN_BASE = 1000

    // rank = main*12 + voiced*4 + small — compare lexicographically over the reading.
    // Plain columns あ..お slot 0..45*12; voiced +4 (same slot), half-voiced +8, small +1.
    private val KANA_RANKS: Map<Char, Int> = buildMap {
        val plain = listOf(
            'あ', 'い', 'う', 'え', 'お',
            'か', 'き', 'く', 'け', 'こ',
            'さ', 'し', 'す', 'せ', 'そ',
            'た', 'ち', 'つ', 'て', 'と',
            'な', 'に', 'ぬ', 'ね', 'の',
            'は', 'ひ', 'ふ', 'へ', 'ほ',
            'ま', 'み', 'む', 'め', 'も',
            'や', 'ゆ', 'よ',
            'ら', 'り', 'る', 'れ', 'ろ',
            'わ', 'を',
            'ん',
        )
        plain.forEachIndexed { i, c -> put(c, i * 12) }
        put('ー', plain.size * 12)
        // Voiced partners sort inside their plain slot.
        val dakuten = mapOf(
            'が' to 'か', 'ぎ' to 'き', 'ぐ' to 'く', 'げ' to 'け', 'ご' to 'こ',
            'ざ' to 'さ', 'じ' to 'し', 'ず' to 'す', 'ぜ' to 'せ', 'ぞ' to 'そ',
            'だ' to 'た', 'ぢ' to 'ち', 'づ' to 'つ', 'で' to 'て', 'ど' to 'と',
            'ば' to 'は', 'び' to 'ひ', 'ぶ' to 'ふ', 'べ' to 'へ', 'ぼ' to 'ほ',
            'ゔ' to 'う',
        )
        dakuten.forEach { (d, base) -> put(d, get(base)!! + 4) }
        mapOf('ぱ' to 'は', 'ぴ' to 'ひ', 'ぷ' to 'ふ', 'ぺ' to 'へ', 'ぽ' to 'ほ')
            .forEach { (h, base) -> put(h, get(base)!! + 8) }
        // Small kana land one step after their full-size counterpart.
        mapOf(
            'ぁ' to 'あ', 'ぃ' to 'い', 'ぅ' to 'う', 'ぇ' to 'え', 'ぉ' to 'お',
            'ゃ' to 'や', 'ゅ' to 'ゆ', 'ょ' to 'よ', 'ゎ' to 'わ', 'っ' to 'つ',
        ).forEach { (s, base) -> put(s, get(base)!! + 1) }
    }

    private fun rank(c: Char): Int? =
        KANA_RANKS[c]
            // Katakana folds to the same weight as its hiragana reading.
            ?: if (c in '\u30A1'..'\u30F6') KANA_RANKS[(c.code - 0x60).toChar()] else null

    private fun toKanaKeys(reading: String): List<Int> =
        Normalizer.normalize(reading, Normalizer.Form.NFC)
            .filter { !it.isWhitespace() }
            .map { rank(it) ?: (UNKNOWN_BASE + it.code) }

    private fun compareKeys(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            if (a[i] != b[i]) return a[i].compareTo(b[i])
        }
        return a.size.compareTo(b.size)
    }

    // ---- pinyin ----

    /**
     * Toneless-alphabetic pinyin order (CC-CEDICT dictionary convention): the syllable letters
     * decide, the tone mark only breaks exact-letter ties. ü folds to v-equivalent 'u' spelling
     * family used by the romaji matcher (v->u), keeping the tab order searchable-consistent.
     */
    private fun pinyinKey(kana: String?, expression: String): String {
        val raw = kana?.takeIf { it.isNotBlank() } ?: expression
        return Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
    }

    private fun comparePinyin(
        aKana: String?, aExpression: String,
        bKana: String?, bExpression: String,
    ): Int {
        val keyCmp = pinyinKey(aKana, aExpression).compareTo(pinyinKey(bKana, bExpression))
        if (keyCmp != 0) return keyCmp
        val rawCmp = (aKana ?: aExpression).compareTo(bKana ?: bExpression)
        if (rawCmp != 0) return rawCmp
        return 0 // expression tie-break applied by compare()
    }
}
