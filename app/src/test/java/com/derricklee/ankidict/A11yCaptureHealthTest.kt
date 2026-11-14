package com.derricklee.ankidict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A11y capture health (roadmap D1): the classifier is the product — a Chromium/AnkiDroid update
 * that silently kills WebView selection capture must flip this status BEFORE it costs a debug
 * day. Pinned as a JVM spec like Romaji/ItemOrdering (no Android, no device).
 *
 * Freshness = selection arrived during the current service session. The original 10s-vs-tap
 * window was FALSIFIED by the first Pixel run (2026-11-14): real usage is select → read the
 * card → tap the bubble, minutes apart, and healthy captures scored degraded. Session-floor
 * semantics keep the forced-degrade case honest: a ledger predating the current service
 * connection means the pipeline delivered nothing this session.
 */
class A11yCaptureHealthTest {

    @Test
    fun classify_table() {
        val s = 1_000_000L  // service connected at
        assertEquals(CaptureStatus.ABSENT, A11yCaptureHealth.classify(0L, s, false))        // never delivered
        assertEquals(CaptureStatus.STALE, A11yCaptureHealth.classify(s + 5, 0L, false))      // session off, ledger has old text
        assertEquals(CaptureStatus.ABSENT, A11yCaptureHealth.classify(0L, 0L, false))        // nothing at all
        assertEquals(CaptureStatus.FRESH, A11yCaptureHealth.classify(s, s, false))           // boundary inclusive
        assertEquals(CaptureStatus.FRESH, A11yCaptureHealth.classify(s + 1_000L, s, false))   // this session
        assertEquals(CaptureStatus.FRESH, A11yCaptureHealth.classify(s + 3_600_000L, s, false)) // an hour later — still this session
        assertEquals(CaptureStatus.STALE, A11yCaptureHealth.classify(s - 1L, s, false))       // pre-session ledger
        assertEquals(CaptureStatus.STALE, A11yCaptureHealth.classify(500L, s, false))         // old session's text
        // the Pixel case: selection event quiet this session, but the service's ACTION_COPY delivered
        assertEquals(CaptureStatus.FRESH, A11yCaptureHealth.classify(0L, s, true))            // copy-only trigger is FRESH
        assertEquals(CaptureStatus.FRESH, A11yCaptureHealth.classify(s - 500_000L, s, true))  // stale ledger + live copy
    }

    @Test
    fun store_recordsFreshThenDegrades() {
        val before = A11yCaptureHealth.triggerCount
        val freshBefore = A11yCaptureHealth.freshCount
        val t = System.currentTimeMillis()
        val session = t - 60_000L  // service connected a minute ago

        // fresh trigger: selection arrived this session (minutes after it — the Pixel case)
        A11yCaptureHealth.recordTrigger(t, t - 5_000L, session, copyDelivered = false)
        assertEquals(before + 1, A11yCaptureHealth.triggerCount)
        assertEquals(freshBefore + 1, A11yCaptureHealth.freshCount)
        assertEquals(CaptureStatus.FRESH, A11yCaptureHealth.lastStatus)
        assertTrue("lastSuccessAt must advance", A11yCaptureHealth.lastSuccessAt >= t)
        val successAt = A11yCaptureHealth.lastSuccessAt

        // forced-degrade (roadmap criterion c): pre-session ledger flips status
        // WITHOUT touching the success ledger
        A11yCaptureHealth.recordTrigger(t + 5_000L, session - 120_000L, session, copyDelivered = false)
        assertEquals(CaptureStatus.STALE, A11yCaptureHealth.lastStatus)
        assertEquals(before + 2, A11yCaptureHealth.triggerCount)
        assertEquals(freshBefore + 1, A11yCaptureHealth.freshCount)
        assertEquals("degraded trigger must not rewrite lastSuccessAt", successAt, A11yCaptureHealth.lastSuccessAt)

        // absent (no session at all) is its own degraded flavor
        A11yCaptureHealth.recordTrigger(t + 9_000L, 0L, 0L, copyDelivered = false)
        assertEquals(CaptureStatus.ABSENT, A11yCaptureHealth.lastStatus)

        // the Pixel flow: selection event quiet, service ACTION_COPY delivered -> FRESH
        A11yCaptureHealth.recordTrigger(t + 12_000L, 0L, session, copyDelivered = true)
        assertEquals(CaptureStatus.FRESH, A11yCaptureHealth.lastStatus)
        assertEquals(freshBefore + 2, A11yCaptureHealth.freshCount)
    }

    @Test
    fun version_isCachedFirstWins() {
        A11yCaptureHealth.noteAnkiVersion("2.25.3")
        A11yCaptureHealth.noteAnkiVersion(null)
        A11yCaptureHealth.noteAnkiVersion("9.9.9")
        assertEquals("2.25.3", A11yCaptureHealth.ankiPackageVersion)
    }
}
