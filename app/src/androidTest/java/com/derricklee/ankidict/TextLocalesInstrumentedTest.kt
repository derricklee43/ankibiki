package com.derricklee.ankidict

import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** TextLocales powers the system Translate chip: the view's declared locale must land. */
@RunWith(AndroidJUnit4::class)
class TextLocalesInstrumentedTest {

    @Test
    fun apply_setsViewLocaleList_soSystemTranslateCanOffer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tv = TextView(context).apply { text = "海賊は恐ろしい。" }
        TextLocales.apply(tv, LanguageTag.JAPANESE)
        assertEquals(1, tv.textLocales.size())
        assertEquals("ja", tv.textLocales[0].language)

        TextLocales.apply(tv, LanguageTag.CHINESE)
        assertEquals("zh", tv.textLocales[0].language)

        // NA leaves the view UNTOUCHED (no bogus locale). Assert immutability against the
        // view's own default list, not a hardcoded size: a multilingual device (Pixel with
        // several system languages) legitimately defaults to >1 fallback locales, which the
        // old `size() <= 1` fixture wrongly flagged (emulator passed — single locale).
        val plain = TextView(context)
        fun langs(v: TextView) = (0 until v.textLocales.size()).map { v.textLocales[it].language }
        val before = langs(plain)
        TextLocales.apply(plain, LanguageTag.NA)
        assertEquals("NA must not rewrite the locale list", before, langs(plain))
    }
}
