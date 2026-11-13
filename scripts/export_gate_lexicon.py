#!/usr/bin/env python3
"""Generate the JVM-test lexicon snapshot for the OCR correction gate.

The parity test (OcrCorrectionGateParityTest) must evaluate the Kotlin port with
EXACTLY the dictionary data the spike measured with. JVM unit tests have no SQLite
driver, so we freeze a deterministic JSON snapshot of the three derived sets
(projected headwords, CJK bigrams, kanji->radicals) from app/src/main/assets/dictionary.db.
The snapshot embeds the source DB's sha256; the test asserts it matches the live asset,
so a dictionary bump cannot silently drift the benchmark.

Usage: python3 scripts/export_gate_lexicon.py
"""
import hashlib
import json
import sys
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DICTDB = REPO / "app/src/main/assets/dictionary.db"
OUT = REPO / "app/src/test/resources/gate_lexicon_snapshot.json"


def is_cjk(ch: str) -> bool:
    return (
        "\u3040" <= ch <= "\u309f"
        or "\u30a0" <= ch <= "\u30ff"
        or "\u4e00" <= ch <= "\u9fff"
        or "\u3400" <= ch <= "\u4dbf"
        or ch == "々"
    )


def main() -> int:
    if not DICTDB.exists():
        print(f"missing {DICTDB}", file=sys.stderr)
        return 1
    import sqlite3

    db = sqlite3.connect(f"file:{DICTDB}?mode=ro", uri=True)
    headwords: set[str] = set()
    bigrams: set[str] = set()
    word_rows = 0
    for (hw,) in db.execute("SELECT headword FROM words"):
        word_rows += 1
        full = "".join(c for c in hw if is_cjk(c))
        if 2 <= len(full) <= 8:
            headwords.add(full)
        for a, b in zip(hw, hw[1:]):
            if is_cjk(a) and is_cjk(b):
                bigrams.add(a + b)
    radicals: dict[str, list[str]] = {}
    rad = defaultdict(set)
    for ch, r in db.execute("SELECT character, radical FROM kanji_radicals"):
        rad[ch].add(r)
    for ch in sorted(rad):
        radicals[ch] = sorted(rad[ch])
    sha = hashlib.sha256(DICTDB.read_bytes()).hexdigest()
    payload = {
        "source": "app/src/main/assets/dictionary.db",
        "sha256": sha,
        "wordRows": word_rows,
        "headwords": sorted(headwords),
        "bigrams": sorted(bigrams),
        "kanjiRadicals": radicals,
    }
    OUT.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(
        f"wrote {OUT} ({OUT.stat().st_size/1e6:.1f} MB): headwords={len(headwords)} "
        f"bigrams={len(bigrams)} radicalChars={len(radicals)} wordRows={word_rows} sha256={sha[:16]}…"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
