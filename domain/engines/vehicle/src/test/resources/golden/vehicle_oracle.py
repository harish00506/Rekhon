#!/usr/bin/env python3
"""
Independent oracle for AI-VEH's golden file (issue 10.4; SRS §12, §21.5).

Why:  the expected figures must not come from the engine under test. This re-implements §12's
      prediction from the knowledge base's own `prediction` and `alerts` blocks, in Python, using
      Python's date arithmetic and Decimal rounding rather than the app's — so a disagreement means
      one of the two is wrong, and neither can be quietly tuned to match the other.
What: one line per scenario: the slope, the due date and its basis, the adjusted cost range, the
      personal index, and the alerts raised.
Result: `vehicle.txt`, which `VehicleGoldenTest` compares line for line.
Changelog: 2026-09-26 — Created for issue 10.4.

Run it from anywhere:  python3 vehicle_oracle.py > vehicle.txt

The conventions, restated from the KB so this file stands alone: a month is thirty days when a rate
is being derived from readings; the slope is the median of the pairwise slopes (Theil-Sen); the
personal index is the median of (paid / class midpoint) in basis points, clamped; and a cost is
scaled by that index with half-even rounding to the paise, the same rule `Money.percentOf` applies.
"""
import json
import os
from datetime import date, timedelta
from decimal import Decimal, ROUND_HALF_EVEN

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = HERE
while not os.path.isfile(os.path.join(ROOT, "ai/knowledge/vehicle-maintenance-kb.json")):
    ROOT = os.path.dirname(ROOT)
KB = json.load(open(os.path.join(ROOT, "ai/knowledge/vehicle-maintenance-kb.json")))

CLASSES = {c["class"]: c for c in KB["vehicle_classes"]}
RENEWALS = {r["item"]: r for r in KB["renewals"]}
PREDICT = KB["prediction"]
ALERTS = KB["alerts"]
DAYS_PER_MONTH = 30


def iso(value):
    """A yyyy-mm-dd string as a date. Input: ISO text. Output: date."""
    return date.fromisoformat(value)


def plus_months(when, months):
    """Adds whole months the way java.time does — clamping the day to the month's length."""
    total = when.month - 1 + months
    year = when.year + total // 12
    month = total % 12 + 1
    day = min(when.day, [31, 29 if year % 4 == 0 and (year % 100 != 0 or year % 400 == 0) else 28,
                         31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1])
    return date(year, month, day)


def median(values):
    """The middle value, or the integer mean of the middle two. Input: list. Output: int."""
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2 == 1:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) // 2


def km_per_month(readings, today):
    """The median pairwise slope over the KB's window, in whole km per month."""
    start = plus_months(today, -PREDICT["slope_window_months"])
    inside = sorted([r for r in readings if iso(r[0]) >= start], key=lambda r: r[0])
    if len(inside) < max(PREDICT["min_readings_for_slope"], 2):
        return PREDICT["default_km_per_month"]
    slopes = []
    for i in range(len(inside)):
        for j in range(i + 1, len(inside)):
            days = (iso(inside[j][0]) - iso(inside[i][0])).days
            if days > 0:
                # Python's // floors; the app's integer division truncates toward zero.
                delta = (inside[j][1] - inside[i][1]) * DAYS_PER_MONTH
                slopes.append(int(delta / days) if delta < 0 else delta // days)
    return median(slopes) if slopes else PREDICT["default_km_per_month"]


def personal_index_bps(services, midpoint):
    """The median of (what was paid / the book midpoint) in bps, clamped, or None."""
    if len(services) < PREDICT["min_services_for_personal_index"] or midpoint <= 0:
        return None
    ratios = [paid * 10000 // midpoint for (_, _, paid) in services]
    return max(PREDICT["personal_index_floor_bps"],
               min(PREDICT["personal_index_ceiling_bps"], median(ratios)))


def scaled(amount, bps):
    """amount x bps / 10000, half-even to the paise — the app's one rounding rule (MNY-001)."""
    return int((Decimal(amount) * Decimal(bps) / Decimal(10000)).quantize(Decimal(1), rounding=ROUND_HALF_EVEN))


def predict(scenario):
    """One scenario's prediction. Input: the scenario dict. Output: a line of the golden file."""
    today = iso(scenario["today"])
    spec = CLASSES[scenario["class"]]["service"]
    readings = sorted(scenario["readings"], key=lambda r: r[0])
    services = sorted(scenario["services"], key=lambda s: s[0])
    slope = km_per_month(readings, today)

    midpoint = (spec["cost_range_minor"][0] + spec["cost_range_minor"][1]) // 2
    index = personal_index_bps(services, midpoint)
    low = spec["cost_range_minor"][0] if index is None else scaled(spec["cost_range_minor"][0], index)
    high = spec["cost_range_minor"][1] if index is None else scaled(spec["cost_range_minor"][1], index)

    last_service = services[-1] if services else None
    since = iso(last_service[0]) if last_service else (iso(readings[0][0]) if readings else today)
    base_odo = last_service[1] if last_service else (readings[0][1] if readings else None)
    due_odo = None if base_odo is None else base_odo + spec["interval_km"]
    by_time = plus_months(since, spec["interval_months"])

    latest = readings[-1] if readings else None
    by_distance = None
    if latest is not None and due_odo is not None and slope > 0:
        remaining = due_odo - latest[1]
        days = int(remaining * DAYS_PER_MONTH / slope) if remaining < 0 else remaining * DAYS_PER_MONTH // slope
        by_distance = iso(latest[0]) + timedelta(days=days)
    on_distance = by_distance is not None and by_distance < by_time
    due = by_distance if on_distance else by_time
    days_away = (due - today).days

    alerts = []
    km_away = None if (due_odo is None or latest is None) else due_odo - latest[1]
    if days_away < 0:
        alerts.append(("SERVICE_OVERDUE", days_away))
    elif days_away <= ALERTS["service_due_days"] or (km_away is not None and km_away <= ALERTS["service_due_km"]):
        alerts.append(("SERVICE_DUE", days_away))
    for item, last_done, _cost in scenario["renewals"]:
        renewal_due = plus_months(iso(last_done), RENEWALS[item]["cadence_months"])
        away = (renewal_due - today).days
        if away < 0:
            alerts.append(("RENEWAL_EXPIRED", away))
        elif away <= max(ALERTS["renewal_reminder_days"]):
            alerts.append(("RENEWAL_DUE", away))
    alerts.sort(key=lambda a: (a[1], a[0]))

    return (f"{scenario['name']} class={scenario['class']} km/mo={slope} "
            f"due={due.isoformat()}/{'DISTANCE' if on_distance else 'TIME'}@"
            f"{'-' if due_odo is None else due_odo} days={days_away} "
            f"cost={low}..{high} index={'-' if index is None else index} "
            f"alerts={'|'.join(f'{k}:{d}' for k, d in alerts) if alerts else '-'}")


# Five households, chosen so each exercises a different limb: a scooter serviced on time, a
# hatchback driven hard enough for the distance to arrive first, an SUV whose owner pays well above
# the book, an EV with nothing recorded but one reading, and a sedan that is overdue with lapsed
# paperwork.
SCENARIOS = [
    {
        "name": "scooter_regular", "class": "2W", "today": "2026-08-15",
        "readings": [["2026-05-04", 12000], ["2026-06-03", 12400], ["2026-07-03", 12800], ["2026-08-02", 13200]],
        "services": [["2026-05-04", 12000, 90000]],
        "renewals": [["insurance", "2026-03-01", 450000]],
    },
    {
        "name": "hatchback_hard", "class": "hatch", "today": "2026-08-15",
        "readings": [["2026-06-03", 40000], ["2026-07-03", 42000], ["2026-08-02", 44000]],
        "services": [["2026-06-01", 40000, 500000]],
        "renewals": [["PUC", "2026-06-01", 12000]],
    },
    {
        "name": "suv_expensive", "class": "SUV", "today": "2026-08-15",
        "readings": [["2026-02-01", 20000], ["2026-08-02", 26000]],
        "services": [["2025-08-01", 14000, 2200000], ["2026-02-01", 20000, 2400000]],
        "renewals": [["insurance", "2025-09-10", 3500000]],
    },
    {
        "name": "ev_fresh", "class": "EV", "today": "2026-08-15",
        "readings": [["2026-08-10", 1200]],
        "services": [],
        "renewals": [],
    },
    {
        "name": "sedan_overdue", "class": "sedan", "today": "2026-08-15",
        "readings": [["2025-01-10", 60000], ["2025-03-11", 61000]],
        "services": [["2025-01-10", 60000, 750000]],
        "renewals": [["insurance", "2025-02-01", 2800000], ["PUC", "2025-06-01", 9000]],
    },
]

if __name__ == "__main__":
    print("# AI-VEH golden file — generated by vehicle_oracle.py (issue 10.4). Do not edit by hand.")
    print("# <name> class=<kb class> km/mo=<slope> due=<iso>/<basis>@<odometer> days=<away> "
          "cost=<low>..<high> index=<bps> alerts=<kind:days|...>")
    for scenario in SCENARIOS:
        print(predict(scenario))
