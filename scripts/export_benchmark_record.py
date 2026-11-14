#!/usr/bin/env python3
"""Generate app/src/main/assets/benchmark_record.json — the gold dashboard's (roadmap B2)
STATIC recorded-benchmark table. The numbers are the current gold-harness headline; their
single home is docs/extraction-pipeline-state.md (P0 rule 2026-11-14: docs point, never
restate — this asset is the one sanctioned machine copy). BenchmarkRecordParityTest fails
loudly if the doc's headline moves while this asset still shows old numbers, so the app
table cannot silently drift from the record.

Usage (from the repo root, after a benchmark run's numbers are written into the doc):
  python3 scripts/export_benchmark_record.py \
    --gold-photos 126 --sentence-exact 73/181 --pct 40.3% \
    --seg-exact 31/126 --char-acc 0.799 --recorded 2026-11-14
"""
import argparse
import hashlib
import json
import pathlib
import datetime

REPO = pathlib.Path(__file__).resolve().parent.parent
DOC = REPO / "docs" / "extraction-pipeline-state.md"
OUT = REPO / "app" / "src" / "main" / "assets" / "benchmark_record.json"


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--gold-photos", type=int, required=True)
    p.add_argument("--sentence-exact", required=True, help='e.g. "73/181"')
    p.add_argument("--pct", required=True, help='e.g. "40.3%%"')
    p.add_argument("--seg-exact", required=True, help='e.g. "31/126"')
    p.add_argument("--char-acc", required=True, help='e.g. "0.799"')
    p.add_argument("--recorded", required=True, help="date of the run of record, YYYY-MM-DD")
    a = p.parse_args()

    doc_bytes = DOC.read_bytes()
    record = {
        "source_doc": "docs/extraction-pipeline-state.md",
        "source_doc_sha256": hashlib.sha256(doc_bytes).hexdigest(),
        "generated_utc": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "recorded": a.recorded,
        "gold_fixture_photos": a.gold_photos,
        "sentence_exact": a.sentence_exact,
        "sentence_exact_pct": a.pct,
        "segmentation_exact": a.seg_exact,
        "char_accuracy": a.char_acc,
        "caveat": "as-typed gold is a correctness FLOOR — surviving typos are misses, not endorsements",
    }
    OUT.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUT.relative_to(REPO)} (doc sha {record['source_doc_sha256'][:12]}…)")

    # Tripwire now instead of at test time: every number must appear in the doc of record.
    doc_text = doc_bytes.decode("utf-8")
    for needle in (a.sentence_exact, a.seg_exact, a.char_acc, f"{a.gold_photos} photos"):
        if needle not in doc_text:
            raise SystemExit(f"ERROR: '{needle}' not found in {DOC.relative_to(REPO)} — "
                             f"update the doc first (it is the single home of the numbers)")
    print("headline numbers verified present in the doc of record")


if __name__ == "__main__":
    main()
