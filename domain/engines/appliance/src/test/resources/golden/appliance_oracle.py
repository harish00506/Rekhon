#!/usr/bin/env python3
"""
Independent oracle for AI-APP's golden file (issue 13.2; SRS §12, §21.5).

Why:  the expected figures must not come from the engine under test. This re-implements §12's
      appliance prediction from the knowledge base's own `prediction` and `alerts` blocks, in
      Python, using Python's date arithmetic and Decimal rounding rather than the app's — so a
      disagreement means one of the two is wrong, and neither can be quietly tuned to match the
      other. It reads the same JSON the Kotlin mirror is checked against, so a KB edit moves both.
What: one line per scenario: the next service and its basis, the cost range, the warranty, the
      running cost per month, each consumable's due date, and the alerts raised.
Result: `appliance.txt`, which `ApplianceGoldenTest` compares line for line.
Changelog: 2026-10-03 — Created for issue 13.2.

Run it from anywhere:  python3 appliance_oracle.py > appliance.txt

The conventions, restated from the KB so this file stands alone: a seasonal class is due on the
`seasonal_due_day_of_month` of the next occurrence of its `seasonal_month`, on or after today, and
ignores the service history; a cadence class is due `interval_months` after its last service, or
after the purchase date when it has never been serviced; a consumable runs from its own last
replacement, else the purchase date; the running cost is
watts x minutes/day x days/month x tariff / 60000 with half-even rounding to the paise; and a
midpoint is (low + high) / 2, half-even.
"""
import json
import os
from datetime import date
from decimal import Decimal, ROUND_HALF_EVEN

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = HERE
while not os.path.isfile(os.path.join(ROOT, "ai/knowledge/appliance-maintenance-kb.json")):
    ROOT = os.path.dirname(ROOT)
KB = json.load(open(os.path.join(ROOT, "ai/knowledge/appliance-maintenance-kb.json")))

CLASSES = {c["class"]: c for c in KB["appliance_classes"]}
PREDICT = KB["prediction"]
ALERTS = KB["alerts"]


def iso(value):
    """A yyyy-mm-dd string as a date. Input: ISO text. Output: date."""
    return date.fromisoformat(value)


def plus_months(when, months):
    """Adds whole months, clamping the day to the target month's length, as java.time does."""
    total = when.month - 1 + months
    year = when.year + total // 12
    month = total % 12 + 1
    last = [31, 29 if (year % 4 == 0 and year % 100 != 0) or year % 400 == 0 else 28,
            31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1]
    return date(year, month, min(when.day, last))


def next_seasonal(on_or_after, month, day):
    """The next occurrence of `day` of `month`, on or after `on_or_after`."""
    this_year = date(on_or_after.year, month, day)
    return this_year if this_year >= on_or_after else date(on_or_after.year + 1, month, day)


def half_even(numerator, denominator):
    """Integer division with half-even rounding — the rule Money.percentOf applies."""
    return int((Decimal(numerator) / Decimal(denominator)).quantize(Decimal(1), ROUND_HALF_EVEN))


def midpoint(low, high):
    """The single figure a forecast line needs, from a range."""
    return half_even(low + high, 2)


def running_cost(watts, minutes, tariff):
    """watts x minutes/day x days/month x tariff / 60000, to the paise."""
    if minutes == 0:
        return 0
    return half_even(watts * minutes * PREDICT["days_per_month"] * tariff, 60000)


def predict(s):
    """Runs one scenario. Input: the scenario dict. Output: the golden line."""
    spec = CLASSES[s["class"]]
    today = iso(s["today"])
    purchased = iso(s["purchased"])
    services = sorted(s.get("services", []))

    if spec["service"]["seasonal_month"] is not None:
        due = next_seasonal(today, spec["service"]["seasonal_month"], PREDICT["seasonal_due_day_of_month"])
        basis = "SEASONAL"
    elif services:
        due = plus_months(iso(services[-1]), spec["service"]["interval_months"])
        basis = "CADENCE"
    else:
        due = plus_months(purchased, spec["service"]["interval_months"])
        basis = "SINCE_PURCHASE"
    service_days = (due - today).days

    low, high = spec["service"]["cost_range_minor"]
    expires = plus_months(purchased, spec["warranty_months"])
    warranty_days = (expires - today).days
    in_warranty = warranty_days >= 0

    cost = running_cost(
        s.get("watts", spec["power"]["rated_watts"]),
        s.get("minutes", spec["power"]["typical_minutes_per_day"]),
        s.get("tariff", PREDICT["default_tariff_paise_per_kwh"]),
    )

    consumables = []
    for c in spec["consumables"]:
        last = s.get("replaced", {}).get(c["item"])
        from_date = iso(last) if last else purchased
        c_due = plus_months(from_date, c["interval_months"])
        consumables.append((c["item"], c_due, (c_due - today).days, c["cost_range_minor"]))
    consumables.sort(key=lambda row: row[2])

    alerts = []
    if service_days < 0:
        alerts.append(("SERVICE_OVERDUE", service_days))
    elif service_days <= ALERTS["service_due_days"]:
        alerts.append(("SERVICE_DUE", service_days))
    for _, _, days, _ in consumables:
        if days < 0:
            alerts.append(("CONSUMABLE_OVERDUE", days))
        elif days <= ALERTS["consumable_due_days"]:
            alerts.append(("CONSUMABLE_DUE", days))
    if not in_warranty:
        alerts.append(("WARRANTY_EXPIRED", warranty_days))
    elif any(warranty_days <= d for d in ALERTS["warranty_reminder_days"]):
        alerts.append(("WARRANTY_EXPIRING", warranty_days))
    alerts.sort(key=lambda a: a[1])

    return {
        "class": s["class"],
        "purchased": s["purchased"],
        "today": s["today"],
        "watts": s.get("watts"),
        "minutes": s.get("minutes"),
        "tariff": s.get("tariff"),
        "services": "|".join(services) or None,
        "replaced": "|".join(f"{k}:{v}" for k, v in sorted(s.get("replaced", {}).items())) or None,
        "expect_due": due.isoformat(),
        "expect_basis": basis,
        "expect_days": service_days,
        "expect_cost_low": low,
        "expect_cost_high": high,
        "expect_warranty": expires.isoformat(),
        "expect_warranty_days": warranty_days,
        "expect_in_warranty": "true" if in_warranty else "false",
        "expect_run": cost,
        "expect_consumables": "|".join(f"{i}:{d}:{n}" for i, d, n, _ in consumables) or "-",
        "expect_alerts": "|".join(f"{k}:{d}" for k, d in alerts) or "-",
    }


SCENARIOS = [
    # An AC in January: the seasonal rule puts the service in March, before summer, and the
    {"name": "ac_before_summer", "note": "an AC in January: the seasonal rule puts the service in March, before summer, and the warranty has long gone", "class": "AC", "purchased": "2023-06-15", "today": "2026-01-10"},
    {"name": "ac_on_anchor_day", "note": "the same AC asked on the anchor day itself — due TODAY, not in a year", "class": "AC", "purchased": "2025-03-01", "today": "2026-03-01"},
    {"name": "purifier_filter_due", "note": "the filter is what is due, not the machine (§12's named example)", "class": "WATER_PURIFIER", "purchased": "2025-10-20", "today": "2026-04-14"},
    {"name": "fridge_never_serviced", "note": "never serviced: the cadence runs from the purchase, and it is years overdue", "class": "REFRIGERATOR", "purchased": "2023-02-01", "today": "2026-10-03"},
    {"name": "washer_recently_serviced", "note": "serviced last month, so the cadence runs from the service and the warranty is still live", "class": "WASHING_MACHINE", "purchased": "2025-09-01",
     "today": "2026-10-03", "services": ["2026-09-20"]},
    {"name": "geyser_heavy_use", "note": "the household's own figures: heavier use and a dearer tariff than the book", "class": "GEYSER", "purchased": "2026-01-31", "today": "2026-10-03",
     "watts": 3000, "minutes": 90, "tariff": 950},
    {"name": "ac_switched_off", "note": "switched off at the wall all month — a running cost of exactly zero, not a tiny one", "class": "AC", "purchased": "2026-02-28", "today": "2026-02-28", "minutes": 0},
    {"name": "purifier_filter_replaced", "note": "a filter replaced late, so its clock restarts from the replacement rather than the purchase", "class": "WATER_PURIFIER", "purchased": "2024-01-15",
     "today": "2026-10-03", "replaced": {"filter_cartridge": "2026-08-01"}},
]

if __name__ == "__main__":
    print("AI-APP golden file — generated by appliance_oracle.py (issue 13.2). Do not edit by hand.")
    print("Regenerate:  python3 appliance_oracle.py > appliance.txt")
    print()
    print("Amounts are PAISE (MNY-001). Dates are ISO yyyy-MM-dd (TIM-002). `days` is from `today`,")
    print("negative when already past. `consumables` and `alerts` are `-` when empty, never blank.")
    for scenario in SCENARIOS:
        print()
        print(f"=== {scenario['name']} — {scenario['note']}")
        for key, value in predict(scenario).items():
            if value is not None:
                print(f"# {key}={value}")
