#!/usr/bin/env python3
"""Build gold-set benchmark fixtures from a backup copy of the live corpus DB.

Gold standard = the user's in-app corrected photo texts (scraped_photos rows
with modified_by_user_flag=1). Their text is authoritative for CONTENT and
their \\n breaks are authoritative for SEGMENTATION (docs/benchmarks/
gold-set-iteration-plan.md, Q1/Q2 answered 2026-11-12).

Caveat baked into the design: the user strips speaker nameplates while
correcting, so gold never contains them. This script DRAFTS nameplate
candidates (OCR lines absent from gold, short, punctuation-free) which the
user confirms into nameplate_labels; the scorer is nameplate-agnostic.

Usage:
  python3 scripts/build_gold_fixtures.py --db backups/<ts>/mining.db \
      --out app/src/test/resources/gold_fixtures.json \
      [--mirror app/src/androidTest/assets/gold_fixtures.json] [--existing FILE]

Regeneration is idempotent: rerunning on a newer backup updates gold for
re-edited photos but PRESERVES human-reviewed nameplate_labels for photos
carried over from --existing (candidates are re-drafted around them).
"""
import argparse
import json
import re
import sqlite3
import sys
from pathlib import Path

TERMINAL_PUNCT = "。！？!?.…"

# A line is a nameplate CANDIDATE (not production behavior — benchmark-side
# draft label for human review) when it is short, punctuation-free, and its
# content characters appear nowhere in the user-corrected gold text.
def norm(s: str) -> str:
    return re.sub(r"\s+", "", s)

STRIP_CHARS = " \t、・。「」『』（）()【】〈〉—–_-*#:/\\~＾＾「」"

def content_chars(s: str) -> str:
    return "".join(c for c in s if not c.isspace() and c not in STRIP_CHARS)

def ocr_lines(doc: dict) -> list[str]:
    lines = []
    for b in doc.get("blocks", []):
        for ln in b.get("lines", []) or [{"text": b.get("text", "")}]:
            t = ln.get("text", "").strip()
            if t:
                lines.append(t)
    # fall back: some stored docs only carry fullText
    if not lines and doc.get("fullText"):
        lines = [l.strip() for l in doc["fullText"].split("\n") if l.strip()]
    return lines

def draft_candidates(doc: dict, gold: list[str]) -> tuple[list[str], list[str]]:
    """(candidates, ambiguous) — benchmark-side drafts only, human-reviewed later.

    candidate:  short, punctuation-free OCR line whose CONTENT appears nowhere
                in the gold sentences -> near-certain non-utterance leak.
    ambiguous:  short line that IS a substring of some gold sentence but is not
                a whole gold sentence -> either a nameplate that also occurs
                inside an utterance (ゴードン / ゴードンです。) or a wrapped
                content fragment (神剣ファルシオン). Goes to human review;
                NEVER auto-ignored by the scorer.
    """
    gold_norm = [norm(g) for g in gold]
    gold_contents = {content_chars(g) for g in gold}
    cand, ambig = [], []
    for line in ocr_lines(doc):
        if len(line) > 8 or any(c in line for c in TERMINAL_PUNCT):
            continue
        cc = content_chars(line)
        if not cc or cc in gold_contents:
            continue
        if not any(cc in g for g in gold_norm):
            if line not in cand:
                cand.append(line)
        else:
            if line not in ambig:
                ambig.append(line)
    return cand, ambig

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True, help="backup mining.db (read-only source)")
    ap.add_argument("--out", required=True)
    ap.add_argument("--mirror", default=None)
    ap.add_argument("--existing", default=None, help="prior gold fixture file to carry reviewed labels from")
    args = ap.parse_args()

    db = sqlite3.connect(f"file:{args.db}?mode=ro", uri=True)
    db.row_factory = sqlite3.Row

    reviewed = {}
    if args.existing and Path(args.existing).exists():
        prev = json.loads(Path(args.existing).read_text(encoding="utf-8"))
        for f in prev.get("fixtures", []):
            if f.get("nameplateLabels"):
                reviewed[str(f["photoId"])] = f["nameplateLabels"]

    rows = db.execute(
        "SELECT media_store_id, content_uri, extracted_text, ocr_blocks_json "
        "FROM scraped_photos WHERE modified_by_user_flag=1 AND extracted_text IS NOT NULL "
        "ORDER BY media_store_id"
    ).fetchall()

    fixtures = []
    for r in rows:
        gold = [l.strip() for l in r["extracted_text"].split("\n") if l.strip()]
        if not gold:
            continue
        doc = json.loads(r["ocr_blocks_json"]) if r["ocr_blocks_json"] else None
        if not doc or not doc.get("blocks"):
            print(f"SKIP photo {r['media_store_id']}: no frozen OCR blocks", file=sys.stderr)
            continue
        pid = str(r["media_store_id"])
        cand, ambig = draft_candidates(doc, gold)
        has_np = pid in reviewed or bool(cand) or bool(ambig)
        f = {
            "photoId": r["media_store_id"],
            "contentUri": r["content_uri"],
            "category": ("menu-or-ui" if r["media_store_id"] == 983
                          else "user-corrected-nameplate-prone" if has_np
                          else "user-corrected"),
            "gold": gold,
            "assert_segmentation": False,
            "sourceBackup": Path(args.db).parent.name,
            "nameplateLabels": reviewed.get(pid, []),
            "nameplateCandidates": cand,
            "ambiguousShortLines": ambig,
            "ocrDocument": doc,
        }
        fixtures.append(f)

    out = {
        "$schema_note": (
            "Gold-set fixtures for docs/benchmarks/gold-set-iteration-plan.md. "
            "gold = user-corrected scraped_photos.extracted_text split on \\n; "
            "user text is authoritative for content AND segmentation (Q1/Q2). "
            "nameplateLabels are human-confirmed (scorer ignores matching leaks); "
            "nameplateCandidates are auto-drafted, unconfirmed. Regenerated by "
            "scripts/build_gold_fixtures.py from a backup DB copy."
        ),
        "fixtures": fixtures,
    }
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    if args.mirror:
        Path(args.mirror).parent.mkdir(parents=True, exist_ok=True)
        Path(args.mirror).write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {len(fixtures)} gold fixtures -> {args.out}")
    n_cand = sum(1 for f in fixtures if f["nameplateCandidates"])
    print(f"photos with drafted nameplate candidates: {n_cand}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
