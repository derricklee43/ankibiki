# Spike 001: Anki deck + field-name contract probe

**Question:** Can AnkiBiki, on the user's real Pixel/AnkiDroid (their Anki account's collection),
reliably (a) enumerate ALL decks with ids/names/counts, (b) enumerate note types, and — the part
that burned us before — (c) get each note type's **field NAMES in ordinal order**, so the card-creation
wizard labels fields properly instead of the hardcoded indices in `KnownNoteTypes.kt`?

**Why it matters:** §4 wizard = deck picker → templated fields. The whole build is killed if field
names are unreliable — which is exactly what happened during the lookup phase; every model today is
handled by positional constants (`CHARACTER_FIELD_INDEX`, `WORD_FIELD_INDEX`, …) and
`FieldSelector.ByName` carries a `fallbackIndex` because name lookup wasn't trusted.

**Risk:** HIGH → spike first. Answer is device-dependent (installed AnkiDroid version + provider
spec version), which is why this needs the Pixel; emulator install would need a synthetic collection.

## What the docs/reading say (before probing)

| Surface | Path | Notes |
|---|---|---|
| Field names | `Model.CONTENT_URI/<id>` → `Model.FIELD_NAMES` | `\u001F`-joined, same separator as `Note.FLDS` values → names zip 1:1 with ordinals. `AddContentApi.getFieldList(modelId)` wraps exactly this. |
| All models | `api.getModelList()` + `Model.CONTENT_URI` (single-model rows via `/models/<id>`) | |
| Per-field definitions | `…/models/<id>/fields` (contract has a `FIELD_NAME` column constant) | Whether the installed AnkiDroid answers this path = probe P6. |
| Decks | `api.getDeckList()` / `Deck.CONTENT_ALL_URI` with `DECK_ID, DECK_NAME, DECK_COUNTS` | Counts come from the provider — no note join needed for the wizard. |
| Spec negotiation | `api.getApiHostSpecVersion()` | Probe P1 logs this FIRST; v1 lacks direct notes query. |

**Verified against the pinned artifact (`com.github.ankidroid:Anki-Android:api-v1.1.0`, javap of the
actual classes.jar):** `AddContentApi` exposes `getDeckList/getModelList/getFieldList/getNote/
getNoteCount/addNote/addNotes/addNewDeck/addNewBasicModel/apiHostSpecVersion`; `NoteInfo` =
`id/fields/tags/key` (no mid); `FlashCardsContract` in this artifact has **no `CardInDeck` class**
and **no `Model.CONTENT_URI_ALL`** — deck→model joins must go through notes, and model enumeration
uses `getModelList()`. The probe was written against these confirmed members and compile-verified.

## Probe plan (throwaway, runs on the connected Pixel)

`AnkiContractProbeTest.kt` (this directory) — copy into `app/src/androidTest/java/com/derricklee/ankidict/`,
run once with the Pixel attached, then delete. One probe per question, results logged + asserted:

- **P1 spec**: installed AnkiDroid versionName + `apiHostSpecVersion`. Everything after is interpreted against these.
- **P2 decks**: `Deck.CONTENT_ALL_URI` → count, list all `(did, name)`; compare vs what the app already knows
  (`getDeckName` worked in lookup phase); verify EMPTY decks are present (the wizard must show them).
- **P3 models**: `api.getModelList()` vs raw `Model.CONTENT_URI` rows → `(mid, name, field_names, num_cards, note_count)`.
- **P4 names-vs-indices (the grudge test)**: for the three known models (Chinese Mnemonics, NihongoShark Kanji,
  Chinese Word), split `FIELD_NAMES` and assert the hardcoded `*_FIELD_INDEX` constants still point at fields
  whose names are what we assume ("kanji" at 4, "Word" at 0, …). If names ≠ expectations anywhere, that is the
  smoking gun for the past confusion — and the wizard cannot trust order-stability assumptions either.
- **P5 zip check**: for a sample note, split `FLDS` and pair with names — count equal, values match known display
  behavior (CardAdapters output) so names→values wiring is proven, not just names→nothing.
- **P6 `/models/<id>/fields`**: query; if the path exists (newer provider), enumerate columns per field
  (name, ordinal, required?). Preferred wizard source if present, gracefully skipped if 404/empty.
- **P7 write round-trip on a scratch sandbox**: `addNewDeck("AnkiBiki spike <ts>")` +
  `addNewBasicModel("AnkiBikiSpikeModel<ts>")` → `getFieldList(new model)` returns ["Front","Back"] (or the
  BasicModel's actual names) → `addNote` with values in that order → re-query note → FLDS order == names order →
  delete the note. Proves the wizard's whole create path (deck pick → field labels → positional values) end to end.
  Leaves the scratch deck+model behind (named with a timestamp; delete by hand in Anki or via provider later).

## Success criteria → build decisions

- P2+P4+P7 green → wizard data model: `Deck(did, name, noteCount)` → `Model(mid, name, fields: List<FieldName@ord>)`
  → draft screen renders one input per named field; `KnownNoteTypes.kt` indices become *cache/fallback*, not truth.
- P4 partial (names present but ordinals disagree with indices) → ordinals from FIELD_NAMES become canonical;
  audit CardAdapters for positional assumptions.
- P4/P3 fail on installed version (FIELD_NAMES null/empty) → fall back to `/models/#/fields` (P6); if BOTH fail,
  INVALIDATED: field labels would have to come from a one-time Desktop-side export (scripts/ already talks to
  Anki Desktop via collection.anki2 — the sibling `scripts-anki` setup path), baked into app config. Still
  workable but worse; the spike's job is to find out which world we're in.
- P1 spec < 2 → notes/decks queries must use the browser-syntax URI variants; probe logs and adapts.

## Verdict

PENDING — requires the Pixel connected (real account's decks + installed AnkiDroid version are the
subject). Run instructions above; paste `adb logcat -s AnkiContractProbe` output back here.

## Wizard mapping (deliverable 1 of §4 plan)

1. **Screen 1 — deck picker**: P2 source; shows every deck (incl. empty) with provider counts
   (`DECK_COUNTS`). Selection stored as (deckId, deckName).
2. **Screen 2 — note type**: from `getModelList()` (P3). A strict deck→models join is not available in the
   pinned contract (no CardInDeck; Card rows carry no mid) — the wizard offers all models with names/counts,
   defaulting to the app's language-mapped type when sensible.
3. **Screen 3 — templated fields**: inputs labeled by FIELD_NAMES ordinals (P4/verify), prefilled from the
   reviewed draft (expression/reading/meaning/sentence/photo → mapped by NAME where possible, e.g. "kanji"/"keyword",
   index fallback only when name missing). Preview card via `generateHtml` before write (already in AddContentApi).
