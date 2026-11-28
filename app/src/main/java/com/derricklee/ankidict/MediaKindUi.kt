package com.derricklee.ankidict

/**
 * Pure media-kind badge/label registry (docs/sentence-media-refs-design.md §4 U1).
 * Keyed by `media_refs.kind`; unknown future kinds fall back to their raw kind string
 * so readers never crash on kinds added later.
 */
object MediaKindUi {

    data class BadgeSpec(
        /** Resource id for known kinds, null for unknown future kinds (use [fallbackLabel]). */
        val labelResId: Int?,
        val fallbackLabel: String,
        /** True for non-photo sources so they visually stand out in a mixed inbox. */
        val emphasized: Boolean,
    )

    fun badgeFor(kind: String): BadgeSpec = when (kind) {
        "photo" -> BadgeSpec(R.string.media_kind_photo, "写真", emphasized = false)
        "video_cues" -> BadgeSpec(R.string.media_kind_video, "動画", emphasized = true)
        else -> BadgeSpec(null, kind.ifBlank { "?" }, emphasized = true)
    }

    /** Groups non-photo [MatchedSentence] rows by mediaId for word detail (R4 / U3). */
    fun groupNonPhotoSources(
        matchedSentences: List<MatchedSentence>,
        resolveLabel: (mediaId: Long, fallbackLabel: String?, mediaKind: String) -> String,
    ): List<MediaSourceItem> {
        val grouped = LinkedHashMap<Long, MutableList<MatchedSentence>>()
        for (m in matchedSentences) {
            if (m.mediaKind == "photo" || m.mediaId < 0L) continue
            grouped.getOrPut(m.mediaId) { ArrayList() }.add(m)
        }
        return grouped.entries.map { (mediaId, list) ->
            val first = list.first()
            MediaSourceItem(
                mediaId = mediaId,
                mediaKind = first.mediaKind,
                label = resolveLabel(mediaId, first.mediaLabel, first.mediaKind),
                occurrenceCount = list.size,
            )
        }.sortedWith(
            compareByDescending<MediaSourceItem> { it.occurrenceCount }
                .thenBy { it.label },
        )
    }
}
