#!/usr/bin/env python3
"""Spike 004 — nameplate exclusion, executed. Follows README design verbatim:
Phase 0 truth classes over current-parser output on the 126-photo gold set;
solo H1-H9 diagnostics; pre-registered variants A0-A5 (standalone flags) and
the gold-free H9 inline-excision rule scored on the known merges;
zero-casualty audit (G4). Offline: fixtures + run JSON + A/B artifact + JMdict.
"""
import json, sqlite3
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
GOLD_F = REPO / "app/src/test/resources/gold_fixtures.json"
AB_F = REPO / "docs/benchmarks/data/provider_ab_1794555946992.json"
RUN_F = Path("/home/derri/.hermes/cache/scratch/gold_run9.json")
DICTDB = REPO / "app/src/main/assets/dictionary.db"
OUT = REPO / "spikes/004-nameplate-exclusion/results.json"

TERM = "。．！？!?.…"
PARTICLES = set("はがをにでへとやばなもかよねぞ")
ACKS = ("はい", "いいえ", "なあに", "うん", "ええ", "フム", "むむ")

def is_cjk(ch):
    return ('\u3040' <= ch <= '\u309f') or ('\u30a0' <= ch <= '\u30ff') or \
           ('\u4e00' <= ch <= '\u9fff') or ('\u3400' <= ch <= '\u4dbf') or ch == '々'
def is_hira(ch): return '\u3040' <= ch <= '\u309f'
def is_kata(ch): return '\u30a0' <= ch <= '\u30ff' or ch in 'ーヽヶ'
def is_kanji(ch): return '\u4e00' <= ch <= '\u9fff' or '\u3400' <= ch <= '\u4dbf'
def cjk_only(s): return ''.join(ch for ch in s if is_cjk(ch))
def norm(s): return ''.join(s.split())
def has_term(s): return any(ch in TERM for ch in s)

def lev(a, b):
    if abs(len(a) - len(b)) > 6: return 99
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i] + [0] * len(b)
        for j, cb in enumerate(b, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb))
        prev = cur
    return prev[-1]

# ---------- dictionary ----------
HEADWORDS, OKURI = set(), set()
db = sqlite3.connect(f"file:{DICTDB}?mode=ro", uri=True)
for (hw,) in db.execute("SELECT headword FROM words"):
    full = cjk_only(hw)
    if 2 <= len(full) <= 8: HEADWORDS.add(full)
    for a, b in zip(hw, hw[1:]):
        if is_kanji(a) and is_hira(b):
            OKURI.add((a, b))
for hw in list(HEADWORDS)[:0]: pass
def in_dict(s): return s in HEADWORDS
def anchored(s):
    for L in range(min(8, len(s)), 1, -1):
        for i in range(len(s) - L + 1):
            if s[i:i + L] in HEADWORDS: return True
    return False
def oov(s): return not in_dict(s) and not anchored(s)

# ---------- data ----------
fixtures = {f['photoId']: f for f in json.load(open(GOLD_F))['fixtures']}
run = {e['photoId']: e for e in json.load(open(RUN_F)) if e.get('metric') == 'BENCHMARK_GOLD_FIXTURE'}
AB = {p['photoId']: p['providers'] for p in json.load(open(AB_F))['photos']}

GLOBAL_LABELS = {cjk_only(L) for f in fixtures.values() for L in f.get('nameplateLabels', [])}
GLOBAL_LABELS.discard('')
GLOBAL_LABELS = {L for L in GLOBAL_LABELS if len(L) >= 2}

# ---------- Phase 0 truth ----------
def matches_gold(p, gold_ns): return any(lev(norm(p), g) <= 1 for g in gold_ns)

PHOTO = {}
for pid, e in run.items():
    f = fixtures[pid]
    gold_ns = [norm(g) for g in f['gold']]
    legit_proj = {cjk_only(x) for x in f.get('legitShortLines', [])} | \
                 {cjk_only(x) for x in f.get('ambiguousShortLines', []) if x in f.get('legitShortLines', [])}
    short_gold = {g for g in gold_ns if len(g) <= 8 and not has_term(g)}
    parsed = list(dict.fromkeys(e['parsed']))
    truth = {}
    for p in parsed:
        proj = cjk_only(p)
        if any(lev(proj, L) <= 2 and len(proj) <= len(L) + 2 for L in GLOBAL_LABELS) and not matches_gold(p, gold_ns):
            truth[p] = 'NAMEPLATE'
        elif matches_gold(p, gold_ns) or (proj and proj in legit_proj) or \
             any(lev(proj, g) <= 2 for g in short_gold):
            truth[p] = 'LEGIT'
        else:
            truth[p] = 'NOISE'
    inline = set(e.get('inlineMergeTexts', []))  # scorer's own reference implementation (27)
    PHOTO[pid] = dict(lines=parsed, truth=truth, inline=inline, gold_ns=gold_ns,
                      labels={cjk_only(L) for L in f.get('nameplateLabels', [])}, doc=f['ocrDocument'])

np_total = sum(1 for r in PHOTO.values() for p in r['lines'] if r['truth'][p] == 'NAMEPLATE')
inline_total = sum(len(r['inline']) for r in PHOTO.values())
legit_total = sum(1 for r in PHOTO.values() for p in r['lines'] if r['truth'][p] == 'LEGIT')
noise_total = sum(1 for r in PHOTO.values() for p in r['lines'] if r['truth'][p] == 'NOISE')
print(f"PHASE 0: standalone NAMEPLATE {np_total} | inline merges {inline_total} | LEGIT {legit_total} | NOISE {noise_total}")

# ---------- detectors ----------
STRIP = set("\t、・。「」『』（）()【】〈〉—–_*:/\\~^")
def draftset(doc):  # verbatim kotlin A0 proxy port
    out = set()
    for b in doc.get('blocks', []):
        for ln in b.get('lines', []):
            t = norm(ln['text'])
            if not t: continue
            core = t.rstrip(TERM)
            if not core or has_term(core): continue
            if len(''.join(c for c in core if c not in STRIP)) > 8: continue
            if not any(is_kata(c) for c in core) or any(is_hira(c) for c in core): continue
            proj = cjk_only(core)
            if len(proj) >= 2: out.add(proj)
    return out

def h2(p):
    proj = cjk_only(p)
    return 0 < len(proj) <= 8 and not has_term(p)

def med_dialog_h(doc, gold_ns):
    hs = sorted(ln['height'] for b in doc.get('blocks', []) for ln in b.get('lines', [])
                if any(lev(cjk_only(ln['text']), g) <= 2 for g in gold_ns))
    return hs[len(hs) // 2] if hs else 128

def h1(p, pid, use_cloud):
    if not h2(p): return False
    proj = cjk_only(p)
    rec = PHOTO[pid]
    doc = (AB.get(pid, {}).get('cloud') or {}).get('document') if use_cloud else None
    if not doc: doc = rec['doc']
    med = med_dialog_h(doc, rec['gold_ns'])
    for b in doc.get('blocks', []):
        for ln in b.get('lines', []):
            if lev(cjk_only(ln['text']), proj) <= 1 and len(b.get('lines', [])) == 1 \
               and ln['height'] < med:
                return True
    return False

def h3_lexicon(K):
    cnt = {}
    for pid, rec in PHOTO.items():
        for p in rec['lines']:
            if not h2(p) or matches_gold(p, rec['gold_ns']): continue
            cnt.setdefault(cjk_only(p), set()).add(pid)
    return {s for s, ps in cnt.items() if len(ps) >= K and s}

def proj_cut_index(p, nchars):
    seen = 0
    for i, ch in enumerate(p):
        if is_cjk(ch):
            seen += 1
            if seen == nchars: return i + 1
    return len(p)

def h4_lexicon(K):  # head recurrence against gold-shaped remainder (offline proxy)
    cnt = {}
    for pid, rec in PHOTO.items():
        for p in rec['lines']:
            for T, rn in cut_candidates(p):
                if any(lev(rn, g) <= 2 for g in rec['gold_ns']):
                    cnt.setdefault(T, set()).add(pid)
    return {s for s, ps in cnt.items() if len(ps) >= K}

def h5(p):
    proj = cjk_only(p)
    return h2(p) and all(is_kata(c) for c in proj)

def h6(p): return h2(p) and oov(cjk_only(p))

def h7(p, pid):
    if pid not in AB: return False
    proj = cjk_only(p)
    cloud_doc = (AB[pid].get('cloud') or {}).get('document') or {}
    cloud = [cjk_only(ln['text']) for b in cloud_doc.get('blocks', []) for ln in b.get('lines', [])]
    return h2(p) and not any((proj in c or c in proj) for c in cloud if len(c) >= 2)

# ---------- inline H9 ----------
KOTO = "、・，,"
SUFFIX_HEADS = ("の", "たち", "ども", "ちゃ", "さん", "くん", "様", "王国", "帝国")
PRONOUNS = ("私", "僕", "ぼく", "君", "きみ", "あな", "貴", "俺", "おら", "わし")
def cut_candidates(p):
    proj = cjk_only(p)
    for L in range(2, 7):  # DESIGN DEVIATION (run): gate said len<=4 but its own examples
        if len(proj) <= L: continue   # (モロドフ 5ch) are longer; <=6 honors the examples
        T = proj[:L]
        if any(is_hira(c) for c in T): continue
        if all(is_kanji(c) for c in T) and len(T) < 3: continue  # 2-char kanji prefixes are word stems, not plates
        rem_raw = p[proj_cut_index(p, L):]
        rn = norm(rem_raw)
        if not rn: continue
        head = rn.lstrip('」』）)')  # closing quote right after the token = plate-bubble boundary
        if not head: continue
        if head[0] in KOTO: continue                      # comma-attached = vocative
        if head.startswith(SUFFIX_HEADS): continue        # bound suffix/compound: part of T's phrase
        bare = head.strip(TERM + "」』）)』】 ")
        if is_kanji(T[-1]) and len(bare) <= 3 and all(is_cjk(c) or c in TERM for c in head) and not any(is_hira(c) for c in head):
            continue  # bare title phrase remainder (マルス王子。) = content
        if any(is_hira(c) for c in head[:1]) and head[0] in PARTICLES: continue  # attachable particle
        if is_kata(T[-1]) and is_kata(head[0]): continue  # katakana run continues: mid-name cut
        if is_kanji(head[0]) and head[0] not in "私僕君貴俺わ" and len(head) > 1 and is_hira(head[1]) and head[1] in "はがのもにで":
            continue  # single kanji + particle immediately = bound reading (王は…) — pronouns exempt
        if is_kanji(T[-1]) and is_hira(head[0]) and (T[-1], head[0]) in OKURI: continue
        yield T, rn

def tier(T, rn):
    if any(rn.startswith(a) for a in ACKS): return 'b'
    run = 0
    for ch in rn:
        if (is_kanji(ch) or is_kata(ch)): run += 1
        else: break
    if run >= 1 and len(rn) > run and rn[run] in 'はがも': return 'a'
    return 'c'

def inline_hits(p, lexicon=None, ab_only=False):
    out = []
    for T, rn in cut_candidates(p):
        t = tier(T, rn)
        if ab_only:
            fire = t in 'ab'
        elif t in 'ab':
            fire = True
        else:  # c: exact-token dictionary absence OR lexicon license (A2/A3), len<=6 per deviation note
            fire = len(T) <= 6 and (not in_dict(T) or (lexicon is not None and T in lexicon))
        if lexicon is not None and fire and t in 'ab' and T not in lexicon: fire = False  # lexicon-mode filter
        if fire: out.append((T, rn, t))
    return out


LEX_H3_2, LEX_H3_3 = h3_lexicon(2), h3_lexicon(3)
LEX_H4_2, LEX_H4_3 = h4_lexicon(2), h4_lexicon(3)
A1_STORED = {(pid, p) for pid in PHOTO for p in PHOTO[pid]['lines'] if h1(p, pid, False)}
A1_CLOUD = {(pid, p) for pid in PHOTO if pid in AB for p in PHOTO[pid]['lines'] if h1(p, pid, True)}
A1_SEED = {cjk_only(p) for _, p in A1_STORED}
A3_LEX_2 = A1_SEED | LEX_H3_2 | LEX_H4_2
A3_LEX_3 = A1_SEED | LEX_H3_3 | LEX_H4_3

# ---------- scoring ----------
def score(name, pairs):
    tp = cas = noisef = 0
    casualties = []
    for (pid, p) in pairs:
        t = PHOTO[pid]['truth'][p]
        if t == 'NAMEPLATE': tp += 1
        elif t == 'LEGIT': cas += 1; casualties.append(f"{pid}:{p}")
        else: noisef += 1
    return dict(name=name, flagged=len(pairs), hits=tp,
                recall=round(tp / np_total, 3) if np_total else 0,
                casualties=cas, noise=noisef,
                precision=round(tp / len(pairs), 3) if pairs else 0,
                casualty_examples=casualties[:12])

def solo_sets():
    return {
        'H1_geom_stored': A1_STORED,
        'H1_geom_cloud44': A1_CLOUD,
        'H2_shape': {(pid, p) for pid in PHOTO for p in PHOTO[pid]['lines'] if h2(p)},
        'H3_recur_K2': {(pid, p) for pid in PHOTO for p in PHOTO[pid]['lines'] if cjk_only(p) in LEX_H3_2},
        'H4_headrecur_K2': {(pid, p) for pid in PHOTO for p in PHOTO[pid]['lines']
                            if any(cjk_only(p)[:L] in LEX_H4_2 for L in (2, 3, 4)) and not matches_gold(p, PHOTO[pid]['gold_ns'])},
        'H5_katakana': {(pid, p) for pid in PHOTO for p in PHOTO[pid]['lines'] if h5(p)},
        'H6_oov': {(pid, p) for pid in PHOTO for p in PHOTO[pid]['lines'] if h6(p)},
        'H7_disagree44': {(pid, p) for pid in PHOTO for p in PHOTO[pid]['lines'] if h7(p, pid)},
    }

def variant_sets():
    v = {}
    v['A0_proxy'] = {(pid, p) for pid in PHOTO for p in PHOTO[pid]['lines'] if cjk_only(p) in draftset(PHOTO[pid]['doc'])}
    v['A1_geom'] = A1_STORED
    v['A2_lex_K2'] = {(pid, p) for pid in PHOTO for p in PHOTO[pid]['lines'] if cjk_only(p) in LEX_H3_2}
    v['A2_lex_K3'] = {(pid, p) for pid in PHOTO for p in PHOTO[pid]['lines'] if cjk_only(p) in LEX_H3_3}
    v['A3_boot_K2'] = {(pid, p) for pid in PHOTO for p in PHOTO[pid]['lines'] if h2(p) and cjk_only(p) in A3_LEX_2}
    v['A3_boot_K3'] = {(pid, p) for pid in PHOTO for p in PHOTO[pid]['lines'] if h2(p) and cjk_only(p) in A3_LEX_3}
    return v

def score_inline(name, lexicon=None, ab_only=False):
    tp = 0; fp_cas = []; fp_noise = []; missed = []
    for pid, rec in PHOTO.items():
        for p in rec['lines']:
            hits = inline_hits(p, lexicon, ab_only)
            if not hits: continue
            if p in rec['inline']: tp += 1
            elif rec['truth'][p] == 'LEGIT': fp_cas.append(f"{pid}:{p}:{hits[0][0]}")
            else: fp_noise.append(f"{pid}:{p}:{hits[0][0]}")
    for pid, rec in PHOTO.items():
        for p in rec['inline']:
            if not inline_hits(p, lexicon, ab_only): missed.append(f"{pid}:{p}")
    total = tp + len(fp_cas) + len(fp_noise)
    return dict(name=name, recall=f"{tp}/{inline_total}", precision=round(tp / total, 3) if total else 0,
                casualties=len(fp_cas), noise_fp=len(fp_noise),
                casualty_examples=fp_cas[:12], noise_examples=fp_noise[:14], missed=missed[:14])

# ---------- A5 scoping ----------
def clusters():
    ids = sorted(PHOTO.keys()); out = []; cur = [ids[0]]
    for x in ids[1:]:
        if x - cur[-1] > 150: out.append(cur); cur = [x]
        else: cur.append(x)
    out.append(cur); return out
CL = clusters()
def cluster_of(pid):
    for i, c in enumerate(CL):
        if pid in c: return i
    return -1
LEX2 = LEX_H3_2 | LEX_H4_2
# A5 = A2 restricted to lexicon strings whose recurrence stays inside one photoId-cluster
cnt3 = {}
for pid, rec in PHOTO.items():
    for p in rec['lines']:
        if not h2(p) or matches_gold(p, rec['gold_ns']): continue
        cnt3.setdefault(cjk_only(p), set()).add(pid)
CLUSTERED = {s for s, ps in cnt3.items() if len({cluster_of(x) for x in ps}) <= 1 and len(ps) >= 2}
cnt4p = {}
for pid, rec in PHOTO.items():
    for p in rec['lines']:
        for T, rn in cut_candidates(p):
            if any(lev(rn, g) <= 2 for g in rec['gold_ns']):
                cnt4p.setdefault(T, set()).add(pid)
CLUSTERED_H4 = {s for s, ps in cnt4p.items() if len(ps) >= 2 and len({cluster_of(x) for x in ps}) <= 1}
A5_SET = {(pid, p) for pid in PHOTO for p in PHOTO[pid]['lines']
          if cjk_only(p) in CLUSTERED
          or (not matches_gold(p, PHOTO[pid]['gold_ns'])
              and any(cjk_only(p).startswith(T) for T in CLUSTERED_H4))}

# ---------- run ----------
solo_out = {k: score(k, v) for k, v in solo_sets().items()}
var_out = {k: score(k, v) for k, v in variant_sets().items()}
var_out['A5_scoped_K2'] = score('A5_scoped_K2', A5_SET)
inline_out = {
    'H9_gold_free': score_inline('H9_gold_free'),
    'H9a_b_only': score_inline('H9a_b_only', ab_only=True),
    'H9_and_A3lex': score_inline('H9_and_A3lex', lexicon=A3_LEX_2),
}
bleed = [(s, sorted({cluster_of(x) for x in ps}), len(ps))
         for s, ps in cnt3.items() if len(ps) >= 2 and len({cluster_of(x) for x in ps}) > 1]
json.dump(dict(phase0=dict(nameplate_standalone=np_total, inline=inline_total, legit=legit_total, noise=noise_total),
               solo=solo_out, variants=var_out, inline=inline_out,
               clusters=[len(c) for c in CL], cross_cluster=bleed[:20]),
          open(OUT, 'w'), ensure_ascii=False, indent=1)

def show(title, d):
    print(f"\n== {title} ==")
    for r in d.values():
        if 'recall' in r and isinstance(r['recall'], str):
            print(f"{r['name']:>16}: recall {r['recall']} | prec {r['precision']} | casualties {r['casualties']} | noiseFP {r['noise_fp']}")
            if r['casualty_examples']: print('   CAS:', r['casualty_examples'])
            if r['noise_examples']: print('   NOISE:', r['noise_examples'][:8])
            if r['missed']: print('   MISSED:', r['missed'])
        else:
            print(f"{r['name']:>16}: flagged {r['flagged']:>3} | recall {r['recall']:.2f} | casualties {r['casualties']} | noise {r['noise']} | prec {r['precision']:.2f}")
            if r['casualty_examples']: print('   CAS:', r['casualty_examples'])
show('SOLO', solo_out)
show('VARIANTS (standalone)', var_out)
show('INLINE (H9)', inline_out)
print("\ncross-cluster lexicon strings:", [b[0] for b in bleed][:12], '| clusters:', [len(c) for c in CL])
