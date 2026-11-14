package com.derricklee.ankidict

/**
 * A11y capture health (roadmap D1, user go 2026-11-14).
 *
 * The floating-bubble lookup depends on [AnkiAccessibilityService]'s WebView warm-up, which
 * leans on Chromium internals that can move with any Chrome/AnkiDroid update — and until now
 * the service had NO detector for its own failure: a silent a11y regression reads as "the app
 * is broken" instead of "selection capture stopped delivering".
 *
 * Model: at every bubble trigger (resolveBestText), classify the a11y selection timestamp
 * against the trigger clock:
 * - FRESH   — a selection arrived within [FRESH_WINDOW_MS] of the tap (capture is alive);
 * - STALE   — a selection exists but predates the window (likely a dead tree + old text);
 * - ABSENT  — nothing ever recorded (service not running or trees never warmed).
 * The Settings popup renders the rolling status so a regression shows up BEFORE anyone
 * debugs the app. Pure JVM logic (no Android types) pinned by A11yCaptureHealthTest.
 */
enum class CaptureStatus { FRESH, STALE, ABSENT }

object A11yCaptureHealth {

    /** Selection must be no older (or newer) than this around the tap to count as live capture. */
    const val FRESH_WINDOW_MS = 10_000L

    fun classify(triggerAt: Long, selectionTimestamp: Long, windowMs: Long = FRESH_WINDOW_MS): CaptureStatus =
        when {
            selectionTimestamp <= 0L -> CaptureStatus.ABSENT
            kotlin.math.abs(triggerAt - selectionTimestamp) <= windowMs -> CaptureStatus.FRESH
            else -> CaptureStatus.STALE
        }

    // ---- rolling store (written from the bubble's IO path, read by the Settings popup) ----

    @Volatile var lastTriggerAt: Long = 0L; private set
    @Volatile var lastStatus: CaptureStatus = CaptureStatus.ABSENT; private set
    @Volatile var lastSuccessAt: Long = 0L; private set
    @Volatile var freshCount: Int = 0; private set
    @Volatile var triggerCount: Int = 0; private set
    @Volatile var ankiPackageVersion: String? = null; private set

    /** One recorded bubble trigger; [selectionTimestamp] from the a11y service's ledger. */
    fun recordTrigger(triggerAt: Long, selectionTimestamp: Long, nowMs: Long = System.currentTimeMillis()) {
        val status = classify(triggerAt, selectionTimestamp)
        lastTriggerAt = triggerAt
        triggerCount += 1
        lastStatus = status
        if (status == CaptureStatus.FRESH) {
            freshCount += 1
            lastSuccessAt = nowMs
        }
    }

    /** Cached once per service session; shown so a Chromium-side change is attributable. */
    fun noteAnkiVersion(version: String?) {
        if (version != null && ankiPackageVersion == null) ankiPackageVersion = version
    }
}
