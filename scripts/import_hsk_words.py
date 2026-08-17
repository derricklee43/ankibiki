#!/usr/bin/env python3
"""Seeds the Chinese Word deck with every HSK 1 and 2 (old 6-level standard) vocabulary word,
tagged HSK::1 / HSK::2, and keeps their Translation in sync with the HSK wordlist's own English
gloss (its simpler, curated meaning reads better for a beginner-level word than a raw CC-CEDICT
dictionary entry would).

Source: drkameleon/complete-hsk-vocabulary (MIT licensed), the "exclusive/old" wordlists --
"exclusive" meaning each word appears at only its lowest level (no HSK1 word repeated in the
HSK2 file), "old" meaning the classic 6-level standard (not the 2021 HSK 3.0 revision).

Translation is the *only* field this script owns for these words -- Pinyin/HanziBreakdown are
left for the on-device sync (the button in the app) to fill from CC-CEDICT and auto-create/
cross-link the component hanzi, same as every other word in the deck. Re-running this script
only ever touches Translation (via a partial field update, so it never disturbs whatever the
sync already filled in for Pinyin/Audio/HanziBreakdown) -- safe to re-run any time upstream data
changes, or after adding HSK 3/4 support here later.

Talks to Anki Desktop via AnkiConnect, same as setup_chinese_word_deck.py.

Usage: python3 scripts/import_hsk_words.py
"""
import json
import urllib.request

ANKICONNECT_URL = "http://127.0.0.1:8765"
WORD_MODEL_NAME = "Chinese Word"
WORD_DECK_NAME = "🇨🇳 Chinese Words"

HSK_LEVELS = [1, 2]
WORDLIST_URL = "https://raw.githubusercontent.com/drkameleon/complete-hsk-vocabulary/main/wordlists/exclusive/old/{level}.json"


def invoke(action: str, **params):
    payload = json.dumps({"action": action, "version": 6, "params": params}).encode("utf-8")
    req = urllib.request.Request(ANKICONNECT_URL, data=payload)
    with urllib.request.urlopen(req, timeout=10) as resp:
        body = json.loads(resp.read())
    if body.get("error") is not None:
        raise RuntimeError(f"{action} failed: {body['error']}")
    return body["result"]


def fetch_words(level: int) -> list[tuple[str, str]]:
    """Returns (simplified, translation) pairs -- translation is the wordlist's own gloss,
    its first form's senses joined the same way CC-CEDICT definitions already are ("; ")."""
    with urllib.request.urlopen(WORDLIST_URL.format(level=level), timeout=30) as resp:
        entries = json.loads(resp.read())
    return [(e["simplified"], "; ".join(e["forms"][0]["meanings"])) for e in entries]


def main():
    version = invoke("version")
    print(f"Connected to AnkiConnect (API v{version})")

    existing_ids = invoke("findNotes", query=f'note:"{WORD_MODEL_NAME}"')
    existing_by_word = {}
    if existing_ids:
        for note in invoke("notesInfo", notes=existing_ids):
            existing_by_word[note["fields"]["Word"]["value"]] = note
    print(f"{len(existing_by_word)} word(s) already in the deck.")

    notes_to_add = []
    translation_updates = []
    seen_words = set()
    for level in HSK_LEVELS:
        pairs = fetch_words(level)
        print(f"HSK{level}: {len(pairs)} words from upstream")
        for word, translation in pairs:
            if word in seen_words:
                continue  # guards against the same word appearing in both levels
            seen_words.add(word)

            existing_note = existing_by_word.get(word)
            if existing_note is None:
                notes_to_add.append({
                    "deckName": WORD_DECK_NAME,
                    "modelName": WORD_MODEL_NAME,
                    "fields": {"Word": word, "Translation": translation, "Pinyin": "", "Audio": "", "HanziBreakdown": ""},
                    "tags": [f"HSK::{level}"],
                })
            elif existing_note["fields"]["Translation"]["value"] != translation:
                translation_updates.append((existing_note["noteId"], translation))

    if notes_to_add:
        result = invoke("addNotes", notes=notes_to_add)
        added = sum(1 for note_id in result if note_id is not None)
        failed = len(result) - added
        print(f"Added {added} new word note(s).")
        if failed:
            print(f"{failed} failed to add (likely duplicates AnkiConnect itself caught).")
    else:
        print("No new words to add.")

    updated = 0
    for note_id, translation in translation_updates:
        invoke("updateNoteFields", note={"id": note_id, "fields": {"Translation": translation}})
        updated += 1
    print(f"Updated Translation on {updated} existing word note(s) (Pinyin/Audio/HanziBreakdown untouched).")

    if notes_to_add:
        print("New words added with Pinyin left blank -- run the sync button in the app to fill "
              "it in and auto-create/cross-link the component hanzi.")


if __name__ == "__main__":
    main()
