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

## On-device findings (user test 2026-11-23, first real Sync attempts)
Approval route pinned live: the create response'"'"'s link field (https://link.stremio.com/XXXX) is the
approval URL -- 302 to stremio.com/login?code=XXXX; "stremio.com/pair" does not
exist and there is no "Link devices" settings row. The dialog now shows the link. 
Two bugs only a real device run could surface, both fixed same day:
1. "Can't create handler inside thread" — the link dialog was constructed on
   the AppIo worker thread. Sync flow split: network off-main, AlertDialog
   created+shown on main, polling continues off-main (StremioConsoleActivity).
2. REQUEST-BODY FORMAT: api.strem.io REJECTS the legacy JSON-array bodies
   ("JSON unmarshal err") that third-party examples use. Keyless live probes
   pinned the working shapes — single objects mirroring stremio-core serde:
   datastoreGet {"authKey","collection":"libraryItem","all":true};
   addonCollectionGet {"authKey","update":true}; success responses ride in
   {"result":...}, failures in {"error":{code,message}} (fake key ->
   "Session does not exist", observed live). Client rewritten to that truth;
   error envelopes now THROW with the server message instead of silently
   parsing to empty lists (spec-pinned). Link envelopes re-verified live too:
   create -> {"result":{"success","code","link","qrcode"}}; read pending ->
   error code 101; poll query is type=Read&code=XXX (not the key the original
   third-party design assumed).
   JVM suite re-run: 123/123. Remaining S0-A: user approves a code once, then
   the REAL datastore responses pin the row-level field spellings.
