# Spike design — Sentence→Media references (extensible, optional)
Status: DESIGN PROPOSAL awaiting user rulings (D1-D5). No implementation go.
Date: 2026-11-23. Trigger: user ruling on the stremio stage-2 discussion --
"occurrences map to sentences. sentences have an optional field: media.
It should be as simple as a new foreign key. Also allow easily for other
media forms in the future." This doc deliberately speaks in media-generic
terms; the stremio mining console is referenced only as consumer #2 and as
the owner of one provisional hack this design retires.

## 0. STATE OF THE WORLD (verified in schema + code, 2026-11-23)
  item_occurrences (MiningRepository.kt onCreate):
    id, language_tag, item_type, expression,
    photo_id INTEGER NOT NULL,          <-- the constraint this design relaxes
    sentence TEXT NOT NULL,             <-- "sentence" = duplicated column, not a row
    created_at, state, modified_by_user_flag, is_manual
  scraped_photos: media_store_id PK, content_uri, date_added, status,
    extracted_text, ocr_blocks_json, modified_by_user_flag, is_gold,
    flag_color, duplicate_of_photo_id.
  Facts that shape every option below:
  F1 A "sentence" today is a TEXT VALUE repeated across one occurrence row
     per matched word. There is no sentences table. Sentence-level state
     (user-edited flag, gold) lives on the PHOTO row (scraped_photos) and on
     occurrence rows via markSentencesModifiedByUser (MiningRepository:660) /
     updateSentenceText (MiningRepository:725) -- both keyed (photoId, text).
  F2 photo_id is NOT NULL with no foreign key. Non-photo media therefore
     cannot be expressed today except by value-space tricks. The stremio
     console's stage-1 landing uses one such trick: photo_id = NEGATIVE
     cue-document id. It works and harms nothing (writers audited; all
     photo mutators key on positive ids), but it is a provisional hack with
     a documented shelf life, and it does NOT generalize: every future
     media kind would need its own sign/range convention.
  F3 Occurrence writers (complete list): MiningRepository.insertOccurrence
     (:1423, called from MiningPipelineRunner :305/:321/:407) and
     CorpusInsertion.insertCandidates (called from pipeline :721 and from
     StremioExtractionEngine :112-115 with the negative ids). One shared
     insertion path already exists.
  F4 Seven read sites join occurrences to scraped_photos and therefore see
     only photo-sourced sentences (the stremio stage-2 plan's R1-R7 table in
     docs/stremio-stage2-plan.md §2 has file:line for each).
  F5 Rebuild precedent exists: v9 rebuilt nameplate_claims via
     _new + RENAME (MiningRepository.kt:171); v14 materialized stremio
     tables with full ceremony. Additive precedent also exists: ALTER ADD
     COLUMN for v7 is_gold / v11 flag_color.
  F6 The recovery bundle ships the whole mining.db file (validation covers
     the three photo-era tables only), so any new tables ride automatically;
     old bundles restored into a new app migrate-on-open (established rule).

## 1. GOAL / NON-GOALS
GOAL: model the user's sentence plainly --
    word -> occurrence row -> sentence -> OPTIONAL media (photo, video, or a
    kind not invented yet). Every occurrence flows through the existing
    inbox; no parallel inbox.
NON-GOALS (each has its own door elsewhere):
  - actually producing video/audio clips for a sentence (extraction lives in
    the console design's later stage; this design only gives a sentence a
    pointer that CAN mean "clip extractable from here").
  - first-class sentences table (this design's M2; trigger-gated, §3).
  - gold/flag semantics for non-photo media (stays photo-keyed here).
  - multi-media per sentence (photo AND a video grab on the same sentence)
    -- representable under M1's model but no UI for it; say the word.

## 2. THE HONEST COST OF "SIMPLE OPTIONAL FIELD"
Adding a column is cheap (ALTER). Making photo_id OPTIONAL is not: SQLite
cannot flip NOT NULL in place; it requires one guarded rebuild of
item_occurrences. That rebuild is unavoidable for any real
"sentence.media optional" model (including any design where video sentences
have occurrence rows). There are exactly two escape hatches, both rejected:
keep abusing sign/range conventions (F2 -- does not extend), or a
0-sentinel "not a photo id" while photo_id stays NOT NULL (a lie in every
future reader's join predicate; worse than F2 because it looks legitimate).
So the design accepts: ONE rebuild, used to do all the multiplexing at once.

## 3. MODELS
M1 (RECOMMENDED) -- media pointer on occurrences, sentences stay text:
    NEW TABLE media_refs (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      kind TEXT NOT NULL,              -- 'photo' | 'video_cues' | future
      ref_key TEXT NOT NULL,           -- MediaStore id / cue-document id /
                                       -- opaque key for future kinds
      label TEXT,                      -- display: filename, show title
      attrs_json TEXT,                 -- kind-specific: timestamp ranges etc.
      UNIQUE(kind, ref_key)
    )
    item_occurrences gains  media_id INTEGER NULL REFERENCES media_refs(id)
    via the SAME v15 rebuild that makes photo_id NULLABLE:
      rebuild does: INSERT INTO media_refs from every distinct positive
      photo_id (kind='photo', ref_key=photo_id, label=content_uri tail) and
      from every negative photo_id (kind='video_cues', ref_key=-photo_id,
      label from cue document row, attrs={video_id, generation});
      occurrence rows get media_id filled accordingly; negative rows also
      get photo_id := NULL (retires the hack atomically; zero left behind).
    Readers: F3 writers pass a mediaRefId instead of a signed Long; F4
    readers change from `JOIN scraped_photos p ON o.photo_id = p.media_store_id`
    to `JOIN media_refs m ON o.media_id = m.id` + per-kind branch or
    kind-scoped filters. Photo-sentence behavior is identical by
    construction because backfill is a bijection of the current data.
    Extensibility (the actual ask): a new media kind = new kind value +
    rows; occurrence and reader shape NEVER changes again. Detail tables
    per kind stay wherever they already live.
M2 (DEFERRED, trigger-gated) -- promote sentences to rows:
    sentences(id, text, media_id, flags...) ; occurrences gain sentence_id.
    Needed IF/WHEN: sentence-level flags must exist independent of media
    (e.g., gold for video), duplicate-text cross-source collisions become a
    real problem (F1's text identity merges equal sentences from different
    sources in distinctSentencesByDate), or one sentence must carry TWO
    media (photo + video grab). M1's media_refs does not block M2 -- it is
    the table M2 hangs sentences on. Trigger = first of those needs;
    decision recorded in §8 D5.
M0 (status quo + signed ids, REJECTED): cannot express "optional", cannot
    extend to kind N, and leaves the negative namespace permanent.
M3 (EAV attrs / wide typed columns per kind on occurrences, REJECTED):
    photo_id + video_doc_id + audio_clip_id + ... = schema churn per kind,
    the exact anti-extensibility the user asked to avoid.

## 4. UI CHANGES REQUIRED BY M1 (all inside the EXISTING inbox)
  U1 Row model: every inbox/detail row resolves its media through
     media_refs. Renderer registry keyed by kind -- one small Kotlin file:
       'photo'     -> today's Glide/thumbnail path, photo viewer on tap
       'video_cues'-> text card + source label (show title chip); NO fake
                      thumbnail (the stage-2-plan I2 lesson: null-uri
                      flooding adapters is how pages go blank)
       unknown     -> kind label + text-only fallback (forward-compat rule:
                      readers never crash on a kind they don't render)
  U2 Filters: one segmented control on the inbox (all / photos / other)
     reading media_refs.kind. Word counts/frequency include ALL kinds so
     ranking reflects total exposure (open policy: newest-generation-only
     for video re-mines, inherited from stage-2 plan I1).
  U3 Word detail: "where seen" splits photo grid (unchanged) + a media list
     grouped by kind (labels only in M1).
  U4 Sentence editor: unchanged in M1 (still keyed on media row + text for
     photos; non-photo sentences are read-only -- editing a sentence whose
     media has no text store needs M2's flags or per-row text; see R2).
  U5 Anki export: card image slot stays photo-only; non-photo sentences
     export text (+label line). addMediaFile call sites must gate on
     kind='photo' (stage-2 plan I8).
  U6 Recovery/undo screens: photo-centric flows keep keying on
     scraped_photos; media_refs is inert for them (no UI).

## 5. RISKS AND MITIGATIONS
  R1 Hot-table rebuild (item_occurrences is the write-core). MITIGATE:
     (a) backup bundle taken before migration (existing recovery.md path);
     (b) all-or-nothing transaction, integrity_check + count/sum parity
     assertions inside the migration itself (row count, per-kind counts vs
     media_refs; failures abort -> pre-migration db untouched);
     (c) STAGED variant if user prefers (D1b): v15 = additive media_refs +
     media_id + dual-WRITE with parity tests over a soak period, v16 =
     rebuild to nullable photo_id + backfill + retire dual-write. Two
     smaller ceremonies instead of one.
  R2 Text identity fragility (F1): unchanged by M1 (pre-existing), but
     non-photo sentences have no per-source text-edit story. MITIGATE: M1
     declares non-photo sentence text read-only (UI U4); M2 trigger §3.
  R3 Dual-pointer confusion during rollout: readers might mix photo_id and
     media_id predicates. MITIGATE: after v15, a drift-guard test (repo
     precedent: RulingAuthorshipCallSiteTest) fails on NEW main-source
     occurrences of `o.photo_id` in sentence readers; allowlist legacy sites.
  R4 Bundle/restore compatibility: new tables absent in old bundles ->
     migration backfill runs on restore (media_refs derives 100% from
     occurrence+photo data; nothing invented). MINING_COLUMNS contract
     gains media_refs entries (scripts/backup_bundle.py + RecoveryManager
     REQUIRED_COLUMNS) additively; pre-M15 bundles keep validating (rule:
     never REQUIRE new columns).
  R5 Undo/nameplate/gold/duplicate-detection are photo-scoped by id or row
     -- media_refs cannot confuse them (they never consult it in M1). Pin
     with tests rather than trusting: undo a photo run -> video-kind rows
     untouched; duplicate detector scans keep their scraped_photos base
     query.
  R6 Performance: +1 join on hot list queries. MITIGATE: indexes
     idx_occ_media(media_id), idx_media_refs(kind); the rebuild is the right
     moment to add covering indexes the F4 queries already want.
  R7 The F2 legacy negatives must be zero after migration; instrumented
     assertion `SELECT COUNT(*) FROM item_occurrences WHERE photo_id < 0`
     == 0 post-migration (and the console's delete-doc helper loses its
     double-lock comment as photo_id=NULL rows retire -- update specs).
  R8 Stremio stage-1 rows survive: per stage-2 rule, every stage-1 row
     keeps its data; only its pointer representation changes in v15.
     Ledger/cue tables untouched.

## 6. MIGRATION SKETCH (v15; D1a single-ceremony version)
  1. takeBundleBackup (existing)
  2. BEGIN IMMEDIATE:
     CREATE TABLE media_refs; CREATE item_occurrences_v15 (photo_id NULL)
     + indexes; INSERT INTO media_refs from distinct o.photo_id > 0 (join
     scraped_photos for label) and from distinct -o.photo_id < 0 (join
     stremio_cue_documents for label/attrs);
     INSERT INTO _v15 SELECT ..., CASE WHEN photo_id>0 THEN photo_id END,
       ..., media_id FROM item_occurrences JOIN media_refs ON ...;
     DROP + RENAME + re-index + ANALYZE;
     PRAGMA integrity_check = ok; per-kind count parity; user_version=15.
  3. COMMIT. On any failure: ROLLBACK -> old table intact (RENAME never ran).
  Code after: CorpusInsertion/insertOccurrence take mediaRefId; readers
  switch joins; writers never write photo_id for non-photo media.

## 7. SPIKE STEPS (same PASS/KILL discipline; emulator-first, device last)
  SM-1 Schema+backfill on a COPY of a real device db pulled to emulator:
    PASS: parity counts, zero negatives, integrity ok, seven readers return
    photo rows identical to pre-migration (golden SQL outputs compared).
    KILL: any mismatch -> redesign, no on-device attempt.
  SM-2 Writers routed through media_refs (photo pipeline unchanged
    behaviorally; instrumented suite green + new kind='photo' assertions).
  SM-3 UI renderer registry (U1-U3) on emulator with seeded kinds; device
    smoke on the same real mined episode if one exists, else first mine.
  SM-4 Recovery round-trip: export bundle (pre-M15 fixture) -> restore ->
    backfill self-heals; export post -> restore on the older-app branch is
    explicitly NOT promised (documented one-way door, consistent with
    feature_log precedent).

## 8. USER DECISIONS — RULED 2026-11-23
  D1 RULED (a): single v15 ceremony doing pointer + backfill + photo_id
     nullability in one guarded transaction; failure path = automatic
     ROLLBACK (pre-RENAME), plus takeBundleBackup restore as the deep
     defense.
  D2 RULED: attrs_json allowed on media_refs (kinds never churn schema).
  D3 RULED: inbox default = ALL kinds mixed; single inbox confirmed.
  D4 RULED: M1 video rows are text card + kind chip, no fake thumbnails.
     Poster variant PARKED as docs/parked-features.md PF-2.
  D5 RULED: M2 sentence-promotion is trigger-gated, not scheduled.
  (Filter-control upgrade to a dropdown: PARKED, PF-1.)

## 9. WHAT THIS RETIRES / RELATES TO
  - Retires the negative-photo_id namespace (console design §6.1 stage-1
    landing shape) as of v15; docs/stremio-mining-console-design.md and
    spikes/006 record the history.
  - SUPERSEDES the mechanism section of docs/stremio-stage2-plan.md (§1
    corpus_sources view): the view trick becomes unnecessary -- R1-R7
    readers convert to media_refs joins instead; that plan's I1 (generation
    dedupe), I4 (undo property pinning), I5 (bundle note), I10/I11 (S0-A
    closeout items) remain valid and are absorbed as §5/§7 concerns here.
  - Independent of the auto-scrub parked doc; a future screen-recording or
    any-kind media plugs in as a §3 kind value with zero schema churn.
