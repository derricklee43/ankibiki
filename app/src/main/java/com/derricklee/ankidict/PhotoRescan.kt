package com.derricklee.ankidict

/** Explicit choices offered before replacing user-edited photo text during a rescan. */
enum class PhotoRescanDecision {
    SKIP_TEXT_EXTRACTION,
    ACCEPT,
    CANCEL,
}

enum class PhotoRescanResult {
    COMPLETED,
    CONFIRMATION_REQUIRED,
    SKIPPED,
    CANCELLED,
}
