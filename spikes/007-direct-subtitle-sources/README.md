# Spike 007 — Direct subtitle sources (bypass the Buta No Subs proxy)

Status: 007a EXECUTED 2026-11-23 — VERDICT: PASS WITH CONSTRAINTS (§16).
007b (SubDL) is PARKED as explicitly-wanted future work per user ruling on D1 —
its rubric stays in this doc verbatim so resuming costs only a probe session.
P1 (ASS parser) SHIPPED alongside this verdict, same pass (§16.5).

Date: 2026-11-23. Trigger: user question after the jpn content-gap diagnosis —
"buta no subs is just a subtitle aggregator, why do we need to proxy through this
service? Can we not call the endpoints ourselves?" Design home for the console this
extends: `docs/stremio-mining-console-design.md`; prior build evidence:
`spikes/006-stremio-console/README.md`.

## 1. Problem statement (every claim grounded)

The mining engine's subtitle step tries the user's synced Stremio subtitle addons
in rank order and stops at the first with results
(`StremioExtractionEngine.kt:57-77`; ranking `StremioWrites.kt:149-161`). For anime
Japanese this loop currently dead-ends — verified live 2026-11-23:

- OpenSubtitles (v1): synced transport is the unconfigured bare base
  `https://opensubtitles.strem.io/stremio/v1`; the resource-path builder appends to
  it as-is (`AddonResources.kt:14-19`) and it answers `text/html` (the addon landing
  page) — observed in the ledger as `java.lang.String cannot be converted to JSONObject`.
- OpenSubtitles v3: healthy JSON; `tt32612521:1:4` → 10 subs, 0 jpn;
  `tt0106226:1:1` → 0 subs total (curl probes 2026-11-23).
- Anime Kitsu: declared-stub `subtitles` resource → HTTP 404.

`Buta No Subs` (community addon, Jimaku+Kitsunekko+MySubs) DOES have the content —
live probe 2026-11-23 returned 7 jpn files for `tt32612521:1:4`, one fetched clean:
`https://jimaku.cc/entry/9898/download/[NanakoRaws] Gachiakuta - 04 (...).ass`,
52,854 bytes, UTF-8 BOM ASS text, no auth. But the author's README asks that its
hosted instance not be used as a bulk API ("Please consult me first... Fork/clone
the repo and make your own instance: All the API's I've used are free"). Using his
keyed endpoints for our mining is exactly what he asks not to happen — so we ask
ourselves instead: call the upstream sources directly, under our own free keys.

Key discovery motivating the spike: the resolution chain is cheaper than it looks.
Jimaku's OFFICIAL REST API keys entries by TMDB/AniList id
(`https://jimaku.cc/api/openapi.json`, fetched 2026-11-23):

```
GET /api/entries/search   params: anime, anilist_id, tmdb_id, query, after, before
GET /api/entries/{id}
GET /api/entries/{id}/files   params: id, episode   <-- per-episode extraction is NATIVE
```

and the console ALREADY fetches the TMDB id: Cinemeta `/meta/series/tt32612521.json`
carries `moviedb_id: 256721` (verified 2026-11-23), parsed at
`AddonResources.metaEpisodes` — which today throws it away (it reads only `videos[]`,
`AddonResources.kt:25-42`). One more field from a call we already make is the whole
id-resolution step. Anime Kitsu's meta additionally carries `kitsu_id` (48946 for
Gachiakuta, verified) for a kitsu→AniList fallback hop (relations.yuna.moe, keyless).

Auth/etiquette facts (verified 2026-11-23 unless noted):
- Jimaku JSON API without key: `401 {"error":"unauthorized","code":7}` on
  `/api/entries/search` and `/api/entries/9898/files`. Free account + account-page
  API key (docs; Buta README confirms keys are free).
- Jimaku FILE DOWNLOADS are public: the `.ass` URL above fetched with zero auth
  (that was the site download path, not the JSON API — the spike must confirm the
  `files` endpoint hands back the same public URL shape).
- Jimaku rate limits are IP-level with `x-ratelimit-*` headers, HTTP 429 on trip
  (docs). We would be hitting them under OUR users' own keys/IPs — the polite shape.
- SubDL operates an OFFICIAL Stremio-facing product with a free API key and
  explicit anime identity support (Kitsu/MAL/AniList/AniDB) + "extract the
  requested episode from a subtitle pack" (subdl.com/stremio, fetched 2026-11-23).
  Its plain REST API shape is NOT yet pinned — `subdl.com/api/docs` returned
  HTTP 403 to our fetcher. Spike 007b must pin it from primary source before any
  parser is written.
- `SubtitleParser` handles SRT/VTT only (`SubtitleParser.kt:4,14`). Fansub rips are
  ASS (proven above). ASS ingestion is a PREREQUISITE (P1), not a spike question —
  it gates shipping on ANY route (including installing Buta in Stremio).

Answer to the user's question, restated as the spec this spike tests:
**Yes, we can call upstream directly.** Jimaku is a documented, free-keyed official
API whose search takes exactly the id (TMDB) our existing meta call already returns,
and whose files endpoint natively picks one episode out of a season pack. SubDL is a
vendor whose official API is DESIGNED for programmatic use. The spike's job is not
to prove this is possible — it is to measure COVERAGE and EPISODE-NUMBERING
SEMANTICS on the user's real library, where the killable assumptions live.

## 2. Order of work

STEP 1 (human prerequisites — this blocks execution, not design):
- user registers a free Jimaku account, generates the API key (account page);
- user generates a free SubDL API key (subdl.com account panel);
- both keys handed to the agent session or entered where the spike script reads them
  (never committed; spike stores keys in env/local-only file).

STEP 2: run 007a (jimaku-direct) — cheapest assumption-killer first.
STEP 3: run 007b (subdl-direct) on the IDENTICAL input set and rubric.
STEP 4: 007c measurement pass on whichever won (rate/etiquette), then the verdict
table; production slice (builtin source adapters + P1 ASS parser) is a separate go.

Ground-truth construction: no new data collection is needed. The input set is the
episodes already written into the device ledger (reproducible from `stremio_ledger`
JOIN `stremio_library`) plus the three probe ids logged 2026-11-23; the cross-check
oracle for Gachiakuta E04 is the recorded Buta probe (7 filenames, captured in this
doc §1). Verdict labels are all derivable from these committed facts.

## 3. Decomposition

| # | Spike | Validates (Given/When/Then) | Risk | Can it kill? |
|---|-------|------------------------------|------|--------------|
| 007a | jimaku-direct | Given a free Jimaku key, when resolving the pinned set via Cinemeta `moviedb_id` → `/api/entries/search?tmdb_id=` → `/files?episode=`, then ≥8/11 episodes have ≥1 directly-downloadable jpn file with correct episode semantics | High | YES — kills the Jimaku tier |
| 007b | subdl-direct | Given a free SubDL key, when the official REST API is pinned from primary source and queried (imdb/tmdb + season/episode + jpn) for the IDENTICAL set, then ≥8/11 episodes yield a downloadable jpn subtitle | Medium | YES — kills the SubDL tier |
| 007c | rate + etiquette | Given the winning source, when mining a realistic batch (30 queued episodes) under documented limits, then zero 429s with default throttling + per-show entry cache | Low | NO — degrade-only (see §7 ladder) |

007a runs first: it is the most likely to kill and the cheapest to run (one curl
chain per show: meta → search → files → HEAD the download).

## 4. The input set (pinned from the live corpus, 2026-11-23)

| video_id | title | trap it exercises |
|----------|-------|-------------------|
| tt32612521:1:4 | Gachiakuta S01E04 | baseline — known-good oracle (Buta: 7 files) |
| tt32612521:1:9 | Gachiakuta S01E09 | same entry, later episode (pack extraction) |
| tt13293588:0:1 | Mushoku Tensei, season **0**, ep 1 | anime cour/season numbering vs Jimaku absolute episode numbers |
| tt13293588:0:2 | Mushoku Tensei, season **0**, ep 2 | same |
| tt17069148:1:1 | Sakamoto Days S01E01 | recent anime, current-season coverage |
| tt17069148:1:2 | Sakamoto Days S01E02 | same |
| tt3718778:0:1 | Over the Garden Wall | NON-anime control (Jimaku is anime-only: expected NO_ENTRY, must not count against anime-coverage scoring — see rubric) |
| tt3718778:0:2 | Over the Garden Wall | non-anime control |
| tt0106226:1:1 | (probe id from 2026-11-23 OS-v3 zero-coverage test) | classic-era title that OpenSubtitles v3 returns ZERO for |
| tt0877093:1:1 | (probe id, same batch) | classic-era title |
| tt0386076:1:1 | (probe id, same batch) | classic-era title |

Re-derive the list at run time from the device tables (the three "(probe id)" rows
are named there); the rubric and thresholds stay identical.

## 5. Scoring rubric (identical for 007a and 007b — divergent inputs make the
comparison unreportable)

Per episode, record exactly one failure class when not a clean hit:
- RESOLVED: found entry/collection → file list → ≥1 jpn file for the correct
  episode → download HEAD returns 200 with subtitle-ish content (not HTML/zip of
  unknown shape).
- NO_ENTRY: id chain finds nothing (for anime; non-anime NO_ENTRY on Jimaku is
  EXPECTED-NEGATIVE and excluded from the denominator — see §6).
- NO_JPN: entry exists, zero jpn files.
- WRONG_EPISODE: files exist but the episode the API points at ≠ the episode the
  filename/`videos[]` title says (human-check label: filename group token, e.g.
  "Gachiakuta - 04" for `:1:4`).
- FORMAT: only unusable payloads (e.g. `.rar` season pack that needs on-device
  unrar, image-based `.sup`).
- AUTH: download requires browser-session/cookies/beyond-key auth.

## 6. Pass and KILL criteria (falsifiable, pre-registered)

007a Jimaku:
- PASS: ≥8/9 anime-set episodes RESOLVED **and** WRONG_EPISODE + FORMAT + AUTH ≤ 1
  combined. (9 = 11 minus the 2 EXPECTED-NEGATIVE Over-the-Garden-Wall rows.)
- KILL (abandons the Jimaku tier; anything else in the design still stands):
  id-chain finds NO_ENTRY for >30% of anime episodes despite `moviedb_id` being
  present in Cinemeta; OR WRONG_EPISODE on ≥2 of the season-0 episodes with no
  deterministic mapping rule (the rule candidates are enumerated in §8); OR
  downloads demand session auth. If killed: record which, fall to 007b.

007b SubDL:
- PASS: same bar, same denominator logic (SubDL serves non-anime too, so its
  Garden Wall rows count normally).
- KILL: the documented REST shape cannot be pinned from primary source in one
  session (docs page 403 is already a warning; free-key signup friction beyond
  "account + key" is a second); OR >30% NO_JPN across the set. If both tiers die,
  §9 ships the fallback.

007c (non-killable): any 429 observed at default pacing (one search + one files
call per show, cached; one download per episode) → add throttle/cache tiers per §7.
This can only degrade convenience, never correctness.

Predictions written BEFORE running (must be TESTED, not discovered):
1. "tmdb_id search resolves all 5 anime titles first try" — plausible but Jimaku
   entries may be AniList-backed more often than TMDB-backed; refutation costs one
   fallback hop, not the tier.
2. "The three classic-era ids that returned ZERO from OpenSubtitles v3 resolve on
   Jimaku with ≥3 files each" — the headline content-gap claim; refutation here is
   coverage-grade information.
3. "Cinemeta `videos[]` filenames are enough to human-verify episode mapping on
   every hit" — if wrong, the oracle itself breaks and rubric needs a second source.
4. "Jimaku `files?episode=` returns absolute episode numbers for season-split
   shows" — the season-0 trap; falsifiable on 2 rows.

## 7. Fallback ladder for risky probes (with killability labels)

- Search misses (can kill a tier): tier1 tmdb_id → tier2 anilist via
  kitsu_id→relations.yuna.moe (keyless; Anime Kitsu meta already parsed by nothing
  today — one more GET we already know how to make) → tier3 title `query=` text
  search → tier4 miss recorded. Bottom tier degrades to NO_ENTRY, never a wrong file.
- Download refused (can kill): retry with UA/X-Client-Id (docs: descriptive UA
  required-ish); record AUTH if session-shaped.
- Rate (cannot kill): per-show entry cache in the settings table; serial lane
  already caps concurrency (AppIo.pipeline); on 429 honor `x-ratelimit-reset-after`
  (same semantics as our link-poll "keep waiting" pattern).
- Unusable format (cannot kill): skip the FORMAT file, take the next candidate —
  selection ladder stays pin > pattern > first.

## 8. Episode-numbering semantics (the design's real hard part)

Stremio video ids are `imdb:S:E` and anime cour splits produce season 0/odd values
in Cinemeta (lived example: Mushoku Tensei `:0:1`). Jimaku `files?episode=` is
absolute-numbered per entry. Mapping rule candidates to test in order, record which
survives:
1. pass `E` directly as `episode=` (works when the show has one continuous season
   under one entry and S∈{0,1});
2. absolute ordinal = E within the entry's own numbering, verified by the returned
   filename's group-token pattern (`Show - 04` style — `AddonResources.parseEpisodeFromFilename`
   already ships this heuristic, `AddonResources.kt:107-121` — REUSE it as the
   verifier, not the resolver);
3. season-offset arithmetic (kill candidate: needs a season-start table nobody has).
PASS requires the rule to hold with zero WRONG_EPISODE on the two season-0 rows —
a coin-flip match there is a failed test, not a pass.

## 9. Verdict outcomes → shipping scope (read this before running anything)

| 007a | 007b | Ship | Deferred / parked |
|------|------|------|-------------------|
| pass | pass | Builtin source chain: Jimaku primary, SubDL fallback (ranked after synced addons — the loop gains a `builtin` kind, not a rewrite) | media-demux stays the timing-perfect long game |
| pass | fail | Jimaku builtin alone | SubDL re-probe only if Jimaku coverage disappoints in use |
| fail | pass | SubDL builtin alone | — |
| fail | fail | No direct sources: user installs Buta No Subs **in Stremio** (author's sanctioned intended use — zero new code path, it flows through the existing synced-addon loop) + existing hygiene fix | the media-demux spike (stage-3-style, from the console design) becomes the primary next route |

In every branch: P1 (ASS parser) ships — it gates Buta files as much as Jimaku ones.

## 10. Process topology (which component runs on which machine)

- Spike execution: this WSL box, plain curl/python against jimaku.cc / api.subdl.com.
- Production (if any tier ships): the PHONE — every call is a direct
  phone→source HTTPS GET reusing the `StremioTransport` shape; the user's keys live
  in app prefs exactly like the RD token (prefs are outside the bundle contract —
  the same dated reasoning as `StremioPrefs.rdToken`).
- Do we need a backend? **No.** There is no component that runs on any server we
  operate: resolution (meta→search→files→download) is four stateless GETs the
  device makes itself; caches are local tables. Evidence: every endpoint above was
  probed today from a keyless box with plain curl.

## 11. Rejected approaches (explicit, so nobody re-proposes them)

- Using Buta's hosted instance as our API: author's explicit ask; his keys pay his
  quotas; ours pay ours. (Direct calling IS his suggested alternative.)
- Kitsunekko/MySubs direct: Buta scrapes their HTML; kitsunekko ships `.rar` season
  packs → on-device unrar = new native dependency for a tier we already reach via
  Jimaku/SubDL's pack handling. Fragile + heavy. Only resurrected if BOTH tiers die
  and demux is also rejected.
- Installing unrar/ffmpeg for subtitle extraction: out of scope for text-only
  mining (media stage is a separate design).
- A Stremio-side "configure the OpenSubtitles v1 addon with an account" fix:
  cosmetic only — same DB, still zero jpn anime content (verified §1).
- Frame-OCR of burned-in subs: much later capability, unchanged ruling.

## 12. Prerequisites (needed regardless of verdict; not spike questions)

- P1 — ASS parser branch in `SubtitleParser` (Dialogue lines; strip `{\…}`
  overrides; `\N`→space; BOM tolerance already present) + JVM specs.
  SHIPPED 2026-11-23 with the §16 verdict (5 new specs; see 16.5 for the two
  first-run corrections the real corpus forced).
- P2 — STEP 1 keys (user, 5 minutes).
- P3 — hygiene: HTML responses → "addon not configured" ledger note instead of
  JSON crash. SHIPPED 2026-11-23 (`AddonResources.parseJsonBody` guards meta/
  subtitles/streams parsing; 2 JVM specs pin the actionable-tail wording so the
  engine's keep-last-50 rendering survives truncation intact).

## 13. Evaluation method

- Layer 1 (diagnostic): each source scored SOLO on the set; per-episode failure
  class located; any NO_ENTRY/WRONG_EPISODE disqualifies as standalone shipping
  rule but decides nothing else.
- Layer 2 (shipping candidates): ONLY the three pre-registered combos (§9:
  Jimaku-only, SubDL-only, Jimaku+SubDL). No post-hoc combinations; a new combo is
  written in here as a variant BEFORE testing it.
- Layer 3: coverage and mapping-correctness are SEPARATE result tables and never
  averaged (8/10 found with 3 mis-mapped is worse than 6/10 clean).
- Controls: Over-the-Garden-Wall rows on Jimaku are EXPECTED-NEGATIVE (anime-only
  site); on SubDL they count normally (that asymmetry is part of the scoring).

## 14. Decision points — RULED 2026-11-23 (user answers in his framing)

1. RULED: **Jimaku only for now; SubDL "put a pin in it" but documented as wanted
   in the future.** 007b does not run this session; its rubric/kill criteria stay
   in §6 unchanged as the resume kit. (This supersedes offered options 1a/1b/1c.)
2. RULED: keys saved to the file (`/home/derri/.config/ankibiki/spike007.env`,
   chmod 600) before execution; both present (58/49 chars, never echoed). The
   file-only rule was corrected into this doc the same day (see prior revision).
3. RULED: **(a)** throttle + per-show entry cache if rate friction appears.
   Observed: remaining 19–23 of 25 through a ~15-call probe; no friction hit.
4. RULED: **(a)** builtin sources join the ranked candidate loop after synced addons.
5. RULED: **(a)** ASS parser bundled with the probe session — shipped in this same
   commit (`SubtitleParser.parseAss` + 5 new specs (129->134); see §16.5).
6. RULED: **(a)** Buta-in-Stremio install also happens — user confirmed the double
   consumption: he watches those tracks in Stremio's player AND the console picks
   the addon up via 同期 as one more synced candidate.

## 15. Relation to sibling docs (this doc is self-contained by design)

- Supersedes nothing; EXTENDS `docs/stremio-mining-console-design.md` §subtitle
  candidate sourcing (stage-1 loop stays; this spike investigates additional
  builtins).
- Answers roadmap item 8's open thread "first SUCCESSFUL extraction" at its ROOT
  cause (content gap), not at the code level (engine behaved correctly; ledger
  evidence in spikes/006 debug log).
- Orthogonal to `docs/sentence-media-refs-design.md` (v15 landing shape already
  shipped; any source's cues land identically).
- If both tiers die (§9 last row), the media-demux route in the console design's
  stage-3 material and the parked `docs/stremio-auto-scrub-parked.md` verified
  facts become the next design round — this spike deliberately does not spend that
  parked consent.
- Shared modules touched by a passing verdict (design only, not in this spike):
  `AddonResources` (new builtin adapter or sibling), `SubtitleParser` (P1),
  `StremioExtractionEngine` candidate loop (§9), `StremioPrefs` (keys).

## 16. 007a VERDICT (executed 2026-11-23, ~15 keyed calls, WSL probe script)

Verdict: **PASS WITH CONSTRAINTS** — Jimaku-direct is viable as a builtin source.
Evidence file: scratch `spike007a-results.json` (reproducible via §2 with the keys;
throwaway probe script, not committed by design).

Scored against §6 (anime denominator corrected — see ledger #4): **6/6 anime
episodes RESOLVED** — entry found, `files?episode=` returned the requested
episode's file(s), download HTTP 200, formats ASS/SRT, zero AUTH failures, zero
429s (rate remaining 23→19). `Over the Garden Wall` = NO_ENTRY via tmdb+query as
predicted for an anime-only site (expected-negative control, out of denominator).

Constraints the production builtin must carry (each found live):
- **C1: `tmdb_id` search is dead for these titles** — 0 hits on all 4 despite
  Cinemeta moviedb_id being valid. Entries are AniList-backed. The WORKING tier
  this run was fuzzy `query=<Cinemeta name>` (3/3 anime hits). The yuna
  kitsu→anilist hop ERRORED on my URL shape (`InvalidURL`) and was not needed —
  production should re-derive it properly OR validate query hits by filename
  title-token match (query can hit the wrong season/entry — Mushoku query
  landed entry "Mushoku Tensei II" for the parent imdb id, see C2).
- **C2: season-0 semantics are plausible but human-verification-pending**:
  `tt13293588:0:1/0:2` → entry 1927 files "- 01"/"- 02" are S2 numbers matching
  Cinemeta's season-0 rows; final proof = the device walkthrough reading mined
  sentences (acceptance gate, not yet run).
- **C3: file lists are NOT format-safe** — Sakamoto E01's row #1 was a 2.9 MB
  `.zip` (Netflix image subs). Builtin must filter to text formats (.ass/.srt/
  .vtt) by name suffix before selection; zip/rar rows get FORMAT-skipped.
- **C4: content-type is unreliable** (`application/octet-stream` on .ass,
  `application/x-subrip` on .srt) — classify by bytes/extension, never CT.

Honest ledger (numbered, per execution discipline):
1. Prediction 1 ("tmdb resolves all titles first try") — REFUTED (C1). Design
   survived because §7's ladder pre-registered fallbacks; query tier rescued all.
2. Prediction 4 ("files?episode= absolute numbering") — CONFIRMED per entry:
   `episode=N` returns N-numbered files within the entry; the S-vs-absolute
   question collapses into entry selection (C2), not files arithmetic.
3. PREDICTION MISSED BY THE DOC: "1x04"-style regex poisoning — my own verifier
   mis-parsed `(TBS 1920x1080 x265)` as episode (20,108), producing two FALSE
   `WRONG_EPISODE` labels. Root cause investigation showed the SHIPPED
   `AddonResources.parseEpisodeFromFilename` contains the identical unbounded
   `(\d{1,2})x(\d{1,3})` pattern — a real production bug surfaced by the spike
   (its only consumer is the pre-check hint, so blast radius was hint-poisoning).
   FIXED same commit: digit-boundary lookarounds + 3 new real-corpus cases pinned
   in `StremioKeysAndSelectionTest` (old `2x11.720p` pin passes UNEDITED).
4. SET CORRECTION: three "classic-era" rows (§4) turn out to be MOVIES
   (tt0106226 = The Age of Innocence; two ids now 403'd by Cinemeta geoblocking)
   — last night's `:1:1`-shape OS-v3 probes on them were malformed (series-shape
   on movie ids), so "Death Note returns zero" was NOT verified. Anime
   denominator 9→6; the PASS bar's ≥8/9 was impossible as written — scored as
   6/6 of the valid anime set instead, deviation recorded here, not silently.
5. Cinemeta from this box intermittently 403s (spike 006 geoblock note re-
   confirmed); the PHONE is unaffected (production calls run from device IP).
6. DEVICE WALKTHROUGH 2026-11-23 (first real use of the builtin, post-9676605):
   extraction reached Jimaku, resolved the entry, downloaded the .ass — then
   died `PatternSyntaxException near index 8: \{[^}]*}`. Desktop JDK compiles
   quoted punctuation `\{`; Android's java.util.regex does NOT. First fix
   attempt `[{][^}]*}` THREW ON DEVICE TOO (ledger 22:05:54, "near index 9" —
   the STRAY UNQUOTED closing brace: Android rejects a lone `}`, OpenJDK
   accepts it) — one more lesson: guessing engine-quirky regex spellings burns
   a device round-trip per guess. Final fix is engine-independent: override
   stripping rewritten as a HAND SCANNER (`stripAssOverrides`, no regex at
   all for brace shapes) + a main-source-wide scan for brace-bearing patterns
   (all remaining `{n,m}` are quantifiers after atoms — portable everywhere).
7. DEVICE ANR, same walkthrough (22:06:41, MainActivity "input dispatching
   timed out, 5000ms for FocusEvent", system killed the process mid-mine of
   tt8515016:1:1): the FIRST full-episode extraction ever attempted — a single
   transaction of hundreds of cues x vocabulary lookups held the rollback-
   journal write lock (repo has no WAL) 25+ seconds; main-thread DB reads
   cannot run behind a writer in default journal mode. Photo-path transactions
   were always sub-second, hiding the class. FIXED: cue->occurrence insertion
   chunked at CUE_BATCH=50 per committed transaction (bound lock hold; ledger
   marks MINED only after the last batch; a mid-batch kill stays correctly
   re-queueable because old-occurrence deletion is doc-scoped before inserts).
   WAL deliberately NOT enabled: uncheckpointed -wal pages silently break the
   file-copy backup tooling (backup_bundle.py, adb pulls) — the whole corpus
   backup story assumes a complete single-file db.
8. SESSION END 2026-11-23 ~22:20 (user paused; RESUME STATE, read this first):
   - Regex PSE is GONE on the device: the 13671fc build parsed real .ass fine
     (no new PSE rows after 22:12; ledger stopped distinguishing — PARSING
     spans parse+all-batches until MINED, so a stuck PARSING row cannot say
     WHERE it died; process kills explain the stalls).
   - BUT A SECOND ANR at 22:16:18 with the chunked build: identical signature
     (MainActivity FocusEvent 5002ms, killed; tt33028568:1:1 row stuck PARSING).
     So CUE_BATCH=50 did NOT fix the freeze — batches still starve the UI.
   - WORKING THEORY for tomorrow (hypothesis, NOT verified): without WAL,
     SQLiteOpenHelper = ONE pooled connection shared by all threads; the
     pipeline thread OWNS it for the duration of each batch, and my batches
     still include the CPU work (CorpusInsertion.analyzeCandidates runs
     INSIDE inTransaction) — each batch therefore holds the only connection
     ~seconds; the main thread's window-focus DB work serializes behind the
     batch queue -> 5s input timeout. FIX DIRECTION: analyze OUTSIDE the
     transaction (pure (config,text) -> candidates, no DB), batch = inserts
     only (~100-300ms holds), optionally smaller CUE_BATCH; retest on a long
     episode while backgrounding/returning mid-mine. WAL reconsideration only
     if insert-only batches still miss the input deadline — and any WAL move
     MUST first teach the backup tooling to checkpoint-before-copy.
   - First actions next session: (1) re-pull ledger (everything after
     22:15:47) + `logcat -b events | grep am_anr` to confirm; (2) implement
     the analyze-outside-transaction fix; (3) instrumented emulator run
     (whole suite vs changed engine + the 2 builtin specs, still never run);
     (4) user walkthrough for Gachiakuta E04 first SUCCESSFUL extraction +
     the C2 sentence-spot-check; (5) D6a Buta install remains user-side.
   - Phone state: newest APK installed, Jimaku key injected+verified in prefs,
     authKey/RD untouched, mining.db data intact (all failed rows re-queueable).
9. DEVICE SESSION 2026-11-26 — RESOLVED: FIRST SUCCESSFUL EXTRACTION.
   Walkthrough tt8515016:1:2 (Banana Fish S01E02) reached ledger `mined`,
   doc 4: 396 cues -> 867 item_occurrences; app stayed responsive mid-mine
   (watcher saw zero am_anr / "database is locked" events for the whole run).
   Two NEW root causes beyond §16.7-.8 theory, both device-verified before fix:
   - a) MainActivity.onDestroy called shutdown() on AppIo.pipeline — the
     process-lifetime shared lane. One activity destroy poisoned it for the
     whole process; next 抽出 tap threw RejectedExecutionException FATAL on
     the MAIN thread (logcat 07:43:33, pool state [Terminated]). Fix: shutdown
     removed (lanes die with the process); pinned by AppIoLaneLifecycleTest
     source-scan guard (no executor .shutdown( in app/src/main + lane aliases).
   - b) The kit's "CPU-inside-transaction" hypothesis was the WRONG CPU:
     morphological analysis was hoisted (07:53 build) and batches STILL took
     60-68s (dumpsys dbinfo on the frozen run: TRANSACTION-EXCLUSIVE took
     60560ms while ALL SQL summed 366ms; pool [1 active, 0 available], io
     thread starved 90s -> user's "sync failed: database is locked"). The
     real hold: CorpusInsertion.ensureCanonicalItemExists fires per-candidate
     AnkiDroid CONTENT-PROVIDER round-trips (findMatch: model list + field
     names + note search per word) + dictionary scans INSIDE the transaction,
     and the connection stays held across them. Fix: PLAN/APPLY split —
     planInsertions resolves the whole batch's write set OUTSIDE any
     transaction (existence probes as lambdas; per-episode InsertionMemo
     makes repeated subtitle vocab cost zero IPC after first sight; negative
     results cached), applyInsertions commits sub-ms pure writes in one short
     tx. Semantics mirror ensureCanonicalItemExists verbatim; same-expression/
     different-reading batches adopt late matches (pinned by
     CorpusInsertionPlanTest 5 specs; one spec caught a real parity detail:
     ideograph expressions without dict entries carry kanji=expression).
   Suite fresh 153/153 (--rerun, XML-parsed, 08:02). Snapshots:
   backups/20261126-074910 (pre), 20261126-080324, 20261126-082421 (post,
   integrity ok). C2 season-0 sentence verification still open (needs a
   tt13293588:0:x or Gachiakuta E04 full mine read on-device); instrumented
   emulator run still pending; Buta-in-Stremio install (D6a) user-side.

16.5 P1 SHIPPED this pass: `SubtitleParser.parseAss` ([Events] section; Format
column-order respected incl. SSA default; Dialogue-only; Text re-merge for
comma-bearing fields — a split-LIMIT cut mis-lands Text when `\pos(960,40)`
carries commas, caught by parsesBasicAss on first run and fixed; override blocks
are `{...}` NOT `\{...}`, the second first-run lesson; `\N`/`\h`/tags -> spaces,
centisecond stamps -> ms). 5 new JVM specs; suite 134/134 (`--rerun`, XML-parsed,
fresh timestamps). Instrumented re-run deferred to next device session.

Next slice — IMPLEMENTED 2026-11-23 (same session, user go "let's get this
implemented"): the production builtin Jimaku source shipped as `JimakuClient`
(D4a rung wired into `StremioExtractionEngine.runJob` after the synced-addon
loop, gated on jpn lang; query-hit VALIDATION per C1 — leading-title-token gate
+ episode-number check via the bugfixed `parseEpisodeFromFilename` with a
name-fallback for Japanese-only filenames; `.zip/.rar` rows filtered per C3;
prefs-backed per-show entry cache with self-heal per D3a; key field + masked
presence row on the console like the RD token). NO schema change: cache and key
live in prefs (outside the bundle contract by the StremioPrefs header rule).
Evidence: JVM 144/144 fresh (`--rerun`, XML-parsed; 10 new Jimaku specs pinning
live-captured response shapes incl. the Sakamoto zip row); two new instrumented
specs compiled (builtin rescue + tried-list append) — RUN PENDING emulator
session; device walkthrough still open. Test-run first-passes caught three real
bugs (cache keying via substringBeforeLast stranding the season segment;
URLEncoder '+' vs %20 route expectations; one inverted pure-helper assertion).
