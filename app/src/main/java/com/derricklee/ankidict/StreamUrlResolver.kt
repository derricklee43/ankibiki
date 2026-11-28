package com.derricklee.ankidict

/**
 * Resolves a playable HTTPS stream URL for a Stremio episode (Stage 3 Step 1):
 *  1. Direct Real-Debrid API mint from `(infoHash, fileIdx)` when `rdToken` is set
 *     (phone mints under its own IP, zero middleman).
 *  2. Stream-addon lookup (Torrentio RD etc., skipping `org.stremio.local`) to discover
 *     `(infoHash, fileIdx)` or a debrid resolve URL; if `rdToken` is set and an infoHash
 *     is found (either top-level or embedded in the Torrentio resolve URL), mints direct
 *     via RD, falling back to resolving the stream URL's 302 redirect.
 */
class StreamUrlResolver(
    private val transport: StremioTransport,
    private val rdClient: RdClient = RdClient(transport),
) {

    data class ResolvedStream(
        val directUrl: String,
        val infoHash: String?,
        val fileIdx: Int?,
        val source: String,
        val displayName: String,
        val resolveMs: Long,
    )

    fun resolve(
        videoId: String,
        metaType: String,
        storedInfoHash: String?,
        storedFileIdx: Int?,
        addons: List<StremioAddon>,
        rdToken: String?,
    ): ResolvedStream {
        val t0 = System.currentTimeMillis()
        val cleanToken = rdToken?.trim()?.takeIf { it.isNotEmpty() }

        // Path 1: Stored identity + RD token -> mint straight from Real-Debrid (0 addon calls).
        if (!storedInfoHash.isNullOrBlank() && cleanToken != null) {
            val direct = runCatching {
                rdClient.mintDirectStreamUrl(cleanToken, storedInfoHash, storedFileIdx)
            }.getOrNull()
            if (direct != null) {
                return ResolvedStream(
                    directUrl = direct.downloadUrl,
                    infoHash = storedInfoHash.lowercase(),
                    fileIdx = storedFileIdx,
                    source = "rd-direct-stored",
                    displayName = direct.filename.ifBlank { videoId },
                    resolveMs = System.currentTimeMillis() - t0,
                )
            }
        }

        // Path 2: Query stream addons (Torrentio RD etc.).
        val streamAddons = addons.streamAddonCandidates()
        if (streamAddons.isEmpty()) {
            throw IllegalStateException("no stream addon installed in Stremio")
        }
        val candidates = ArrayList<StreamCandidate>()
        val errors = ArrayList<String>()
        for (addon in streamAddons) {
            val res = runCatching { AddonResources(transport).streams(addon, metaType, videoId) }
            if (res.isFailure) {
                errors += "${addon.name}: ${res.exceptionOrNull()?.message?.takeLast(60)}"
                continue
            }
            val got = res.getOrDefault(emptyList())
            if (got.isNotEmpty()) {
                candidates += got
                break
            }
        }
        if (candidates.isEmpty()) {
            throw IllegalStateException("no streams returned (${errors.joinToString("; ")})")
        }

        val ranked = rankCandidates(candidates, storedInfoHash)
        var lastErr: Throwable? = null
        for (cand in ranked.take(5)) {
            // Prefer direct RD API mint when token + infoHash are both available.
            if (cleanToken != null && !cand.infoHash.isNullOrBlank()) {
                val rdRes = runCatching {
                    rdClient.mintDirectStreamUrl(cleanToken, cand.infoHash, cand.fileIdx)
                }
                if (rdRes.isSuccess) {
                    val u = rdRes.getOrThrow()
                    return ResolvedStream(
                        directUrl = u.downloadUrl,
                        infoHash = cand.infoHash,
                        fileIdx = cand.fileIdx,
                        source = "rd-direct-addon",
                        displayName = u.filename.ifBlank { cand.filename ?: cand.title ?: cand.name },
                        resolveMs = System.currentTimeMillis() - t0,
                    )
                }
                lastErr = rdRes.exceptionOrNull()
            }
            // Fallback: follow HTTP redirect on the configured stream URL (e.g. Torrentio RD resolve URL).
            if (!cand.url.isNullOrBlank()) {
                val urlRes = runCatching { transport.resolveRedirectUrl(cand.url) }
                if (urlRes.isSuccess) {
                    return ResolvedStream(
                        directUrl = urlRes.getOrThrow(),
                        infoHash = cand.infoHash,
                        fileIdx = cand.fileIdx,
                        source = "addon-redirect",
                        displayName = cand.filename ?: cand.title?.lineSequence()?.firstOrNull() ?: cand.name,
                        resolveMs = System.currentTimeMillis() - t0,
                    )
                }
                lastErr = urlRes.exceptionOrNull()
            }
        }
        throw IllegalStateException(
            "failed to mint stream URL: ${lastErr?.message ?: "no usable stream candidates"}",
            lastErr,
        )
    }

    companion object {
        /** Ranks stream candidates: matching stored hash > RD+ cached HTTP streams > other HTTP > infoHash-only. */
        fun rankCandidates(
            candidates: List<StreamCandidate>,
            storedInfoHash: String?,
        ): List<StreamCandidate> {
            val normStored = storedInfoHash?.lowercase()
            return candidates.sortedBy { c ->
                when {
                    normStored != null && c.infoHash == normStored -> 0
                    !c.url.isNullOrBlank() && c.name.contains("RD+", ignoreCase = true) -> 1
                    !c.url.isNullOrBlank() -> 2
                    !c.infoHash.isNullOrBlank() -> 3
                    else -> 4
                }
            }
        }
    }
}
