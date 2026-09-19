#!/usr/bin/env python3
"""
Independent oracle for AI-SEAS's golden file (issue 9.3; §9.3, §21.5).

Why:  the expected values must not come from the engine under test. This reads the calendar
      knowledge base itself (not the Kotlin mirror) and does every step in exact fractions, so an
      arithmetic or transcription slip in the engine cannot be shared by its expectation.
What: builds the scenario the golden test builds, then computes §9.3's index per category and
      month, the lookback-divided factor per month, and the named events.
Result: writes seasonality.txt next to this file.
Changelog: 2026-09-19 — Created for issue 9.3.

Rounding, exactly as ADR-0044 fixes it:
  raw   = HALF_EVEN(median(same calendar month) / median(all observed months) * 10000)
  index = 10000 + trunc((raw - 10000) * min(n, 24) / 24)          (truncation toward zero)
  factor= HALF_EVEN((sum_c s_c * index_c(m) * D / T_c + s_uncategorised) / S * 10000)
  where T_c = sum of index_c over the D lookback days.
Own history needs med_all > 0 AND median(same month) > 0; otherwise the calendar prior (or none).
A category's effect on a month = s_c/S * (index_c(m)*D/T_c - 1) * 10000, exact; its event is named
(rising: the month's prior event; easing: its lookback months' prior events) when |effect| >= NAMED.
A factor within NAMED of 10000 is noise: it becomes exactly 10000 with nothing named.
"""
import json, os
from datetime import date, timedelta
from fractions import Fraction

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = HERE
while not os.path.isfile(os.path.join(ROOT, "ai/knowledge/calendar-seasonality.json")):
    ROOT = os.path.dirname(ROOT)
KB = json.load(open(os.path.join(ROOT, "ai/knowledge/calendar-seasonality.json")))
MONTHS = ["jan","feb","mar","apr","may","jun","jul","aug","sep","oct","nov","dec"]
DENOM = KB["method"]["shrinkage_denominator_months"]
NAMED = KB["method"]["min_effect_bps"]

EVENTS = []
for e in KB["events"]:
    a, b = [MONTHS.index(x.strip().lower()) + 1 for x in e["window"].split("-")]
    EVENTS.append((e["id"], a, b, {c.lower() for c in e["inflates"]}, int(round(Fraction(str(e["prior_multiplier"])) * 10000))))

def ym(y, m): return (y, m)
def add_months(t, k):
    y, m = t; i = y * 12 + (m - 1) + k; return (i // 12, i % 12 + 1)
def fmt(t): return "%04d-%02d" % t

def strongest(name, month):
    best = None
    for (eid, a, b, inf, bps) in EVENTS:
        inwin = a <= month <= b if a <= b else (month >= a or month <= b)
        if inwin and name is not None and name.lower() in inf and (best is None or bps > best[1]):
            best = (eid, bps)
    return best

def median(values):
    v = sorted(values); n = len(v)
    return Fraction(v[n // 2]) if n % 2 else Fraction(v[n // 2 - 1] + v[n // 2], 2)

def half_even(fr): return round(fr)   # Fraction.__round__ is round-half-even

# ---- the scenario (mirrored line for line in SeasonalityGoldenTest) ----------------------------
def history():
    rows = []
    for k in range(30):                       # Mar 2024 .. Aug 2026
        t = add_months((2024, 3), k); y, m = t
        wobble = (k * 37) % 11                # 0..10, deterministic noise
        shop = 400000 + wobble * 1000
        if m == 10: shop = shop * 16 // 10
        if m == 11: shop = shop * 13 // 10
        rows.append(("shopping", "Shopping", t, shop))
        if k % 3 != 1: rows.append(("dining", "Dining", t, 150000 + wobble * 500))
        tr = 250000 + wobble * 300
        if 6 <= m <= 9: tr = tr * 12 // 10
        rows.append(("transport", "Transport", t, tr))
        if m == 4: rows.append(("education", "Education", t, 3000000))
        if t >= (2026, 1): rows.append(("utilities", "Utilities", t, 180000 + (40000 if m in (4, 5) else 0)))
        if k % 4 == 0: rows.append((None, None, t, 50000))
    return rows

LOOKBACK = [("shopping", "Shopping", 1200000), ("dining", "Dining", 600000), ("transport", "Transport", 900000),
            ("utilities", "Utilities", 450000), (None, None, 300000)]
LB_START, LB_END = date(2026, 6, 21), date(2026, 9, 18)
TARGETS = [add_months((2026, 9), k) for k in range(12)]

# ---- §9.3 ---------------------------------------------------------------------------------------
H = history()
observed = sorted({t for (_, _, t, _) in H})
n = len(observed)
names = {}
for (cid, name, _) in LOOKBACK:
    if cid is not None and name is not None and cid not in names: names[cid] = name
for (cid, name, _, _) in H:
    if cid is not None and name is not None and cid not in names: names[cid] = name
cats = sorted({cid for (cid, _, _, _) in H if cid is not None} | {cid for (cid, _, _) in LOOKBACK if cid is not None})
spend = {}
for (cid, _, t, a) in H:
    if cid is not None: spend[(cid, t)] = spend.get((cid, t), 0) + a

def index(cid, t):
    series = [spend.get((cid, o), 0) for o in observed]
    same = [spend.get((cid, o), 0) for o in observed if o[1] == t[1]]
    med_all = median(series) if series else Fraction(0)
    if same and med_all > 0 and median(same) > 0:
        raw, source, event = half_even(median(same) / med_all * 10000), "OWN_HISTORY", None
    else:
        s = strongest(names.get(cid), t[1])
        raw, source, event = (s[1], "CALENDAR_PRIOR", s[0]) if s else (10000, "NONE", None)
    k = min(n, DENOM)
    idx = 10000 + int(Fraction((raw - 10000) * k, DENOM))
    return raw, idx, source, event

days = []
d = LB_START
while d <= LB_END: days.append((d.year, d.month)); d += timedelta(days=1)
D = len(days)
lb_months = sorted(set(days))
weights = {}
unc = 0
for (cid, _, a) in LOOKBACK:
    if cid is None: unc += a
    else: weights[cid] = weights.get(cid, 0) + a
S = sum(weights.values()) + unc
T = {c: sum(index(c, t)[1] for t in days) for c in weights}
KB_ORDER = [e[0] for e in EVENTS]

out = ["# AI-SEAS golden file — generated by seasonality_oracle.py (issue 9.3). Do not edit by hand.",
       "# index <month> <category> <raw> <index> <source> <event|->",
       "# factor <month> <factorBps> <rising|-> <easing|-> <own:true|false>",
       "observed %d" % n]
for t in TARGETS:
    for c in cats:
        raw, idx, src, ev = index(c, t)
        out.append("index %s %s %d %d %s %s" % (fmt(t), c, raw, idx, src, ev or "-"))
for t in TARGETS:
    if S == 0:
        out.append("factor %s 10000 - - false" % fmt(t)); continue
    total = Fraction(unc)
    rising, easing, own = set(), set(), False
    for c in sorted(weights):
        raw, idx, src, ev = index(c, t)
        total += Fraction(weights[c] * idx * D, T[c])
        effect = Fraction(weights[c], S) * (Fraction(idx * D, T[c]) - 1) * 10000
        if effect >= NAMED and src == "CALENDAR_PRIOR": rising.add(ev)
        if effect <= -NAMED:
            for lm in lb_months:
                _, _, lsrc, lev = index(c, lm)
                if lsrc == "CALENDAR_PRIOR" and lev != ev: easing.add(lev)
        if abs(effect) >= NAMED and src == "OWN_HISTORY": own = True
    easing -= rising
    f = half_even(total / S * 10000)
    if abs(f - 10000) < NAMED:
        f, rising, easing, own = 10000, set(), set(), False
    r = ",".join(e for e in KB_ORDER if e in rising) or "-"
    e_ = ",".join(e for e in KB_ORDER if e in easing) or "-"
    out.append("factor %s %d %s %s %s" % (fmt(t), f, r, e_, "true" if own else "false"))
open(os.path.join(HERE, "seasonality.txt"), "w").write("\n".join(out) + "\n")
print("\n".join(out[3:]))
