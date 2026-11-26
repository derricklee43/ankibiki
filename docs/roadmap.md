# AnkiBiki improvement roadmap

## Purpose

Prioritize the work that makes AnkiBiki a dependable personal learning tool, not
just a larger collection of features. These are recommendations from the project
assessment, not scheduled commitments. Items remain open until their acceptance
criteria are verified.

The milestone that opened this roadmap —

> I can collect material, correct it once, trust that my corrections and review
> decisions survive, and turn the useful results into study material with less
> effort than doing it manually.

— is **met as plumbing** (2026-11-13: recovery, safe reprocessing, correction UX,
wizard, gate, gold marks). The next one:

> The pipeline catches the recognition errors it can *prove*, my correction
> effort per photo trends toward zero, and every automatic change is visible,
> measurable, and reversible.

The original feature designs remain in
[the photo-mining plan](saikutsu-photo-mining-plan.md) and
[the Chinese word/hanzi sync plan](chinese-word-hanzi-sync-plan.md).
This roadmap complements those designs rather than replacing them.

**Maintenance rule (2026-11-14):** completed items live in
[the feature log](feature_log.md) — shipped work moves there verbatim with its exit
evidence; this file keeps open items, parked decisions, and dated pointers.
Small optional/low-priority features are ledged in
[parked-features.md](parked-features.md), not here.

**Organization note (2026-11-23):** the numbered assessment sections (§1–§5) and the
lettered tracks (A–D) are retired — open work now lives once, in **Open issues** below.
Historical references elsewhere to "roadmap §N" or "track X" point at that retired
organization; the completed work they name is in [the feature log](feature_log.md).
The P0 rule still holds: this file points, it never restates numbers or evidence —
measured state lives only in [docs/extraction-pipeline-state.md](extraction-pipeline-state.md).

## Current assessment (refreshed 2026-11-23)

- The capture-to-study loop is **closed end-to-end**: photo mining -> transparent
  correction UX -> explicit gold marking (金 button) -> auto-applying quality gate ->
  card-creation wizard writing real AnkiDroid notes. Recovery bundles are proven
  against the live corpus. Surfaces keep landing on top of the loop (user overrides,
  folders, cross-view undo v12, entry picks v13 — evidence in the log).
- **Extraction quality is the bottleneck, not plumbing.** The dominant error
  classes — phantom-`。` stitching failures and nameplate leaks — are OUT of the
  correction gate's reach (it fixes recognition-substitution islands only).
- The S003-D' gate ships auto-apply (user decision 2026-11-13) behind the per-island
  `ocr_corrections` audit; its real-world precision is an EMPIRICAL question the gold
  loop answers, and the accruing ~200-sample checkpoint renders live on the gold
  dashboard. Per the spike-004 verdict, the gate's nameplate-adjacency input is
  H3-recurrence ∪ ledger-confirmed strings (`OcrCorrectionPass.nameplateGateStrings`,
  pinned) — the over-excluding structural A0 proxy is retired.
- Language support is asymmetric in every direction: Japanese has morphology, a grammar
  catalogue, the gold set, AND the gate; Chinese has dictionary segmentation only — no
  grammar extraction and (deliberately) no gate, because the evidence base is JMdict.
- Lifecycle: long work runs on process-wide AppIo lanes (rotation + survives-destroy +
  serial pipeline pinned; kill -9 mid-rescan and permission-loss proven on the Pixel;
  editor draft-survival withdrawn by user ruling 2026-11-23). Still open: the
  progress/cancel/retry UI — listed below.

## Open issues (2026-11-23: one canonical list, replacing tracks and §-sections)

The governing domain decision (2026-11-10) stands over all of these, quoted in the log:
the **sentence is the primary object**, an image an optional field of it; the single
sentence view is its face; first-class sentence records are the parked page-merge
prerequisite. Suggested ordering: **item 1 is the next frontier** — it is the point of
the whole mining loop; the rest waits for an itch to spend a session on. These should
follow demonstrated learning-workflow needs, not expand scope at the expense of data
trust and extraction quality. Prefer incremental changes over wholesale architecture
migration.

1. [ ] **Add-to-existing-note path + wizard validation on the real collection**:
       append a newly mined sentence to a card that already exists; validate the wizard
       against the user's own decks on device (the 2026-11-12 emulator E2E stands as
       plumbing evidence only). Design base:
       [card-creation-wizard-plan.md](card-creation-wizard-plan.md).
2. [ ] **Resumable scan progress / cancel / retry UI.** The execution half shipped
       (serial AppIo lanes, survives screen teardown, re-issue skips processed photos —
       2026-11-14); the user-facing layer can only be proven in real use, so it waits
       for a real scan to spend.
3. [ ] **Settings surface** hosting the correction-gate kill switch
       (`PipelineConfig.ocrCorrectionEnabled`) and future toggles — today they live
       only in code.
4. [ ] **Consolidate mutation/reprocessing rules into explicit use cases** rather than
       letting Activity callbacks become the source of business rules.
5. [ ] **Deck/note-type configuration less collection-specific** — conditional: only if
       the app is meant to outlive this collection.
6. [ ] **Fresh-install photo re-link helper**: a restore today needs the device
       connected to stage photo bytes; re-linking a DB-only restore to MediaStore would
       decouple the two. (Residue recorded when the trust-foundation work closed,
       2026-11-12.)
7. [ ] **Evaluate Japanese grammar-catalogue coverage and Chinese segmentation
       quality** against the benchmark — BLOCKED behind the parked extraction work; do
       not imply that a language-routing interface means equivalent language support.
8. [ ] **Stremio Mining Console (manual episode extraction, Android-only)**: design
       ruled and documented 2026-11-23 (user) in
       [stremio-mining-console-design.md](stremio-mining-console-design.md) — D1-D10
       all ruled, spike plan S0-A..S0-G. STATUS (2026-11-23 one-shot build): stage-1
       IMPLEMENTED — v14 schema + ceremony, engine on the shared CorpusInsertion
       path (photo path rewired verbatim, suite green with zero test-body edits),
       console + episodes UI wired into the settings popup; evidence table in
       spikes/006-stremio-console/README.md (JVM 129/129, instrumented 146/146 on
       emulator). LIVE on device 2026-11-23: link flow + authKey OK, library +
       addons sync OK (descriptor envelope pinned), episodes render; four
       live bugs found and fixed via ledger/logcat evidence (spikes/006 debug
       log). Still open: first SUCCESSFUL extraction — ROOT-CAUSED 2026-11-23 as a
       CONTENT gap, not code (all three synced subtitle addons verified empty/crashed
       for jpn anime; ledger reported accurately), S0-A
       closeout items (parse gap RESOLVED 2026-11-26: server-side datastoreGet
       diff showed rows=45/parsed=13 are 32 `removed:true` tombstones correctly
       skipped, incl. the user's temp-added Demon Slayer rows — not a parser
       bug; time_offset unit), stage-2 reader
       generalization LANDED 2026-11-23 as schema v15 media_refs under
       [sentence-media-refs-design.md](sentence-media-refs-design.md) (user
       go; negative photo_id namespace retired, video sentences now visible
       in inbox/counts, I1 dedupe via active refs) -- supersedes the
       stage-2-plan mechanism, keeps its issue inventory. Device smoke
       (SM-3/SM-4) + first real extraction pending user session. The
       automatic-scrub / watch-time-capture alternative is PARKED (same ruling)
       with its verified facts and resume ladder in
       [stremio-auto-scrub-parked.md](stremio-auto-scrub-parked.md). Design pins
       the console doc carries: §11.1 mass-delete mining runs (low priority),
       §11.2 intro/credits-range exclusion (user future thread 2026-11-23,
       deliberately not hashed), §11.3 cards-with-media phase (deferred,
       contract preserved in the parked doc §C). Direct-upstream subtitle sourcing
       (Jimaku/SubDL official APIs, bypassing the Buta proxy per its author's
       ask) designed AND EXECUTED 2026-11-23: 007a Jimaku probe PASSED with
       constraints (6/6 anime eps via query-tier + native per-episode files;
       tmdb_id tier dead — spikes/007 README §16); SubDL pinned as future work
       (user ruling); ASS parser P1 SHIPPED (+ prod filename bugfix); production
       builtin `JimakuClient` SHIPPED same day user go (D4a rung, C1/C3/D3a
       constraints, prefs key+cache, JVM 144/144) — DEVICE WALKTHROUGH COMPLETED
       2026-11-26: FIRST SUCCESSFUL EXTRACTION on device (Banana Fish S01E02
       tt8515016:1:2, 396 cues -> 867 item_occurrences, app responsive mid-mine;
       the two device stalls root-caused and fixed same session — shared-pool
       shutdown crash + AnkiDroid-IPC-inside-transaction, see spikes/007 README
       §16 item 9; JVM 153/153 fresh incl. 5 new plan specs + lane-lifecycle
       guard). Still open: instrumented emulator run, SM-3/SM-4, C2 sentence
       verification (needs a season-0 row mined end-to-end, e.g. Gachiakuta E04
       walkthrough), 44->13 parse-gap closeout (RESOLVED as tombstones above).
       Console UI rulings 2026-11-26 (user, implemented + device-verified on the
       Demon Slayer episode list): unwatched rows carry NO label ("watched" kept
       for watched rows), nothing is pre-selected (supersedes the watched&&unmined
       pre-check design default), and the whole list row — not just the checkbox —
       is the selection touch target (ripple added; long-press still opens
       per-episode settings). Sentence view media support SHIPPED 2026-11-26
       (user request, device-verified on a real Demon Slayer cue sentence):
       provenance header "Series - S{season}-E{episode}  [title]" (series from
       attrs_json going forward / stremio_library fallback; engine now persists
       series+episode title into the media ref attrs), the parsed-word chips +
       編集 editor (rename/add/remove) generalized to a (media_refs.id,
       sentence) scope — MEDIA_WORDS_EDIT undo with active-ref guard, photo
       path untouched — and 文を削除 soft-delete of the whole sentence with
       SENTENCE_DELETE undo (re-mine also restores). JVM 157/157 fresh
       (4 MediaProvenanceTest pins); VideoSentenceOpsInstrumentedTest (3 specs)
       pending emulator run with the rest.
9. [ ] **User dictionary (add own words — names — so parsing extracts them
       right)**: design doc DELIVERED 2026-11-26 (user request) at
       [user-dictionary-design.md](user-dictionary-design.md) — **RULED
       2026-11-26: D1a/D2a/D3a/D5a/D6a/D7a/D8a; D4 SUPERSEDED** by the merged
       definition model (item 10, its rulings gate this implementation too).
       Change: `user_words` table (schema v16 ceremony) + span-forcing
       WordAnalyzer decorator at the single config seam + provider-first
       definition registration + ユーザー事典 screen (hamburger entry) +
       USER_WORD_DELETE undo. Why: IPADIC misses invented names → fragments with
       no/wrong definitions; today's only remedy is the per-sentence 編集
       free-add. Exit (acceptance): `UserDictionaryAnalyzerTest` JVM specs incl.
       the empty-index byte-identity regression lock and the
       strict-substring-ignore rule; v16 migration instrumented round-trip +
       bundle restore of a v15 backup; device walkthrough = add a real name →
       mine one episode → chip shows the single token with the user's meaning;
       delete + undo round-trips.
       STATUS 2026-11-26 EVENING: shipped at 938619c + bidi-follow-up (below).
       Device walkthrough PASSED on the phone: 炭治郎 registered via UI
       (katakana input normalized), E5/E6 mined -> single-token item, 73
       occurrences, 名簿 chip + user gloss on word detail, chips in sentence
       view. JVM 174/174, emulator instrumented 161/0-fail, v16 migration ran
       clean on the real v15 db (10 legacy USER rows normalized). REMAINING:
       bundle restore of a v15 backup (recovery ritual — needs the SAF flow,
       not driven from adb). DEVICE-FIX found during walkthrough: fan-sub
       lines carry bidi isolates (U+202A..E) INSIDE names — exact-substring
       matching silently failed while Kuromoji tokenized around them (E2 doc:
       331/336 cues affected) -> SubtitleParser now strips invisible formatting
       controls at ingest; JVM spec pins it; legacy cue rows stay as-stored
       (re-mine re-parses clean).
10. [ ] **Merged definition model (one definition per item; user text +
       dictionary unified; notes untouched)**: design doc DELIVERED 2026-11-26
       (user D4 rework request: "merging user entered def and default dictionary,
       plus keep the notes section") at
       [definition-model-design.md](definition-model-design.md) — **RULED
       2026-11-26: E1a/E2a/E3a/E4a/E5a/E6a/E7a** ("this is a good idea"); §7 in
       doc records each. Change: `meaning` stays
       the single read truth (v13 write-through precedent) +
       `definition_source`(AUTO/PICK/USER_DICT/USER) + `system_meaning` revert
       target; user_definition becomes a mirror (old card_templates keep
       exporting); the 意味/定義 two-row UI collapses into one definition row
       with source chip + pencil + 変更 picker + 辞書に戻す; ノート row
       untouched; USER survives re-mine via the upsert carry path. Why: today
       three parallel definition voices with no winner — the v13 proposal itself
       named the flaw. Exit (acceptance): `DefinitionSourceLadderTest` JVM pins
       (USER never auto-overwritten; v13 pick behavior byte-identical),
       upsert-carry instrumented hazard spec (rule 7), re-mine-over-USER-device
       walkthrough, `DraftAdapterContractTest` deliberate re-pin. Same v16 bump
       as item 9 (E3) — these two ship together or not at all.
       STATUS 2026-11-26 EVENING: shipped at 938619c. Re-mine-over-USER
       walkthrough PASSED on the phone: pencil-edit 炭治郎 -> "my boy - hand
       typed" (chip ユーザー), E6 re-mined -> meaning + USER stamp +
       system_meaning revert target all intact, kana carried. Migration on real
       db normalized 10 legacy user-definition rows. REMAINING: same
       v15-bundle-restore ritual as item 9.

Acceptance framing for the list: interrupted work resumes or retries without corrupting
or duplicating data; a fresh checkout has a documented path to a working build; a fix
at one pipeline stage is never presented as fixing all four error classes; passing
implementation tests is necessary but is not an accuracy benchmark.

## ⏸ PARKED — extraction & text processing

User decision 2026-11-13: the pipeline work freezes here until un-parked.
**docs/extraction-pipeline-state.md** is the single re-entry kit: measured state,
decision log, gated invariants, backlog with entry points, and the re-entry
protocol. The gate keeps auto-applying and accruing audit rows passively while this
sleeps. Parked items, biggest lever first:

1. **Stitching fixes** for parser-bound photos (`CjkSentenceParser`: phantom-`。`
   guard + wrap stitching; target per the harness, not restated here).
2. **`gate_score.py`** — score `ocr_corrections` against marked gold. The accruing
   N/200 checkpoint VIEW is already on-device (Settings → 金ダッシュボード, see the
   log); this parked item is the scoring SCRIPT.
3. **Claims ledger follow-through** — pending 正しい/違う rulings, H9v2 merge
   proposals, then the A6 fuzzy-recurrence revisit.
4. **Benchmark completion** — representative fixture set (menus/furigana/Chinese,
   poor photos, wraps), wrong-candidate (precision) metric, correction-effort
   measure; gold semantics as decided (explicit 金 marks = finalizing stamp; era
   started from zero at v7; committed fixtures = baseline until re-marking).
5. **Chinese gate port** (CC-CEDICT evidence base; deliberately last).

Benchmark artifacts, statuses owned by the harness doc:
[baseline](benchmarks/2026-11-10-extraction-baseline.md) ·
[gold baseline](benchmarks/2026-11-12-gold-baseline.md) ·
[provider A/B](benchmarks/2026-11-12-provider-ab.md) ·
[error census](benchmarks/2026-11-13-error-census.md) (regenerate with
`scripts/error_census.py` after the next run) ·
[iteration plan](benchmarks/gold-set-iteration-plan.md) ·
[spike 002](../spikes/002-ocr-plausibility-gate/README.md) ·
[spike 003](../spikes/003-gate-upgrades/README.md).

## Parked ideas

### Homophone / sense dropdown (pulled from the dissolved UI track 2026-11-23 by user)

User parked the whole homophone line ("We are parking the homophone"), following the
2026-11-15 table ("pending my thinking"). The spike DESIGN is written but un-run:
[spikes/005-homophone-disambiguation](../spikes/005-homophone-disambiguation/README.md)
— its Option C also owns the pin-which-sense half of the わし question. The manual
item-level half of that case shipped 2026-11-23 (entry picks, schema v13 —
docs/feature_log.md); per-occurrence senses and the runtime-join question stay here.
No implementation, no experiment run until the user asks. Re-entry trigger: the user asks.

### Batch "create cards" from the unseen list (pulled 2026-11-14 by user)

Was a card-making item (batching the one-word-at-a-time wizard over the unseen list).
User decision, same standing as bulk photo ops: not to be implemented until he
personally feels the want — no spec, no investigation record, nothing written ahead of
that. Re-entry trigger: the user asks.

### Bulk photo ops — mark / ignore / delete (pulled 2026-11-14 by user)

User decision: not to be implemented until he personally feels the want — no spec, no
investigation record, deliberately nothing written ahead of that. Re-entry trigger: the
user asks. (Design note if/when: 削除 is the one op with corpus consequences — today no
photo-deletion path exists in the app at all, so it would need the same reversibility
ceremony as every other destructive surface here.)

### Merge photo page + sentence page into one (pinned 2026-11-13 by user after investigation)

User model: one page, the photo optional. Investigation verdict: worth doing, but the
long pole is **sentence granularity** — the photo page renders all sentences as one
static blob with a blob editor, so merging means per-sentence rows (tap-to-expand chips
+ word editor + amend) that stay coherent with blob-level reprocess/stale semantics and
the pinned sentence-ordering tests. Stage plan when un-parked: (1) sentence page grows
the full photo panel (gold/rescan/raw-OCR/claims/stale — moved, not rewritten; low
risk), (2) text becomes sentence rows, blob editor demoted to overflow, gallery opens
photo-anchored, (3) delete PhotoDetailActivity and rewire ~6 instrumented classes that
deep-link these activities. Open decisions to settle first: row interaction shape;
keep-or-kill the blob text editor; swipe axes (horizontal=photo vs sentence). Cost
estimate: 2–3 sessions, stage 2 the big one.

### Speaker-nameplate exclusion (pinned 2026-11-10; spike-004 verdict recorded 2026-11-13)

**Auto-exclusion NOT GO** (measured against the pre-registered bars on the 126-photo
human-truth set; full tables and deviations in
[spikes/004-nameplate-exclusion/README.md](../spikes/004-nameplate-exclusion/README.md)).
Manual strip + claims-ledger rulings remain the mechanism; A6 (fuzzy recurrence) is
written up but unmeasured. Standing guardrails for any future policy: the parser only
*flags*, never deletes (dropping is policy); opt-in per photo, default KEEP, a boolean
is the wrong primitive; raw `ocr_blocks_json` stays authoritative so policy flips
without re-OCR. Shipped consequence: the gate's plate-adjacency input is H3-recurrence
∪ ledger-confirmed strings, replacing the A0 proxy. Nothing drops by nameplate reasoning
alone — hides ship only behind the user's own 正しい verdict (claims ledger v8).
