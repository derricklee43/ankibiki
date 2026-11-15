package com.derricklee.ankidict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KuromojiWordAnalyzerTest {

    private val analyzer = KuromojiWordAnalyzer()

    @Test
    fun `extracts katakana words`() {
        val zama = analyzer.extractWords("ザマを見ろ").map { it.expression }
        val kagi = analyzer.extractWords("カギを開ける").map { it.expression }
        assertTrue("ザマ should be extracted: $zama", "ザマ" in zama)
        assertTrue("カギ should be extracted: $kagi", "カギ" in kagi)
    }

    @Test
    fun `rejects pure prolonged sound mark noise like ーー`() {
        val words = analyzer.extractWords("知れるーー").map { it.expression }
        assertFalse("ーー should not be extracted: $words", "ーー" in words)
        assertTrue("知れる should be extracted: $words", "知れる" in words)
    }

    @Test
    fun `imperative 殺せ extracts 殺す`() {
        val words = analyzer.extractWords("殺せ").map { it.expression }
        assertEquals(listOf("殺す"), words)
    }
}
