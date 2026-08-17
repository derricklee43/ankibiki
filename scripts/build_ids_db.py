#!/usr/bin/env python3
"""Builds app/src/main/assets/ids.db -- character decomposition trees for the "Constituents"
field added to the Hanzi note type (see docs/chinese-word-hanzi-sync-plan.md).

Source: cjkvi-ids' ids.txt (github.com/cjkvi/cjkvi-ids), derived from the CHISE project. That
repo's own README says this specific file "follows [CHISE's] terms" rather than the rest of the
repo's blanket GPLv2 -- and CHISE's own site doesn't spell out clear terms for it either. Given
that ambiguity, unlike dictionary.db's KANJIDIC2/JMdict/CC-CEDICT (confirmed CC BY-SA, freely
redistributable, hence committed), ids.db built here is NOT committed to the repo -- same
treatment as audio.db, for the same reason: don't commit data whose redistribution terms aren't
clearly established, even for a personal/unpublished app. Re-run this to rebuild it.

Each character's IDS entry describes its *direct* structure (e.g. 好 -> ⿰女子), but only
inlines further nesting for sub-groupings that aren't themselves an encoded character (e.g.
亭 -> ⿱⿳亠口冖丁 -- the top part has no character of its own, so CHISE spells it out in
place). Where a component *is* a real character (讠, 射, 女, ...), it's referenced by that
character alone, and getting *its* further breakdown means a separate lookup into the same
table. So building the fully-recursive tree needs both: a proper recursive-descent parse of
each IDC operator's arity (2-way for most, 3-way for the left-middle-right/top-middle-bottom
ones), and re-expanding every resulting leaf character through the table again, down to a
depth cap (guards against any accidental cycle, not just runaway depth).

Usage: python3 scripts/build_ids_db.py
"""
import json
import re
import sqlite3
import urllib.request
from pathlib import Path

IDS_URL = "https://raw.githubusercontent.com/cjkvi/cjkvi-ids/master/ids.txt"
OUT_PATH = Path(__file__).parent.parent / "app" / "src" / "main" / "assets" / "ids.db"

# Ideographic Description Characters -> how many components each takes.
IDC_ARITY = {
    "⿰": 2, "⿱": 2, "⿲": 3, "⿳": 3, "⿴": 2, "⿵": 2,
    "⿶": 2, "⿷": 2, "⿸": 2, "⿹": 2, "⿺": 2, "⿻": 2,
}

MAX_DEPTH = 6

# ids.txt covers every CJK Unicode plane (rare extensions, PUA, etc.) -- ~89k characters,
# ~39MB unfiltered. This app only ever needs practical Chinese characters and the radicals
# they decompose into, so keep just: CJK Unified Ideographs (the vast majority of actual
# Chinese text), CJK Extension A (less common but still real characters), and the radical/
# stroke blocks that show up as leaf components in decompositions.
RELEVANT_RANGES = [
    (0x4E00, 0x9FFF),  # CJK Unified Ideographs
    (0x3400, 0x4DBF),  # CJK Extension A
    (0x2E80, 0x2EFF),  # CJK Radicals Supplement
    (0x2F00, 0x2FDF),  # Kangxi Radicals
    (0x31C0, 0x31EF),  # CJK Strokes
]


def is_relevant(char: str) -> bool:
    cp = ord(char)
    return any(lo <= cp <= hi for lo, hi in RELEVANT_RANGES)


def fetch_ids_lines() -> list[str]:
    with urllib.request.urlopen(IDS_URL, timeout=30) as resp:
        return resp.read().decode("utf-8").splitlines()


def pick_decomposition(raw_candidates: list[str]) -> str:
    """A line may list multiple region-variant decompositions (tagged [G]/[T]/[J]/[K]/[V]/...
    at the end). Prefer the untagged (universal) or Mainland-China ([G]) one, since the deck
    this feeds is Simplified Chinese."""
    def tag(cand: str) -> str | None:
        m = re.search(r"\[([A-Z]+)\]$", cand)
        return m.group(1) if m else None

    def strip_tag(cand: str) -> str:
        return re.sub(r"\[[A-Z]+\]$", "", cand)

    for cand in raw_candidates:
        t = tag(cand)
        if t is None or "G" in t:
            return strip_tag(cand)
    return strip_tag(raw_candidates[0])


def parse_ids_table(lines: list[str]) -> dict[str, str]:
    """character -> its raw decomposition string (may still contain nested IDCs)."""
    table: dict[str, str] = {}
    for line in lines:
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        table[parts[1]] = pick_decomposition(parts[2:])
    return table


def parse_tree(ids_str: str, pos: int = 0) -> tuple[dict, int]:
    """Recursive-descent parse of one IDS string starting at pos. A leaf's "char" is a real
    character; an operator node's "char" is None (it has no identity of its own)."""
    ch = ids_str[pos]
    if ch in IDC_ARITY:
        children = []
        p = pos + 1
        for _ in range(IDC_ARITY[ch]):
            child, p = parse_tree(ids_str, p)
            children.append(child)
        return {"char": None, "op": ch, "children": children}, p
    return {"char": ch, "op": None, "children": []}, pos + 1


def expand(char: str, table: dict[str, str], memo: dict[str, dict], seen: frozenset, depth: int) -> dict:
    """Fully-recursive decomposition tree for `char`, memoized (many characters share
    common radicals, so this saves a lot of repeated work over ~89k entries)."""
    if depth == 0 and char in memo:
        return memo[char]

    node = {"char": char, "op": None, "children": []}
    if depth >= MAX_DEPTH or char in seen:
        return node

    decomposition = table.get(char)
    if decomposition is None or decomposition == char:
        return node  # atomic, or no data for it

    parsed, _ = parse_tree(decomposition)
    next_seen = seen | {char}

    def fill(n: dict) -> dict:
        if n["char"] is not None:
            return expand(n["char"], table, memo, next_seen, depth + 1)
        return {"char": None, "op": n["op"], "children": [fill(c) for c in n["children"]]}

    filled = fill(parsed)
    node["op"] = filled["op"]
    node["children"] = filled["children"]

    if depth == 0:
        memo[char] = node
    return node


def main():
    print(f"Downloading {IDS_URL} ...")
    lines = fetch_ids_lines()
    table = parse_ids_table(lines)
    print(f"Parsed {len(table)} characters.")

    memo: dict[str, dict] = {}
    rows = []
    for char in table:
        if not is_relevant(char):
            continue
        tree = expand(char, table, memo, frozenset(), 0)
        rows.append((char, json.dumps(tree, ensure_ascii=False, separators=(",", ":"))))

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUT_PATH.unlink(missing_ok=True)
    conn = sqlite3.connect(OUT_PATH)
    conn.execute("CREATE TABLE ids (character TEXT PRIMARY KEY, tree_json TEXT NOT NULL)")
    conn.executemany("INSERT INTO ids VALUES (?, ?)", rows)
    conn.commit()
    conn.close()

    size_mb = OUT_PATH.stat().st_size / (1024 * 1024)
    print(f"Wrote {len(rows)} decomposition trees to {OUT_PATH} ({size_mb:.1f} MB)")


if __name__ == "__main__":
    main()
