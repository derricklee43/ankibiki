package com.derricklee.ankidict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Undo memento codec (cross-view undo, user go 2026-11-15). Pure-JVM round-trip tests over
 * the values that actually live in sentences and OCR JSON: tabs, newlines, CJK, control
 * characters, the backslash itself, and NULL-sentinel columns. The payload IS the restore
 * contract — a codec that loses a byte loses data on undo, so everything here is byte-exact.
 */
class UndoCodecTest {

    @Test
    fun escape_roundTripsHostileCharacters() {
        val hostile = "a\tb\nc\\ré\r\u0000\u001F\u001E\u007Fend—文\t\n"
        assertEquals(hostile, UndoCodec.unescape(UndoCodec.escape(hostile)))
    }

    @Test
    fun escape_outputContainsNoRawLineOrUnitSeparators() {
        val escaped = UndoCodec.escape("x\u0000y\nz\u001Fw")
        assertTrue(escaped.none { it == '\n' || it == '\u001F' || it.code < 0x20 })
    }

    @Test
    fun memento_roundTripsFullShape() {
        val m = UndoMemento(
            action = UndoAction.SENTENCE_TEXT_EDIT,
            label = "文を編集しました",
            header = listOf("42", "行1\tx\n行2"),
            photo = UndoPhotoRow(
                mediaStoreId = 42L,
                extractedText = "行1\tx\n行2",
                languageTag = "JAPANESE",
                status = "KEEP",
                modifiedByUserFlag = true,
                isGold = true,
                flagColor = null,
                ocrBlocksJson = "{\"blocks\":[{\"text\":\"\u0001ctrl\"}]}",
            ),
            occurrences = listOf(
                UndoOccRow(7L, "JAPANESE", "WORD", "手\t\n", 42L, "行1", 100L, "SEEN", true, false),
                UndoOccRow(8L, "JAPANESE", "GRAMMAR", "文法〜", 42L, "行1\t2", 101L, "UNSEEN", false, true),
            ),
        )
        val decoded = UndoMemento.decode(m.action, m.label, m.encode())
        assertEquals(m, decoded)
    }

    @Test
    fun decode_rejectsUnknownFormat() {
        assertNull(UndoCodec.decode(UndoAction.TOGGLE_GOLD, "x", "v2\nh\u001F1"))
        assertNull(UndoCodec.decode(UndoAction.TOGGLE_GOLD, "x", "garbage"))
        // a truncated row line must not half-decode
        assertNull(UndoCodec.decode(UndoAction.TOGGLE_GOLD, "x", "v1\nr\u001F1\u001F2"))
    }

    @Test
    fun encode_emptyMemento_roundTrips() {
        val m = UndoMemento(UndoAction.REORDER_SENTENCES, "並び", listOf("JAPANESE", "WORD", "海賊", ""))
        val d = UndoMemento.decode(m.action, m.label, m.encode())
        assertEquals(m, d)
        assertEquals(null, d?.photo)
        assertEquals(emptyList<UndoOccRow>(), d?.occurrences)
    }
}
