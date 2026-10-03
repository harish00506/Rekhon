# AI-APP — appliance maintenance, warranty and running cost

**Module:** `:domain:engines:appliance` · **Version:** 1.0 · **Layer:** L4 · **SRS:** §12
**Issue:** 13.2 · **ADR:** [ADR-0070](../../../docs/adr/0070-appliances-are-a-sibling-of-ai-veh-and-the-running-cost-is-the-new-number.md)
**Knowledge base:** `ai/knowledge/appliance-maintenance-kb.json` v1.0

## Contract

```
ApplianceInput(appliance, todayIsoDate, nowUtcMillis, services, consumables, usage)
    -> Result<AppliancePrediction, AppError>

AppliancePrediction(
    nextService      ApplianceDue(dueIsoDate, basis, daysAway)
    predictedCost    ApplianceCostRange(low, high)        paise, MNY-001
    warranty         WarrantyStatus(expiresOnIsoDate, daysAway, inWarranty)
    runningCostPerMonth  Money                            paise per month
    consumablesDue   List<ConsumableDue>                  soonest first
    alerts           List<ApplianceAlert>                 soonest first
    scheduled        List<ApplianceOutflow>               for the forecast, future-dated only
    provenance       EngineProvenance                     AI-ARC-003
)
```

Pure (P-08): `todayIsoDate` and `nowUtcMillis` are inputs. No clock, no I/O, no Android (ARC-002).

## Formula

### The date — three rules, in priority order

```
seasonal_month != null  ->  next occurrence of seasonal_due_day_of_month/seasonal_month,
                            ON OR AFTER today                            basis = SEASONAL
else last service       ->  lastService + interval_months                basis = CADENCE
else                    ->  purchasedOn + interval_months                basis = SINCE_PURCHASE
```

A seasonal class **ignores the service history**, and that is the point: an AC serviced in November
is still due again before summer, because the rule was never "twelve months since" but "before it
gets hot" (§12: *AC service pre-summer*). *On or after*, not strictly after — a service due today is
due today, and rolling it forward would hide it for a year.

Adding months **clamps** to the shorter month: a service a month after 31 January is due 28
February, not 3 March.

### The warranty

```
expiresOn  = purchasedOn + warranty_months
inWarranty = daysAway >= 0            cover "to" a date includes that date
```

### The consumables

Each runs on its own clock, from its own last replacement, falling back to the purchase date —
an appliance ships with its first filter already in it.

### The running cost — the number this engine adds

```
paise/month = watts x minutes_per_day x days_per_month x tariff_paise_per_kwh / 60 000
```

60 000 is watt-minutes per kWh (1 000 W for 60 minutes). **One division, at the end**, so the
intermediate is exact and only the final paise are rounded — `BigDecimal`, `HALF_EVEN`, the rule
`Money.percentOf` applies (MNY-001). Minutes rather than hours because a geyser runs about forty
minutes a day and a fraction of an hour would be a decimal in money arithmetic.

The household's own `ratedWatts`/`minutesPerDay`/`tariffPaisePerKwh` beat the knowledge base's; a
`null` means the book was used, which lowers `confidenceBps` from 10 000 to 7 000. The dates are
exact either way — it is the rupees that are someone else's average.

### The alerts

| Kind | Fires when |
|---|---|
| `SERVICE_DUE` | `0 <= daysAway <= alerts.service_due_days` (30, equal to AI-VEH's) |
| `SERVICE_OVERDUE` | `daysAway < 0` |
| `CONSUMABLE_DUE` | `0 <= daysAway <= alerts.consumable_due_days` (14) |
| `CONSUMABLE_OVERDUE` | `daysAway < 0` |
| `WARRANTY_EXPIRING` | `daysAway <=` any of `alerts.warranty_reminder_days` (60, 14) |
| `WARRANTY_EXPIRED` | `!inWarranty` |

A warranty alert carries **no amount**: the KB holds no price for an extension, and a figure there
would be invented (P-03).

### What the forecast is handed

Service and consumables, **future-dated only**, each at the **midpoint** of its range. A cost that
was due last month is an alert, not a forecast line — the horizon starts today, and dating a past
cost into it would move money the household has either already spent or decided not to.

The running cost is deliberately **not** a scheduled outflow: it is a standing monthly cost the
everyday-spending pool already contains, and adding it would count the same electricity twice.

## Assumptions

- **One flat tariff.** Slabs that change with monthly consumption, star ratings, inverter vs
  non-inverter and standby draw are all not modelled. Each would move the running cost and each
  needs a figure the app does not hold. A flat tariff the user can override is an honest
  approximation; a slab table the app cannot fill would not be.
- **A thirty-day month** for the running cost, so the figure is comparable between months rather
  than swinging with February.
- **The consumable list is the KB's**, not the user's. An appliance with a part the KB does not know
  about has no prediction for it rather than a guessed one.

## Data it reads

`ai/knowledge/appliance-maintenance-kb.json` v1.0 — `APP-PREDICT`, `APP-ALERTS` and the five class
rows. The engine is pure and cannot read the file, so it reads the typed mirror in
`ApplianceKnowledge`; `ApplianceKbDriftTest` fails the build the moment the two disagree.

## Tests

| Test | What it holds |
|---|---|
| `ApplianceGoldenTest` | 8 scenarios against **`appliance_oracle.py`**, an independent Python re-implementation reading the same JSON. Nothing in the fixture came from the engine under test. Plus a coverage assertion, so the fixture cannot be trimmed to the easy cases |
| `ApplianceMathTest` | The half-even midpoint on **odd** sums (which the shipped KB never produces — found by mutation), running-cost linearity and monotonicity over seeded cases, the overflow bound, the month-end clamp, the seasonal boundary |
| `ApplianceEngineTest` | The four refusals, every alert window at its exact edge, cover on the expiry day, the forecast's future-only rule, overrides beating the book, provenance and determinism |
| `ApplianceKbDriftTest` | Every number in the mirror against the file, both directions, including classes added to one and not the other |

## Version log

| Version | Issue | What changed |
|---|---|---|
| 1.0 | 13.2 | Created. Five classes, seasonal and cadence due bases, warranty, consumables, running cost. |
