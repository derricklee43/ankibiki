#!/usr/bin/env python3
"""Build the nameplate label review table (round 4+) for user verdicts.

One row per (photo, drafted candidate string) occurrence, showing:
  - the parsed sentence exactly as the pipeline emitted it (standalone leak,
    or the utterance the label was merged into),
  - the user's gold sentence that the parsed line corresponds to (blank when
    the leak stands alone — there is no counterpart),
  - the predicted nameplate string,
  - an empty verdict column the user fills with y (really a nameplate -> omit)
    or n (legit content -> never excludable).

Rows where the current parser already drops the candidate (geometry/length
filters) are listed with parsed="(no current leak)" — they still need a
verdict because they are ground truth for the exclusion spike either way.

Usage: python3 scripts/build_nameplate_review.py --out docs/benchmarks/<file>.md
Reads: gold_fixtures.json (live) + latest BENCHMARK_GOLD run JSON in
/home/derri/.hermes/cache/scratch (falls back to fixture-blocks replay note).
"""
import argparse, json, re, sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
GOLD = REPO / "app/src/test/resources/gold_fixtures.json"
RUN = Path("/home/derri/.hermes/cache/scratch/gold_run7.json")

def lev(a, b):
    if not a: return len(b)
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i] + [0] * len(b)
        for j, cb in enumerate(b, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb))
        prev = cur
    return prev[-1]

def norm(s): return ''.join(s.split())

def sim(a, b):
    m = max(len(a), len(b))
    return 1.0 if m == 0 else 1.0 - lev(a, b) / m

TERMINAL = '。．！!？?'

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    fixtures = json.load(open(GOLD))['fixtures']
    run = {l['photoId']: l for l in json.load(open(RUN)) if l.get('metric') == 'BENCHMARK_GOLD_FIXTURE'}

    rows = []
    for f in sorted(fixtures, key=lambda x: x['photoId']):
        pid = f['photoId']
        cands = f.get('nameplateCandidates', [])
        if not cands or pid not in run:
            continue
        parsed = [norm(p) for p in run[pid]['parsed']]
        golds = [g for g in f['gold']]
        goldn = [norm(g) for g in golds]
        for c in cands:
            cn = norm(c)
            standalone = next((p for p in parsed if p == cn or p.rstrip(TERMINAL) == cn), None)
            inline = None
            for p in parsed:
                if p != cn and p.startswith(cn) and len(p) > len(cn):
                    rem = p[len(cn):]
                    best = max(range(len(goldn)), key=lambda i: sim(rem, goldn[i]), default=-1)
                    if best >= 0 and sim(rem, goldn[best]) >= 0.6:
                        inline = (p, golds[best]); break
            if standalone is not None:
                rows.append((pid, f"`{standalone}`", "—", f"`{c}`", ""))
            elif inline is not None:
                rows.append((pid, f"`{inline[0]}`", f"`{inline[1]}`", f"`{c}`", ""))
            else:
                rows.append((pid, "(no current leak)", "—", f"`{c}`", ""))

    by_string = {}
    for r in rows:
        by_string.setdefault(r[3], []).append(r)

    out = []
    A = out.append
    A("# Nameplate review — round 4 (81 drafted candidates, " + str(len(rows)) + " occurrences)")
    A("")
    A("Fill the last column: **y** = really a speaker nameplate (exclusion may omit it),")
    A("**n** = legitimate content (must never be omitted). Blank = undecided.")
    A("`parsed` is the pipeline's actual output line; `gold` is YOUR corrected sentence")
    A("that it corresponds to (em-dash when the leak stands alone; '(no current leak)'")
    A("means the parser already drops it — still needs your verdict, it is ground truth")
    A("for the exclusion spike either way). Rows grouped by identical string.")
    A("When done, tell me — verdicts freeze into nameplateLabels/legitShortLines and")
    A("regeneration carries them forward.")
    A("")
    A("| photo | parsed sentence | your gold sentence | predicted nameplate | y/n |")
    A("|---|---|---|---|---|")
    for s in sorted(by_string, key=lambda s: (-len(by_string[s]), s)):
        for (pid, parsed, gold, cand, _) in by_string[s]:
            A(f"| {pid} | {parsed} | {gold} | {cand} |  |")
    Path(args.out).write_text("\n".join(out) + "\n", encoding='utf-8')
    print(f"wrote {args.out}: {len(by_string)} distinct candidates, {len(rows)} rows")

if __name__ == '__main__':
    sys.exit(main())
