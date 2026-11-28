package com.derricklee.ankidict

import java.security.MessageDigest

/**
 * Stremio cue extraction engine (design §5 M7). One job = one whole episode, scope
 * human-declared (D3/D5: pointer is reference-only; mining always takes the entire
 * resource). Pipeline position: subtitle addon list -> pick by settings (pin > pattern >
 * first, D1) -> download -> hash -> SubtitleParser -> per-cue CorpusInsertion into the
 * EXISTING vocabulary + occurrence machinery (D4: no new engines), with the cue document
 * as the provenance key via media_refs kind='video_cues' (v15; the stage-1 negative
 * photo_id landing shape was retired by docs/sentence-media-refs-design.md).
 *
 * Threading: callers run this on AppIo.pipeline (the single serial corpus lane); this
 * class itself is synchronous and transport-injected, so JVM specs can drive it with a
 * fake. All remote failures land in the ledger as failed+error and are retryable (F0-F:
 * resumable queue), never half-committed: cue rows + occurrences are replaced per doc
 * inside one transaction.
 */
class StremioExtractionEngine(
    private val transport: StremioTransport,
    private val repo: MiningRepository,
    private val configFor: (LanguageTag) -> PipelineConfig?,
    private val addonsProvider: () -> List<StremioAddon>,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Builtin fallback source (spikes/007 D4a): consulted only after EVERY synced
     *  addon candidate came up empty. Null = feature inert (legacy test call sites). */
    private val jimaku: JimakuClient? = null,
) {

    data class Outcome(
        val status: String,
        val videoId: String,
        val docId: Long?,
        val cuesStored: Int,
        val occurrencesCreated: Int,
        val error: String? = null,
    )

    fun runJob(
        videoId: String,
        metaType: String,
        baseId: String,
        showName: String? = null,
        /** Cinemeta episode name (StremioEpisodesActivity has it on the row); persisted
         *  into the media ref attrs so the single-sentence view can show series + episode. */
        episodeTitle: String? = null,
    ): Outcome {
        val settings = repo.stremioSettingsFor(videoId, baseId)
        val generation = Generation.of(settings)
        return try {
            repo.upsertStremioLedger(videoId, generation, StremioLedgerStatus.FETCHING,
                now = now())
            val addons = addonsProvider()
            if (settings.subtitleLang.isBlank()) {
                return fail(videoId, generation,
                    "no subtitle language set - open the gear and enter e.g. jpn")
            }
            val candidates = addons.subtitleAddonCandidates()
            if (candidates.isEmpty() && jimaku == null) {
                return fail(videoId, generation, "no subtitle addon installed in Stremio")
            }
            // User-ordered subtitle ladder (2026-11-27):
            //   1. Anime Kitsu
            //   2. Jimaku (builtin direct)
            //   3. OpenSubtitles
            //   4. OpenSubtitles v3
            //   5. Other synced subtitle addons
            //   6. Buta No Subs (LAST resort)
            val beforeJimaku = candidates.filter { it.addonId == SUBTITLE_ADDON_KITSU }
            val afterJimaku = candidates.filter { it.addonId != SUBTITLE_ADDON_KITSU }

            var choices: List<SubtitleChoice> = emptyList()
            val tried = ArrayList<String>()

            fun tryAddonList(list: List<StremioAddon>) {
                for (cand in list) {
                    if (choices.isNotEmpty()) return
                    val result = runCatching {
                        AddonResources(transport).subtitles(cand, metaType, videoId,
                            settings.subtitleLang)
                    }
                    if (result.isFailure) {
                        // StremioHttpException ends with "-> HTTP <code>" -- keep the
                        // TAIL of the message (the status is the diagnostic; an earlier
                        // take(60) amputated it and broke the failure-path spec).
                        val m = result.exceptionOrNull()?.message
                        tried += "${cand.name}: " +
                            (if (m != null && m.length > 50) "…" + m.takeLast(50) else m ?: "?")
                        continue
                    }
                    val got = result.getOrDefault(emptyList())
                    if (got.isNotEmpty()) {
                        choices = got
                        return
                    }
                    tried += "${cand.name}: 0 results"
                }
            }

            tryAddonList(beforeJimaku)
            if (choices.isEmpty()) {
                val jm = jimaku
                if (jm != null) {
                    val builtinResult = runCatching {
                        jm.subtitlesFor(showName, metaType, videoId, settings.subtitleLang)
                    }
                    val got = builtinResult.getOrDefault(emptyList())
                    if (got.isNotEmpty()) {
                        choices = got
                    } else {
                        val m = builtinResult.exceptionOrNull()?.message
                        tried += "Jimaku (builtin): " +
                            (if (m != null && m.length > 50) "…" + m.takeLast(50) else m ?: "0 choices")
                    }
                }
            }
            if (choices.isEmpty()) {
                tryAddonList(afterJimaku)
            }
            if (choices.isEmpty()) {
                return fail(videoId, generation, "no ${settings.subtitleLang} subtitles - " +
                    "tried: ${tried.joinToString("; ").take(180)}")
            }
            val choice = selectChoice(settings, choices, videoId)

            val text = AddonResources(transport).fetchSubtitleText(choice.fileUrl)
            if (text.isBlank()) return fail(videoId, generation, "empty subtitle file")
            val fileHash = sha256(text)

            repo.upsertStremioLedger(videoId, generation, StremioLedgerStatus.PARSING,
                now = now())
            val rawCues = SubtitleParser.parse(choice.releaseName, text)
            val cues = SubtitleParser.shifted(rawCues, settings.offsetMs)
                .filter { it.text.isNotBlank() }
            if (cues.isEmpty()) return fail(videoId, generation, "no parseable cues")

            val existingDoc = repo.findStremioDocId(videoId, generation, fileHash)
            val docId = existingDoc ?: repo.createStremioDoc(
                videoId, generation, choice, fileHash,
                infoHash = null, fileIdx = null, now = now(),
            )
            repo.replaceStremioCues(docId, cues)

            // v15: occurrences hang off a media_refs row; the doc's ref is
            // stable across re-runs of the same generation (UNIQUE keeps one).
            val mediaRefId = repo.getOrCreateMediaRef(
                kind = "video_cues",
                refInt = docId,
                label = videoId,
                attrsJson = org.json.JSONObject().apply {
                    put("doc_id", docId)
                    put("video_id", videoId)
                    put("generation", generation)
                    // Provenance for the single-sentence view header (user 2026-11-26).
                    if (!showName.isNullOrBlank()) put("series", showName)
                    if (!episodeTitle.isNullOrBlank()) put("title", episodeTitle)
                }.toString(),
            )
            // I1 dedupe: this generation becomes the active one for the video.
            repo.retireStaleVideoMediaRefs(videoId, keepDocId = docId)
            // replace-in-place for THIS doc only (kind-scoped lock in helper)
            repo.deleteStremioOccurrencesForDoc(docId)

            val tag = tagForLang(settings.subtitleLang)
            val config = configFor(tag) ?: return fail(
                videoId, generation, "no pipeline config for $tag",
            )
            var occurrences = 0
            val createdAt = now() / 1000L
            // PLAN / APPLY (device freeze 2026-11-26 07:54, third root-cause pass):
            // chunking (13671fc) and moving pure analysis out of the transaction
            // were both necessary but insufficient - dumpsys dbinfo showed the
            // batches themselves took 60+s while ALL SQL summed 366ms. The hold was
            // the cross-app AnkiDroid ContentProvider round-trips + dictionary scans
            // that ensureCanonicalItemExists fires per candidate INSIDE the
            // transaction, on the ONE pooled connection. Fix: resolve each batch's
            // whole write set outside any transaction (planInsertions, with a
            // per-episode memo so repeated subtitle vocabulary costs zero IPC after
            // first sight), then apply pure sub-millisecond writes in a short
            // transaction. Correctness unchanged vs the sequential path (see
            // CorpusInsertion.planInsertions doc); doc-scoped delete-before-insert
            // above still makes a mid-batch kill re-queueable, and the ledger marks
            // MINED only after the LAST batch. WAL stays OFF on purpose:
            // uncheckpointed -wal pages break the file-copy backup tools
            // (backup_bundle.py + adb pulls).
            val memo = CorpusInsertion.InsertionMemo()
            var idx = 0
            while (idx < cues.size) {
                val slice = cues.subList(idx, minOf(idx + CUE_BATCH, cues.size))
                val batch = slice.map { cue ->
                    cue.text to CorpusInsertion.analyzeCandidates(config, cue.text)
                }
                val plan = CorpusInsertion.planInsertions(
                    config, batch,
                    hasItem = { t, e -> repo.hasExtractedItem(config.tag, t, e) },
                    hasLink = { t, e -> repo.hasLinkedAnkiCard(config.tag, t, e) },
                    memo = memo,
                )
                repo.inTransaction {
                    occurrences += CorpusInsertion.applyInsertions(
                        repo, config, plan, mediaId = mediaRefId, photoId = null,
                        timestamp = createdAt,
                    )
                }
                idx += CUE_BATCH
            }

            observeFileIdentity(videoId, generation, docId, metaType, addons)

            repo.upsertStremioLedger(
                videoId, generation, StremioLedgerStatus.MINED,
                docId = docId, cueCount = cues.size, now = now(),
            )
            Outcome(StremioLedgerStatus.MINED, videoId, docId, cues.size, occurrences)
        } catch (e: Exception) {
            fail(videoId, generation, "${e.javaClass.simpleName}: ${e.message?.take(160)}")
        }
    }

    private fun fail(videoId: String, generation: String, error: String): Outcome {
        runCatching {
            repo.upsertStremioLedger(
                videoId, generation, StremioLedgerStatus.FAILED, error = error, now = now(),
            )
        }
        return Outcome(StremioLedgerStatus.FAILED, videoId, null, 0, 0, error)
    }

    /** D1/D8 selection ladder: exact pin > name-pattern substring > episode-matching candidate > first. */
    fun selectChoice(
        settings: StremioSettingsRow,
        choices: List<SubtitleChoice>,
        videoId: String? = null,
    ): SubtitleChoice = StremioSelection.select(settings, choices, videoId)

    /**
     * M6-lite (S0-D tier2 fallback, D2 ruling): best-effort infoHash/fileIdx observation
     * from the user's stream addon; NEVER fails the job, never blocks mining.
     */
    private fun observeFileIdentity(
        videoId: String,
        generation: String,
        docId: Long,
        metaType: String,
        addons: List<StremioAddon>,
    ) {
        runCatching {
            val streamAddon = addons.firstStreamAddon() ?: return
            val candidates = AddonResources(transport).streams(streamAddon, metaType, videoId)
            val withHash = candidates.firstOrNull { it.infoHash != null } ?: return
            repo.writableDatabase.execSQL(
                "UPDATE stremio_cue_documents SET info_hash = ?, file_idx = ? WHERE id = ?",
                arrayOf(withHash.infoHash, withHash.fileIdx, docId),
            )
        }
    }

    companion object {
        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        fun tagForLang(lang: String): LanguageTag = when (lang.lowercase()) {
            "zho", "chn", "chi", "zh" -> LanguageTag.CHINESE
            else -> LanguageTag.JAPANESE
        }

        /** cues per committed batch during cue->occurrence insertion (ANR bound;
         *  sized so one batch stays well under ~1s of lock hold on device). */
        const val CUE_BATCH = 50
    }

    /** Settings generation: content hash of the effective settings tuple (6.1). */
    object Generation {
        fun of(settings: StremioSettingsRow): String =
            StremioExtractionEngine.sha256(settings.generationSeed()).take(16)
    }
}

/** Pure subtitle-version selection ladder (D1/D8), extracted for JVM specs: exact pin >
 *  release-name pattern substring > episode-matching candidate (guards against addons like
 *  Buta No Subs / Kitsunekko that mix S05E02..S01E02 in reverse order) > first candidate
 *  (never fails on a pin miss). */
object StremioSelection {
    fun select(
        settings: StremioSettingsRow,
        choices: List<SubtitleChoice>,
        videoId: String? = null,
    ): SubtitleChoice {
        settings.subtitlePin?.let { pin ->
            choices.firstOrNull { it.releaseName.equals(pin, ignoreCase = true) }?.let { return it }
        }
        val target = videoId?.let { AddonResources.regexEpisodeRef(it) }
        settings.subtitlePattern?.let { pattern ->
            if (pattern.isNotBlank()) {
                val patternHits = choices.filter { it.releaseName.contains(pattern, ignoreCase = true) }
                if (patternHits.isNotEmpty()) {
                    return pickByEpisodeMatch(patternHits, target)
                }
            }
        }
        return pickByEpisodeMatch(choices, target)
    }

    private fun pickByEpisodeMatch(
        choices: List<SubtitleChoice>,
        target: ParsedEpisodeRef?,
    ): SubtitleChoice {
        if (target == null) return choices.first()
        // Tier 0 = exact (season, episode) match; Tier 1 = no parseable S/E tag;
        // Tier 2 = explicit season/episode mismatch (e.g. S05E02 when target is S01E02).
        return choices.minByOrNull { choice ->
            val parsed = parseChoiceEpisode(choice)
            when {
                parsed == null -> 1
                parsed.season == target.season && parsed.episode == target.episode -> 0
                else -> 2
            }
        } ?: choices.first()
    }

    private fun parseChoiceEpisode(choice: SubtitleChoice): ParsedEpisodeRef? {
        AddonResources.parseEpisodeFromFilename(choice.releaseName)?.let { return it }
        val urlTail = runCatching {
            java.net.URLDecoder.decode(
                choice.fileUrl.substringAfterLast('/').substringBefore('?'),
                "UTF-8",
            )
        }.getOrNull() ?: return null
        return AddonResources.parseEpisodeFromFilename(urlTail)
    }
}
