SPIKE DESIGN: STREMIO MINING CONSOLE (MANUAL EPISODE EXTRACTION, ANDROID-ONLY)
===============================================================================
Status: STAGE-1 IMPLEMENTED under the user's one-shot go 2026-11-23 (see §12 for the
build-pass evidence and what remains). Self-contained by design: you should
not need to read the other docs to understand this one. Cross-references to
docs/stremio-auto-scrub-parked.md appear only in §10 (how the two connect).
Date: 2026-11-23. Fact citations are to Stremio's own repos (stremio-core /
stremio-web / stremio-shell / stremio-video), cloned and read 2026-11-23.

1. BACKGROUND — WHY THIS DESIGN EXISTS
---------------------------------------
1.1 The product goal (unchanged from the vision): watch shows in Stremio on
    Windows/Android with Real-Debrid + subtitle addons; later, mine vocabulary
    from the subtitle lines of what you watched, with rich cards (audio +
    screenshot) extracted lazily from the stream.

1.2 Verified facts about Stremio that killed "fully automatic sync" as the
    FIRST deliverable (full evidence + code citations in
    docs/stremio-auto-scrub-parked.md §0; summary here):
      a) Stremio's cloud keeps ONE resume slot per series libraryItem
         (state.video_id + state.time_offset). Continue Watching is derived
         from exactly that (continue_watching_preview.rs + library_item.rs:58).
      b) Watching past 70% of an episode auto-marks it watched and REWRITES
         the slot to the next episode with time_offset reset (player.rs:
         604-681, WATCHED_THRESHOLD_COEF=0.7, advance_to_video
         library_item.rs:144-152; 0.9 credits threshold on close). So cloud
         state CANNOT tell us where a finished episode actually ended.
      c) Per-episode watched status exists but only as a base64 BITFIELD
         (state.watched) with no timestamps (stremio_watched_bitfield crate).
    CONSEQUENCE: inferring "what you watched" from state is lossy by design.

1.3 The insight behind this pivot: if the USER declares which episodes to
    extract, the fatal inference problem disappears. We never need a live
    position again for text mining -- we need a list, a checkbox, and your
    settings. Everything in this design works with human-declared scope:
      - Episode list: from the meta addon (videos[] with ids like
        "tt1234567:1:4"), not from resume state.
      - "Have I watched this?" hint: the watched bitfield, decoded -- a
        DEFAULT-CHECK convenience, overridable by you, not a boundary.
      - "Where am I right now?" hint: the one resume slot (timeOffset),
        shown as a badge. USER CLARIFICATION 2026-11-23: the pointer is
        REFERENCE ONLY -- it has no bearing on mining runs whatsoever; a
        mining run always mines the ENTIRE resource (episode/movie).
        Its single-slot limitation is labeled in the UI.
      - Mined status: OUR ledger, not Stremio's state.

1.4 Why build this FIRST: it exercises every API the auto design will need
    (auth, library, addon discovery, subtitle resolution, ingest plumbing)
    with a human as the safety net, and it produces the persisted settings
    (subtitle version choice, audio track choice, offset) that the media-
    slicing phase later consumes verbatim. Manual mode is the foundation,
    not a throwaway.

2. SCOPE
--------
2.1 What this is:
    An Android-only screen (or small set of screens) in Ankibiki that:
      * Shows your Stremio library (shows + movies).
      * Lists episodes per show, with watched hint and resume-pointer badge.
      * Lets you configure per-show settings: subtitle source/version
        (filtered to your language), audio track (filtered to your language,
        where discoverable), millisecond offset.
      * Lets you select whole episodes and queue extraction: download the
        chosen subtitle file, parse cues, ingest them into the existing
        word-extraction / known-word / frequency pipeline.
      * Tracks mined status per (episode, settings-generation) so repeat
        runs don't re-mine, and renders the states in the views.
    ZERO servers. ZERO laptop components. No ffmpeg in the shipping scope
    (see 2.2). The only remote endpoints are Stremio's own APIs + the addon
    HTTP resources + the subtitle file download.

2.2 Explicit NON-GOALS (things this design does NOT spend):
      * No automatic progress sync / watch-time capture -- PARKED by user
        ruling 2026-11-23; preserved as pick-up summary in
        docs/stremio-auto-scrub-parked.md. This console is the active route.
      * No partial-episode boundaries, ever in this design: the resume
        pointer never gates or trims a mining run (user clarification).
      * No Real-Debrid media slicing, screenshots, or audio clips (stays in
        the parked doc's §C; settings captured HERE are the input for when
        it lands).
      * No subtitle ground-truth sniffing (no browser extensions, no CDP).
      * No Anki export changes; mined sentences flow into the EXISTING
        triage + wizard + AnkiDroid export untouched.
      * No multi-account / family profiles.

3. USER FLOW
------------
  [Console] Your Stremio library, one row per show/movie:
      title, poster, "12/24 watched" (from bitfield), "3 mined"
      (from our ledger), and a settings chip when configured.
  [Taps row] -> [Seasons/Episodes] list:
      checkbox per episode, pre-checked where watched & unmined.
      Per row: watched hint (bitfield), resume badge on the ONE slot
      Stremio knows ("resume @ 14:32" + footnote that Stremio only
      tracks the last-watched episode per show) -- reference-only,
      never a boundary per ruling, mined status chip
      (mined w/ settings generation / failed / queued).
      Header: gear = per-show settings, "Extract N episodes" = queue.
  [Gear] -> [Settings sheet]:
      Subtitles: language (persisted default from your mining locale),
        addon picker (auto-discovered from your Stremio account's addons;
        all of them merged, not hardcoded), version filter (e.g. prefer a
        named release group; you routinely pick among "a bunch of
        versions"), manual per-episode override, ms offset nudge.
      Audio: language hint (jpn), probe result when available (see §5 M6:
        tracks come from PROBINING THE STREAM FILE, not from Stremio --
        Stremio's API cannot list them), manual track-index override,
        and a clear "not verified yet" state.
      Saving settings changes the generation hash -> re-extract becomes
      offered, never automatic (§6, D6).
  [Queue] -> [Extraction jobs]: WorkManager drains the queue: resolve
      subtitle file -> download -> parse -> ingest cues -> flip ledger.
      Notifications only on failure; per-row status chips are the progress.
  [Done] -> sentences appear in your existing occurrences/triage/frequency
      views tagged source='stremio:<show>', indistinguishable from other
      sentences downstream (D4).

4. VERIFIED API SURFACE (what the phone can plain-HTTP without Stremio)
------------------------------------------------------------------------
  A. Link sign-in -> authKey: link.stremio.com api/v2 create+poll. (Unchanged
     from original doc's assumption; risk accepted, it is what the official
     clients use -- LINK_API_URL, constants.rs.)
  B. POST https://api.strem.io/api/datastoreGet, collection "libraryItem"
     (LIBRARY_COLLECTION_NAME, constants.rs:21) -> library items:
     { name, type, state: { video_id, time_offset, duration, lastWatched,
     timesWatched, flaggedWatched, watched(bitfield) } }.
     Field-name casing verified against serde models; UNITS of time_offset on
     the Android client are a pinned spike item (web dispatches ms; the native
     app may differ -- player.rs TimeChanged handler is client-agnostic).
  C. APIRequest "addonCollectionGet" on api.strem.io (types/api/request.rs:169;
     ctx.rs:369-413 fetches it right after login) -> the user's INSTALLED
     addons including their config URLs. THIS is the discovery mechanism:
     no hardcoded addon list; subtitle addons, stream addons (RD-enabled,
     config URL carries the key), and meta/cinemeta all come from here.
  D. Addon resources, plain GETs on the addon's transportUrl:
     /meta/{type}/{id}.json            -> videos[] (episode ids, names)
     /subtitles/{type}/{videoId}.json  -> [{ id, lang, url, ... }]
     /stream/{type}/{videoId}.json     -> [{ url | externalUrl, name, ... }]
     (standard addon protocol; shape pinned per-fixtures in spike).
  E. The honest gaps (things Stremio does NOT expose):
     - AUDIO TRACKS: NOT in the Stremio API, and -- important correction --
       NOT in the Real-Debrid API either. Nobody exposes them because they
       are not API data: the track list lives in the MKV bytes. Stremio's
       own track menu is produced by the player PROBING THE OPENED FILE
       (mpv enumerates it; the web player likewise). "Re-create what
       Stremio shows" therefore means: fetch the same file reference and
       run the same kind of probe ourselves. On-device answer: MediaExtractor
       on the stream URL -> per-track MIME, language, channel count. This is
       the design's one real technical unknown -> S0-D.
     - PER-EPISODE WATCHED TIMESTAMPS: don't exist (bitfield only). We
       don't need them; the human supplies scope.
     - SUBTITLE FILE THE PLAYER USED: not in state; we re-resolve from the
       addon list using YOUR settings (you choose the version once per show
       -- this replaces the auto design's ground-truth problem).

4.5 WHAT REAL-DEBRID ITSELF EXPOSES (the "do we even need Stremio?" answer)
----------------------------------------------------------------------------
  RD REST base: https://api.real-debrid.com/rest/1.0/ ; Bearer token
  (personal token from the account page, or RD's own OAuth2 device flow --
  same UX pattern as Stremio link login). Documented global limit:
  250 requests/minute (429 on excess) -- generous; click-time use and even
  30-60s polling are safe. The API sends ETags / honors If-None-Match, so
  history polling can be dirt cheap.

  RD DOES know (and Stremio does not):
    - GET /user           -> username, premium expiry, points. Sub-status
                             gate for the whole feature (show a warning in
                             the console when expired; every stream job
                             fails otherwise).
    - GET /downloads      -> the ACCESS LOG: every unrestrict/stream event
                             with Filename, Size, host, link, type
                             (download vs stream), Submitted/completed
                             timestamps. This is the closest thing to a
                             "watch history API" RD has: WHICH FILES were
                             pulled WHEN. Whether torrent-sourced streams
                             reliably appear here is an S0-D2 verification.
    - GET /torrents, /torrents/info/{id} -> torrent filename, SHA1
                             infoHash, per-file paths/sizes/ready%, status,
                             added/ended, host link lists. The infoHash is
                             exactly what the media phase needs to RE-MINT
                             its own IP-correct link later (parked doc
                             §0.4) --
                             obtainable from RD directly, no filename
                             guessing once a torrent was added.
    - GET /torrents/instantAvailability/{hash} -> whether a hash is cached
                             (fast "can this be sliced at all, now?").
    - GET /connections    -> active IPs using the account (diagnostic for
                             the IP-binding rules).
    - POST /unrestrict/link has a documented `remote=1` flag ("remote
                             traffic, dedicated servers, account-sharing
                             protections lifted") -- an official knob for
                             the cross-machine pull problem; costs/limits
                             get verified in S0-D2 before anyone relies
                             on it.

  RD does NOT know (so Stremio stays load-bearing for these):
    - HOW FAR you watched. Zero position tracking anywhere in RD. The
      resume pointer is Stremio-only (and the real watched-until-now truth
      is watch-time capture only, per 1.2).
    - EPISODE IDENTITY: RD metadata is filename strings. "Show X episode
      4" must be regex-parsed out of release names; imdb/season/episode
      ids exist only on Stremio's meta side.
    - SUBTITLE FILES and their language lists: hosted by addons, never by
      RD (embedded subtitle tracks are inside the file -- probe again).
    - Track metadata of any kind: same as Stremio -- in the bytes only.

  ANSWER TO THE QUESTION AS ASKED: for AUDIO/SUBTITLE TRACK LISTS the
  console needs neither API -- it needs a file URL (from RD /downloads
  links or stream-addon replay) plus the M6 probe. For IDENTITY, LIBRARY,
  RESUME POINTER, and SUBTITLE ADDON DISCOVERY the console needs Stremio.
  For SUB STATUS, RECENT-FILE CANDIDATES, INFOHASHES, and RE-MINTING, RD
  is strictly better than Stremio. Module M9 below takes the RD side.

5. ARCHITECTURE — MODULES AND CONTRACTS (blur-proof)
-----------------------------------------------------
  M1 StremioAuth      -> authKey (Keystore-backed private storage; never in
                        DB columns that travel in bundles; never logged).
  M2 LibraryReader    -> dataStoreGet -> LibrarySnapshot { items[], fetchedAt }
                        Pure read + parse against pinned JSON fixtures.
  M3 AddonDiscovery   -> addonCollectionGet -> AddonCatalog { metaAddons,
                        subtitleAddons, streamAddons }, each with transportUrl.
                        Merges multiple addons; no per-show binding.
  M4 EpisodeCatalog   -> M2 item ids + M3 meta addon -> EpisodeList
                        { videoId, season, episode, title, watchedFromBitfield }.
                        Bitfield decode isolated here (S0-B) with an explicit
                        "unknown -> unchecked, overridable" degradation.
  M5 SubtitlePicker   -> M3 subtitle addons + videoId -> SubtitleChoices
                        { id, lang, releaseName, sourceAddon } for the sheet;
                        selected choice pins (addonId, subId, name), NEVER a
                        URL (URLs rotate). Download-on-demand by selection.
  M6 StreamProber     -> candidate file URLs (source: stream-addon replay via
                        M3, or RD /downloads links via M9); MediaExtractor
                        probe -> AudioTracks[] { index, codec, language,
                        channels }. OPTIONAL module: consumers must degrade
                        to "track unknown" when it fails (fallback ladder
                        in S0-D). Feeds settings persistence only.
  M7 ExtractionEngine -> job per (videoId, settingsGeneration): resolve
                        subtitle file -> download -> parse cues (Kotlin .srt/
                        .vtt parser, cues keyed (videoId, fileHash, startMs))
                        -> HANDOFF CONTRACT: cue list + "boundary = whole
                        episode, source = human declaration" into the SAME
                        ingest boundary a resumed auto design reuses
                        (parked doc §B):
                        existing known-word filter, frequency ranking,
                        extraction pipeline. M7 writes nothing else; no new
                        scoring paths.
  M8 Ledger           -> extraction_ledger table (§6) is the ONLY authority
                        on mined status. UI chips render from it; re-runs
                        consult it; M7 updates it transactionally.
  M9 RdBridge         -> RD REST (Bearer token, Keystore-backed, OUT of
                        bundles). Uses: GET /user -> premium-expiry warning
                        chip in the console header; GET /downloads (ETag
                        delta) -> optional "recent files" candidate feed,
                        episode identity regex-parsed from filenames as a
                        PRE-CHECK HINT ONLY (never authority; Stremio owns
                        identity); GET /torrents/info -> infoHash stash per
                        file for the future media phase's IP-correct
                        re-minting; instantAvailability -> cheap "slicable
                        now?" gate before probing. Read-only in this
                        design's scope; unrestrict/minting belongs to the
                        media phase (parked doc §C), not here.
  Boundary rules: M1-M6 and M9 are external-service adapters (Stremio,
  RD); none touches mining content. M7/M8 are the seam: they consume
  contracts and write the DB. The UI never speaks raw API. Every remote
  response shape is fixture-pinned in tests, so Stremio/RD API drift
  fails loudly in specs, not silently in your queue (repo convention).

5.5 IDENTITY SPINE (user directive 2026-11-23: use the matching identifier
    as our system key)
----------------------------------------------------------------------
  How Stremio matches a listing to an RD-cached torrent is not magic and
  not an RD-side lookup: the STREAM OBJECTS returned by stream addons carry
  the torrent's infoHash (plus fileIdx for multi-file torrents) -- standard
  fields of the Stremio addon protocol. RD's /torrents reports the same
  SHA1 hash. That hash IS the identifier; adopt it as the file-level key.

  Three identities, three jobs -- never conflate, always store:
    EPISODE identity  = videoId ("<imdb>:<season>:<episode>"; movies: imdb
                        id). Canonical key for library, ledger, and mining
                        runs. An episode has MANY releases -> many hashes;
                        hash can never replace this key.
    FILE identity     = infoHash (+ fileIdx when the torrent holds several
                        files). Recorded whenever M3 stream-addon replay or
                        M9 /torrents reveals it; the ONLY way to re-mint an
                        IP-correct stream link in the future media phase;
                        the join between "what Stremio offered" and "what
                        RD holds". NEVER use RD's per-user internal torrent
                        ids or links (unstable, account-scoped) as keys.
    TEXT identity     = subtitle (addonId, subId, releaseName) + fileHash
                        (decided, D8). A subtitle file is not content-
                        addressed by any API; our own hash closes the gap.
  Mining run = (videoId, settingsGeneration); its ledger row also stores
  infoHash/fileIdx as OBSERVED AT RESOLUTION (nullable: some addons return
  bare https streams with no infoHash -- then record host+path as a weak
  reference and mark the file "remint-unknown"). Cue rows keep (videoId,
  fileHash, startMs) per D8. The spine means a future cards-with-media
  phase can go cue -> episode -> exact torrent -> fresh link with no
  filename regex anywhere.

6. DATA MODEL (mining.db migration -- v14 in repo terms)
----------------------------------------------------------
  [stremio_settings]    scope (global|show|videoId), subtitle_prefs(json:
                        lang, addonId, name-pattern), audio_track_id (or
                        null), offset_ms, updated_at.
  [extraction_ledger]   videoId, season, episode, info_hash (nullable per
                        5.5), file_idx (nullable), settings_generation(hash),
                        subtitle_file_hash, status(queued|fetching|parsed|
                        mined|failed), cue_count, error, timestamps.
                        UNIQUE(videoId, settings_generation) -> repeat
                        extraction of same settings = no-op (idempotency
                        is a spec test, not a promise).
  [extraction_jobs]     queue rows (videoId, gen) consumed by WorkManager;
                        survives process death; resumable.
  Cue sentences/occurrence rows land through the EXISTING tables, but the
  schema does not accept them as-is, and that is not a given:
  item_occurrences.photo_id is NOT NULL (MiningRepository.kt:82), joining
  scraped_photos.media_store_id (a MediaStore photo id, :26) -- and
  provenance today is photo membership itself (no source column exists).
  The shape decision is deferred to the 6.1 data-model plan, which is a
  precondition to S0-E. The schema-bump ceremony (version gates +
  bundles) runs once, deliberately: ledger+settings IN bundle contract
  (corpus state, no secrets); stream URLs/transport secrets OUT
  (D7 signed off by user 2026-11-23).

6.1 DATA-MODEL PLAN (owed before S0-E; a design round, not a spike verdict)
    The three tables above are the cheap half. The expensive half is how
    Stremio cues land, and every candidate shape has a price:
      (a) Fabricate scraped_photos rows for cue batches: pollutes every
          photo-shaped reader (PhotoRescan, duplicate detector, gold/flags,
          nameplate machinery). REJECTED on principle; recorded so nobody
          "discovers" it cheaply later.
      (b) Generalize occurrences: source-kind column + nullable photo_id (or
          a cue_batch_id): touches the provenance discipline that stands on
          photo membership; v14 must run the full schema-versioning
          ceremony; needs a written-before-decided audit of every photo_id
          writer/reader (the repo's behavior-question rule: enumerate, don't
          debate).
      (c) Parallel stremio_occurrences, unioned at the triage/frequency read
          layer only: zero risk to photo machinery; risk = two diverging
          occurrence implementations over time (what the repo hates).
    The plan must also cover, none of it speccable by analogy: raw-cue
    retention (re-generations; re-filtering when known-word state changes;
    the §11.1 mass-delete's run membership); run-membership links
    (ledger -> cues -> occurrences); watched-snapshot cursors (the §10
    auto-resume promise); settings-generation identity (content hash of
    the settings row, not an increment); and which existing flags
    (is_manual, modified_by_user_flag) mean anything for video-sourced
    rows -- answers from reading the writer/gate code, not from this doc.

    RESOLVED 2026-11-23 (one-shot build, user "implement it as a one shot"):
    STAGE-1 shipped = option (c)'s no-rebuild safety crossed with option (b)'s
    single-engine requirement (D4): occurrence rows land in item_occurrences
    itself via the SHARED CorpusInsertion path, keyed by NEGATIVE photo_id =
    -stremio_cue_documents.id (MediaStore ids are always positive -> the
    namespaces cannot collide; every photo-scoped guard/writer naturally
    excludes them; VOCABULARY identity (extracted_items rows, known-word/Anki
    linkage, visibility states) sees stremio mining immediately. Known stage-1
    gaps, accepted and visible (all from the same cause -- seven INNER JOIN
    scraped_photos readers):
    1) the occurrences inbox (ExtractedList) and photo-keyed sentence views
       do not LIST stremio sentences (MiningQueries.kt:518/:583/:651/:762);
    2) the per-word occurrence COUNT subquery (:501/:570/:638) excludes them,
       so frequency ranking under-counts stremio hits until stage 2;
    3) stremio-native content (cues, ledger, settings, snapshots, addon and
       library caches, watch snapshots) lives in seven new v14 tables and is
       fully functional either way.
    Writer audit: MiningRepository.kt:82 is NOT NULL with NO foreign key, and
    insertOccurrence (:1307) takes any Long; every photo-scoped mutator
    (deleteOccurrencesForPhoto/Sentence, markSentencesModifiedByUser, undo,
    nameplate) keys on positive ids or photo rows -- no existing writer can
    reach the negative namespace. The console's own delete helper
    (deleteStremioOccurrencesForDoc) double-locks with photo_id < 0; a
    photo-survives-cue-deletion spec pins that guarantee
    (StremioConsoleInstrumentedTest).
    STAGE-2 (needs its own go + device session): make photo_id nullable + add
    a source column via guarded rebuild, convert the inbox/sentence readers to
    LEFT JOIN with a synthetic-source label, decide is_manual/GOLD semantics
    for video rows, then close the §11.1 mass-delete pin against the final
    shape. Every stage-1 row survives stage 2 unchanged (negative ids simply
    gain an explicit source marker).

    RETIRED 2026-11-23 (user go): stage 2 landed as v15 media_refs under the
    more general sentence-media design -- see docs/sentence-media-refs-design.md
    (§10 for deltas, spikes/006 for evidence). The negative photo_id namespace
    no longer exists in new writes and is backfilled away on upgrade; the
    §11.1 mass-delete pin and media extraction remain open items unchanged.

7. SPIKE PLAN — QUESTIONS, PASS, KILL
--------------------------------------
  Every step runs from a debug-only test screen behind a flag; no shipping
  UI until S0-G. Measurements go into spikes/006-<name>/README.md verdict
  format.

  S0-A  Auth + library + addon discovery from the phone.
        Do: link flow -> datastoreGet -> addonCollectionGet; dump shapes to
        fixtures; compare show list against the Stremio app's own library.
        PASS: counts match; every addon's transportUrl + resources[] parse;
              time_offset unit recorded for your devices (phone app +
              desktop app both exercised once).
        KILL: link-flow or datastoreGet broken -> design blocked upstream;
              escalate to the parked watch-time capture idea (parked doc
              §B.2), this doc parks.
        (Low risk: this is the same API the official clients use.)

  S0-B  Watched bitfield decode.
        Do: implement decode; compare per-episode check state to Stremio UI
        on 3+ shows with irregular patterns (gaps, mid-season restarts,
        marked-watched-without-watching).
        PASS: 100% agreement on all sampled shows, or documented systematic
              difference we then treat as noise.
        FALLBACK (this one cannot kill the design): bitfield unknown/corrupt
        -> no pre-checks; every checkbox is yours. Human-declared scope is
        the safety net -- note the asymmetry: S0-B failing degrades UX, not
        correctness.

  S0-C  Episode list + subtitle resolution + file download + cue parse.
        Do: for 5 real episodes across 2 shows: meta list -> subtitle addon
        list (filtered to your language) -> download chosen version ->
        parse to cue array; hash files; diff versions a release-group apart.
        PASS: parse OK 5/5; your usual version resolvable by name-pattern at
              least 4/5; cue keys stable across re-download (fileHash+startMs).
        KILL: subtitle addons refusing anonymous resource calls from a
              non-Stremio client (UA sniffing etc.) -> park subtitle mining;
              escalate to laptop-side file fetch (breaks android-only premise;
              would reopen the pruned event-transport question (parked
              doc §A).

  S0-D  Audio track probe (THE design's one genuine unknown).
        Do: replay user's stream addon -> get file URL for the same 5
        episodes -> MediaExtractor.setDataSource(url) -> enumerate audio
        tracks (MIME, KEY_LANGUAGE, channels) -> identify the jpn track;
        record which addon configs return: (i) direct RD URL, (ii) magnet,
        (iii) external/app-player hints.
        PASS: >= 3/5 playable URLs AND >= 3/5 correct-language track
              identified; probe p95 < ~5s; bytes read during probe small
              (header-seeking only, no full download).
        FALLBACK LADDER (no kill, by design):
          tier1: probe works -> pick track by language in settings sheet;
          tier2: probe flaky -> store a language HINT only ("prefer jpn"),
                 verify at card creation time later (parked doc §C S3.3);
          tier3: addon returns no direct URLs for your config -> settings
                 show "audio track: default/unknown" explicitly; the D6
                 ruling (clip must match subtitle) is then enforced at
                 slice-time only, never claimed here.
        NOTE: audio choice is settings-persistence for FUTURE media cards;
        text mining does not depend on M6 at all. S0-D failing does not
        delay S0-E.

  S0-D2 RD-side inventory (small; same debug screen as S0-D).
        Do: /user; /downloads first page + ETag revalidation round;
        /torrents info for an episode you just watched. Record: (i) do
        torrent-sourced STREAMS actually appear in /downloads (the docs
        imply yes; verify), (ii) episode-parse rate over your last ~10
        real entries (regex vs weird release names), (iii) can we recover
        infoHash for files we will later want sliced, (iv) is
        instantAvailability fast enough to be a pre-flight gate, (v) what
        `remote=1` costs, if anything (documentation only for now).
        PASS: parse rate >= 80% AND (i) confirmed -> add the recent-files
              candidate feed as a pre-check HINT (D9 ships).
        FALLBACK: either fails -> M9 shrinks to /user expiry + infoHash
              stash, no candidate feed. RD extras NEVER block the design:
              everything they add is convenience over the Stremio-only
              flow.

  S0-E  End-to-end one episode through the real pipeline.
        Do: 1 episode -> M7 -> mining.db -> appears in triage/occurrences
        with source tag; rerun same settings = zero new rows (idempotency
        spec); change settings generation -> offers re-extract, old rows
        stay until you prune (D6); known-word filter + frequency ranking
        untouched and provably so (same code path as photo sentences).
        PASS: all assertions green incl. a v14 migration demo on the corpus
              copy; bundle round-trip restores ledger + settings.
        KILL: none plausible short of "the existing engine rejects non-photo
              sources", which would be a repo-wide finding, not a spike fail.

  S0-F  Batch + background behavior.
        Do: queue 10 episodes via WorkManager on a real battery cycle;
        kill the app mid-batch; resume.
        PASS: queue survives; each job retries-with-backoff on network
              failure; no notification spam; total subtitle bytes per
              episode reported (should be tens of KB).

  S0-G  Verdict: assemble pass/fail table -> decision table §8 -> UI build
        gets its go with S0-A..F evidence recorded in the spike README.

8. WHAT EACH VERDICT CHANGES
------------------------------
  A,B,C,E pass + D any tier -> ship the Console (text mining, manual scope),
      with whatever audio setting fidelity tier D landed on.
  C fails                 -> the android-only premise breaks; the parked
      watch-time capture design becomes the only subtitle path; this doc
      parks behind it.
  D lands tier2/3         -> ship anyway; cards-with-audio waits for
      parked-doc §C S3.3 verification, which now has your persisted hint
      to check against.
  B fails                  -> ship anyway; checkboxes manual (UX only).

9. DECISION POINTS -- ALL RULED 2026-11-23
--------------------------------------------
  D1. [1 - DECIDED] Global default + per-show override; per-episode
      override behind an "advanced" fold.
  D2. [1 - DECIDED] Audio-track probe failure degrades to language hint +
      "verify later" state; extraction is never blocked by audio
      uncertainty.
  D3. [1 - DECIDED, strengthened by clarification] Whole episodes only.
      The resume pointer has NO bearing on mining runs; it is a
      reference-only display. "Up to pointer" mode is out of this design
      permanently.
  D4. [1 - DECIDED] Cues go straight into the existing occurrence/known-
      word/frequency pipeline with source tag 'stremio:...'. No staging
      queue, no new engines.
  D5. [RULED - NO WARNING MODEL] User: no warning/confirm gates. Watched
      hints and the pointer are affordances only; the UI is unopinionated
      about what gets mined -- any episode can be queued silently,
      watched or not. (Supersedes both originally offered options.)
  D6. [1 - DECIDED + CAVEAT PINNED] Settings change creates a new
      generation; generations coexist. CAVEAT: mass-delete of a mining
      run is a DESIGN PIN (§11.1) -- the coexist-forever model is only
      acceptable with that escape hatch on the roadmap.
  D7. [1 - DECIDED] Schema v14; ledger + settings IN the bundle contract,
      stream URLs and secrets OUT. This ruling IS the explicit sign-off
      the column-contract rule requires; the ceremony runs at
      implementation.
  D8. [1 - DECIDED] Subtitle identity = (addonId, subId, releaseName) +
      our fileHash; the URL is never stored as identity.
  D9. [1 - DECIDED, gated on S0-D2] RD recent-files as pre-check HINTS
      only; identity stays Stremio's. S0-D2 failing its pass bar drops to
      tier 2 (expiry chip + infoHash stash).
  D10. [ADDED + DECIDED] File-level system key = infoHash (+fileIdx),
      per the §5.5 identity spine; videoId remains the episode key.

10. RELATIONSHIP TO THE AUTO-SCRUB DESIGN (PARKED)
----------------------------------------------------
  Auto-scrub / watch-time capture is PARKED by user ruling 2026-11-23
  ("Let's go this route for now. Park the idea of automatic scrubbing
  based off watching."). Pick-up summary + preserved verified facts live
  in docs/stremio-auto-scrub-parked.md. What this console
  already banks for a possible resumption, so restarting the parked idea
  later is cheap rather than a rewrite:
   * M5 (subtitle resolution + settings) fills the parked design's former
     P2 resolver role, with the human supplying what it wanted to guess;
   * M8 ledger + M4 snapshots are the watched-snapshot store its salvage
     policy (parked §0.1f) needed anyway;
   * §5.5 infoHash spine answers its media re-mint keying requirement;
   * M6/M9 probe results are its S3.2 RD-link measurement in miniature.
  What the parked design still owes before it could resume at all: the
  capture-freshness and ground-truth answers (desktop CDP feasibility,
  per-client offset units, subtitle ground-truth from the player). None
  of that blocks anything in this console, because the human declares
  scope here.

11. PINS & FUTURE THREADS (design pins -- noted, no detail work now)
----------------------------------------------------------------------
  11.1 MASS-DELETE MINING RUNS (user caveat on D6; LOW priority):
       delete every cue + ledger row for a chosen run set (per episode /
       per show / per generation). Shape already supports it: ledger rows
       group by (videoId, settingsGeneration). When built, the deletion
       must route through the repo's existing undo + provenance
       machinery like any other destructive path (wizard-referenced
       sentences, bundle integrity). Pin, not spec.
  11.2 INTRO/CREDITS EXCLUSION (user future thread; roadmap note):
       mine cues while excluding opening/credits time ranges. Useful
       discovery: Stremio itself computes intro/outro ranges internally
       (player.rs intro_outro_update + skip_gaps, CREDITS_THRESHOLD_COEF)
       and skip-intro-style addons publish them per episode -- a future
       SubtitleBundle sibling could carry ranges alongside the cues.
       ROADMAP ENTRY RECORDED 2026-11-23 (docs/roadmap.md Open issues item 8);
       the line as pasted read:
         "- [ ] Stremio console thread (parked, user 2026-11-23): exclude
            intro/credits ranges from mined cues; addon-provided skip
            ranges as source. Pin:
            docs/stremio-mining-console-design.md §11.2."
  11.3 CARDS-WITH-MEDIA PHASE (deferred): screenshot + audio clip per
       mined cue, assembled from the §5.5 spine (episode -> infoHash ->
       fresh link), the stored audio_track_id, and offset. Extraction
       technology decision resumes from the parked doc §0.3 bake-off
       (native platform vs community fork vs self-built minimal FFmpeg)
       and its S3 measurement plan. Text-only mining ships without this.

12. BUILD PASS 2026-11-23 (user one-shot go; this section records WHAT EXISTS)
--------------------------------------------------------------------------------
  Shipped main sources: StremioModels / StremioTransport / StremioClient
  (link flow, datastoreGet, addonCollectionGet) / AddonResources (meta,
  subtitles, streams incl. infoHash+fileIdx, S0-D2 filename parser) /
  SubtitleParser / WatchedBitfield (v14-adjacent: pure Kotlin port) / RdClient
  (read-only M9) / StremioWrites (storage extensions) / StremioPrefs (secrets
  in private prefs, OUT of bundles by construction) / StremioExtractionEngine
  (whole-episode jobs, generation = settings content hash, replace-in-place
  idempotency, best-effort identity probe) / MiningPipelineHolder.
  CorpusInsertion.kt extracted VERBATIM from MiningPipelineRunner (photo path
  now delegates; full suite green with ZERO test-body edits = mechanical
  proof, per repo rule).
  Schema v14 (seven stremio_* tables) + bump ceremony complete: DB_VERSION,
  backup_bundle SUPPORTED_USER_VERSIONS, docs/recovery.md prose (v13 catch-up
  included), NameplateClaims literal re-pin, nothing added to REQUIRED/
  MINING_COLUMNS.
  UI: StremioConsoleActivity (library list, link flow, RD token) +
  StremioEpisodesActivity (bitfield pre-checks, mined chips, resume badge
  reference-only, D1 settings dialog incl. per-episode long-press override,
  serial-lane extraction) + manifest + Settings-popup entry.
  Evidence + the accepted reader gaps + what still needs the user (S0-A live
  pin via link approval, first UI walkthrough, device session): see
  spikes/006-stremio-console/README.md verdict table.
