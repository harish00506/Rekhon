#!/usr/bin/env python3
"""
Independent oracle for AI-INS's golden file (issue 13.3; SRS §39.1, §21.5).

Why:  the expected figures must not come from the engine under test. This re-implements §39.1 in
      Python, reading the same `ai/rules/rules-kb.json` rows the Kotlin mirror is checked against,
      using Python's own Decimal arithmetic rather than the app's — so a disagreement means one of
      the two is wrong, and neither can be quietly tuned to match the other.
What: one record per household: the term cover needed and on which arm, the gap, the health floor
      and its gap, and for each flagged policy the buy-term-invest-the-rest workings.
Result: `protection.txt`, which `ProtectionGoldenTest` compares field by field.
Changelog: 2026-10-03 — Created for issue 13.3.

Run it from anywhere:  python3 protection_oracle.py > protection.txt

The conventions, restated from the rulebook so this file stands alone: money is PAISE; a lakh is
10 000 000 paise; the income multiple is the band's MINIMUM unless the household has one income, in
which case it is the MAXIMUM (§39.3); termCoverNeeded is the LARGER of
(multiple x income + liabilities) and (IRDAI HLV multiple x income); a gap is floored at zero; the
premium-per-lakh discriminator is half-even; and the future value is an ORDINARY annuity —
FV = PMT x ((1+r)^n - 1) / r, the payment made at the END of each year.
"""
import json
import os
from decimal import Decimal, ROUND_HALF_EVEN, getcontext

getcontext().prec = 34

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = HERE
while not os.path.isfile(os.path.join(ROOT, "ai/rules/rules-kb.json")):
    ROOT = os.path.dirname(ROOT)
RULES = {r["rule_id"]: r for r in json.load(open(os.path.join(ROOT, "ai/rules/rules-kb.json")))["rules"]}

TERM = RULES["RULE-TERM-10X"]["params_json"]
HEALTH = RULES["RULE-HEALTH-COVER"]["params_json"]
ENDOW = RULES["RULE-TERM-VS-ENDOW"]["params_json"]
PAISE_PER_LAKH = 1_00_000 * 100


def half_even(value):
    """A Decimal to the nearest whole paise, ties to even — the rule Money applies."""
    return int(Decimal(value).quantize(Decimal(1), ROUND_HALF_EVEN))


def hlv_multiple(age):
    """The IRDAI multiple for an age, or None when no band covers it."""
    for low, high, multiple in TERM["hlv_age_multipliers"]:
        if low <= age <= high:
            return multiple
    return None


def premium_per_lakh(premium, cover):
    """Paise of annual premium per lakh of cover, half-even. None when there is no cover."""
    if cover <= 0:
        return None
    return half_even(Decimal(premium) * Decimal(PAISE_PER_LAKH) / Decimal(cover))


def future_value(yearly, rate_bps, years):
    """Ordinary annuity: FV = PMT x ((1+r)^n - 1) / r. Paise in, paise out."""
    if yearly <= 0:
        return 0
    if rate_bps == 0:
        return yearly * years
    rate = Decimal(rate_bps) / Decimal(10_000)
    factor = ((Decimal(1) + rate) ** years - Decimal(1)) / rate
    return half_even(Decimal(yearly) * factor)


def assess(h):
    """Runs one household. Input: the scenario dict. Output: a dict of expectations."""
    income = h["income"]
    liabilities = h.get("liabilities", 0)
    policies = h.get("policies", [])

    out = {}

    if h.get("dependents", True) or not TERM["requires_dependents"]:
        multiple = TERM["income_multiple_max"] if (
            h.get("single_income", False) and TERM["single_income_uses_max_multiple"]
        ) else TERM["income_multiple_min"]
        by_income = multiple * income + (liabilities if TERM["include_outstanding_liabilities"] else 0)
        by_hlv = hlv_multiple(h["age"]) * income
        needed = max(by_income, by_hlv)
        basis = "INCOME_MULTIPLE_PLUS_LIABILITIES" if by_income >= by_hlv else "HUMAN_LIFE_VALUE"
        existing = sum(p["cover"] for p in policies if p["kind"] == "TERM")
        out["expect_term_needed"] = needed
        out["expect_term_existing"] = existing
        out["expect_term_gap"] = max(needed - existing, 0)
        out["expect_term_basis"] = basis
    else:
        out["expect_term"] = "none"

    floor_lakh = HEALTH["metro_floor_inr_lakh"] if h.get("metro", False) else HEALTH["base_floor_inr_lakh"]
    health_needed = floor_lakh * PAISE_PER_LAKH
    health_existing = sum(p["cover"] for p in policies if p["kind"] == "HEALTH")
    out["expect_health_needed"] = health_needed
    out["expect_health_existing"] = health_existing
    out["expect_health_gap"] = max(health_needed - health_existing, 0)

    flagged = []
    for p in policies:
        if p["kind"] != "OTHER":
            continue
        per_lakh = premium_per_lakh(p["premium"], p["cover"])
        if per_lakh is None or per_lakh < ENDOW["flag_premium_per_lakh_paise_min"]:
            continue
        lakhs = Decimal(p["cover"]) / Decimal(PAISE_PER_LAKH)
        term_equivalent = half_even(lakhs * Decimal(ENDOW["term_premium_per_lakh_paise_max"]))
        difference = max(p["premium"] - term_equivalent, 0)
        years = ENDOW["comparison_horizon_years"]
        flagged.append((
            per_lakh,
            f"{p['id']}:{per_lakh}:{term_equivalent}:{difference}:"
            f"{future_value(difference, ENDOW['equity_sip_return_bps'], years)}:"
            f"{future_value(p['premium'], ENDOW['endowment_return_bps_low'], years)}:"
            f"{future_value(p['premium'], ENDOW['endowment_return_bps_high'], years)}",
        ))
    flagged.sort(key=lambda row: -row[0])
    out["expect_flagged"] = "|".join(line for _, line in flagged) or "-"
    return out


SCENARIOS = [
    {
        "name": "young_metro_single_income",
        "note": "30, one income, a home loan — the single-income nudge takes the multiple to 15x, "
                "and 15x + the loan still loses to the 25x HLV band",
        "age": 30, "income": 18_00_000_00, "liabilities": 50_00_000_00,
        "single_income": True, "metro": True,
        "policies": [{"id": "term1", "kind": "TERM", "cover": 1_00_00_000_00, "premium": 14_000_00}],
    },
    {
        "name": "midlife_dual_income_big_loan",
        "note": "42, two incomes, a large loan — 10x + liabilities beats the 20x band, so the basis "
                "flips to the income arm",
        "age": 42, "income": 25_00_000_00, "liabilities": 1_20_00_000_00,
        "single_income": False, "metro": True,
        "policies": [
            {"id": "term1", "kind": "TERM", "cover": 1_50_00_000_00, "premium": 32_000_00},
            {"id": "health1", "kind": "HEALTH", "cover": 5_00_000_00, "premium": 18_000_00},
        ],
    },
    {
        "name": "no_dependents",
        "note": "term cover is not assessed at all — 'you need none' is not a gap of zero",
        "age": 28, "income": 12_00_000_00, "dependents": False, "metro": False,
        "policies": [{"id": "health1", "kind": "HEALTH", "cover": 5_00_000_00, "premium": 9_000_00}],
    },
    {
        "name": "over_covered",
        "note": "more cover than needed on both counts — the gap floors at zero, never negative",
        "age": 55, "income": 10_00_000_00, "liabilities": 0, "metro": True,
        "policies": [
            {"id": "term1", "kind": "TERM", "cover": 3_00_00_000_00, "premium": 90_000_00},
            {"id": "health1", "kind": "HEALTH", "cover": 25_00_000_00, "premium": 45_000_00},
        ],
    },
    {
        "name": "endowment_holder",
        "note": "an endowment and a ULIP, both well past the threshold, with a genuine term plan "
                "that must NOT be flagged",
        "age": 38, "income": 15_00_000_00, "liabilities": 20_00_000_00, "metro": False,
        "policies": [
            {"id": "term1", "kind": "TERM", "cover": 1_00_00_000_00, "premium": 18_000_00},
            {"id": "endow1", "kind": "OTHER", "cover": 10_00_000_00, "premium": 80_000_00},
            {"id": "ulip1", "kind": "OTHER", "cover": 12_00_000_00, "premium": 1_20_000_00},
        ],
    },
    {
        "name": "older_term_not_flagged",
        "note": "a 60-year-old's term plan at INR 1,400 per lakh is dear but genuine — the "
                "threshold sits above it on purpose, and the kind is TERM anyway",
        "age": 60, "income": 8_00_000_00, "metro": True,
        "policies": [{"id": "term1", "kind": "TERM", "cover": 50_00_000_00, "premium": 70_000_00}],
    },
    {
        "name": "no_policies_at_all",
        "note": "the whole need is the gap, on both counts",
        "age": 35, "income": 20_00_000_00, "liabilities": 0, "metro": True,
    },
    {
        "name": "zero_cover_policy",
        "note": "a policy recorded with no cover: it has no price per lakh, so it is not flagged "
                "rather than being flagged as infinitely expensive",
        "age": 45, "income": 10_00_000_00, "metro": False,
        "policies": [{"id": "odd1", "kind": "OTHER", "cover": 0, "premium": 25_000_00}],
    },
]

if __name__ == "__main__":
    print("AI-INS golden file — generated by protection_oracle.py (issue 13.3). Do not edit by hand.")
    print("Regenerate:  python3 protection_oracle.py > protection.txt")
    print()
    print("Money is PAISE (MNY-001). `expect_term=none` means the rule did not apply (no dependents),")
    print("which is a different answer from a gap of zero. `expect_flagged` is `-` when nothing was")
    print("flagged; otherwise id:perLakh:termEquiv:difference:invested:policyLow:policyHigh, dearest first.")
    for s in SCENARIOS:
        print()
        print(f"=== {s['name']} — {s['note']}")
        print(f"# age={s['age']}")
        print(f"# income={s['income']}")
        print(f"# liabilities={s.get('liabilities', 0)}")
        print(f"# dependents={'true' if s.get('dependents', True) else 'false'}")
        print(f"# single_income={'true' if s.get('single_income', False) else 'false'}")
        print(f"# metro={'true' if s.get('metro', False) else 'false'}")
        policies = "|".join(
            f"{p['id']}:{p['kind']}:{p['cover']}:{p['premium']}" for p in s.get("policies", [])
        )
        print(f"# policies={policies or '-'}")
        for key, value in assess(s).items():
            print(f"# {key}={value}")
