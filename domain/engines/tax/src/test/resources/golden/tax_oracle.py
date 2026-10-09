#!/usr/bin/env python3
"""
Independent oracle for AI-TAX's golden file (issue 13.4; SRS §38, §21.5).

Why:  the expected figures must not come from the engine under test. This re-implements §38 in
      Python, reading the same `ai/knowledge/tax-kb-fy2025-26.json` the Kotlin mirror is checked
      against, with Python's own Decimal arithmetic — so a disagreement means one of the two is
      wrong, and neither can be quietly tuned to match the other. Tax is the subject where that
      matters most: the figures look plausible over a wide range, so a wrong slab boundary or a
      forgotten cess would not announce itself.
What: one record per household — each regime's taxable income, tax before rebate, rebate, cess and
      total; the winner and margin; and the capital-gains split.
Result: `tax.txt`, which `TaxGoldenTest` compares field by field.
Changelog: 2026-10-09 — Created for issue 13.4.

Run it from anywhere:  python3 tax_oracle.py > tax.txt

The conventions, restated from the KB so this file stands alone: money is PAISE; slabs are
MARGINAL (income in a band is taxed at that band's rate); the 87A rebate caps at `rebate_max` and
applies only when taxable income is at or below `rebate_taxable_upto`; the 4% cess applies to tax
AFTER the rebate; the new regime allows only the standard deduction and employer NPS; a
post-Apr-2023 debt gain is added to SALARY and taxed at slab rate; the equity LTCG exemption is
annual and taken once over the NET long-term equity gain.
"""
import json
import os
from decimal import Decimal, ROUND_HALF_EVEN

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = HERE
while not os.path.isfile(os.path.join(ROOT, "ai/knowledge/tax-kb-fy2025-26.json")):
    ROOT = os.path.dirname(ROOT)
KB = json.load(open(os.path.join(ROOT, "ai/knowledge/tax-kb-fy2025-26.json")))

OLD = KB["regimes"]["old_regime"]
NEW = KB["regimes"]["new_regime"]
CESS_BPS = KB["surcharge_and_cess"]["health_and_education_cess_bps"]
EQUITY = next(r for r in KB["capital_gains"]["rules"] if r["asset"] == "equity_or_equity_mf")
P = 100  # paise per rupee


def half_even(value):
    """A Decimal to the nearest whole paise, ties to even — the rule Money applies."""
    return int(Decimal(value).quantize(Decimal(1), ROUND_HALF_EVEN))


def pct(amount, bps):
    """A rate in basis points applied to paise, half-even."""
    if amount <= 0:
        return 0
    return half_even(Decimal(amount) * Decimal(bps) / Decimal(10_000))


def walk(taxable, slabs):
    """Marginal slab walk. Returns (total tax, [(taxed_here, tax_here) per band])."""
    total, lower, bands = 0, 0, []
    for slab in slabs:
        upper = slab["upto_inr"] * P if slab["upto_inr"] is not None else None
        top = taxable if (upper is None or taxable < upper) else upper
        taxed_here = max(top - lower, 0)
        tax_here = pct(taxed_here, slab["rate_bps"])
        bands.append((taxed_here, tax_here))
        total += tax_here
        if upper is not None:
            lower = upper
    return total, bands


def regime(name, rules, gross, deductions, employer_nps):
    """One regime, from gross to total tax. Returns a dict of paise figures."""
    base = rules["standard_deduction_inr"] * P + employer_nps
    if name == "old":
        allowed = (
            base
            + min(deductions.get("c80", 0), rules["80C_cap_inr"] * P)
            + min(deductions.get("c80ccd1b", 0), rules["80CCD(1B)_nps_additional_inr"] * P)
            + min(deductions.get("c80d", 0), rules["80D_cap_inr"] * P)
            + deductions.get("home_loan_interest", 0)
            + deductions.get("hra", 0)
        )
    else:
        allowed = base

    taxable = max(gross - allowed, 0)
    before, _ = walk(taxable, rules["slabs"])
    rebate = min(before, rules["rebate_87A_max_inr"] * P) if taxable <= rules["rebate_87A_taxable_income_upto_inr"] * P else 0
    after = before - rebate
    cess = pct(after, CESS_BPS)
    return {
        "taxable": taxable,
        "allowed": allowed,
        "before": before,
        "rebate": rebate,
        "cess": cess,
        "total": after + cess,
    }


def capital_gains(gains):
    """§38.2 by asset class. Returns a dict of paise figures."""
    short_tax, slab_taxed, exempt, equity_long = 0, 0, 0, 0
    for g in gains:
        kind, amount, months = g["asset"], g["gain"], g["months"]
        if kind == "SGB_HELD_TO_MATURITY":
            exempt += amount
        elif kind == "DEBT_POST_2023":
            slab_taxed += amount
        elif kind == "EQUITY":
            if months > EQUITY["long_term_after_months"]:
                equity_long += amount
            else:
                short_tax += pct(amount, EQUITY["stcg_rate_bps"])
        elif kind == "DEBT_PRE_2023_OR_GOLD":
            if months > 24:
                short_tax += pct(amount, 1250)
            else:
                slab_taxed += amount

    exemption = EQUITY["ltcg_annual_exemption_inr"] * P
    used = min(equity_long, exemption) if equity_long > 0 else 0
    taxable_long = max(equity_long - used, 0)
    return {
        "stcg_tax": short_tax,
        "ltcg_tax": pct(taxable_long, EQUITY["ltcg_rate_bps"]),
        "exemption_used": used,
        "exemption_remaining": exemption - used,
        "slab_taxed": slab_taxed,
        "exempt": exempt,
    }


def estimate(s):
    """Runs one scenario. Input: the scenario dict. Output: a dict of expectations."""
    gains = capital_gains(s.get("gains", []))
    gross = s["salary"] + gains["slab_taxed"]
    d = s.get("deductions", {})
    nps = s.get("employer_nps", 0)
    old = regime("old", OLD, gross, d, nps)
    new = regime("new", NEW, gross, d, nps)
    winner = "OLD" if old["total"] <= new["total"] else "NEW"
    margin = abs(old["total"] - new["total"])
    return {
        "expect_old_taxable": old["taxable"],
        "expect_old_before_rebate": old["before"],
        "expect_old_rebate": old["rebate"],
        "expect_old_cess": old["cess"],
        "expect_old_total": old["total"],
        "expect_new_taxable": new["taxable"],
        "expect_new_before_rebate": new["before"],
        "expect_new_rebate": new["rebate"],
        "expect_new_cess": new["cess"],
        "expect_new_total": new["total"],
        "expect_winner": winner,
        "expect_margin": margin,
        "expect_stcg_tax": gains["stcg_tax"],
        "expect_ltcg_tax": gains["ltcg_tax"],
        "expect_exemption_used": gains["exemption_used"],
        "expect_exemption_remaining": gains["exemption_remaining"],
        "expect_slab_taxed_gains": gains["slab_taxed"],
        "expect_exempt_gains": gains["exempt"],
    }


L = 100000 * P  # one lakh, in paise

SCENARIOS = [
    {
        "name": "fresher_no_deductions",
        "note": "6L salary, nothing invested — the new regime's bigger standard deduction wins and "
                "the 87A rebate wipes the tax out entirely",
        "salary": 6 * L, "today": "2026-06-15",
    },
    {
        "name": "twelve_lakh_new_regime_free",
        "note": "§38.1's headline: 12L is 'effectively tax-free' under the new regime once the "
                "75,000 standard deduction and the 60,000 rebate are applied",
        "salary": 1275000 * P, "today": "2026-06-15",
    },
    {
        "name": "maxed_deductions_new_STILL_wins",
        "note": "18L salary with 80C, 80CCD(1B), 80D all maxed AND 2L of home-loan interest — "
                "4.25L of deductions, and the new regime STILL wins by 67,600. This is the "
                "practical answer FY2025-26's rates give, and it surprises people",
        "salary": 18 * L, "today": "2026-06-15",
        "deductions": {"c80": 150000 * P, "c80ccd1b": 50000 * P, "c80d": 25000 * P,
                       "home_loan_interest": 200000 * P},
    },
    {
        "name": "old_finally_wins",
        "note": "the same 18L salary with 5L of home-loan interest on top — 7.25L of deductions "
                "before the old regime beats the new. A genuine old-regime win, not the "
                "zero-income tie",
        "salary": 18 * L, "today": "2026-06-15",
        "deductions": {"c80": 150000 * P, "c80ccd1b": 50000 * P, "c80d": 25000 * P,
                       "home_loan_interest": 500000 * P},
    },
    {
        "name": "same_deductions_new_still_wins",
        "note": "the same deductions at 10L salary: the new regime's lower slabs still win, which "
                "is the comparison most people get wrong",
        "salary": 10 * L, "today": "2026-06-15",
        "deductions": {"c80": 150000 * P, "c80ccd1b": 50000 * P, "c80d": 25000 * P},
    },
    {
        "name": "deductions_over_the_caps",
        "note": "80C of 3L and 80D of 1L claimed — capped at 1.5L and 25,000, so claiming more "
                "changes nothing",
        "salary": 15 * L, "today": "2026-06-15",
        "deductions": {"c80": 300000 * P, "c80d": 100000 * P},
    },
    {
        "name": "employer_nps_both_regimes",
        "note": "80CCD(2) is deductible in BOTH regimes, so it lowers the new regime's tax too",
        "salary": 20 * L, "today": "2026-06-15", "employer_nps": 160000 * P,
    },
    {
        "name": "equity_gains_within_exemption",
        "note": "1L of long-term equity gain — inside the 1.25L annual exemption, so no LTCG tax "
                "and 25,000 of exemption left",
        "salary": 12 * L, "today": "2026-06-15",
        "gains": [{"asset": "EQUITY", "gain": 100000 * P, "months": 18}],
    },
    {
        "name": "equity_gains_over_exemption",
        "note": "3L of long-term equity gain — the exemption is used up and the excess is taxed at "
                "12.5%; a short-term lot is taxed at 20% beside it",
        "salary": 12 * L, "today": "2026-06-15",
        "gains": [{"asset": "EQUITY", "gain": 300000 * P, "months": 18},
                  {"asset": "EQUITY", "gain": 50000 * P, "months": 6}],
    },
    {
        "name": "debt_gain_stacks_on_salary",
        "note": "a post-Apr-2023 debt gain is taxed at SLAB rate, so it stacks on salary and is "
                "taxed in whatever band it lands in — not at a capital-gains rate",
        "salary": 11 * L, "today": "2026-06-15",
        "gains": [{"asset": "DEBT_POST_2023", "gain": 200000 * P, "months": 40}],
    },
    {
        "name": "sgb_exempt",
        "note": "sovereign gold bonds held to maturity are fully exempt — a gain that changes no "
                "tax at all",
        "salary": 12 * L, "today": "2026-06-15",
        "gains": [{"asset": "SGB_HELD_TO_MATURITY", "gain": 500000 * P, "months": 96}],
    },
    {
        "name": "high_income_surcharge_territory",
        "note": "60L salary — above the surcharge threshold the KB does not model, so the engine "
                "must FLAG the estimate as incomplete rather than report a low figure",
        "salary": 60 * L, "today": "2026-06-15",
    },
    {
        "name": "zero_income",
        "note": "no income at all: no tax, no rebate, no cess — and not a crash",
        "salary": 0, "today": "2026-06-15",
    },
]

if __name__ == "__main__":
    print("AI-TAX golden file — generated by tax_oracle.py (issue 13.4). Do not edit by hand.")
    print("Regenerate:  python3 tax_oracle.py > tax.txt")
    print()
    print("Money is PAISE (MNY-001). Slabs are MARGINAL. The 4% cess applies AFTER the 87A rebate.")
    print("`deductions` and `gains` are the inputs; everything `expect_*` is computed here, by this")
    print("file's own arithmetic, from the same knowledge base the engine reads.")
    for s in SCENARIOS:
        print()
        print(f"=== {s['name']} — {s['note']}")
        print(f"# salary={s['salary']}")
        print(f"# employer_nps={s.get('employer_nps', 0)}")
        print(f"# today={s['today']}")
        d = s.get("deductions", {})
        print(f"# d_80c={d.get('c80', 0)}")
        print(f"# d_80ccd1b={d.get('c80ccd1b', 0)}")
        print(f"# d_80d={d.get('c80d', 0)}")
        print(f"# d_home_loan={d.get('home_loan_interest', 0)}")
        gains = "|".join(f"{g['asset']}:{g['gain']}:{g['months']}" for g in s.get("gains", []))
        print(f"# gains={gains or '-'}")
        for key, value in estimate(s).items():
            print(f"# {key}={value}")
