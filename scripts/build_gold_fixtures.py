#!/usr/bin/env python3
"""Build gold-set benchmark fixtures from a backup copy of the live corpus DB.

Gold standard = photos the user EXPLICITLY marked "Gold" in-app
(scraped_photos.is_gold = 1; schema v7 + Mark-as-gold button, user revision
2026-11-13). Membership used to be ASSUMED from modified_by_user_flag=1, which
punished partial edits and excluded never-edited perfect photos; now the mark
is the membership. Their text is authoritative for CONTENT and their \n breaks
are authoritative for SEGMENTATION (docs/benchmarks/gold-set-iteration-plan.md,
Q1/Q2 answered 2026-11-12).

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

def lev(a: str, b: str) -> int:
    if not a:
        return len(b)
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i] + [0] * len(b)
        for j, cb in enumerate(b, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb))
        prev = cur
    return prev[-1]

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
    def best_window_sim(a: str, b: str) -> float:
        """Max 1−NED of `a` against any sliding window of b (len ± 2)."""
        if not b:
            return 0.0
        best = 0.0
        for w in range(max(1, len(a) - 2), min(len(b), len(a) + 2) + 1):
            for i in range(len(b) - w + 1):
                d = lev(a, b[i:i + w])
                best = max(best, 1.0 - d / max(len(a), w))
                if best >= 0.6:
                    return best
        return best

    gold_norm = [norm(g) for g in gold]
    gold_contents = {content_chars(g) for g in gold}
    cand, ambig = [], []
    for line in ocr_lines(doc):
        # trailing terminal punctuation is tolerated in drafting (OCR hallucinates
        # 。 on nameplates, e.g. ならずもの。 on photo 1149) — judge the core.
        core = line.rstrip(TERMINAL_PUNCT)
        if len(core) > 8 or any(c in core for c in TERMINAL_PUNCT):
            continue
        cc = content_chars(line)
        if not cc or cc in gold_contents:
            continue
        if any(cc in g for g in gold_norm) or best_window_sim(cc, "".join(gold_contents)) >= 0.6:
            # substring, or within ~40% edits of a gold window -> a MISREAD
            # CONTENT FRAGMENT (跡上がると for 技が上ると), never auto-ignored;
            # it goes to human review instead.
            if line not in ambig:
                ambig.append(line)
        else:
            if line not in cand:
                cand.append(line)
    return cand, ambig

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True, help="backup mining.db (read-only source)")
    ap.add_argument("--out", required=True)
    ap.add_argument("--mirror", default=None)
    ap.add_argument("--existing", default=None, help="prior gold fixture file to carry reviewed labels from")
    ap.add_argument("--adjudications", default=str(Path(__file__).resolve().parent.parent / "docs/benchmarks/gold-adjudications.json"),
                    help="user-approved gold text fixes JSON (applied only while old text still present)")
    args = ap.parse_args()

    db = sqlite3.connect(f"file:{args.db}?mode=ro", uri=True)
    db.row_factory = sqlite3.Row

    reviewed = {}
    legit = {}
    if args.existing and Path(args.existing).exists():
        prev = json.loads(Path(args.existing).read_text(encoding="utf-8"))
        for f in prev.get("fixtures", []):
            pid = str(f["photoId"])
            if f.get("nameplateLabels"):
                reviewed[pid] = f["nameplateLabels"]
            if f.get("legitShortLines"):
                # user-confirmed content lines — never re-drafted as candidates
                legit[pid] = f["legitShortLines"]

    adjudications = {}
    if args.adjudications and Path(args.adjudications).exists():
        adj = json.loads(Path(args.adjudications).read_text(encoding="utf-8"))
        for a in adj.get("adjudications", []):
            adjudications.setdefault(a["photoId"], []).append(a)

    def apply_adjudications(pid: int, gold: list[str]) -> tuple[list[str], list[str]]:
        """User-approved text fixes (--adjudications JSON, gold cleanup round).
        Rules retire automatically once the user's in-app text no longer contains
        the old substring/line — from then on the in-app text is the truth."""
        applied = []
        for a in adjudications.get(pid, []):
            hit = False
            for frm, to in a.get("replaces", []):
                new = [g.replace(frm, to) for g in gold]
                if new != gold:
                    gold = new
                    hit = True
            m = a.get("mergeLines")
            if m and all(line in gold for line in m):
                i = min(gold.index(line) for line in m)
                gold = gold[:i] + [a["into"]] + gold[i + len(m):]
                hit = True
            if hit:
                applied.append(a["id"])
        return gold, applied

    rows = db.execute(
        "SELECT media_store_id, content_uri, extracted_text, ocr_blocks_json "
        "FROM scraped_photos WHERE is_gold=1 AND extracted_text IS NOT NULL "
        "ORDER BY media_store_id"
    ).fetchall()

    fixtures = []
    for r in rows:
        gold = [l.strip() for l in r["extracted_text"].split("\n") if l.strip()]
        if not gold:
            continue
        gold, applied_adj = apply_adjudications(r["media_store_id"], gold)
        doc = json.loads(r["ocr_blocks_json"]) if r["ocr_blocks_json"] else None
        if not doc or not doc.get("blocks"):
            print(f"SKIP photo {r['media_store_id']}: no frozen OCR blocks", file=sys.stderr)
            continue
        pid = str(r["media_store_id"])
        cand, ambig = draft_candidates(doc, gold)
        confirmed_legit = legit.get(pid, [])
        cand = [c for c in cand if c not in confirmed_legit]
        ambig = [c for c in ambig if c not in confirmed_legit]
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
            "legitShortLines": confirmed_legit,
            "appliedAdjudications": applied_adj,
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
