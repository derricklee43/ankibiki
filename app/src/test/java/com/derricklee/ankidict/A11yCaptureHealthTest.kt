package com.derricklee.ankidict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A11y capture health (roadmap D1): the classifier is the product — a Chromium/AnkiDroid update
 * that silently kills WebView selection capture must flip this status BEFORE it costs a debug
 * day. Pinned as a JVM spec like Romaji/ItemOrdering (no Android, no device).
 */
class A11yCaptureHealthTest {

    @Test
    fun classify_table() {
        val t = 1_000_000L
        assertEquals(CaptureStatus.ABSENT, A11yCaptureHealth.classify(t, 0L))
        assertEquals(CaptureStatus.ABSENT, A11yCaptureHealth.classify(t, -5L))
        assertEquals(CaptureStatus.FRESH, A11yCaptureHealth.classify(t, t))               // same-ms selection
        assertEquals(CaptureStatus.FRESH, A11yCaptureHealth.classify(t, t - 9_999L))      // just inside window
        assertEquals(CaptureStatus.FRESH, A11yCaptureHealth.classify(t, t + 1_000L))      // copy lands after tap
        assertEquals(CaptureStatus.FRESH, A11yCaptureHealth.classify(t, t - A11yCaptureHealth.FRESH_WINDOW_MS)) // boundary inclusive
        assertEquals(CaptureStatus.STALE, A11yCaptureHealth.classify(t, t - A11yCaptureHealth.FRESH_WINDOW_MS - 1))
        assertEquals(CaptureStatus.STALE, A11yCaptureHealth.classify(t, t - 60_000L))     // last week's selection
    }

    @Test
    fun store_recordsFreshThenDegrades() {
        val before = A11yCaptureHealth.triggerCount
        val freshBefore = A11yCaptureHealth.freshCount
        val t = System.currentTimeMillis()

        // fresh trigger: status OK, counters move, lastSuccessAt stamps
        A11yCaptureHealth.recordTrigger(t, t - 2_000L)
        assertEquals(before + 1, A11yCaptureHealth.triggerCount)
        assertEquals(freshBefore + 1, A11yCaptureHealth.freshCount)
        assertEquals(CaptureStatus.FRESH, A11yCaptureHealth.lastStatus)
        assertTrue("lastSuccessAt must advance", A11yCaptureHealth.lastSuccessAt >= t)
        val successAt = A11yCaptureHealth.lastSuccessAt

        // forced-degrade case (roadmap criterion c): a stale selection flips status
        // WITHOUT touching the success ledger
        A11yCaptureHealth.recordTrigger(t + 5_000L, t - 120_000L)
        assertEquals(CaptureStatus.STALE, A11yCaptureHealth.lastStatus)
        assertEquals(before + 2, A11yCaptureHealth.triggerCount)
        assertEquals(freshBefore + 1, A11yCaptureHealth.freshCount)
        assertEquals("degraded trigger must not rewrite lastSuccessAt", successAt, A11yCaptureHealth.lastSuccessAt)

        // absent (service never delivered anything) is its own degraded flavor
        A11yCaptureHealth.recordTrigger(t + 9_000L, 0L)
        assertEquals(CaptureStatus.ABSENT, A11yCaptureHealth.lastStatus)
    }

    @Test
    fun version_isCachedFirstWins() {
        A11yCaptureHealth.noteAnkiVersion("2.25.3")
        A11yCaptureHealth.noteAnkiVersion(null)
        A11yCaptureHealth.noteAnkiVersion("9.9.9")
        assertEquals("2.25.3", A11yCaptureHealth.ankiPackageVersion)
    }
}
