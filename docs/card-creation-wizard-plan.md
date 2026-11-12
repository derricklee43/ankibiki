# Card-creation wizard — design plan (roadmap §4)

Status: **designing; no implementation** (user, 2026-11-10). Feasibility gate:
[spike 001](../spikes/001-anki-field-deck-probe/README.md) — field names/decks/create-path must probe
green on the Pixel before build order is finalized.

## Flow

```
reviewed item (from mining corpus)
   └─> Screen 0  Deck picker          all decks from provider (spike P2), incl. empty + counts
          └─> Screen 1  Field mapping  configure this deck's template against its Anki fields
                 └─> Screen 2  Preview / confirm
                        └─> (later) write + read-back verification
```

## Screen 1 — Field mapping (user-specified, 2026-11-10)

For the selected deck's note type, list **every Anki field** (name + ordinal from `FIELD_NAMES` —
spike P4; this is why the probe matters). One row per field, in ordinal order:

```
Anki field label        mapping widget
Kanji:        [ term ▼ ]  ⟨prefix: 【⟩ {溺れる} ⟨suffix: 】⟩
Meaning:      [ meaning ▼ ]  { to drown }
Reading:      [ blank/custom ▼ ]   ⟨free literal input for THIS note⟩
```

- The dropdown lists **only AnkiBiki field labels** plus a SINGLE `blank/custom` option (one entry,
  not two) — selecting it exposes a plain literal input for the current note.
- Once a field is selected, the dropdown **stays** showing the selected label; to its right the
  **resolved value** appears inside a composite input as `{value}`:
  - the braced core is **immutable** (it is the live resolved value of the selected AnkiBiki field
    for the current draft item — never editable inside the braces);
  - the user may **prepend / append text outside the braces** (two literal slots around it), which
    is how affixes like `【】`, `【音】…`, tags, or joined "reading (meaning)" shapes are expressed.
- **Templates store bindings only (revised, user 2026-11-10):** no custom literal and no
  prefix/suffix text is added to the saved template for ANY field — `Save template & continue`
  persists only which AnkiBiki field (or blank) maps to each Anki ordinal. Literals/affixes typed
  on screen affect the note being created right now, and are gone the next time the screen opens.
  (May change in the future — keep the persistence schema able to grow slots later.)
- Bottom buttons: **`Save template & continue`** persists this mapping as the deck's template and
  advances; **`Continue`** uses the current mapping for this note only, without persisting.
  (Both paths then land on Screen 2.)

## AnkiBiki export vocabulary — the adapter contract

The dropdown contents are a **closed, stable enum**, deliberately decoupled from internal models.
`DraftAdapter` maps internal data → this enum; when the mining schema changes (e.g. first-class
sentence rows from §1, or new fields), **only the adapter changes**; templates persist enum labels,
never internal column names, so saved templates keep working.

| Enum label | Meaning (current backing source) | Example |
|---|---|---|
| `term` | reviewed item's expression | 溺れる |
| `reading` | item's kana/reading | でいれる / nì |
| `meaning` | item's English meaning | to drown |
| `sentence` | the selected source sentence | アリティアの弓兵部に所霧する。 |
| `sentence_words` | parsed words of the sentence (joined) | list, joiner configurable later |
| `photo` | source photo (media attachment; see open questions) | img.jpg ref |
| `language` | Japanese / Chinese (item's tag) | Japanese |
| `level` | JLPT/HSK badge | N3 |
| `rank` | corpus frequency rank | #344 |
| `raw_ocr` | photo's raw OCR text (only if §3 surfaces it) | |

- Stored form of a mapping row: `SavedMapping = Blank | Bound(field: ExportField)` — nothing else.
  Ephemeral per-creation state (not saved): prefix/suffix slots and the single blank/custom literal.
- Contract test duty: one table-driven test pins every enum → value extraction; adapter refactor is
  only allowed through that gate ("my ankibiki implementation can change without fucking my exports up").
- Unknown/retired enum in a stored template → row falls back to `blank` + visible warning (never a
  silent wrong value).

## Template storage

- Key: `(deckId, modelId)` — a deck may host more than one note type; the saved template must record
  which it was configured against (open question Q1 below).
- Persist in **mining.db** (new table `card_templates`, did/mid/ordinal/enum/prefix/suffix/literal,
  schema `user_version` bump + migration). Columns stay minimal for now
  (did/mid/ordinal/bound_enum — nullable), so adding affix/custom persistence later is an additive
  change, not a rewrite. Consequence worth noting: templates become covered by the §1 backup bundles
  automatically — recovery restores your mappings too.
- On entry to Screen 1: saved template pre-selects dropdowns and prefix/suffix slots; resolved
  values recompute live from the current draft item.

## Screen 2 — Preview / confirm

- Per resolved Anki field: show final composed string for THIS note (prefix + value + suffix, or the
  custom literal) — richer than what the template persists, by design.
- Rendered card preview via the provider's generate-HTML path (`AddContentApi.generateCardHtml(flds,
  mid, ord)` family — exact method to be confirmed by spike P7's sandbox run), one tab per card template.
- Explicit confirm button performs the write; **cancel writes nothing**; a retry after failure must
  read-back check for an existing note first (roadmap acceptance: "retries must not silently create
  duplicate cards"). Read-back = query the created note and re-zip fields↔names (spike P5).

## Non-goals (from roadmap §4 acceptance)

Not a general Anki editor; no deck/note-type management UI; template config stays focused on mapping.

## Open questions (next design passes)

- **Q1** Deck vs deck+model as the template key: wizard screen 0 picks a deck — where does the note
  type come from: a second picker, the deck's last-used model, or per-language default (`SEARCH_SOURCE_MODEL_ID`)?
- **Q2** Multiple card templates in one note type (Basic & reversed): does the preview confirm ONE note
  (all its cards) or allow per-template selection?
- **Q3** `photo` enum: media bytes via provider media insert + `[filename]` embedding — and what happens
  when the draft's photo is MediaStore-inaccessible (off-device restore case).
- **Q4** Which enum labels are actually available at the moment the wizard opens (item detail is the
  draft source) — e.g. `sentence_words` joiner defaults, `raw_ocr` availability gating.
- **Q5** Where does the wizard open from: the single word view's existing `カードを作成` button
  (`btn_create_card` today falls back to lookup-open), or a new card-creation surface?
- **Q6** Editing a saved template later: re-enter Screen 1 prefilled (yes) + a reset-to-blank action?
- **Q7** (from the 2026-11-10 revision) Confirm: when `blank/custom` is chosen and a literal typed, that
  literal DOES flow into the note being created (it's just not saved to the template) — vs. "custom
  ignored entirely for now, option is effectively blank". The plan assumes the former; say the word
  if it's the latter and the literal input disappears until it can be persisted.
