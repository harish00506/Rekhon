#!/usr/bin/env python3
"""
Independent oracle for AI-MKT's golden file (issue 10.7; SRS §30, §21.5).

Why:  the expected scores must not come from the engine under test. This re-implements §30's
      signal library from `market-signals.json` in Python — the ladders, the bands, the history
      minimums and the staleness limits — so a disagreement means one of the two is wrong.
What: one line per scenario: the score, the possible score, the band, and each signal's points.
Result: `market.txt`, which `MarketGoldenTest` compares line for line.
Changelog: 2026-09-27 — Created for issue 10.7.

Run it from anywhere:  python3 market_oracle.py > market.txt

The conventions, restated so this file stands alone: a proportion is basis points; a percentile is
the mid-rank (everything below plus half of everything equal), so a flat series sits at 50 and not
at 0; RSI is Wilder's, seeded by the simple average of the first window; and integer division
truncates toward zero, as Kotlin's does.
"""
import json
import os
from datetime import date, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = HERE
while not os.path.isfile(os.path.join(ROOT, "ai/knowledge/market-signals.json")):
    ROOT = os.path.dirname(ROOT)
KB = json.load(open(os.path.join(ROOT, "ai/knowledge/market-signals.json")))

SIGNALS = {s["id"]: s for s in KB["scored_signals"]}
BANDS = KB["opportunity_score"]["verdict_bands"]
HISTORY = KB["history"]
STALE = KB["staleness"]
RSI_PERIOD = 14
START = date(2023, 1, 2)


def ladder_points(signal_id, measured):
    """The strongest step a measured value reaches. Input: id, value. Output: int."""
    spec = SIGNALS[signal_id]["score_points"]
    kind, best = spec["kind"], 0
    for threshold, points in spec["ladder"]:
        reached = (
            measured <= threshold if kind in ("percentile_low", "tier_negative_pct")
            else measured >= threshold if kind in ("percentile_high", "threshold_at_least")
            else measured < threshold
        )
        if reached:
            best = max(best, points)
    return best


def truncdiv(a, b):
    """Integer division truncating toward zero, as Kotlin's `/` does. Input: a, b. Output: int."""
    q = abs(a) // abs(b)
    return -q if (a < 0) != (b < 0) else q


def percentile(value, window):
    """Mid-rank percentile of a value in a window. Input: value, list. Output: int or None."""
    if not window:
        return None
    below = sum(1 for v in window if v < value)
    equal = sum(1 for v in window if v == value)
    return ((below * 2 + equal) * 100) // (len(window) * 2)


def rsi(closes, period=RSI_PERIOD):
    """Wilder's RSI as a whole number. Input: closes. Output: int or None."""
    if len(closes) <= period:
        return None
    changes = [b - a for a, b in zip(closes, closes[1:])]
    gain = sum(c for c in changes[:period] if c > 0) // period
    loss = sum(-c for c in changes[:period] if c < 0) // period
    for change in changes[period:]:
        up = change if change > 0 else 0
        down = -change if change < 0 else 0
        gain = (gain * (period - 1) + up) // period
        loss = (loss * (period - 1) + down) // period
    if loss == 0:
        return 100
    return 100 - truncdiv(100 * loss, gain + loss)


def down_streak(closes):
    """Consecutive lower closes at the end of the series. Input: closes. Output: int."""
    streak = 0
    for i in range(len(closes) - 1, 0, -1):
        if closes[i] < closes[i - 1]:
            streak += 1
        else:
            break
    return streak


def score(closes, valuation, vix):
    """
    Every signal's points for the last day of a series.
    Input: closes (paise, ascending), the two context percentiles or None.
    Output: {signal id: (points, evaluated)}.
    """
    out = {}
    latest = closes[-1]
    long_window = closes[-HISTORY["minimum_days_for_52w_high"]:]
    enough = len(closes) >= HISTORY["minimum_days_for_any_score"]

    for sid, given in (("SIG-VALUATION", valuation), ("SIG-VIX", vix)):
        out[sid] = (ladder_points(sid, given), True) if given is not None else (0, False)

    high = max(long_window) if long_window else None
    if high and enough:
        bps = truncdiv((latest - high) * 10000, high)
        out["SIG-DRAWDOWN"] = (ladder_points("SIG-DRAWDOWN", truncdiv(bps, 100)), True)
    else:
        out["SIG-DRAWDOWN"] = (0, False)

    if len(closes) >= HISTORY["minimum_days_for_ma200"]:
        window = closes[-HISTORY["minimum_days_for_ma200"]:]
        average = sum(window) // len(window)
        bps = truncdiv((latest - average) * 10000, average)
        out["SIG-MA200"] = (ladder_points("SIG-MA200", truncdiv(bps, 100)), True)
    else:
        out["SIG-MA200"] = (0, False)

    value = rsi(closes)
    out["SIG-RSI"] = (ladder_points("SIG-RSI", value), True) if value is not None else (0, False)

    pct = percentile(latest, long_window)
    if pct is not None and enough:
        out["SIG-RARITY"] = (ladder_points("SIG-RARITY", pct), True)
    else:
        out["SIG-RARITY"] = (0, False)

    out["SIG-STREAK"] = (ladder_points("SIG-STREAK", down_streak(closes)), True)
    return out


def band_for(total):
    """The verdict band for a score. Input: int. Output: str."""
    if total >= int(BANDS["STRONG_BUY_DAY"].split(">=")[1]):
        return "STRONG_BUY_DAY"
    if total >= int(BANDS["GOOD_DAY"].split("-")[0]):
        return "GOOD_DAY"
    if total >= int(BANDS["NEUTRAL"].split("-")[0]):
        return "NEUTRAL"
    return "NO_EDGE"


def assess(name, closes, valuation, vix, days_old=0):
    """One scenario's line. Input: the scenario. Output: str."""
    points = score(closes, valuation, vix)
    possible = sum(SIGNALS[sid]["max_points"] for sid, (_, ok) in points.items() if ok)
    if days_old > STALE["refuse_after_days"]:
        outcome, total, band = "TOO_STALE", 0, "-"
    elif len(closes) < HISTORY["minimum_days_for_any_score"]:
        outcome, total, band = "NOT_ENOUGH_HISTORY", 0, "-"
    else:
        outcome = "SCORED"
        total = min(sum(p for p, _ in points.values()), 100)
        band = band_for(total)
    detail = "|".join(f"{sid.replace('SIG-','')}:{p if ok else '-'}"
                      for sid, (p, ok) in sorted(points.items()))
    return f"{name} {outcome} score={total}/{possible} band={band} {detail}"


FLAT = [10_000_00] * 300
DIP = FLAT + [8_700_00]
CRASH = FLAT + [7_000_00 - d * 10_00 for d in range(1, 9)]
SAWTOOTH = [10_000_00 + (d % 60 if d % 60 < 30 else 60 - d % 60) * 20_00 for d in range(400)]

SCENARIOS = [
    ("flat_ordinary_day", FLAT, 50, 50, 0),
    ("flat_no_context", FLAT, None, None, 0),
    ("thirteen_pct_dip", DIP, 50, 50, 0),
    ("cheap_and_frightened", FLAT, 8, 93, 0),
    ("crash_with_context", CRASH, 2, 99, 0),
    ("sawtooth_today", SAWTOOTH, 50, 50, 0),
    ("young_history", FLAT[:30], 50, 50, 0),
    ("month_old_price", FLAT, 50, 50, 31),
]

if __name__ == "__main__":
    print("# AI-MKT golden file — generated by market_oracle.py (issue 10.7). Do not edit by hand.")
    print("# <name> <outcome> score=<n>/<possible> band=<band> <SIGNAL:points|...>  ('-' = not evaluated)")
    for name, closes, valuation, vix, days_old in SCENARIOS:
        print(assess(name, closes, valuation, vix, days_old))
