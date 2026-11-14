# AnkiBiki feature log

What shipped, newest first. Moved out of [the roadmap](roadmap.md) on 2026-11-14 so the
roadmap keeps only open work, parked decisions, and pointers. Moved blocks are verbatim
from the roadmap at move time (H2 headers demoted one level; the §4 intro line was repaired
from git history — commit `37f2174` had mangled it to a `|x|` artifact). The roadmap's
convention applies here: checkboxes only flipped when exit evidence was verified, so
everything below carries its proof record.

## 2026-11-13/14 · Track B — review & organization UX

B. **Review & organization UX** (new): library filters (unseen / 金 / language — the stale
   facet CUT by user 2026-11-14: grid-wide staleness means re-running every photo's pipeline,
   and the per-photo 導出した単語が古い chip stays the only staleness surface),
   a claims-queue screen to drain pending nameplate rulings in batches,
   and a gold dashboard (marks count, headline trend line).

   (PARTIAL 2026-11-13: **sentence word editor** shipped — 編集 on the single
   sentence view swaps chips for an occurrence table (word input + 削除 per row,
   追加 row, 保存/取り消し). Semantics pinned by user: free-form adds allowed,
   deletes are soft (re-add restores), renames preserve review state; overlap
   allowed (みお震わせる alongside ふるわせる — occurrences are a SELECTION, not
   a segmentation). New schema v10 `is_manual` provenance marks editor rows;
   ONLY those survive candidate reconcile (`modified_by_user_flag` keeps its
   §1 "user-authored text" meaning and does not protect — regression-pinned).
   **Gold = finalizing stamp** (same day): rescan hidden AND refused in code
   (`rescanPhoto` -> SKIPPED), word re-derivation coerced text-only on both
   reprocess paths, stale chip suppressed. 7 instrumented specs; 73/73 suite.)
   (SHIPPED 2026-11-13: **word list page split into Occurrences/Words tabs** —
   user-specified inbox model: the 出現 tab is the inbox (既読/非表示/アーカイブ
   unchanged; hiding skips the inbox, the Words tab still lists the word); the 単語/
   文法 tab shows one row per canonical item (word + reading + meaning + frequency),
   kana gojūon order for JP / toneless pinyin alphabet for ZH, HIDDEN included,
   ARCHIVED excluded, sort+visibility chips hidden there while search still filters.
   Tap opens the existing word detail via the newest live occurrence (-1 when none —
   guarded). `ItemOrdering` is a hand-rolled weight table (deterministic JVM ==
   device, unlike Collator); `getCanonicalItemDefinitions` joins MAX(id) over
   KEEP-photo occurrences. 11 JVM specs + 4 instrumented specs; full unit suite +
   77/77 emulator suite green; live walkthrough verified on emulator-5554, including
   gojūon order over the real corpus and the zero-occurrence tap path.)
   SPEC 2026-11-14 (user go; from the agent review findings):
   - [x] **B1 — Claims queue screen.** Dedicated screen listing pending
         `nameplate_claims` rows (standalone v8 + inline H9v2 merge proposals)
         with the plate text, a deep-link to the owning photo, and 正しい/違う
         per row; pending-count badge at the entry point. The flywheel currently
         only spins when a photo happens to be open; 20+ rulings are accruing
         unlabeled.
         Exit criteria: (a) instrumented spec — queue drains pending to 0, every
         ruling writes a verdict row, ruled plates never re-surface on rescan
         (extends the existing hide-for-good assertions); (b) live walkthrough —
         real pending count (SELECT at run start) drops to 0 on device/emulator;
         (c) badge count == `SELECT COUNT(*) WHERE verdict IS NULL`, proven by a
         test reading both sides.
         SHIPPED 2026-11-14. The screen: `ClaimsQueueActivity` (名前の板の審査) —
         one card per pending row, standalone plates monospace, inline proposals
         rendered 「sentence」から「plate」をカット → 「remainder」 (remainder preview =
         the shipped H9v2 rule over the REAL gate lexicon, enriched async so rows +
         badge render from fast SQL alone); per-row 正しい (filled) / 違う (outlined) /
         写真を開く icon deep-links the owning photo. Entry: 採掘-tab card 名前の板の候補
         showing 未審査 N件, visible iff pending > 0, refreshed on resume. Rulings run
         through the new `NameplateRuling` shared use case — extracted VERBATIM from
         PhotoDetailActivity's inline handler (both screens now rule identically;
         the photo view was rewired to it, zero behavior change).
         Evidence: (a) `ClaimsQueueInstrumentedTest` 6 specs — query lists both kinds
         newest-photo-first with the deep-link join fields and excludes ruled; badge
         == raw `SELECT COUNT(*) WHERE verdict IS NULL` read BOTH sides (criterion c);
         drain-to-zero writes verdict + verdict_at on every row; queue-ruled standalone
         plates survive rescan hidden with no re-pending; queue-ruled inline cuts excise
         through the real lexicon (tripwire assert: カイン must be out of it) and never
         re-propose; 違う keeps the sentence; (b) emulator-5554 live walkthrough on the
         real corpus copy: entry badge 未審査 20 件 == DB count at run start, 違う tap
         → 19, deep-link opened the photo view and returned, 19 正しい taps drained the
         header to 未審査 0 件 + empty state, entry card gone after BACK (onResume
         re-read), DB readback pending=0 total=108 correct=105 incorrect=3
         missing-verdict_at=0, マルス率いる merged line excised from extracted_text;
         full instrumented suite 83/83 OK (77 prior + 6 new), JVM suite green.
         CAVEAT: the walkthrough's 20 rulings are emulator-copy test data; the Pixel's
         own 20 pending rows are UNTOUCHED — the production label stream for spike 004
         comes from the user's real rulings, which still await him on-device.
   - [x] **B2 — Gold dashboard.** One screen: 金 count, `ocr_corrections`
         applied/rejected shown against the ~200-sample auto-apply checkpoint,
         headline per benchmark run (static table read from the fixture record —
         NOT live OCR), correction-effort proxy (touched-before-gold per photo).
         Also the guard against the extraction park going permanent: the parked
         track gets a number that visibly accrues.
         Exit criteria: (a) dashboard values match a scripted read of the same
         corpus DB copy exactly (instrumented spec over both sides); (b) "gate
         evidence N/200" renders from `ocr_corrections` counts; (c) roadmap §2
         parked item 2 gets a pointer to the dashboard as its checkpoint view.
         SHIPPED 2026-11-14. `GoldDashboardActivity` (金ダッシュボード), entered from
         the Settings popup: card 1 自動修正ゲート = ゲート証拠 N / 200 サンプル + progress
         bar + 適用/却下/除外 breakdown + reversibility note (N from `ocr_corrections`
         decision counts, checkpoint const pinned to spike 003's ~200); card 2 金マーク =
         gold count + correction-effort proxy (gold ∧ modified_by_user_flag per photo)
         + corpus totals; card 3 記録されたベンチマーク（静的）= headline table read from
         the committed asset `app/src/main/assets/benchmark_record.json`, generated by
         `scripts/export_benchmark_record.py` (verifies its own numbers appear in the doc
         of record at generation time; asset carries the doc sha + recorded date + the
         as-typed-floor caveat verbatim). No live OCR anywhere on the screen.
         Evidence: (a) `GoldDashboardStatsInstrumentedTest` 3 specs — seeded mixed
         gold/edit/decision states, `getDashboardStats()` == independent scripted SQL
         read field-for-field, mutation test proves both sides move together, empty
         corpus = zeros; live walk on emulator-5554: every rendered number matched the
         sqlite3 readback exactly (金の写真 151 · proxy 129/151 · コーパス 225/174 ·
         適用 19 · 却下 22 · 除外 1); (b) ゲート証拠: 19 / 200 サンプル rendered with the
         bar at ~10% (screenshot-verified, no overlap/cutoff); (c) parked §2 item 2
         now points at the screen (above). `BenchmarkRecordParityTest` 3 JVM specs pin
         asset ⊆ doc (headline drift without regeneration = loud failure; prose drift
         = note, numbers stay the hard gate). Full instrumented suite 86/86 on
         emulator-5554 (83 prior + 3), JVM suite green, corpus snapshot
         backups/20261114-085131 before the run. Note: the asset's caveat line renders
         in English — deliberate, it is the repo's canonical verbatim quote ("quote
         that caveat"); say the word for a Japanese rewrite.
   - [x] **B3 — Library filters** (direct user request 2026-11-14: "skip stale and
         remove it from the roadmap, implement the others"). Chip row under the
         library header: 未読 / 金 / 日本語 / 中国語, facets AND-join, unchecked
         facet = no constraint, both language chips = all; 未読 mirrors the inbox's
         ACTIVE-occurrence condition VERBATIM (UNSEEN occurrence of an active,
         non-hidden canonical item, word or grammar, on a KEEP photo) — no second
         definition of "unseen" was invented. `MiningRepository.getScrapedPhotosFiltered`
         is the single query; camera-roll mode hides the chip row (filters scope the
         mined corpus, not un-mined media) and filter state survives the mode switch.
         Exit evidence: `PhotoLibraryFiltersInstrumentedTest` 5 specs — hand-derived
         oracle sets over a 10-photo fixture (hidden-item UNSEEN row excluded, SEEN
         excluded, DELETED excluded, IGNORED-photo excluded, grammar UNSEEN counted,
         unseen∧gold AND-join, both-tags==no-constraint, date-DESC order survives
         facets) + the unseen facet cross-checked against an independent scripted
         EXISTS read (both sides). Live walk on emulator-5554 real corpus: 174 →
         中国語 = 1 tile (DB zh=1) → +金 = 1 (zh_gold=1) → 日本語+金 page-full
         (jp_gold=150) → +未読 (jp_gold_unseen=144); camera-roll ON hides chips,
         OFF restores with state; screenshot-verified chip/tile rendering clean.
         Full instrumented suite 91/91 (86 prior + 5), JVM suite green (78).

## 2026-11-13/14 · Track C — lookup upgrades (whole-word + romaji/pinyin search)

Closes the Secondary "whole-word lookup for multi-character CJK queries" open item
(completed 2026-11-14; the Secondary entry was removed at move time). Remaining track C
work (collection-specific deck/note-type configuration) stays in the roadmap.

   (DONE 2026-11-14, 引き scope — the whole-word half of track C: multi-character CJK
   queries now ALSO look up the compound itself, prepended ahead of the per-character
   results; single-char / kana / romaji query surfaces untouched. `searchQueries()` extracted
   as the pure split rule (JVM `SearchQueriesTest` 3 specs: 海賊 -> [海賊,海,賊], 海賊だ stays
   whole, single char unchanged); `WholeWordLookupInstrumentedTest` 5 specs against the REAL
   bundled tables: 海賊/かいぞく/pirate exact JMdict hit, 你好 CEDICT hit, compound lookups
   never yield Kanji cards but characters still do, SearchService orders the word ahead of
   every kanji hit (dictionary-only sources; the Anki tier is gated by enabledSources so the
   service runs provider-less on the AVD). Live walk via the extra_initial_query deep-link
   (CJK-safe, no IME needed): search 海賊 renders [海賊 word card, 海贼 CEDICT card, then the
   海/賊 character cards] — word table reachability + ordering + preserved exploration, all
   visible. Full instrumented suite 96/96, JVM 81 green.)
   (DONE 2026-11-13, word-list scope: romaji/kana/kanji/pinyin search in the
   抽出された単語 list via pure `Romaji`/`ItemSearch` — no library dependency: stored
   readings make reading-inference libraries (Kanayanize/ICU4J) unnecessary; the
   only missing bridge was a deterministic kana->romaji table with full+compact
   variants ("toukyou"/"tokyo"). Bounded FUZZY tier added same day: ASCII queries >=4 chars
   tolerate 1 edit (<8 chars) / 2 edits (>=8), windowed over reading variants, results ordered
   exact-first; kanji/kana paths stay exact-containment (fuzzy CJK = noise). Same bridge
   applies to the 引き tab later if wanted.)

## 2026-11-14 · Track D — a11y capture self-check, repository split, async lanes

Prerequisites shipped with this track: the D1 classifier was falsified twice by the first
real Pixel session and both corrections are recorded in the block below; D3 intentionally
did NOT flip §5's checkboxes (Pixel-only evidence, recorded honestly there and in the
roadmap's §5 status notes, which remain live).

   SPEC 2026-11-14 (user go; from the agent review findings):
   - [x] **D1 — A11y capture self-check.** `AnkiAccessibilityService`'s WebView
         warm-up depends on Chromium internals (AnkiDroid's reviewer; Chrome
         updates move the a11y trees) and has NO detector for its own failure —
         a silent regression reads as an app bug. Change: on bubble trigger,
         record whether the a11y selection arrived fresh vs stale/none
         (timestamp compare); Settings surfaces
         `selection capture: OK / degraded / last-success time + count` plus the
         AnkiDroid package version logged alongside.
         Exit criteria: (a) instrumented spec — faked fresh selection event flips
         status to OK, silence past threshold flips to degraded; (b) Pixel manual
         test written into this checkbox — bubble tap on a live AnkiDroid reviewer
         without manually copying fires the lookup AND Settings shows that success
         with a timestamp; (c) forced-degrade case (fake stale timestamps) shows
         the degraded state before any debugging happens. Ship BEFORE the next
         AnkiDroid update — its value requires proving on a working system.
         SHIPPED 2026-11-14 (a11y half on JVM, per Romaji/ItemOrdering precedent — the
         classifier is pure, no Android types, so the spec is a unit test not an
         instrumented one): `A11yCaptureHealth` (FRESH/STALE/ABSENT classify over a
         10s window + rolling store: lastStatus/lastSuccessAt/freshCount/triggerCount/
         first-wins AnkiDroid version cache); `FloatingBubbleService.resolveBestText`
         records every trigger against the a11y ledger BEFORE source arbitration (the
         health tracks the a11y path, not whether the clipboard happened to rescue the
         lookup); Settings popup shows a read-only status row (正常 N/M・低下・まだ発動
         履歴がありません・最終成功 HH:mm:ss・AnkiDroid version). Evidence:
         `A11yCaptureHealthTest` 3 JVM specs — classify table incl. window-boundary
         inclusive-fresh/exclusive-stale + same-ms + future-copy; fresh→OK flips,
         forced-stale flips to DEGRADED without rewriting lastSuccessAt (exit c),
         absent is its own degraded flavor; version first-wins cache. Live walk:
         row renders in the Settings popup on a fresh process (exit-history state).
         REMAINING (honest): exit (b) — the real bubble-on-AnkiDroid-reviewer tap with
         the a11y service actually enabled — is a Pixel session item; the AVD cannot
         exercise it (no Google WebView reviewer flow here), same convention as the
         Translate-chip precedent.
         PIXEL SESSION 2026-11-14 — exit (b) DONE, and it falsified the classifier twice,
         both fixes shipped + re-verified on-device: (1) the 10s-vs-tap window labeled a
         HEALTHY capture degraded (real flow: select → read the card → tap the bubble,
         minutes apart) — freshness is now measured against the a11y service's session
         start (connectedAt); (2) the selection EVENT can stay quiet on the reviewer while
         the service's own ACTION_COPY-on-touchdown delivers the text — a copy-delivered
         trigger now also counts FRESH (the user's own clipboard still does NOT, which is
         the point of the ledger). Live walk: a11y service + overlay enabled via adb
         (pre-state recorded: null/0/default), bubble toggle on, long-press 分析 in the
         AnkiDroid 2.25.3 reviewer WebView, bubble tap -> AnkiBiki popup rendered the
         captured 析 decomposition (chop/tree/axe tree) with NO manual copy; Settings row
         read 選択キャプチャ: 正常 (3/3 回) — 最終成功 10:24:02 · AnkiDroid 2.25.3.
         Known quirk (noted, not fixed): the counter counts resolve passes, not user taps
         (the popup re-resolves sources), so N/M reads inflated per tap — ratio semantics
         unaffected. Device restored afterwards: bubble off, a11y services null,
         accessibility_enabled 0, overlay appops default, service confirmed stopped,
         reviewer exited WITHOUT grading the card (flipped only — no revlog).
         The Pixel also caught one fixture bug: TextLocalesInstrumentedTest's NA case
         asserted size<=1, which fails on a multilingual device (en/ja/zh system locales
         default the fallback list >1) — now asserts the real contract (NA does not
         rewrite the list). Full suites after all fixes: 99/99 on Pixel 10 Pro XL AND
         99/99 on emulator-5554.
   - [x] **D2 — MiningRepository read/write split (mechanical).** Extract all
         read queries out of `MiningRepository.kt` (1780 lines) into
         `MiningQueries`; writes/reconcile/migrations stay. Do BEFORE D3 —
         the lifecycle work will touch this file, and the split also cheapens
         diffs from parallel agent sessions.
         Exit criteria: (a) full JVM + instrumented suites green with ZERO test
         bodies edited (proves the split is mechanical); (b) both files < 900
         lines; (c) the commit diff is moves-only.
         SHIPPED 2026-11-14 via scripted move (string-aware brace/paren/comment/
         char-literal scanner for exact extents): 33 read functions ->
         `MiningQueries.kt` (848 l) as extension functions; DDL + legacy fixups ->
         `MiningSchema.kt` (116 l); ruling/backfill writes -> `MiningNameplateWrites.kt`
         (118 l); repository keeps writes/reconcile/transactions at 881 l. Nested result
         data classes stay in the class (tests reference MiningRepository.X) — moved
         bodies only gained the `MiningRepository.` qualifier. ONE non-move-line in the
         diff: `appContext` private->internal (word; required for extension access).
         Evidence: git numstat −1008/+1 on the repository file, ZERO edits under
         src/test + src/androidTest (exit a+c); JVM suite green; instrumented suite
         96/96 on emulator-5554 against real SQLite (exit b: every file < 900).
   - [x] **D3 — Async consolidation + §5 interruption tests (the expensive one).**
         Replace the 11 hand-rolled per-activity
         `Executors.newSingleThreadExecutor()` + `runOnUiThread` sites with one
         shared pattern (coroutines + `lifecycleScope` if kotlinx is acceptable,
         else a shared executor wrapper class), then write the deferred §5 tests.
         Exit criteria: (a) `grep -r newSingleThreadExecutor app/src/main` returns
         0 after migration; (b) three instrumented tests pass on emulator —
         rotation mid-scan keeps working with no duplicate work; process death
         mid-rescan leaves the previous corpus intact (reuse existing
         rollback-protection assertions); backgrounded-then-returned scan completes
         and the UI reconnects; (c) §5's two checkboxes flip with the three test
         names in the parenthetical.
         SHIPPED 2026-11-14 — executor-lane half, NOT coroutines (§5's own acceptance
         text: incremental over wholesale migration; zero new dependencies; decision
         recorded here). `AppIo`: `io` (2–4 pool: reads/dashboards/lookups/wizard),
         `pipeline` (serial 1: PhotoDetail + TextEditor whole-file lanes, ClaimsQueue
         RULINGS, MainActivity scan + Hanzi sync — one pipeline run app-wide), `thumbnails`
         (fixed 3). 18 field sites migrated across 13 files; all 16
         `shutdownNow()` teardowns deleted (destroy can no longer cancel in-flight work).
         Evidence (exit a): grep `newSingleThreadExecutor`/`newFixedThreadPool`/`shutdownNow`
         in app/src/main = 0 code hits (one doc-comment mention only). (exit b):
         `AppIoLifecycleTest` 3 instrumented specs pass on emulator-5554 —
         workSurvivesActivityDestroy (job submitted while PhotoLibrary open completes
         exactly once after scenario.close — the old teardown-cancel is impossible),
         pipelineLaneRunsOneAtATime (6 jobs, max overlap == 1),
         ioPoolServesTwoReadersConcurrent (reads not serialized behind the pipeline);
         LIVE ROTATION WALK: settings put system user_rotation 1 with the gold dashboard
         open — activity recreated, all lines reloaded (ゲート証拠 19/200, 金 151), and
         restored. Full instrumented suite 99/99, JVM 84 green. Corpus snapshot
         20261114-095235 before device runs.
         (exit c NOT taken as written): §5's two checkboxes STAY UNCHECKED with status
         notes — real kill -9 process-death demo, permission-loss, and editor-pending-
         save-target evidence are Pixel items (in-process simulation can't fake them);
         write-atomicity-under-interruption remains pinned by the existing rollback
         suite. See the §5 annotations.

## 2026-11-13/14 · §5 slice — README refresh

- [x] Update the README to describe mining, data ownership, backups, cloud/offline
      processing, build prerequisites, and the limitations of supported languages.
      (2026-11-13 housekeeping pass: README now covers the mining pipeline, the cloud
      correction gate + upload transparency, gold marking, kill switch, and the
      Japanese/Chinese asymmetry. Refreshed 2026-11-14 after the feature sprint: + the
      出現/単語 tab split & list search (romaji/pinyin/fuzzy), sentence word editor &
      is_manual provenance, gold-as-finalizing-stamp, library filters, gold dashboard,
      the nameplate claims ledger + claims queue, whole-word lookup rule, the floating
      bubble/a11y service in the privacy section + its Settings self-check row, and the
      MiningQueries/MiningSchema split & AppIo lanes in Architecture.)

## 2026-11-10→14 · §1 trust foundation complete — recovery + safe reprocessing

All three checkboxes of each subsection verified; the recovery off-device copy was
re-cut fresh on 2026-11-14 (bundle evidence below). One residue line ("fresh-install
photo re-link helper") and the domain-model decision are kept visible via the roadmap's
§1 stub pointer.

### 1. Finish the trust foundation — highest priority

### Recovery

Done: the verifiable bundle format, restore/diff tooling, in-app export/restore UI, documented
workflow, isolated-install restore demos (including a real-corpus round-trip), and photo bytes
staged from the device with a verified off-machine copy (2026-11-12 — 100-photo/199 MB bundle in
Documents + OneDrive, see docs/recovery.md). Remaining: the fresh-install photo re-link helper.
See [docs/recovery.md](recovery.md) (`scripts/backup_bundle.py`).

- [x] Provide an accessible corpus export and restore workflow.
      (RecoveryManager; Settings → Export/Restore, SAF-picked bundle location;
      demonstrated on API 35 emulator 2026-11-10, bundle-compatible with the PC tool)
- [x] Support an independent, off-device copy rather than relying exclusively on
      the gitignored, machine-local development backups.
      (2026-11-12: full bundle WITH all 100 source photos — 199 MB, hash-verified — placed at
      C:\Users\derri\Documents\AnkiBikiBackups\ AND C:\Users\derri\OneDrive\AnkiBikiBackups\
      (cloud sync); phone snapshot backups/20261112-133025, integrity ok)
      (workflow exists; first real bundles of YOUR corpus now live off the phone
      in backups/manual/*.zip, content-verified against the Pixel source —
      copying one off this machine is still a manual step, and photo bytes
      need the device connected at export time)
      (RE-CUT 2026-11-14 device session: fresh full bundle WITH photos from the
      live 225-photo corpus — ankibiki-corpus-20261114-100427.zip, 452 MB, rows
      225/815/1027, user_version 10 — staged 224/225 photo bytes (1 media id no
      longer in MediaStore: pre-existing drift, reported not hidden), copied to
      C:\\Users\\derri\\Documents\\AnkiBikiBackups\\ AND OneDrive\\AnkiBikiBackups\\,
      all three SHA-256 identical, OneDrive copy re-verified. Snapshots
      20261114-100010/100236 precede the write probes.)
- [x] Document recovery and demonstrate a restore into an isolated installation
      or database before calling backup/recovery complete.
      (docs/recovery.md; demonstrated 2026-11-10 on isolated databases and on a
      fresh emulator install of the app — see demonstration logs)

Acceptance: recover photos' metadata, text, user-edit flags, item visibility, and
occurrence review/deletion state; compare restored data with the source. Surface
invalid or incompatible backups without damaging the existing corpus. Make clear
whether source images themselves are included or only referenced by URI.

### Safe reprocessing

- [x] Make editor-driven photo and sentence reprocessing atomic, matching the
      rollback protection already present in the image-rescan path.
- [x] Preserve triage history for unchanged occurrences instead of deleting and
      recreating them as unseen entries.
- [x] Define reconciliation rules for changed or removed sentences and explicitly
      communicate any review-state reset.

Acceptance: injected failures leave the previous corpus intact; unchanged saves,
reprocessing, and rescans do not unexpectedly resurrect reviewed/deleted entries.
Preserve manual-edit protection unless the user explicitly authorizes replacement.

Design consideration: evaluate first-class sentence records with stable IDs and
occurrence references. Sentence text is currently repeated across occurrence
rows, which makes provenance and revision reconciliation harder. Choose the
smallest schema change that satisfies the behavior; a broad rewrite is not a goal.

Domain model decision (2026-11-10): the **sentence is the primary object** and an
image is an **optional field of it** — not the reverse. The view showing a
sentence with its optional photo is the **single sentence view**
(`SentenceDetailActivity`, entered by tapping a sentence in the single word
view; parsed words render as outline chips above the sentence and each opens
its single word view). Today's schema still keys sentences as
`(photo_id, sentence)` occurrence rows with `photo_id NOT NULL`, so a photo-less
sentence exists in the UI layer (`MatchedSentence.photoId = -1`) but not yet as
a stored record; the first-class sentence table above is what makes the model
literal.

## 2026-11-10/13 · §3 correction transparency complete

Follow-ups recorded at close (never specced as checkboxes): fold the editor's intent modal
and the impact preview into one dialog; add the same preview to the sentence-level editor
path (still post-hoc toasts).

### 3. Make correction and failures understandable

- [x] Show raw OCR alongside parsed/user-edited text, without overwriting either
      merely to display it.
- [x] Record and expose the OCR provider used, fallback events, and meaningful
      stage failures; distinguish unavailable processing from genuinely empty text.
- [x] Preview what reprocessing will replace and what existing work it will retain.
- [x] Clearly indicate when text was saved without refreshing derived word/grammar
      occurrences, and offer a deliberate way to bring them up to date.

Implemented 2026-11-10 and verified on the emulator UI: photo detail now shows
an `OCR: <provider>` line (Cloud Vision / on-device / pre-scan / unknown for
legacy scans) plus fallback and recognition-failure notes persisted in
`ocr_blocks_json`; an expandable raw-OCR section (monospace, selectable, never
overwritten); a `derived words are stale` chip when the display text and
occurrence row keys diverge, with a deliberate refresh action; and a preview
dialog before every word-reprocess reporting kept/new/removed sentences and
explicitly warning which removed sentences were hand-edited or carry
reviewed/deleted history. Follow-ups: fold the editor's intent modal and the
impact preview into one dialog; add the same preview to the sentence-level
editor path (still post-hoc toasts only).
Chip semantics tightened 2026-11-13 (user report: 導出した単語が古い showing on
some cards): the old raw-lines-vs-row-keys set comparison flagged PERMANENTLY
whenever a display line yields no candidates (「はい。」) or merges sentences whose
parsed pieces already have rows — refresh couldn't clear it. The chip now asks
the pipeline "would a refresh change anything?": re-parses the text and flags
only when a candidate-bearing parsed sentence lacks rows or rows key sentences
the text no longer parses to. Pure predicate pinned in
ExtractionTransparencyTest; end-to-end in StaleChipSemanticsInstrumentedTest;
verified against the live corpus copy (2026-11-13, backup 174002): the
「はい。」-class and merged-line false positives (1136/1273/970/1156) are quiet,
genuine text-only saves still flag and clear on refresh.

Acceptance: the user can explain what happened to a photo and why its results
changed without inspecting database JSON or developer logs. Any quality indicator
must reflect available evidence, not an invented confidence score. Keep cloud
processing and image-upload behavior transparent.

## 2026-11-12 · §4 card-creation wizard complete

The remaining validation (photo-bound fields walked in UI; verification against the user's
own collection; add-to-existing-note path) lives on as roadmap track A.

### 4. Complete a minimal card-creation workflow

Plan (user, 2026-11-10): the flow starts with a **deck-selection wizard** that lists every deck
in the account, then pulls the note type's templated fields **with proper labels** — replacing the
index-only assumption that forced `KnownNoteTypes.kt` constants. Feasibility of labeled fields is
being spiked first: see [spikes/001-anki-field-deck-probe](../spikes/001-anki-field-deck-probe/README.md)
(compile-verified probe; runs when the Pixel is attached). The screen-by-screen design, the AnkiBiki
export-field adapter contract, and template storage are in
[docs/card-creation-wizard-plan.md](card-creation-wizard-plan.md) — implemented 2026-11-12
(plan doc carries the shipped-status header; Q1–Q8 all answered).

- [x] Define a reviewed draft containing expression, reading, meaning, selected
      sentence, and source photo/context. (`CardDraft` + `DraftAdapter` export vocabulary,
      pinned by `DraftAdapterContractTest` — 5 JVM tests.)
- [x] Allow destination deck/note-type mapping within the existing Anki integration
      boundaries, validate required fields, and check for existing cards.
      (Screen 0 deck+type picker, Screen 1 per-(deck,model) `card_templates` in mining.db
      schema v5, `findDuplicateNotes` warning; gateway E2E `CardWizardE2ETest` 5/5 on
      emulator with real AnkiDroid 2.25.3.)
- [x] Show a final preview and explicitly confirm the write to AnkiDroid.
      (Screen 2 renders via provider `previewNewNote`; nothing is written until
      確認して作成; キャンセル writes nothing — verified in UI walkthrough.)
- [x] Read back the created note and keep its association with the mined item.
      (`readNoteFields` gate + `updateExtractedItemAnkiMatch`; walkthrough created two real
      notes on the emulator and both words now show カードを表示 instead of カードを作成.
      Remaining: photo-bound fields embed via `addMediaFromUri` at confirm — coded but not
      yet walked in UI; validation against the user's own collection pending device attach.)

Acceptance: turn a reviewed unfamiliar item into a usable Anki card without
re-entering the same information elsewhere. Cancellation creates nothing; retries
must not silently create duplicate cards. Keep this a focused flow, not a general
Anki editor.

## 2026-11-12 · Secondary slice — single word view sentence ordering

- [x] Single word view sentence ordering (user 2026-11-12, separate from the card wizard): sentences
      sort ascending by date added by default; ordering is adjustable and the rank lives on the word
      entity (`extracted_items`), falling back to date order when unset. The wizard's Sentence 1/2/3
      dropdown (docs/card-creation-wizard-plan.md Q8) reads the same order, so this feature should
      land before or with the wizard's first release.
      (implemented 2026-11-12, schema v4 `sentence_ranks`; verified live on API 35 emulator —
      default date-asc on a 7-sentence word, swap + boundary-disabled + cold-reopen persistence,
      rank map materialized on item; SentenceOrderingInstrumentedTest added, pending the same
      device run as the other pending suites)
