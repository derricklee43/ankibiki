# Stremio Stage-2 plan — make video sentences first-class
Status: PLANNING DOCUMENT (walk-through + issue inventory). No code in this
round. User go required before implementation, plus a device session for
acceptance (per docs/stremio-mining-console-design.md §6.1: "needs its own go
+ device session").
Date: 2026-11-23. Grounded by live debugging the same day (commits ed5f172 →
7f1017b; device ledger + StremioSync logcat pinned descriptor envelope and the
kitsu-stub lesson).

## 0. What stage 2 is, and is not
IS: after extraction, video cues' occurrences and sentences become visible in
every triage surface the photo path uses — inbox, per-word frequency ranking,
word detail, sentence pool — with a source badge. This is the gate to the
product vision's "dedicated triage session."

IS NOT (deferred, each with its own gate):
- lazy screenshot/audio extraction + Anki media  -> stage 3 (spike S0-D/E)
- intro/credits exclusion                        -> roadmap thread
- mass-delete of whole runs (§11.1 pin)          -> §5.7 here + own go
- auto watch-time scrubbing                      -> parked doc
- gold/flags for video sentences                 -> open question §5.4

## 1. Mechanism decision — the rebuild is NOT needed
The console design (§6.1 stage-2 sketch) said "make photo_id nullable + add a
source column via guarded rebuild". That predates stage 1's landing shape.
With NEGATIVE photo_id = -stremio_cue_documents.id already living in
item_occurrences (NOT NULL column, no FK — verified: MiningRepository.kt:82
schema, insertOccurrence takes any Long):

1. Sign already encodes provenance. SQLite cannot flip nullability without a
   rebuild anyway — and we don need it: photo_id stays NOT NULL forever.
2. So stage 2 = ADD COLUMN source_kind TEXT NOT NULL DEFAULT 'photo' plus a
   one-row UPDATE backfill (CASE WHEN photo_id < 0 THEN 'video' END) — the
   addColumnIfMissing ceremony used since v11 (flag_color). v15 migration,
   guarded by the same instrumented pattern as v14
   (StremioConsoleInstrumentedTest.migration_preV14Database_*).
3. Readers stop JOIN-ing scraped_photos blindly and instead resolve a unified
   SOURCE tuple. Two implementation shapes, decision point at go-time:
   (B1) per-reader LEFT JOIN pair:
        LEFT JOIN scraped_photos p  ON o.photo_id = p.media_store_id
        LEFT JOIN stremio_cue_documents d ON o.photo_id = -d.id
        WHERE (p.status='KEEP' OR d.id IS NOT NULL)   [pattern varies]
   (B2, recommended) one VIEW so each reader changes ~1 line:
        CREATE VIEW corpus_sources AS
          SELECT media_store_id AS source_id, 'photo' AS kind,
                 status, date_added, content_uri, is_gold
          FROM scraped_photos
          UNION ALL
          SELECT -id, 'video', 'KEEP', created_at, NULL, 0
          FROM stremio_cue_documents;
        readers: JOIN corpus_sources s ON o.photo_id = s.source_id
             AND s.status = 'KEEP'
   B2 keeps the SQL diff near-mechanical (the change-audit story we want),
   gives photo-only readers a trivial s.kind='photo' filter, and view drop =
   clean rollback. Column set above is the working assumption; §3 pins it.
   (UNION ALL not UNION: no dedup cost, ids namespaces can't collide.)

Explicitly not on the table (do not "rediscover" cheaply): fabricating
scraped_photos rows for cue batches — rejected on principle, design §6.1(a).

## 2. Reader conversion table (enumerate, don't debate — repo behavior rule)
All sites MiningQueries.kt unless noted. "video-in?" = should the reader see
video rows after stage 2.

R1 getActiveOccurrences (L429; joins L501 count-subquery + L518 main)  video-in: YES
   -> the inbox/word frequency engine. Both joins -> corpus_sources.
   ExtractedList adapter + row model need a source kind (badge; no thumb).
R2 getCanonicalItemDefinitions (L556; joins L570 + L583 latest-date)    video-in: YES
   -> frequency + "last seen" ordering; latest-date reads p.date_added ->
      s.date_added (video = doc created_at).
R3 listWordRowsInFolder (L623; joins L638 + L651)                       video-in: YES
   -> same shape as R2 for folder views.
R4 getItemDetail photos list (L686, join ~L762 -> queryScrapedPhotos)   decision:
   -> NOT the photo grid. Recommend: keep the photo list s.kind='photo',
      ADD a parallel "video sources" section (show + SxxEyy, from
      d.video_id -> stremio_library.name). Synthesizing ScrapedPhoto rows
      with null content_uri is the lazy path and WILL flood thumbnail
      loaders (Glide) — reject it; typed list instead.
R5 getItemDetail matchedSentences (join ~L783)                          video-in: YES
   -> occurrence context table already keys on o.photo_id; rows gain
      source label from the view join.
R6 distinctSentencesByDate (L915/923)                                   video-in: YES
   -> THE sentence pool for "pick cleanest example". Ordering mixes photo
      date_added and video created_at — both epoch ms, comparable. OK.
R7 getScrapedPhotosFiltered EXISTS (L46/L59)                            NO CHANGE
   -> keys o.photo_id = scraped_photos.media_store_id from the PHOTO side;
      negatives never match; photo unseen flags unaffected. Verify-by-test
      that a video-only word does not flip any photo row's unseen filter.

Nameplate readers (L243 pendingNameplateClaims JOIN) stay photo-only: nameplate
claims are physically photo concepts; never widen.

## 3. Per-phrase helper audit (photoId-keyed APIs; do negatives even work?)
These take a Long photoId and query item_occurrences directly — several ALREADY
work for negative ids because the sign flows through unmodified; each needs a
one-line verdict + test, not a rewrite:
- getSentenceModificationFlags (L336), getOccurrenceSentenceRows (L348),
  getSentenceCandidates (L370), occurrenceRowsForSentence (L396),
  occurrenceStatesForExpression (L419): occurrence-table queries -> expected
  to work for doc ids as-is; any that touch scraped_photos for the row's
  TEXT must take the sentence from o.sentence instead (it already does in
  R6 — pin by test).
- markSentencesModifiedByUser / clearPhotoUserModificationFlag
  (MiningRepository.kt ~L417): update scraped_photos by media_store_id ->
  structurally cannot reach video rows. Expected issue I3: "user edited the
  sentence" state for VIDEO cues has nowhere to live. Stage-2 answer: either
  occurrence-level flag (column already on item_occurrences? verify) or
  cue-text immutability ("video sentences are not editable until stage 3"),
  decision at go.

## 4. Expected issues (compiled; each with owner-phase)
I1  Generation double-counting (frequency inflation): D6 lets two docs
    coexist for one video_id after a re-mine with changed settings. Readers
    counting per-doc would count the same episode twice. POLICY NEEDED —
    recommended: R1/R2/R3/R6 restrict video side to the newest MINED doc per
    video_id (ledger status='MINED' ORDER BY updated_at DESC LIMIT 1 per
    video_id, expressed as a view: stremio_active_docs). Cheap version:
    only insert occurrences after retiring the previous doc's rows; keep
    old doc for audit with status superseded. Decide at go; spec both ways.
I2  Inbox row UI has no thumbnail for video rows: adapters inflate ScrapedPhoto
    shape in places. Enumerate adapters at implementation time (compiler +
    a UI smoke on emulator, not device, for the null-uri hunt).
I3  Sentence-edit/gold semantics for video rows: §3 + §5.4 — needs product
    decision from user, cannot be derived.
I4  Undo machinery (UndoCore/UndoStore, 6 scraped_photos sites): all key on
    positive media_store_id + photo snapshots; negative rows are invisible to
    undo. Expected: undo of a photo run never touches video occurrences —
    KEEP that property and pin it with an instrumented test rather than
    "generalizing" undo now.
I5  Recovery/bundles: bundle ships the FULL mining.db file, so stremio tables
    ride along automatically — no orphan risk (checked: RecoveryManager
    DB_ENTRY = whole file; scripts/backup_bundle.py same). But validation
    covers only the 3 photo tables (by design) — accept + one line in
    docs/recovery.md that stremio tables are unvalidated payload.
I6  Dashboard stats (L271 counts photos/gold): video sentences absent from
    every number. Recommend: one extra stat "video sentences: N" fed by
    stremio_active_docs; cosmetic, cheap, do in the same pass.
I7  Benchmarks/fixtures: build_gold_fixtures.py scrapes PHOTO content only;
    gold set stays photo-baseline — video sentences are NOT gold candidates
    until stage 3 (media). export_gate_lexicon.py reads extracted_items —
    already sees video-derived vocab (stage-1 property); no action.
I8  Anki export path: cards built from video sentences export text-only;
    AnkiRepository.addMediaFile callers must not be handed null photo
    content_uri. Enumerate export call sites; media stays photo-scoped until
    stage 3's lazy extraction fills the gap.
I9  State-machine noise: video occurrences insert UNSEEN via shared
    CorpusInsertion -> already correct; re-run after re-mine must not revive
    user-SEEN states silently. The generation hash changing = NEW doc =
    genuinely new occurrences — acceptable, but say so in the UI chip.
I10 44 -> 13 datastoreGet parse gap (from live logcat 2026-11-23): MUST be
    explained before stage 2 acceptance, because badges resolve video_id ->
    stremio_library.name and a dropped library row = ugly fallback. Likely
    removed-tombstones + live-type rows, but enumerate by extending the
    StremioSync diagnostics with per-row skip reasons (one line) and one
    user sync tap. Gate: S0-A closeout item.
I11 time_offset unit still heuristic (ms vs s): affects only the resume
    badge; fold into the same I10 diagnostics tap; record desktop-client
    value once (S0-A PASS criteria mention both devices).

## 5. Decisions the user owes at go (cannot be taken silently)
5.1 B2 view vs B1 per-reader LEFT JOIN (recommend B2).
5.2 I1 dedupe policy (recommend newest-MINED-only counting).
5.3 R4 word-detail treatment of video sources (recommend separate section,
    not fake photo rows).
5.4 gold/modified flags for video sentences (recommend: immutability until
    stage 3, no new columns).
5.5 Inbox default: both sources mixed (matches "spoiler-free queue" vision)
    with a source chip — vs photos-first.

## 6. Implementation order (for the go session)
M1 (1 commit): schema v15 ALTER+backfill+view + migration test + R1-R6 swap +
   count specs. JVM/instrumented on emulator; device NOT yet required.
M2 (1 commit): UI source badges + word-detail video section (R4) + dashboard
   stat (I6) + dedupe view (I1) — emulator screenshots.
M3 (device session): real mined episode -> inbox shows video sentence w/
   badge, counts match, export text card; close S0-A items I10/I11 with one
   diagnostics tap; acceptance = design §S0-E list.
Rollback per commit: drop view + readers revert; v15 column is additive and
never read by old code (old APK ignores unknown column — same trick as v7
is_gold).

## 7. What is already done for stage 2 by stage 1
- shared CorpusInsertion (engine unity, R1 rows exist today, just invisible)
- negative namespace + deleteStremioOccurrencesForDoc double-lock (writer audit)
- fail-loud ledger errors + StremioSync diagnostics (I10/I11 tooling exists)
- descriptor-shaped addon cache incl. configured transportUrls (badges/links
  resolve; RD key-bearing URL available for stage 3)
