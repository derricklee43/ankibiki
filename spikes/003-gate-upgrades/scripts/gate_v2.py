#!/usr/bin/env python3
"""Spike 003 — gate upgrades + calibration. Spike 002's plausibility margin
(coverage + bigram violations) measured 88% raw / 96% at margin>=0.10 on the
cleaned gold, but its ordering cannot separate the margin-0 tail, and coverage
per-run under-uses the strongest signal: FULL-WORD dictionary membership of the
substituted context (陛下 is a word, 陸下 is not).

New evidence classes evaluated per island (stored A -> cloud B):
  anchored_B : >=1 JMdict headword occurs in the B-window but no headword that
               covers the island occurs in the A-window.
  anchored_A : mirror of the above (evidence the OLD text is a real word).
  confusable : single/short kanji island where A and B share a radical from the
               kanji_radicals table (plausible glyph confusion; 海幹->迎撃 is NOT
               confusable and should classify as "re-read", not "auto-fix").
  margin     : spike-002 coverage/bigram window margin (baseline signal).

Truth labels come from the CLEANED gold (post round-3 adjudications):
  fix      : B appears in a gold line and A does not appear anywhere in gold.
  harmful  : B appears nowhere in gold (either A was right, or neither variant
             is gold text = layout noise; writing B would corrupt the sentence).
Gates scored on precision (=fix/(fix+harmful) among accepted) and coverage
(=accepted fixes / all true fixes).
"""
import json, re, sqlite3, sys, unicodedata
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

HEADWORDS = set()
HEADWORDS_MULTI = []   # for containment search in windows
BIGRAMS = set()
RADICALS = {}
def load_dict():
    db = sqlite3.connect(f"file:{DICTDB}?mode=ro", uri=True)
    for (hw,) in db.execute("SELECT headword FROM words"):
        proj = ''.join(c for c in hw if is_cjk(c) and not ('\u3040'<=c<='\u30ff'))
        full = ''.join(c for c in hw if is_cjk(c))
        if 2 <= len(full) <= 8:
            HEADWORDS.add(full)
        for a, b in zip(hw, hw[1:]):
            if is_cjk(a) and is_cjk(b):
                BIGRAMS.add(a + b)
    for ch, rad in db.execute("SELECT character, radical FROM kanji_radicals"):
        RADICALS.setdefault(ch, set()).add(rad)

def longest_match_coverage(run):
    n=len(run)
    if n==0: return 1.0
    cov=[False]*n; i=0
    while i<n:
        m=0
        for L in range(min(8,n-i),1,-1):
            if run[i:i+L] in HEADWORDS:
                for k in range(i,i+L): cov[k]=True
                m=L; break
        i+=max(m,1)
    for k in range(n):
        if not cov[k] and ((k+1<n and run[k:k+2] in BIGRAMS) or (k-1>=0 and run[k-1:k+1] in BIGRAMS)):
            cov[k]=True
    return sum(cov)/n

def violations(run):
    return sum(1 for a,b in zip(run,run[1:]) if is_cjk(a) and is_cjk(b) and (a+b) not in BIGRAMS)

def score(text):
    proj=cjk_only(text)
    kr=re.findall(r'[\u4e00-\u9fff々]{2,}',proj)
    if not kr: return 1.0,0
    return sum(longest_match_coverage(r) for r in kr)/len(kr), sum(violations(r) for r in kr)

SMALL={'ぁ':'あ','ぃ':'い','ぅ':'う','ぇ':'え','ぉ':'お','っ':'つ','ゃ':'や','ゅ':'ゆ','ょ':'よ','ゎ':'わ',
       'ァ':'ア','ィ':'イ','ゥ':'ウ','ェ':'エ','ォ':'オ','ッ':'ツ','ャ':'ヤ','ュ':'ユ','ョ':'ヨ','ヮ':'ワ'}
def fold(s):
    out=[]
    for ch in unicodedata.normalize('NFD',s):
        if unicodedata.combining(ch): continue
        ch=SMALL.get(ch,ch)
        if '\u30a1'<=ch<='\u30f6': ch=chr(ord(ch)-0x60)
        out.append(ch)
    return ''.join(out)

def island_kind(x,y):
    if fold(x)==fold(y): return 'kana-styling'
    if all(is_cjk(c) for c in x) and all(is_cjk(c) for c in y): return 'kanji'
    return 'mixed'

def anchored(window_with_variant, variant):
    """True if some dictionary headword occurs in the window AND covers the variant.
    Window is short (~12 chars): enumerate its substrings against the headword set."""
    L=len(window_with_variant)
    for i in range(L):
        for j in range(i+2, min(i+9, L)+1):
            sub=window_with_variant[i:j]
            if sub in HEADWORDS and variant in sub:
                return True
    return False

def main():
    load_dict()
    gold={f['photoId']:f for f in json.load(open(GOLD))['fixtures']}
    ab={p['photoId']:p['providers'] for p in json.load(open(AB))['photos']}
    rows=[]
    for pid,f in sorted(gold.items()):
        pr=ab.get(pid)
        if not pr or 'cloud' not in pr: continue
        st=[cjk_only(l['text']) for b in f['ocrDocument'].get('blocks',[]) for l in b.get('lines',[])]
        st=[s for s in st if s]
        cl=[cjk_only(l['text']) for b in pr['cloud'].get('document',{}).get('blocks',[]) for l in b.get('lines',[])]
        cl=[c for c in cl if c]
        gl=[cjk_only(g) for g in f['gold']]
        for sn in st:
            best,bestsim=None,0.0
            for cn in cl:
                sim=SequenceMatcher(None,sn,cn).ratio()
                if sim>bestsim: best,bestsim=cn,sim
            if best is None or bestsim<0.55 or bestsim>=0.999: continue
            sm=SequenceMatcher(None,sn,best,autojunk=False)
            for tag,i1,i2,j1,j2 in sm.get_opcodes():
                if tag!='replace' or (i2-i1)>4 or (j2-j1)>4: continue
                A,B=sn[i1:i2],best[j1:j2]
                if not A.strip() or not B.strip() or A==B: continue
                kind=island_kind(A,B)
                inA=any(A in g for g in gl); inB=any(B in g for g in gl)
                truth='fix' if (inB and not inA) else ('harmful' if not inB else 'both')
                wA=sn[max(0,i1-4):i2+4]; wB=best[max(0,j1-4):j2+4]
                covA,vA=score(wA); covB,vB=score(wB)
                margin=(covB-covA)+0.15*(vA-vB)
                anchB=anchored(wB,B); anchA=anchored(wA,A)
                conf=False
                if len(A)==len(B)==1 and is_cjk(A) and is_cjk(B):
                    conf=bool(RADICALS.get(A,set())&RADICALS.get(B,set()))
                rows.append(dict(pid=pid,A=A,B=B,kind=kind,truth=truth,
                                 margin=round(margin,4),anchB=anchB,anchA=anchA,conf=conf))
    kanji=[r for r in rows if r['kind']=='kanji']
    # nameplate-adjacency: island variant appears inside a DRAFTED/CONFIRMED
    # nameplate string (legit/adjudicated-content strings deliberately NOT
    # included - they over-exclude real fixes, e.g. 華 in 攻華範囲表示 on 983)
    np_strings=set()
    for pid,f in gold.items():
        for s in f.get('nameplateCandidates',[])+f.get('nameplateLabels',[]):
            np_strings.add(cjk_only(s))
    def np_adjacent(r):
        return any(r['A'] in w or r['B'] in w for w in np_strings if len(w)>=2)
    fixes=[r for r in kanji if r['truth']=='fix']
    print(f"kanji islands: {len(kanji)} | true fixes: {len(fixes)} | harmful: {sum(1 for r in kanji if r['truth']=='harmful')} | both: {sum(1 for r in kanji if r['truth']=='both')}")
    def gate_eval(name, pred):
        acc=[r for r in kanji if pred(r)]
        fx=sum(1 for r in acc if r['truth']=='fix')
        hm=sum(1 for r in acc if r['truth']=='harmful')
        pr=fx/max(len(acc),1); cov=fx/max(len(fixes),1)
        print(f"  {name:52s} accepted {len(acc):3d}  fix {fx:3d}  harmful {hm}  precision {pr:5.0%}  coverage {cov:5.0%}")
        return pr,cov,name,fx,hm
    print("gates:")
    results=[
      gate_eval("S002 baseline: margin>=0.10", lambda r: r['margin']>=0.10),
      gate_eval("S002 baseline: margin>=0.0", lambda r: r['margin']>=0.0),
      gate_eval("anchoredB & !anchoredA", lambda r: r['anchB'] and not r['anchA']),
      gate_eval("[ORACLE, not a gate] anchB&!anchA excl both", lambda r: r['anchB'] and not r['anchA'] and r['truth']!='both'),
      gate_eval("margin>=0.10 OR anchoredB&!anchoredA", lambda r: r['margin']>=0.10 or (r['anchB'] and not r['anchA'])),
      gate_eval("margin>=0.10 AND confusable", lambda r: r['margin']>=0.10 and r['conf']),
      gate_eval("(margin>=0.10 OR anchB&!anchA) AND confusable", lambda r: (r['margin']>=0.10 or (r['anchB'] and not r['anchA'])) and r['conf']),
      gate_eval("margin>=0.05 OR anchB&!anchA", lambda r: r['margin']>=0.05 or (r['anchB'] and not r['anchA'])),
      gate_eval("[S003-A'] anchB&!anchA, nameplate-islands excluded", lambda r: r['anchB'] and not r['anchA'] and not np_adjacent(r)),
      gate_eval("[S003-D'] (anchB&!anchA OR margin>=0&conf), np excluded", lambda r: ((r['anchB'] and not r['anchA']) or (r['margin']>=0.0 and r['conf'])) and not np_adjacent(r)),
      gate_eval("[S003-E'] S003-D' + margin>=0.10 band", lambda r: ((r['anchB'] and not r['anchA']) or (r['margin']>=0.0 and r['conf']) or r['margin']>=0.10) and not np_adjacent(r)),
    ]
    print("\nharmful accepted under best union gate (inspect):")
    for r in kanji:
        if r['truth']=='harmful' and (r['margin']>=0.10 or (r['anchB'] and not r['anchA'])):
            print(f"  {r['pid']}: {r['A']}->{r['B']} margin={r['margin']} anchB={r['anchB']} conf={r['conf']}")
    json.dump(rows, open(REPO/'spikes/003-gate-upgrades/gate_rows_v2.json','w'), ensure_ascii=False, indent=1)
    # calibration: margin histogram by truth
    import collections
    hb=collections.Counter()
    for r in kanji:
        hb[(r['truth'], round(max(min(r['margin'],1.0),-1.0),1))]+=1
    print("\nmargin distribution (truth x rounded margin):")
    for t in ('fix','harmful','both'):
        print(f"  {t:8s}", {m:c for (tt,m),c in sorted(hb.items()) if tt==t})

if __name__=='__main__':
    sys.exit(main())
