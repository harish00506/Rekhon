#!/usr/bin/env python3
"""
Independent oracle for AI-PA's golden file (issue 10.1; SRS §13.1, §21.5).

Why:  the expected verdicts must not come from the engine under test. This reads the rulebook for
      the gate policy, the obligation lines and the opportunity-cost assumption, then re-applies
      §13.1's seven steps to fixed households — arithmetic written a second time, in another
      language, from the spec rather than from the Kotlin.
What: one line per gate (scenario, gate, outcome) and one per scenario for the verdict.
Changelog: 2026-09-25 — Created for issue 10.1.

The four scenarios are the shapes the acceptance criterion names: comfortably affordable, borderline
on two gates, outright unaffordable, and an instalment that pushes obligations past the lender's
line.
"""
import json, os
from decimal import Decimal, ROUND_HALF_EVEN

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = HERE
while not os.path.isfile(os.path.join(ROOT, "ai/rules/rules-kb.json")):
    ROOT = os.path.dirname(ROOT)
RULES = {r["rule_id"]: r["params_json"] for r in json.load(open(os.path.join(ROOT, "ai/rules/rules-kb.json")))["rules"]}
GATES, OPP = RULES["RULE-PA-GATES"], RULES["RULE-PA-OPPCOST"]
EMI40, COOL = RULES["RULE-EMI-40"], RULES["RULE-COOL-OFF"]
DAYS = GATES["days_per_month"]
WARN_BPS, FAIL_BPS = EMI40["warn_pct"] * 100, EMI40["fail_pct"] * 100


def affordability(s, r):
    """Gate 1. Cash asks what is left; an instalment asks whether the month carries it."""
    if r["method"] == "EMI":
        surplus = s["income"] - s["obligations"] - s["essentials"] - r["emi"]
        if surplus < 0:
            return "FAIL"
        return "WARN" if r["emi"] > s["safe_to_spend"] else "PASS"
    after = s["liquid"] - r["price"]
    if after < 0:
        return "FAIL"
    return "WARN" if after < s["floor"] else "PASS"


def cash_flow(s, r):
    """Gate 2. The forecast feels the instalment, or the whole price."""
    if s["lowest"] is None:
        return "PASS"
    outflow = r["emi"] if r["method"] == "EMI" else r["price"]
    after = s["lowest"] - outflow
    if after < 0:
        return "FAIL"
    return "WARN" if after < s["buffer"] or s["crunch_days"] > 0 else "PASS"


def obligations(s, r):
    """Gate 3, RULE-EMI-40."""
    if s["income"] <= 0:
        return "PASS"
    added = r["emi"] if r["method"] == "EMI" else 0
    after = ((s["obligations"] + added) * 10000) // s["income"]
    if after >= FAIL_BPS:
        return "FAIL"
    return "WARN" if after >= WARN_BPS else "PASS"


def goal_delay_days(s, r):
    """Gate 4's figure: the price divided by everything going to goals, in days."""
    if s["goal_monthly"] <= 0:
        return 0
    return (r["price"] * DAYS + s["goal_monthly"] // 2) // s["goal_monthly"]


def goal_impact(s, r):
    return "WARN" if goal_delay_days(s, r) > DAYS else "PASS"


def budget_fit(s, r):
    if s["category_remaining"] is None:
        return "PASS"
    return "WARN" if r["price"] > s["category_remaining"] else "PASS"


def future_value(price, years):
    rate = Decimal(OPP["expected_return_bps"]) / Decimal(10000)
    return int((Decimal(price) * (1 + rate) ** years).quantize(Decimal(1), rounding=ROUND_HALF_EVEN))


def timing(s, r):
    return "PASS" if s["cheaper_month"] is None else "WARN"


def verdict(outcomes, r):
    """The worst of the gates, softened one step for urgency but never onto a hard fail."""
    hard = "FAIL" in outcomes
    worst = "NOT_NOW" if hard else ("STRETCH" if "WARN" in outcomes else "COMFORTABLE")
    soften = (
        r["urgency"] == "URGENT"
        and GATES["urgency_softens_one_step"]
        and not (hard and GATES["soften_blocked_on_hard_fail"])
    )
    if not soften:
        return worst
    return {"NOT_NOW": "STRETCH", "STRETCH": "COMFORTABLE", "COMFORTABLE": "COMFORTABLE"}[worst]


ORDER = [
    ("AFFORDABILITY", affordability),
    ("CASH_FLOW", cash_flow),
    ("OBLIGATIONS", obligations),
    ("GOAL_IMPACT", goal_impact),
    ("BUDGET_FIT", budget_fit),
    ("OPPORTUNITY_COST", lambda s, r: "PASS"),
    ("TIMING", timing),
]


def run(scenario):
    s, r = scenario["signals"], scenario["request"]
    lines, outcomes = [], []
    for name, gate in ORDER:
        outcome = gate(s, r)
        outcomes.append(outcome)
        lines.append(f"gate {scenario['name']} {name} {outcome}")
    lines.append(f"figure {scenario['name']} goalDelayDays {goal_delay_days(s, r)}")
    for years in OPP["horizon_years"]:
        lines.append(f"figure {scenario['name']} futureValue{years}y {future_value(r['price'], years)}")
    annual = s["income"] * 12
    cooloff = r["price"] > (annual * COOL["trigger_pct_of_annual_income"] * 100) // 10000
    lines.append(f"cooloff {scenario['name']} {str(cooloff).lower()}")
    lines.append(f"verdict {scenario['name']} {verdict(outcomes, r)}")
    return lines


def household(**overrides):
    base = dict(liquid=1_00_000_00, floor=50_000_00, essentials=42_000_00, safe_to_spend=25_000_00,
                lowest=90_000_00, buffer=5_000_00, crunch_days=0, income=1_00_000_00,
                obligations=30_000_00, goal_monthly=15_000_00, category_remaining=5_000_00,
                cheaper_month=None)
    base.update(overrides)
    return base


def request(price, method="CASH", emi=0, urgency="ROUTINE"):
    return dict(price=price, method=method, emi=emi, urgency=urgency)


SCENARIOS = [
    {"name": "comfortable", "signals": household(), "request": request(2_000_00)},
    {"name": "borderline", "signals": household(), "request": request(30_000_00)},
    {"name": "unaffordable", "signals": household(), "request": request(2_00_000_00)},
    {"name": "emi_over_the_line", "signals": household(),
     "request": request(3_00_000_00, method="EMI", emi=25_000_00)},
    {"name": "urgent_borderline", "signals": household(),
     "request": request(30_000_00, urgency="URGENT")},
]

if __name__ == "__main__":
    print("# AI-PA golden file — generated by purchase_oracle.py (issue 10.1). Do not edit by hand.")
    print("# gate <scenario> <gate> <outcome> · figure <scenario> <key> <minor> · cooloff · verdict")
    for scenario in SCENARIOS:
        for line in run(scenario):
            print(line)
