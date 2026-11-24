# Spike 007 — Direct subtitle sources (bypass the Buta No Subs proxy)

Status: DESIGN (pending execution). Nothing in this spike has run yet; facts below
were verified by hand-live probes on 2026-11-23, executions are not.

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

- P1 — ASS parser branch in `SubtitleParser` (Dialogue lines; strip `{\...}`
  overrides; `\N`→space; BOM tolerance already present) + JVM specs. Production
  slice, needs user go.
- P2 — STEP 1 keys (user, 5 minutes).
- P3 — hygiene: HTML responses → "addon not configured" ledger note instead of
  JSON crash (already proposed, pending go).

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

## 14. Decision points (answer in bare numbers; recommendation marked)

1. Run order: (a) 007a only first, decide 007b by its outcome [lean]; (b) both
   back-to-back in one session; (c) both in parallel with two agents.
   Recommended: **(a)** — cheapest kill first, one session each way.
2. Key custody during the spike: (a) user pastes keys into the chat/session env
   [lean]; (b) keys written into a gitignored local file the spike script reads.
   Recommended: **(a)** with immediate rotation option (both sites show the key
   again; both are free/revocable) — or (b) if preferred; both keep keys OUT of the
   repo and OUT of the bundle (prefs/file, not DB).
3. If 007a passes but 007c finds real rate friction: (a) throttle+cache and ship
   [lean]; (b) batch-of-one discipline only, no cache. Recommended **(a)** —
   per-show entry caching is trivially safe (episode additions are the only
   invalidation need; a manual "refresh entry" path suffices).
4. Production attach shape IF a tier ships: (a) builtin sources join the existing
   ranked candidate loop after synced addons [lean]; (b) a source dropdown in the
   gear. Recommended **(a)** — zero settings surface, ledger "tried:" list gains
   builtins for free; (b) if the user wants to force one source per show.
5. P1 ASS parser build: (a) bundle with the probe session — verdict + parser in
   one go [lean]; (b) now, before probes; (c) after, only if a tier ships AND we
   also install Buta. Recommended **(a)**; the parser is needed in every §9 branch.
6. Buta-in-Stremio install as belt-and-braces even if a direct tier ships:
   (a) yes, keep as just another synced addon (intended use, no code changes)
   [lean]; (b) no, one route only. Recommended **(a)** — it costs nothing and is
   the fallback chain's third rung at the player's discretion.

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
