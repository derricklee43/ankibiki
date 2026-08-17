#!/usr/bin/env python3
"""Seeds the Chinese Word deck with every HSK 1 and 2 (old 6-level standard) vocabulary word,
tagged HSK::1 / HSK::2.

Source: drkameleon/complete-hsk-vocabulary (MIT licensed), the "exclusive/old" wordlists --
"exclusive" meaning each word appears at only its lowest level (no HSK1 word repeated in the
HSK2 file), "old" meaning the classic 6-level standard (not the 2021 HSK 3.0 revision).

This only creates the Word note with its HSK tag -- Translation/Pinyin/HanziBreakdown are left
blank on purpose. Filling those in (from CC-CEDICT, matching the collection's existing pinyin
convention) and auto-creating/cross-linking the component hanzi is the on-device sync's job
(the button in the app) -- this script's only job is seeding the word list itself, so there's
one consistent source (CC-CEDICT, via the sync) for definitions/pinyin across every word, not
two dictionaries' styles mixed across the deck.

Talks to Anki Desktop via AnkiConnect, same as setup_chinese_word_deck.py. Idempotent: skips
any word that's already a note in the deck, so it's safe to re-run (e.g. after adding HSK 3/4
support here later).

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


def fetch_words(level: int) -> list[str]:
    with urllib.request.urlopen(WORDLIST_URL.format(level=level), timeout=30) as resp:
        entries = json.loads(resp.read())
    return [e["simplified"] for e in entries]


def main():
    version = invoke("version")
    print(f"Connected to AnkiConnect (API v{version})")

    existing_ids = invoke("findNotes", query=f'note:"{WORD_MODEL_NAME}"')
    existing_words = set()
    if existing_ids:
        for note in invoke("notesInfo", notes=existing_ids):
            existing_words.add(note["fields"]["Word"]["value"])
    print(f"{len(existing_words)} word(s) already in the deck.")

    notes_to_add = []
    for level in HSK_LEVELS:
        words = fetch_words(level)
        print(f"HSK{level}: {len(words)} words from upstream")
        for word in words:
            if word in existing_words:
                continue
            notes_to_add.append({
                "deckName": WORD_DECK_NAME,
                "modelName": WORD_MODEL_NAME,
                "fields": {"Word": word, "Translation": "", "Pinyin": "", "Audio": "", "HanziBreakdown": ""},
                "tags": [f"HSK::{level}"],
            })
            existing_words.add(word)  # guards against the same word appearing in both levels

    if not notes_to_add:
        print("Nothing to add -- every HSK 1/2 word is already in the deck.")
        return

    result = invoke("addNotes", notes=notes_to_add)
    added = sum(1 for note_id in result if note_id is not None)
    failed = len(result) - added
    print(f"Added {added} new word note(s).")
    if failed:
        print(f"{failed} failed to add (likely duplicates AnkiConnect itself caught).")
    print("Now run the sync button in the app to fill in Translation/Pinyin/HanziBreakdown "
          "and auto-create/cross-link the component hanzi.")


if __name__ == "__main__":
    main()
