#!/usr/bin/env python3
"""
Independent oracle for AI-FHS's golden file (issue 9.4; §14, §21.5).

Why:  the expected values must not come from the engine under test. This reads the rulebook itself
      (not the Kotlin mirror) and does every step in exact fractions, so a slip in the engine's
      arithmetic or its transcription of a row cannot be shared by its expectation.
What: scores each case below exactly as ADR-0045 fixes the method, and writes health.txt.
Changelog: 2026-09-19 — Created for issue 9.4.

Method (points are hundredths of the 0–100 pillar score, 0..10000; every rounding is half-even):
  runway      = min(10000, runwayBps / M); at >= 1 month, at least floor*100
  obligations = ratio r = obl*10000/income; 10000 at r <= full*100, 0 at r >= zero*100, linear between
  cards       = ratio r = max(used,0)*10000/limit; same shape with utilisation anchors
  savings     = rate r = sum(saved)*10000/sum(income); 0 at r <= 0, 10000 at r >= full*100, linear
  shares      = good*10000/total
  pillar      = round(mean of its signals' points)
  score       = round(sum W_p*P_p * max / (sum W * 10000)) over pillars with data
  contribution: floors of the exact shares, the remainder to the largest fractions (ties: pillar order)
  eff weight  : same apportionment of 10000 over the weights of pillars with data
  lever       = the signal with the largest exact W_p*(10000-pts)*max/(sumW*n_p*10000); ties: signal order
"""
import json, os
from fractions import Fraction as F

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = HERE
while not os.path.isfile(os.path.join(ROOT, "ai/rules/rules-kb.json")):
    ROOT = os.path.dirname(ROOT)
KB = {r["rule_id"]: r["params_json"] for r in json.load(open(os.path.join(ROOT, "ai/rules/rules-kb.json")))["rules"]}
P, B, S = KB["RULE-FHS-PILLARS"], KB["RULE-FHS-BANDS"], KB["RULE-FHS-SIGNALS"]
UTIL_FULL = KB["RULE-CC-UTIL"]["max_utilisation_pct"]
SAVE_FULL = KB["RULE-SAVE-RATE"]["excellent_pct"]
PILLARS = ["LIQUIDITY", "DEBT", "DISCIPLINE", "GOALS", "PROTECTION"]
W = {"LIQUIDITY": P["liquidity_weight_bps"], "DEBT": P["debt_weight_bps"], "DISCIPLINE": P["discipline_weight_bps"],
     "GOALS": P["goals_weight_bps"], "PROTECTION": P["protection_weight_bps"]}
SIGNALS = [("RUNWAY", "LIQUIDITY"), ("OBLIGATIONS", "DEBT"), ("CARD_UTILISATION", "DEBT"),
           ("SAVINGS_RATE", "DISCIPLINE"), ("BUDGET_ADHERENCE", "DISCIPLINE"), ("GOALS_ON_TRACK", "GOALS")]
MAX, MINM = P["score_max"], P["min_months_of_signal"]
r = round  # Fraction.__round__ is half-even

def curve_down(ratio, full, zero):
    if ratio <= full * 100: return 10000
    if ratio >= zero * 100: return 0
    return r(F(zero * 100 - ratio) * 10000 / ((zero - full) * 100))

def signals(case):
    out = {}
    if case.get("runway"):
        bps, m = case["runway"]
        pts = min(10000, r(F(bps, m)))
        if bps >= 10000: pts = max(pts, S["runway_floor_points"] * 100)
        out["RUNWAY"] = (pts, bps, m * 10000)
    if case.get("obligations"):
        obl, inc, months = case["obligations"]
        if inc > 0 and months >= MINM:
            ratio = r(F(obl * 10000, inc))
            out["OBLIGATIONS"] = (curve_down(ratio, S["obligation_full_pct"], S["obligation_zero_pct"]), ratio, S["obligation_full_pct"] * 100)
    if case.get("cards"):
        used, limit = case["cards"]
        if limit > 0:
            ratio = r(F(max(used, 0) * 10000, limit))
            out["CARD_UTILISATION"] = (curve_down(ratio, UTIL_FULL, S["utilisation_zero_pct"]), ratio, UTIL_FULL * 100)
    if case.get("savings") is not None:
        months = case["savings"]
        if sum(1 for (_, inc, _) in months if inc > 0) >= MINM:
            rate = r(F(sum(s for (_, _, s) in months) * 10000, sum(inc for (_, inc, _) in months)))
            pts = 0 if rate <= 0 else 10000 if rate >= SAVE_FULL * 100 else r(F(rate * 10000, SAVE_FULL * 100))
            out["SAVINGS_RATE"] = (pts, rate, SAVE_FULL * 100)
    for key, sig in (("budgets", "BUDGET_ADHERENCE"), ("goals", "GOALS_ON_TRACK")):
        if case.get(key):
            good, total = case[key]
            if total > 0:
                share = r(F(good * 10000, total))
                out[sig] = (share, share, 10000)
    return out

def apportion(exact, total):
    floors = [int(e) if e >= 0 else 0 for e in exact]
    extra = total - sum(floors)
    order = sorted(range(len(exact)), key=lambda i: (-(exact[i] - floors[i]), i))
    for i in order[:extra]: floors[i] += 1
    return floors

def score(case):
    sig = signals(case)
    pillar_pts = {}
    for p in PILLARS:
        pts = [sig[s][0] for (s, pp) in SIGNALS if pp == p and s in sig]
        if pts: pillar_pts[p] = r(F(sum(pts), len(pts)))
    avail = [p for p in PILLARS if p in pillar_pts]
    lines = []
    if not avail:
        lines.append("expect score=- band=- lever=-")
        for p in PILLARS: lines.append("pillar %s eff=0 points=- contribution=0 signals=-" % p)
        return lines
    sw = sum(W[p] for p in avail)
    exact = [F(W[p] * pillar_pts[p] * MAX, sw * 10000) if p in pillar_pts else F(0) for p in PILLARS]
    total = r(sum(exact))
    contrib = apportion(exact, total)
    eff = apportion([F(W[p] * 10000, sw) if p in pillar_pts else F(0) for p in PILLARS], 10000)
    band = ("EXCELLENT" if total >= B["excellent_min"] else "GOOD" if total >= B["good_min"] else
            "FAIR" if total >= B["fair_min"] else "NEEDS_ATTENTION" if total >= B["attention_min"] else "AT_RISK")
    best = None
    for (s, p) in SIGNALS:
        if s not in sig: continue
        n = sum(1 for (s2, p2) in SIGNALS if p2 == p and s2 in sig)
        g = F(W[p] * (10000 - sig[s][0]) * MAX, sw * n * 10000)
        if best is None or g > best[1]: best = (s, g)
    lever = "-" if best is None or best[1] == 0 else "%s:%d" % (best[0], r(best[1]))
    lines.append("expect score=%d band=%s lever=%s" % (total, band, lever))
    for i, p in enumerate(PILLARS):
        ss = ",".join("%s:%d:%d:%d" % (s, *sig[s]) for (s, pp) in SIGNALS if pp == p and s in sig) or "-"
        pts = pillar_pts.get(p)
        lines.append("pillar %s eff=%d points=%s contribution=%d signals=%s" % (p, eff[i], "-" if pts is None else pts, contrib[i], ss))
    return lines

CASES = [
    ("established", dict(runway=(48000, 6), obligations=(3150000, 9500000, 3), cards=(1380000, 4000000),
        savings=[("2026-06", 9500000, 1900000), ("2026-07", 9500000, 2450000), ("2026-08", 9800000, 1210000)],
        budgets=(5, 7), goals=(2, 3))),
    ("new_user", dict(runway=(7000, 6), savings=[("2026-08", 6000000, 450000)])),
    ("nothing_yet", dict()),
    ("exact_edges", dict(runway=(60000, 6), obligations=(3000000, 10000000, 1), cards=(300000, 1000000),
        savings=[("2026-08", 10000000, 3000000)], budgets=(1, 1), goals=(1, 1))),
    ("runway_floor", dict(runway=(10000, 12), obligations=(5500000, 10000000, 2), cards=(1000000, 1000000),
        savings=[("2026-07", 5000000, 0), ("2026-08", 5000000, 0)])),
    ("overspending", dict(runway=(5000, 6), obligations=(6200000, 8000000, 3), cards=(-50000, 20000000),
        savings=[("2026-06", 8000000, -1200000), ("2026-07", 8000000, -300000), ("2026-08", 0, -900000)],
        budgets=(0, 4), goals=(0, 2))),
    ("thin_income", dict(obligations=(1000000, 5000000, 0), cards=(700000, 0), savings=[("2026-08", 0, -40000)],
        budgets=(0, 0), goals=(3, 7))),
]

def fmt_case(cid, c):
    parts = ["case %s" % cid]
    parts.append("runway=%s" % ("%d/%d" % c["runway"] if c.get("runway") else "-"))
    parts.append("obligations=%s" % ("%d/%d/%d" % c["obligations"] if c.get("obligations") else "-"))
    parts.append("cards=%s" % ("%d/%d" % c["cards"] if c.get("cards") else "-"))
    parts.append("savings=%s" % (";".join("%s:%d:%d" % m for m in c["savings"]) if c.get("savings") else "-"))
    parts.append("budgets=%s" % ("%d/%d" % c["budgets"] if c.get("budgets") else "-"))
    parts.append("goals=%s" % ("%d/%d" % c["goals"] if c.get("goals") else "-"))
    return " ".join(parts)

out = ["# AI-FHS golden file — generated by health_oracle.py (issue 9.4). Do not edit by hand.",
       "# A block: one `case` line (inputs), one `expect` line, five `pillar` lines."]
for cid, c in CASES:
    out.append(fmt_case(cid, c))
    out.extend(score(c))
open(os.path.join(HERE, "health.txt"), "w").write("\n".join(out) + "\n")
print("\n".join(out[2:]))
