package com.derricklee.ankidict

/**
 * A11y capture health (roadmap D1, user go 2026-11-14).
 *
 * The floating-bubble lookup depends on [AnkiAccessibilityService]'s WebView warm-up, which
 * leans on Chromium internals that can move with any Chrome/AnkiDroid update — and until now
 * the service had NO detector for its own failure: a silent regression reads as "the app
 * is broken" instead of "selection capture stopped delivering".
 *
 * Freshness semantics (corrected by the first Pixel run, 2026-11-14): the original rule —
 * selection within 10s of the bubble TAP — mislabeled healthy captures as degraded, because
 * real usage is select → read the card → tap the bubble, minutes apart. The invariant that
 * actually proves the pipeline is alive: the selection event ARRIVED DURING THE CURRENT
 * SERVICE SESSION (timestamp >= connectedAt). A pre-session timestamp means the ledger holds
 * only stale text from a dead/old session; nothing recorded means never delivered.
 * Pure JVM logic (no Android types) pinned by A11yCaptureHealthTest.
 */
enum class CaptureStatus { FRESH, STALE, ABSENT }

object A11yCaptureHealth {

    /**
     * Session-based classification (both rules learned from the first Pixel run):
     * FRESH   — the a11y selection ledger delivered text DURING the current service session,
     *           or this trigger's lookup came from the service's own ACTION_COPY (the WebView
     *           copy path is capture machinery too; the real flow often delivers via it while
     *           TYPE_VIEW_SELECTED stays quiet);
     * STALE   — text existed but only from a pre-session ledger or the user's own clipboard —
     *           the lookup worked without the capture pipeline proving itself;
     * ABSENT  — nothing delivered and no session.
     */
    fun classify(selectionTimestamp: Long, serviceConnectedAt: Long, copyDelivered: Boolean): CaptureStatus =
        when {
            selectionTimestamp > 0L && serviceConnectedAt > 0L && selectionTimestamp >= serviceConnectedAt -> CaptureStatus.FRESH
            copyDelivered -> CaptureStatus.FRESH
            selectionTimestamp > 0L -> CaptureStatus.STALE
            else -> CaptureStatus.ABSENT
        }

    fun classify(selectionTimestamp: Long, serviceConnectedAt: Long): CaptureStatus =
        classify(selectionTimestamp, serviceConnectedAt, copyDelivered = false)

    // ---- rolling store (written from the bubble's IO path, read by the Settings popup) ----

    @Volatile var lastTriggerAt: Long = 0L; private set
    @Volatile var lastStatus: CaptureStatus = CaptureStatus.ABSENT; private set
    @Volatile var lastSuccessAt: Long = 0L; private set
    @Volatile var freshCount: Int = 0; private set
    @Volatile var triggerCount: Int = 0; private set
    @Volatile var ankiPackageVersion: String? = null; private set

    /** One recorded bubble trigger; [selectionTimestamp] from the a11y service's ledger. */
    fun recordTrigger(triggerAt: Long, selectionTimestamp: Long, serviceConnectedAt: Long, copyDelivered: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val status = classify(selectionTimestamp, serviceConnectedAt, copyDelivered)
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
