#!/usr/bin/env python3
"""Builds app/src/main/assets/shinjitai.db -- a Kyuujitai/Traditional-Chinese -> Japanese
Shinjitai character lookup, for the third leg of the Hanzi <-> NihongoShark-Kanji cross-
reference in HanziWordSyncService.backfillJpTile() that CC-CEDICT's Simplified/Traditional
pairing alone can't cover.

There are three independent CJK character-form lineages, not two: Traditional Chinese,
Simplified Chinese (Mainland China's post-1950s reform), and Shinjitai (Japan's own,
independent 1946 postwar reform). They often coincide (国/国, 学/学) or Shinjitai matches
Traditional exactly (绍's simplified form has traditional 紹, and Japanese kept 紹 unchanged --
that case is already handled by HanziWordSyncService's plain traditional-form fallback). But
sometimes all three diverge: 灵 (Simplified) / 靈 (Traditional) / 霊 (Shinjitai) are three
different characters. A lookup that only knows Simplified<->Traditional can't find 霊 for 灵.

Verified there's no existing source for this in the project before adding one: Unicode's own
Unihan database (kSimplifiedVariant/kTraditionalVariant/kSemanticVariant/kZVariant in
Unihan_Variants.txt) was checked directly against several known three-way splits (灵/靈/霊,
転/轉/转, 対/對/对, 圧/壓/压, 沢/澤/泽) and has *zero* entries for any of the Shinjitai forms --
Unihan's variant fields are scoped to the Chinese simplification relationship only, not Japan's
separate one.

Source: BYVoid/OpenCC's JPShinjitaiCharacters.txt (Apache-2.0, freely redistributable -- same
tier as KANJIDIC2/JMdict/CC-CEDICT, so unlike audio.db/ids.db this IS committed). It's keyed
Shinjitai -> Kyuujitai/Traditional (one shinjitai can have multiple historical kyuujitai
variants), curated from Japan's official 常用漢字表 (jouyou kanji), 人名用漢字表 (kanji
permitted in names), and 表外漢字字体表 tables. This script inverts it: kyuujitai/traditional
character -> shinjitai character, since that's the direction the sync needs (starting from a
Hanzi note's character, already resolved to its CC-CEDICT traditional form, and looking for a
Japanese kanji match).

Usage: python3 scripts/build_shinjitai_db.py
"""
import sqlite3
import urllib.request
from pathlib import Path

SOURCE_URL = "https://raw.githubusercontent.com/BYVoid/OpenCC/master/data/dictionary/JPShinjitaiCharacters.txt"
OUT_PATH = Path(__file__).parent.parent / "app" / "src" / "main" / "assets" / "shinjitai.db"


def parse_pairs(text: str) -> list[tuple[str, str]]:
    """(kyuujitai_or_traditional, shinjitai) pairs, one per kyuujitai variant listed."""
    pairs = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 2:
            continue
        shinjitai, kyuujitai_variants = parts[0], parts[1].split()
        for kyuujitai in kyuujitai_variants:
            pairs.append((kyuujitai, shinjitai))
    return pairs


def main():
    print(f"Downloading {SOURCE_URL} ...")
    with urllib.request.urlopen(SOURCE_URL, timeout=30) as resp:
        text = resp.read().decode("utf-8")

    pairs = parse_pairs(text)
    print(f"Parsed {len(pairs)} kyuujitai/traditional -> shinjitai pairs.")

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUT_PATH.unlink(missing_ok=True)
    conn = sqlite3.connect(OUT_PATH)
    conn.execute("CREATE TABLE shinjitai (kyuujitai TEXT NOT NULL, shinjitai TEXT NOT NULL)")
    conn.execute("CREATE INDEX idx_shinjitai_kyuujitai ON shinjitai (kyuujitai)")
    conn.executemany("INSERT INTO shinjitai VALUES (?, ?)", pairs)
    conn.commit()
    conn.close()

    size_kb = OUT_PATH.stat().st_size / 1024
    print(f"Wrote {len(pairs)} rows to {OUT_PATH} ({size_kb:.0f} KB)")


if __name__ == "__main__":
    main()
