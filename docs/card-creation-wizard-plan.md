# Card-creation wizard — design plan (roadmap §4)

Status: **designing; no implementation** (user, 2026-11-10). Feasibility gate:
[spike 001](../spikes/001-anki-field-deck-probe/README.md) — field names/decks/create-path must probe
green on the Pixel before build order is finalized.

## Flow

```
single word view「カードを作成」(the only entry point for now — Q5 answered)
   └─> Screen 0  Deck + card type    user explicitly chooses EVERY time (Q1 answered):
             deck list from provider (spike P2, incl. empty + counts)
             note type (card template) list from getModelList (spike P3)
          └─> Screen 1  Field mapping  the (deck × card type) template's bindings pre-fill this
                 └─> Screen 2  Preview / confirm
                        └─> (later) write + read-back verification
```

Template granularity (Q1): **one template per (deck, card type)**. Nothing is auto-selected — the
user picks deck and card type on every creation; the matching saved template only pre-fills Screen 1.
Entry point (Q5): the single word view's existing `カードを作成` button — today it throws
"card creation is deferred"; it becomes the wizard launcher. Other entry points come later.

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
  not two). Per Q7 (answered 2026-11-10): the *choice* is persisted with the template so the user
  doesn't reselect it each time, but **its value is always blank for now** — no literal input is
  wired into the note yet; the option is reserved until custom text can mean something.
- Once a field is selected, the dropdown **stays** showing the selected label; to its right the
  **resolved value** appears inside a composite input as `{value}`:
  - the braced core is **immutable** (it is the live resolved value of the selected AnkiBiki field
    for the current draft item — never editable inside the braces);
  - the user may **prepend / append text outside the braces** (two literal slots around it), which
    is how affixes like `【】`, `【音】…`, tags, or joined "reading (meaning)" shapes are expressed.
- **Templates store bindings only (revised, user 2026-11-10):** no prefix/suffix affix text and no
  custom literal is ever added to the saved template for ANY field — `Save template & continue`
  persists only which AnkiBiki field (or the single `blank/custom` option) maps to each Anki
  ordinal. Affix slots are live-this-note-only and start empty on every visit.
  (May change in the future — persistence schema grows additively, not by rewrite.)
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
| `photo` | source photo (media attachment); unreadable at write time → warning on preview, blank on write (Q3) | img.jpg ref |
| `language` | Japanese / Chinese (item's tag) | Japanese |
| `level` | JLPT/HSK badge | N3 |
| `rank` | corpus frequency rank | #344 |

(`raw_ocr` was dropped from the vocabulary — Q4 answered: OCR content is not wizard-mappable.)

- Stored form of a mapping row: `SavedMapping = Unbound | Bound(field: ExportField)` — `Unbound`
  covers the single `blank/custom` dropdown choice (persisted as a choice; resolves to an empty
  value for now). Ephemeral per-creation state (never saved): prefix/suffix slots.
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

- Per resolved Anki field: show final composed string for THIS note (prefix + value + suffix; a
  `blank/custom` row renders empty) — what gets written equals what is previewed, verbatim.
- Q3 (answered 2026-11-12): if the draft's photo is unreadable, the preview shows a **warning adjacent
  to the photo field** and the write proceeds with that field **blank**.
- Rendered card preview via the provider's generate-HTML path (`AddContentApi.generateCardHtml(flds,
  mid, ord)` family — exact method to be confirmed by spike P7's sandbox run), one tab per card template.
- Explicit confirm button performs the write; **cancel writes nothing**; a retry after failure must
  read-back check for an existing note first (roadmap acceptance: "retries must not silently create
  duplicate cards"). Read-back = query the created note and re-zip fields↔names (spike P5).

## Non-goals (from roadmap §4 acceptance)

Not a general Anki editor; no deck/note-type management UI; template config stays focused on mapping.

## Open questions (next design passes)

Answered 2026-11-10: Q1 = one template per (deck, card type), user chooses explicitly every time;
Q5 = single word view button is the only entry point for now; Q7 = the blank/custom choice persists
but its value is always blank. Answered 2026-11-12: **Q3** = unreadable photo → warning adjacent to
the field on the preview screen, blank on write; **Q4** = `sentence_words` stays in the vocabulary,
OCR-derived labels are not offered at all. Remaining:

- **Q2** (clarified — NOT about sharing one template across decks) In Anki, one note type contains
  its field definitions AND one or more **card templates** (front/back layouts). A type like
  "Basic (and reversed card)" turns the single note your wizard writes into TWO cards. Since the
  provider writes notes, not cards: does the preview/confirm screen show one confirm with a tab per
  generated card (recommended), or something else?
- **Q6** (clarified) Editing a saved template later = running the same wizard for that deck × card
  type: Screen 1 opens pre-filled with the stored bindings and "Save template & continue" overwrites.
  No separate template-management screen. Open: should Screen 1 also offer an explicit "reset to
  blank" action (start mapping from nothing while keeping the same saved template otherwise), or is
  per-field re-selection enough?

