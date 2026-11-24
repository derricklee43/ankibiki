# Spike 006 — Stremio Mining Console (stage-1 build pass)

Date: 2026-11-23. Trigger: user one-shot go ("Let's implement it as a one shot.
Do all that you can without my input"). Design home:
`docs/stremio-mining-console-design.md` (stage-1 decisions D1-D10 all ruled there).

## Verdict table (as built today)

| Step | Status | Evidence | Remaining |
|------|--------|----------|-----------|
| S0-A auth/library/addons | PARTIAL | parsers spec-green against serde-derived fixtures (StremioClientParseTest); public endpoints reachable from WSL (torrentio/opensubtitles HTTP 200; cinemeta redirect target live but returned `{}` — geoblocking suspected) | LIVE pin needs the user's link-flow approval; capture fixtures from the phone, re-run specs unchanged |
| S0-B watched bitfield | BUILT | WatchedBitfieldTest (5): round-trip, anchor realign, colon-anchors, corrupt->blank degrade | cross-check ONE real blob from the account (harmless if wrong: hint only) |
| S0-C meta/subtitle resolution | BUILT | AddonResources + specs (episode refs, selection ladder) | first real subtitle-file download on-device (kill branch: UA sniffing) |
| S0-D audio-track probe | TIER-2 SHIPPED | infoHash/fileIdx observed best-effort during mining, pinned by instrumented test | real stream probe = device + RD session |
| S0-D2 RD inventory | DEFERRED | none (token not configured) | small on-device run when RD token entered |
| S0-E end-to-end pipeline | BUILT (emulator) | StremioConsoleInstrumentedTest 6/6 on emulator-5554: negative-namespace landing, idempotent re-extract (same doc, no dup rows), photo-survives-cue-delete, generation coexistence (D6), failure->ledger->retry, v13->v14 migration landing | live corpus check + real-account run |
| S0-F batch/background | MINIMAL | serial AppIo.pipeline lane reused (existing infra); queue loop in the episodes screen | multi-episode soak on device |

## Suite evidence at commit time
- JVM: `:app:testDebugUnitTest --rerun` -> 23 suites, 121 tests, 0 failures/errors
  (the 4 new spec classes failed 5 real bugs on their FIRST run: episode-ref
  arity, linkPoll pending-shape exception, addon resource `types` leak into
  the type set, regex S/E separator — all fixed before commit).
- Instrumented (emulator-5554 ONLY; the physical Pixel with the real corpus was
  deliberately untouched): full `am instrument` = **OK (146 tests)**, including
  the 6 new console tests. One expected tripwire fired and was re-pinned in the
  same commit: `schema_current_createsNameplateClaimsTable` literal `13` -> `14`.
- v14 ceremony: MiningRepository DB_VERSION=14 + createStremioSchema;
  backup_bundle.py SUPPORTED_USER_VERSIONS += 14 (+ rationale comment);
  docs/recovery.md prose refreshed (it had rotted at v12 — v13 catch-up included);
  nothing added to REQUIRED_COLUMNS/MINING_COLUMNS (pre-v14 bundles restore).

## Accepted stage-1 gaps (documented, not hidden)
Seven INNER-JOIN-scraped_photos readers (list views + per-word count subqueries,
`MiningQueries.kt:501/518/570/583/638/651/762`) silently exclude the negative
namespace: the inbox does not yet LIST stremio sentences and frequency counts
under-count them. Vocabulary identity (extracted_items rows, known-word/Anki
linkage) DOES flow immediately. Stage-2 (own go + device session) converts
readers to LEFT JOIN + explicit source column; stage-1 rows survive it intact.

## UI status
Console + episodes activities built, registered, and reachable (Settings popup
row "Stremio コンソール"); layouts compile and the flows are exercised only by
instrumented tests so far — **no human walkthrough yet** (repo rule: not
"done"; first live session should follow references/device-session.md hygiene).
