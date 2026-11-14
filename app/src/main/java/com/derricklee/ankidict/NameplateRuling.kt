package com.derricklee.ankidict

import android.content.Context

/**
 * THE ruling path for nameplate claims (roadmap B1, 2026-11-14): both the photo view and the
 * claims queue call here, so a ruling cannot drift between screens. Extracted verbatim from
 * PhotoDetailActivity.onNameplateVerdict when the queue shipped.
 *
 * Semantics (schema v8/v9, guardrails G1-G3):
 * - standalone 正しい: [MiningRepository.applyNameplateVerdict] settles the verdict and strips the
 *   matching display lines + their occurrence rows in one transaction; future mine/rescan passes
 *   filter sentences through hiddenNameplateCores, so the plate is hidden for good;
 * - inline 正しい: recompute the remainder with the shipped H9v2 rule ([NameplateInlineRule] +
 *   gate lexicon), excise the plate from the merged sentence through the normal reconciled
 *   text-edit path (reprocessPhotoText, reprocessWords = true — word occurrences rebuild), then
 *   settle the verdict; future rescans re-apply the cut via hiddenInlineClaims. If the merged
 *   sentence is already gone from the text (user stripped it manually), the plate confirmation
 *   itself still stands;
 * - 違う (either kind): verdict settles to 'incorrect', text untouched, and CONFLICT_IGNORE on the
 *   draft inserts means the claim is never re-suggested for that photo.
 *
 * All DB + pipeline work runs on the CALLER's IO thread; no UI here.
 */
object NameplateRuling {

    fun apply(
        context: Context,
        miningRepository: MiningRepository,
        miningPipelineRunner: MiningPipelineRunner,
        mediaStoreId: Long,
        claim: MiningRepository.NameplateClaim,
        correct: Boolean,
    ) {
        if (claim.contextSentence.isNotEmpty() && correct) {
            // Inline ruling: excise through the reconciled text-edit path so word occurrences
            // rebuild — then settle the verdict (hidden for good on rescan).
            val lex = runCatching { GateLexiconProvider.lexicon(context) }.getOrNull()
            val remainder = lex?.let { l ->
                NameplateInlineRule.suggestions(claim.contextSentence, l)
                    .firstOrNull { it.plate == claim.text }?.remainder
            }
            val photo = miningRepository.getPhoto(mediaStoreId)
            val current = photo?.extractedText.orEmpty()
            if (remainder != null && current.lineSequence().any { it.trim() == claim.contextSentence.trim() }) {
                val amended = current.split('\n')
                    .joinToString("\n") { if (it.trim() == claim.contextSentence.trim()) remainder else it }
                miningPipelineRunner.reprocessPhotoText(
                    mediaStoreId = mediaStoreId, amendedText = amended, reprocessWords = true,
                )
            }
            // remainder == null: the merged sentence is already gone from the text (user
            // stripped it manually) — the plate confirmation itself still stands.
        }
        miningRepository.applyNameplateVerdict(claim.id, correct)
    }
}
