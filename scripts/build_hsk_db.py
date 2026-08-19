#!/usr/bin/env python3
"""Builds app/src/main/assets/hsk.db -- character -> HSK 3.0 level, for the "HSK" field added to
the Hanzi note type (see docs/chinese-word-hanzi-sync-plan.md, Section 20).

Source: drkameleon/complete-hsk-vocabulary (MIT licensed -- already the source
scripts-anki/import_hsk_words.py uses for word-level HSK tagging), the official 2021 HSK 3.0
word list, "exclusive/new" wordlists specifically: each level's file contains only the words
first introduced at that level (no repeats across levels), unlike the "inclusive" files which
each cumulatively contain every lower level's words too. Levels 1-6 are their own bands; level 7
covers the combined "7-9" advanced band, matching how the official standard groups them.

HSK 3.0 is word-level, not character-level -- there's no separate official per-character list.
This derives one: every word in a level's file gets split into its individual Han characters,
and a character's HSK level is the *lowest* level any word containing it belongs to (e.g. if 爱
first appears in a level-1 word but also shows up inside a level-4 word, 爱 -> level 1). Processed
in ascending level order and only recorded the first time a character is seen, which already
guarantees "lowest level wins" -- min() is used explicitly anyway so the intent reads clearly
regardless of iteration order.

MIT is permissive enough that hsk.db is committed to the repo, same treatment as dictionary.db
(confirmed CC BY-SA) and unlike ids.db/audio.db (murkier/no-redistribution-clause sources).

Usage: python3 scripts/build_hsk_db.py
"""
import json
import sqlite3
import urllib.request
from pathlib import Path

WORDLIST_URL = (
    "https://raw.githubusercontent.com/drkameleon/complete-hsk-vocabulary/main/"
    "wordlists/exclusive/new/{level}.json"
)
OUT_PATH = Path(__file__).parent.parent / "app" / "src" / "main" / "assets" / "hsk.db"

# File 7 is the combined "7-9" advanced band -- not a literal level 7.
LEVELS = [1, 2, 3, 4, 5, 6, 7]
LEVEL_LABEL = {7: "7-9"}

# Same CJK-relevance filter as build_ids_db.py, for consistency -- HSK words are already almost
# entirely CJK Unified Ideographs, but this guards against stray non-Han characters/punctuation
# inside a "simplified" field.
RELEVANT_RANGES = [
    (0x4E00, 0x9FFF),  # CJK Unified Ideographs
    (0x3400, 0x4DBF),  # CJK Extension A
]


def is_relevant(char: str) -> bool:
    cp = ord(char)
    return any(lo <= cp <= hi for lo, hi in RELEVANT_RANGES)


def fetch_words(level: int) -> list[str]:
    with urllib.request.urlopen(WORDLIST_URL.format(level=level), timeout=30) as resp:
        entries = json.loads(resp.read())
    return [e["simplified"] for e in entries]


def main():
    char_rank: dict[str, int] = {}  # character -> lowest level rank (1-7) seen so far

    for level in LEVELS:
        words = fetch_words(level)
        print(f"HSK new-{LEVEL_LABEL.get(level, level)}: {len(words)} words from upstream")
        for word in words:
            for ch in word:
                if not is_relevant(ch):
                    continue
                char_rank[ch] = min(char_rank.get(ch, level), level)

    rows = [(ch, LEVEL_LABEL.get(rank, str(rank))) for ch, rank in char_rank.items()]

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUT_PATH.unlink(missing_ok=True)
    conn = sqlite3.connect(OUT_PATH)
    conn.execute("CREATE TABLE hsk (character TEXT PRIMARY KEY, level TEXT NOT NULL)")
    conn.executemany("INSERT INTO hsk VALUES (?, ?)", rows)
    conn.commit()
    conn.close()

    size_kb = OUT_PATH.stat().st_size / 1024
    print(f"Wrote {len(rows)} character HSK levels to {OUT_PATH} ({size_kb:.1f} KB)")


if __name__ == "__main__":
    main()
