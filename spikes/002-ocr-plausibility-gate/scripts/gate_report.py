#!/usr/bin/env python3
"""Spike 002 — word-level OCR correction gate + provider selection, evaluated
against the 46-photo user-corrected gold set (docs/benchmarks/gold-set-iteration-plan.md).

Two questions, one scorer:
  1. GATE: when mining-time text and Cloud Vision disagree on a SMALL word island,
     can "one variant is more plausible Japanese" (dictionary coverage + legal
     kanji bigrams) pick the right one? Report precision vs gold at thresholds.
  2. SELECT: same scorer at whole-document level to choose stored vs cloud per
     photo; does provider selection approach the 37/74 oracle?

Evidence source: app/src/main/assets/dictionary.db (JMdict/KANJIDIC, see ATTRIBUTION.md).
Read-only over everything. Gold = ground truth for right/wrong, never mutated.
"""
import json, sqlite3, sys, re
from difflib import SequenceMatcher
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
GOLD = REPO / "app/src/test/resources/gold_fixtures.json"
AB = REPO / "docs/benchmarks/data/provider_ab_1794555946992.json"
DICTDB = REPO / "app/src/main/assets/dictionary.db"

def is_cjk(ch):
    return ('\u3040'<=ch<='\u309f') or ('\u30a0'<=ch<='\u30ff') or ('\u4e00'<=ch<='\u9fff') or ('\u3400'<=ch<='\u4dbf') or ch=='々'

def cjk_only(s):
    return ''.join(ch for ch in s if is_cjk(ch))

# ---------- evidence model ----------
HEADWORDS = set()
BIGRAMS = set()
def load_dict():
    db = sqlite3.connect(f"file:{DICTDB}?mode=ro", uri=True)
    for (hw,) in db.execute("SELECT headword FROM words"):
        run = ''
        chars = [c for c in hw if is_cjk(c) or ('\u3040'<=c<='\u30ff')]
        # index membership on CJK-only projection (readings/okurigana stripped)
        proj = ''.join(c for c in hw if is_cjk(c))
        if 1 < len(proj) <= 8:
            HEADWORDS.add(proj)
        for a, b in zip(hw, hw[1:]):
            if is_cjk(a) and is_cjk(b):
                BIGRAMS.add(a + b)

def longest_match_coverage(run: str) -> float:
    """Fraction of the kanji run covered by greedy longest headword matches (len>=2)."""
    n = len(run)
    if n == 0: return 1.0
    covered = [False]*n
    i = 0
    while i < n:
        matched = 0
        for L in range(min(8, n - i), 1, -1):
            if run[i:i+L] in HEADWORDS:
                for k in range(i, i+L): covered[k] = True
                matched = L
                break
        i += max(matched, 1)
    # a lone char counts as covered if it forms a legal bigram with a neighbor
    for k in range(n):
        if not covered[k]:
            if (k+1 < n and run[k:k+2] in BIGRAMS) or (k-1 >= 0 and run[k-1:k+1] in BIGRAMS):
                covered[k] = True
    return sum(covered)/n

def bigram_violations(run: str) -> int:
    return sum(1 for a,b in zip(run, run[1:]) if is_cjk(a) and is_cjk(b) and (a+b) not in BIGRAMS)

def score(text: str) -> tuple:
    """(coverage, violations) over the kanji runs of a fragment. Higher coverage and
    fewer violations = more plausible Japanese."""
    proj = cjk_only(text)
    kanji_runs = re.findall(r'[\u4e00-\u9fff々]{2,}', proj)
    if not kanji_runs:
        return 1.0, 0
    cov = sum(longest_match_coverage(r) for r in kanji_runs)/len(kanji_runs)
    viol = sum(bigram_violations(r) for r in kanji_runs)
    return cov, viol

# ---------- island extraction ----------
def islands(a: str, b: str, max_len=4):
    """Small replace-islands between two similar strings, with positions."""
    out = []
    sm = SequenceMatcher(None, a, b, autojunk=False)
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == 'replace' and (i2-i1) <= max_len and (j2-j1) <= max_len:
            A, B = a[i1:i2], b[j1:j2]
            if A.strip() and B.strip():
                out.append((A, B, i1, i2, j1, j2))
    return out

def window_margin(Aproj: str, Bproj: str, i1, i2, j1, j2, pad=4):
    """Plausibility margin scored on a context window around the island, not the
    naked 1-char difference (a lone kanji has no bigrams of its own)."""
    wA = Aproj[max(0, i1-pad): i2+pad]
    wB = Bproj[max(0, j1-pad): j2+pad]
    covA, violA = score(wA); covB, violB = score(wB)
    return (covB - covA) + 0.15 * (violA - violB), (covA, violA), (covB, violB)

def main():
    load_dict()
    gold = {f['photoId']: f for f in json.load(open(GOLD))['fixtures']}
    ab = {p['photoId']: p['providers'] for p in json.load(open(AB))['photos']}

    gate_rows = []
    for pid, f in sorted(gold.items()):
        pr = ab.get(pid)
        if not pr or 'cloud' not in pr: continue
        st_lines = [(l['text'], cjk_only(l['text'])) for b in f['ocrDocument'].get('blocks',[]) for l in b.get('lines',[])]
        st_lines = [(t,p) for t,p in st_lines if p]
        cl_lines = [(l['text'], cjk_only(l['text'])) for b in pr['cloud'].get('document',{}).get('blocks',[]) for l in b.get('lines',[])]
        cl_lines = [(t,p) for t,p in cl_lines if p]
        gold_norm = [cjk_only(g) for g in f['gold']]
        for s, sn in st_lines:
            # best-matching cloud line
            best, bproj, bestsim = None, None, 0.0
            for c, cn in cl_lines:
                sim = SequenceMatcher(None, sn, cn).ratio()
                if sim > bestsim: best, bproj, bestsim = cn, c, sim
            if best is None or bestsim < 0.55 or bestsim >= 0.999: continue
            for A, B, i1, i2, j1, j2 in islands(sn, best):
                if len(A) > 4 or len(B) > 4: continue
                in_gold_A = any(A in g for g in gold_norm)
                in_gold_B = any(B in g for g in gold_norm)
                if in_gold_A and in_gold_B: verdict = 'both'
                elif in_gold_A: verdict = 'A_ok'
                elif in_gold_B: verdict = 'B_ok'
                else: verdict = 'neither'
                margin, (covA, violA), (covB, violB) = window_margin(sn, best, i1, i2, j1, j2)
                def kind(x, y):
                    import unicodedata
                    SMALL = {'ぁ':'あ','ぃ':'い','ぅ':'う','ぇ':'え','ぉ':'お','っ':'つ',
                             'ゃ':'や','ゅ':'ゆ','ょ':'よ','ゎ':'わ','ァ':'ア','ィ':'イ','ゥ':'ウ',
                             'ェ':'エ','ォ':'オ','ッ':'ツ','ャ':'ヤ','ュ':'ユ','ョ':'ヨ','ヮ':'ワ'}
                    def fold(s):  # katakana->hiragana, small->full, strip dakuten combos
                        out = []
                        s = unicodedata.normalize('NFC', unicodedata.normalize('NFD', s))
                        for ch in unicodedata.normalize('NFD', s):
                            if unicodedata.combining(ch): continue
                            ch = SMALL.get(ch, ch)
                            if '\u30a1' <= ch <= '\u30f6': ch = chr(ord(ch) - 0x60)
                            out.append(ch)
                        return ''.join(out)
                    if fold(x) == fold(y):
                        return 'kana-styling'   # ヘ/へ, リ/り, ぇ/え — rendering, not content
                    if all(is_cjk(c) for c in x) and all(is_cjk(c) for c in y):
                        return 'kanji-substitution'
                    return 'mixed'
                gate_rows.append(dict(pid=pid, A=A, B=B, verdict=verdict, kind=kind(A,B),
                                      covA=round(covA,3), covB=round(covB,3),
                                      violA=violA, violB=violB, margin=round(margin,4)))
    # ---- gate sweep ----
    total_B_ok = sum(1 for r in gate_rows if r['verdict']=='B_ok')
    print(f"word-level disagreement islands: {len(gate_rows)} | true-fix candidates (B in gold): {total_B_ok}")
    from collections import Counter
    print("by kind:", dict(Counter((r['kind'], r['verdict']) for r in gate_rows)))
    gateable = [r for r in gate_rows if r['kind'] == 'kanji-substitution']
    gb_B = sum(1 for r in gateable if r['verdict']=='B_ok')
    print(f"\nGATEABLE (kanji substitutions only, kana-styling excluded): {len(gateable)} islands, {gb_B} B_ok")
    print(f"{'margin>=':>9} {'gated':>6} {'B_ok':>5} {'A_ok':>5} {'other':>6} {'precision':>9} {'coverage':>9}")
    for t in (0.0, 0.10, 0.30, 0.50, 0.70):
        gated = [r for r in gateable if r['margin'] >= t]
        bo = sum(1 for r in gated if r['verdict']=='B_ok')
        ao = sum(1 for r in gated if r['verdict']=='A_ok')
        ot = sum(1 for r in gated if r['verdict'] in ('both','neither'))
        print(f"{t:>9.2f} {len(gated):>6} {bo:>5} {ao:>5} {ot:>6} {bo/max(len(gated),1):>9.0%} {bo/max(gb_B,1):>9.0%}")
    print("\nremaining A_ok among kanji gate (gold-as-typed keeps these — check if gold is right):")
    for r in [x for x in gateable if x['verdict']=='A_ok' and x['margin']>=0.0]:
        print(f"  {r['pid']}: {r['A']} vs {r['B']} margin={r['margin']:.2f}")
    # ---- document-level provider selection (score line plausibility of each full doc) ----
    def doc_score(doc):
        cov_s = viol_s = 0.0; n = 0
        for b in doc.get('blocks', []):
            for l in b.get('lines', []):
                t = l.get('text', '')
                if not cjk_only(t): continue
                c, v = score(t); cov_s += c; viol_s += v; n += 1
        return (cov_s / n - 0.15 * viol_s / n) if n else 0.0
    sel_exact = oracle_exact = st_exact = cl_exact = 0; picked_cloud = 0; golds = 0
    for pid, f in sorted(gold.items()):
        pr = ab.get(pid)
        if not pr or 'cloud' not in pr: continue
        ss = doc_score(f['ocrDocument']); cs = doc_score(pr['cloud'].get('document', {}))
        chosen = 'cloud' if cs > ss else 'stored'
        if chosen == 'cloud': picked_cloud += 1
        sel_exact += pr[chosen]['exact']
        st_exact += pr['stored']['exact']; cl_exact += pr['cloud']['exact']
        oracle_exact += max(pr['stored']['exact'], pr['cloud']['exact'])
        golds += pr['stored']['gold']
    print(f"\nprovider selection (doc-level plausibility score): picked cloud on {picked_cloud}/46")
    print(f"  sentences exact: stored {st_exact}/{golds} | cloud {cl_exact}/{golds} | SELECTED {sel_exact}/{golds} | oracle {oracle_exact}/{golds}")
    # examples per bucket
    print("\nsample B_ok (would auto-fix correctly):")
    for r in [x for x in gate_rows if x['verdict']=='B_ok'][:8]:
        print(f"  {r['pid']}: {r['A']} -> {r['B']}  margin={r['margin']:.2f}")
    print("sample A_ok (gating this would BREAK a correct line):")
    for r in [x for x in gate_rows if x['verdict']=='A_ok'][:8]:
        print(f"  {r['pid']}: {r['A']} vs {r['B']}  margin={r['margin']:.2f}")
    print("sample neither (layout/segmentation noise, not a substitution):")
    for r in [x for x in gate_rows if x['verdict']=='neither'][:8]:
        print(f"  {r['pid']}: {r['A']} -> {r['B']}")
    json.dump(gate_rows, open(REPO/'spikes/002-ocr-plausibility-gate/gate_rows.json','w'), ensure_ascii=False, indent=1)

if __name__ == '__main__':
    sys.exit(main())
