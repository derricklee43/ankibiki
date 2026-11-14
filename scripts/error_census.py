#!/usr/bin/env python3
"""Error-class census of the gold benchmark headline (spike follow-up, 2026-11-13).

Question: nameplates were the visible garbage; among the sentences that still fail
(181 gold - 73 exact), which classes own the next 10 points? Deterministic offline
replay of the scorer's universe from gold_run9 (parsed lines minus standalone leaks
minus inline-merged lines — the same pool the headline scored) greedily aligned to
user gold. Classification is heuristic but conservative; every non-exact pair lands
in exactly one bucket, examples are printed for eyeballing.

Usage: python3 scripts/error_census.py [--out docs/benchmarks/2026-11-13-error-census.md]
"""
import json, argparse
from difflib import SequenceMatcher
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
GOLD = REPO / "app/src/test/resources/gold_fixtures.json"
RUN = Path("/home/derri/.hermes/cache/scratch/gold_run9.json")

PUNCT = set("。．！？!?….,、・「」『』（）()【】〈〉ー~〜ー ＼/\\-_0900")  # 0900 unused sentinel
KANJI_KANA = lambda c: ('\u3040' <= c <= '\u309f') or ('\u30a0' <= c <= '\u30ff') or ('\u4e00' <= c <= '\u9fff')

def norm(s): return ''.join(s.split())
def sim(a, b): return SequenceMatcher(None, a, b).ratio()
def diff_chars(a, b):
    """chars differing between two aligned strings (rough, order-sensitive)."""
    sm = SequenceMatcher(None, a, b)
    subs, dels, ins = [], [], []
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == 'replace': subs.append((a[i1:i2], b[j1:j2]))
        elif tag == 'delete': dels.append(a[i1:i2])
        elif tag == 'insert': ins.append(b[j1:j2])
    return subs, dels, ins

def classify(gold, parsed):
    subs, dels, ins = diff_chars(gold, parsed)
    changed = ''.join(x for t in subs for x in t) + ''.join(dels) + ''.join(ins)
    if gold == parsed: return 'exact', 0
    if all(not c.isspace() and not KANJI_KANA(c) for c in changed):
        return 'punctuation/latin-only', len(changed)
    nchars = sum(len(x) + len(y) for x, y in subs) + sum(len(x) for x in dels) + sum(len(x) for x in ins)
    if abs(len(gold) - len(parsed)) > max(4, 0.35 * len(gold)):
        return 'boundary/stitch (length mismatch)', nchars
    if nchars <= 2: return 'misread (1-2 chars)', nchars
    if nchars <= 5: return 'misread (3-5 chars)', nchars
    return 'heavy rewrite / garbage tail', nchars

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=None)
    args = ap.parse_args()
    fixtures = {f['photoId']: f for f in json.load(open(GOLD))['fixtures']}
    entries = {e['photoId']: e for e in json.load(open(RUN)) if e.get('metric') == 'BENCHMARK_GOLD_FIXTURE'}
    labels = {cjk for f in fixtures.values() for cjk in (norm(L) for L in f.get('nameplateLabels', [])) if cjk}

    classes = {}
    examples = []
    np_standalone = np_inline = 0
    for pid, e in entries.items():
        f = fixtures[pid]
        ignores = set(map(norm, e.get('ignoredTexts', [])))
        inline_raw = list(dict.fromkeys(map(norm, e.get('inlineMergeTexts', []))))
        np_standalone += len(ignores); np_inline += len(inline_raw)
        # scorer pool: stripped remainders of inline merges count as ordinary lines
        stripped = []
        for m in inline_raw:
            lab = next((L for L in labels if m.startswith(L) and len(m) > len(L)), None)
            stripped.append(m[len(lab):] if lab else m)
        pool = [norm(p) for p in e['parsed'] if norm(p) not in ignores and norm(p) not in inline_raw] + stripped
        pool_u = list(dict.fromkeys(pool))
        golds = [norm(g) for g in f['gold']]
        used = set()
        pairs = []
        for i, p in enumerate(pool_u):
            best, bj = 0.4, -1
            for j, g in enumerate(golds):
                if j in used: continue
                s = sim(p, g)
                if s > best: best, bj = s, j
            if bj >= 0:
                used.add(bj); pairs.append((golds[bj], p, bj))
            else:
                # unmatched parsed line: fragment of a missing gold (boundary) or garbage
                host = next(((g, j) for j, g in enumerate(golds) if j not in used and
                             (g in p or p in g) and min(len(g), len(p)) / max(len(g), len(p)) >= 0.4), None)
                cls = 'boundary/stitch (orphan fragment)' if host else 'extra garbage (no gold match)'
                if host: used.add(host[1])
                classes[cls] = classes.get(cls, 0) + 1
                examples.append((cls, pid, host[0] if host else '', p))
        for j, g in enumerate(golds):
            if j in used: continue
            classes['missing gold (never emitted)'] = classes.get('missing gold (never emitted)', 0) + 1
            examples.append(('missing gold (never emitted)', pid, g, ''))
        for g, p, j in pairs:
            cls, n = classify(g, p)
            if cls == 'exact': cls = 'exact match'
            classes[cls] = classes.get(cls, 0) + 1
            if cls != 'exact match':
                examples.append((cls, pid, g, p))

    total_sentences = sum(classes.values())
    lines = []
    A = lines.append
    A('# Error census — where the remaining sentence failures live')
    A('')
    A(f'Generated offline by `scripts/error_census.py` from `gold_run9` (126 gold photos,')
    A(f'{sum(len(f["gold"]) for f in fixtures.values())} gold sentences). Nameplate noise excluded')
    A(f'up front exactly as the headline scorer does: {np_standalone} standalone leaks +')
    A(f'{np_inline} inline merges. Every scored pool line and unmatched gold lands in ONE bucket.')
    A('')
    A('Method note: this census aligns greedily (best-similarity first, threshold 0.4), so its')
    A('exact count (72) can differ by ±1 from the official headline (73, scorer alignment) —')
    A('the DISTRIBUTION is the deliverable, not the exact-match count.')
    A('')
    A('| class | count | share |')
    A('|---|---|---|')
    for c, n in sorted(classes.items(), key=lambda kv: -kv[1]):
        A(f'| {c} | {n} | {n / total_sentences:.1%} |')
    A('')
    A('## Examples (up to 3 per class, `photoId | gold -> parsed`)')
    A('')
    bycls = {}
    for cls, pid, g, p in examples:
        bycls.setdefault(cls, []).append((pid, g, p))
    for cls, exs in sorted(bycls.items(), key=lambda kv: -len(kv[1])):
        A(f'### {cls} ({len(exs)})')
        for pid, g, p in exs[:3]:
            A(f'- #{pid}  `{g}`  ->  `{p}`')
        A('')
    out = Path(args.out) if args.out else None
    text = '\n'.join(lines)
    if out:
        out.write_text(text, encoding='utf-8')
        print(f'wrote {out}')
    print(text)

if __name__ == '__main__':
    main()
