#!/usr/bin/env python3
"""
Independent oracle for AI-GRD's golden file (issue 9.7; AI-ARC-004, §21.5).

Why:  the expected verdicts must not come from the engine under test. This reads the rulebook for
      the attempt limit and the transform allowlist, then re-applies `ai/chat/guardrail.md` to a
      fixed set of sentences — building the permitted renderings from Python's own formatting
      rather than from the app's `MoneyFormatter`.
What: one line per claim — scenario, the span as written, and whether it verified — then one line
      per scenario for the verdict the ladder reaches.
Changelog: 2026-09-23 — Created for issue 9.7.

The scenarios are the four shapes that matter: a reply that is entirely true; one that rounds and
abbreviates but invents nothing; one with a number the model worked out for itself (GRD-003); and
one that has already been written twice.
"""
import json, os, re
from decimal import Decimal, ROUND_HALF_EVEN

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = HERE
while not os.path.isfile(os.path.join(ROOT, "ai/rules/rules-kb.json")):
    ROOT = os.path.dirname(ROOT)
RULES = {r["rule_id"]: r["params_json"] for r in json.load(open(os.path.join(ROOT, "ai/rules/rules-kb.json")))["rules"]}
LADDER, TRANSFORMS = RULES["RULE-GRD-LADDER"], RULES["RULE-GRD-TRANSFORMS"]
MAX_DECIMALS = TRANSFORMS["max_display_decimals"]


def indian(digits):
    """Indian digit grouping: the last three digits, then pairs. 1234567 -> 12,34,567."""
    s, out = str(digits), ""
    if len(s) <= 3:
        return s
    head, tail = s[:-3], s[-3:]
    while len(head) > 2:
        out = "," + head[-2:] + out
        head = head[:-2]
    return head + out + "," + tail


def rupees(minor, decimals):
    """`minor` paise rounded to `decimals` decimal places, written the way the app writes money."""
    sign = "-" if minor < 0 else ""
    value = (Decimal(abs(minor)) / 100).quantize(Decimal(1).scaleb(-decimals), rounding=ROUND_HALF_EVEN)
    whole, _, frac = str(value).partition(".")
    return sign + "₹" + indian(whole) + ("." + frac if frac else "")


def amount_renderings(minor):
    out = {rupees(minor, 2)}
    if rupees(minor, 2).endswith(".00"):
        out.add(rupees(minor, 2)[:-3])
    if TRANSFORMS["allow_rounded_display"]:
        out |= {rupees(minor, d) for d in range(0, MAX_DECIMALS + 1)}
    if TRANSFORMS["allow_lakh_crore_words"]:
        for word, places in (("lakh", 7), ("crore", 9)):
            value = Decimal(minor).scaleb(-places)
            exact = value.normalize()
            scale = max(-exact.as_tuple().exponent, 0)
            if scale <= MAX_DECIMALS:  # exact only: rounding stops at the rupee (ADR-0048)
                out |= {"₹" + str(value.quantize(Decimal(1).scaleb(-d))) + " " + word
                        for d in range(scale, MAX_DECIMALS + 1)}
    if TRANSFORMS["allow_minor_units"]:
        out.add(f"{minor} paise")
    return {normalise(x) for x in out}


def number_renderings(value):
    out = {str(value)}
    if isinstance(value, Decimal):
        out.add(str(value.normalize()))
        if TRANSFORMS["allow_rounded_display"]:
            for d in range(0, MAX_DECIMALS + 1):
                rounded = value.quantize(Decimal(1).scaleb(-d), rounding=ROUND_HALF_EVEN)
                out |= {str(rounded), str(rounded.normalize())}
    return {normalise(x) for x in out}


def normalise(text):
    return re.sub(r"\s+", "", text).lower()


PATTERNS = [
    (re.compile(r"-?₹\s?\d(?:[\d,]*\d)?(?:\.\d+)?(?:\s?(?:lakhs?|crores?))?", re.I), "AMOUNT"),
    (re.compile(r"\b\d(?:[\d,]*\d)?\s?paise\b", re.I), "AMOUNT"),
    (re.compile(r"\d+(?:\.\d+)?\s?%"), "PERCENT"),
    (re.compile(r"\b\d{4}-\d{2}-\d{2}\b"), "DATE"),
    (re.compile(r"\b\d{1,2}\s[A-Za-z]{3,9}\.?\s\d{4}\b"), "DATE"),
    (re.compile(r"\b[A-Za-z]{3,9}\.?\s\d{1,2},\s?\d{4}\b"), "DATE"),
    (re.compile(r"\b[A-Za-z]{3,9}\.?\s\d{4}\b"), "DATE"),
    (re.compile(r"(?<![\d.])-?\d+(?:\.\d+)?(?![\d%])"), "NUMBER"),
]


def claims(text, names):
    for name in names:
        text = text.replace(name, " " * len(name))
    found = []
    for pattern, kind in PATTERNS:
        for match in pattern.finditer(text):
            found.append((match.start(), match.group(0).strip(), kind))
        text = pattern.sub(lambda m: " " * len(m.group(0)), text)
    return sorted(found)


def verify(scenario):
    allowed = {
        "AMOUNT": set().union(*[amount_renderings(m) for m in scenario["amounts"]]) if scenario["amounts"] else set(),
        "PERCENT": {normalise(f"{p}%") for p in scenario["percents"]},
        "DATE": {normalise(d) for d in scenario["dates"]},
        "NUMBER": set().union(*[number_renderings(n) for n in scenario["numbers"]]) if scenario["numbers"] else set(),
    }
    if TRANSFORMS["allow_year_alone"]:
        allowed["NUMBER"] |= {normalise(d[:4]) for d in scenario["dates"]}
    lines, bad = [], 0
    for _, span, kind in claims(scenario["text"], scenario["names"]):
        ok = normalise(span) in allowed[kind]
        bad += 0 if ok else 1
        lines.append(f"claim {scenario['name']} {normalise(span)} {kind} {'VERIFIED' if ok else 'UNVERIFIABLE'}")
    left = LADDER["max_attempts"] - scenario["attempts_made"]
    verdict = "PASS" if bad == 0 else ("REGENERATE" if left > 0 else "REFUSE")
    lines.append(f"verdict {scenario['name']} {verdict} {max(left, 0) if bad else 0}")
    return lines


SCENARIOS = [
    {
        "name": "all_true", "attempts_made": 0,
        "text": "Groceries is at 80% of budget: you have spent ₹8,000.00 of ₹10,000.00 across 42 payments.",
        "amounts": [800000, 1000000], "percents": [80], "numbers": [42], "dates": [], "names": ["Groceries"],
    },
    {
        "name": "rounded_and_abbreviated", "attempts_made": 0,
        "text": "Your fund holds about ₹1,23,457, which is ₹1.5 lakh short of the target by 2027-03-31.",
        "amounts": [12345678, 15000000], "percents": [], "numbers": [], "dates": ["2027-03-31"], "names": [],
    },
    {
        "name": "model_did_arithmetic", "attempts_made": 0,
        "text": "At ₹500.00 a month for 12 months that is ₹6,000.00 a year.",
        "amounts": [50000], "percents": [], "numbers": [12], "dates": [], "names": [],
    },
    {
        "name": "attempts_spent", "attempts_made": 2,
        "text": "You saved ₹1,000.00 in March 2027 and 15% more in April 2027.",
        "amounts": [100000], "percents": [15], "numbers": [], "dates": ["March 2027"], "names": [],
    },
]

if __name__ == "__main__":
    print("# AI-GRD golden file — generated by guardrail_oracle.py (issue 9.7). Do not edit by hand.")
    print("# claim <scenario> <span> <kind> <VERIFIED|UNVERIFIABLE>   ·   verdict <scenario> <verdict> <attempts-left>")
    for scenario in SCENARIOS:
        for line in verify(scenario):
            print(line)
