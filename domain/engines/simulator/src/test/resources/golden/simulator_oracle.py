#!/usr/bin/env python3
"""
Independent oracle for AI-SIM's golden file (issue 10.3; SRS §36, §40.2, §21.5).

Why:  the expected figures must not come from the engine under test. This re-implements §36's
      prepay comparison and §40.2's payoff loop from the spec, in Python's own decimal arithmetic,
      and reads the rulebook to confirm the two rows are still enabled.
What: one line per scenario — the loan paths and the verdict, or each strategy's months and
      interest.
Changelog: 2026-09-26 — Created for issue 10.3.

The rounding convention is the app's: a month's interest is balance × bps ÷ (10000 × 12), rounded
half-even to the paise, and a residue under 1% of a payment is rounding rather than debt.
"""
import json, os
from decimal import Decimal, ROUND_HALF_EVEN

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = HERE
while not os.path.isfile(os.path.join(ROOT, "ai/rules/rules-kb.json")):
    ROOT = os.path.dirname(ROOT)
RULES = {r["rule_id"]: r for r in json.load(open(os.path.join(ROOT, "ai/rules/rules-kb.json")))["rules"]}
assert RULES["RULE-PREPAY-VS-INVEST"]["enabled"], "the prepay rule is disabled"
assert RULES["RULE-PAYOFF-ORDER"]["enabled"], "the payoff rule is disabled"


def interest(balance, bps):
    """One month's interest, half-even to the paise."""
    return int((Decimal(balance) * Decimal(bps) / Decimal(120000)).quantize(Decimal(1), rounding=ROUND_HALF_EVEN))


def settle(remaining, payment):
    """A residue under 1% of a payment is rounding, not debt."""
    tolerance = int((Decimal(payment) / Decimal(100)).quantize(Decimal(1), rounding=ROUND_HALF_EVEN))
    return 0 if 0 < remaining < tolerance else remaining


def amortise(balance, bps, payment):
    """Run a balance to zero at a fixed payment; returns (months, interest paid)."""
    if balance <= 0:
        return 0, 0
    paid, months = 0, 0
    while balance > 0:
        due = interest(balance, bps)
        if payment <= due:
            return -1, 0
        paid += due
        balance = settle(balance + due - payment, payment)
        months += 1
    return months, paid


def future_value(amount, bps, months):
    """Monthly compounding, rounded each month as the app rounds."""
    value = amount
    for _ in range(months):
        value += interest(value, bps)
    return value


def prepay(s):
    base_months, base_interest = amortise(s["outstanding"], s["rate_bps"], s["emi"])
    prepay_months, prepay_interest = amortise(s["outstanding"] - s["lump"], s["rate_bps"], s["emi"])
    saved = base_interest - prepay_interest
    grown = future_value(s["lump"], s["return_bps"], base_months)
    gain = grown - s["lump"]
    tax = int((Decimal(gain) * Decimal(s["tax_bps"]) / Decimal(10000)).quantize(Decimal(1), rounding=ROUND_HALF_EVEN))
    after_tax = gain - tax
    verdict = "PREPAY_AHEAD" if saved > after_tax else "INVEST_AHEAD" if after_tax > saved else "LEVEL"
    return (f"prepay {s['name']} baseline={base_months}m/{base_interest} after={prepay_months}m/{prepay_interest} "
            f"saved={saved} invest={after_tax} {verdict}")


def payoff(s, strategy):
    debts = [dict(d) for d in s["debts"]]
    cleared, paid, months = [], 0, 0
    while debts:
        for d in debts:
            due = interest(d["balance"], d["bps"])
            if d["minimum"] <= due:
                return f"payoff {s['name']} {strategy} NEVER"
            paid += due
            d["balance"] += due
        if strategy == "avalanche":
            ranked = sorted(debts, key=lambda d: (-d["bps"], d["name"]))
        else:
            ranked = sorted(debts, key=lambda d: (d["balance"], d["name"]))
        target, spare = ranked[0]["name"], s["extra"]
        for d in ranked:
            payment = d["minimum"] + (spare if d["name"] == target else 0)
            settled = min(payment, d["balance"])
            d["balance"] = settle(d["balance"] - settled, payment)
        months += 1
        cleared += [d["name"] for d in ranked if d["balance"] <= 0]
        debts = [d for d in ranked if d["balance"] > 0]
    return f"payoff {s['name']} {strategy} {months}m interest={paid} order={'|'.join(cleared)}"


PREPAY = [
    {"name": "home_loan_9pc", "outstanding": 20_00_000_00, "rate_bps": 900, "emi": 20_285_00,
     "lump": 2_00_000_00, "return_bps": 1_200, "tax_bps": 3_000},
    {"name": "car_loan_14pc", "outstanding": 4_00_000_00, "rate_bps": 1_400, "emi": 13_000_00,
     "lump": 1_00_000_00, "return_bps": 1_000, "tax_bps": 2_000},
]

PAYOFF = [
    {"name": "three_debts", "extra": 5_000_00, "debts": [
        {"name": "Card", "balance": 80_000_00, "bps": 4_200, "minimum": 4_000_00},
        {"name": "Personal loan", "balance": 2_00_000_00, "bps": 1_600, "minimum": 7_000_00},
        {"name": "Phone EMI", "balance": 18_000_00, "bps": 1_400, "minimum": 1_600_00},
    ]},
    {"name": "two_cards", "extra": 2_000_00, "debts": [
        {"name": "Card A", "balance": 60_000_00, "bps": 3_600, "minimum": 3_000_00},
        {"name": "Card B", "balance": 25_000_00, "bps": 4_200, "minimum": 1_500_00},
    ]},
]

if __name__ == "__main__":
    print("# AI-SIM golden file — generated by simulator_oracle.py (issue 10.3). Do not edit by hand.")
    print("# prepay <name> baseline=<m/interest> after=<m/interest> saved=<paise> invest=<paise> <verdict>")
    print("# payoff <name> <strategy> <months>m interest=<paise> order=<cleared|...>")
    for s in PREPAY:
        print(prepay(s))
    for s in PAYOFF:
        for strategy in ("avalanche", "snowball"):
            print(payoff(s, strategy))
