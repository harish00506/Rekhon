# AI-VEH — vehicle maintenance prediction

> `:domain:engines:vehicle` · version **1.0** · layer **L4** · issue 10.4 · SRS **§12** ·
> [ADR-0052](../../../docs/adr/0052-vehicle-prediction-theil-sen-personal-index-and-no-invented-price.md)

## Contract

One public interface, one call.

```kotlin
VehicleEngineFactory.create().predict(VehicleInput) : Result<VehiclePrediction, AppError>
```

**In:** a vehicle (id, label, KB class), its odometer readings, its service history, its renewal
rows, the profile's `todayIsoDate`, `nowUtcMillis`, and the knowledge-base mirror.
**Out:** `kmPerMonth`, `latestOdometerKm`, `nextService {dueIsoDate, dueOdometerKm, basis, daysAway}`,
`predictedCost {low, high}`, `personalIndexBps`, `alerts`, `scheduled` (what the forecast carries),
and `provenance`.

**Refusals** (`AppError.Validation`, by field): `vehicle.date` for an unparseable ISO date,
`vehicle.odometer` for a negative distance, `vehicle.serviceCost` and `vehicle.renewalCost` for a
negative amount.

## Formula

### The rate — Theil–Sen over the knowledge base's window

```
window   = today − prediction.slope_window_months          (12 months)
slopes   = { (km_j − km_i) × 30 ÷ (day_j − day_i)  |  i < j, readings inside the window }
kmPerMonth = median(slopes)        or 0 when fewer than two readings fall inside
```

A month is **thirty days** when a rate is derived, because readings arrive whenever the user looks
at the dial. The median — not the mean, and not first-to-last — is the whole point: a mistyped
reading poisons only the slopes it takes part in.

### The date — whichever limit arrives first

```
since       = last service's date, else the earliest reading's date, else today
baseOdo     = last service's odometer, else the earliest reading's
dueOdometer = baseOdo + service.interval_km
byTime      = since + service.interval_months
byDistance  = latestReading.date + (dueOdometer − latestReading.km) × 30 ÷ kmPerMonth   (needs a rate)
due         = min(byTime, byDistance) ; basis = DISTANCE when byDistance is the earlier
```

`basis` is reported because "you are driving it there" and "it has been sitting a year" are
different facts about the same date.

### The price — the book range, moved by this household

```
midpoint    = (cost_range_minor.low + cost_range_minor.high) ÷ 2
ratios      = { paid × 10 000 ÷ midpoint  |  each recorded service }
indexBps    = clamp(median(ratios), floor 5 000, ceiling 20 000)      needs ≥ 2 services
predicted   = (low, high) × indexBps ÷ 10 000, half-even                else the range unchanged
```

Below two services there is **no index** — the book range stands, and `personalIndexBps` is null so
the screen can say which it is showing.

### The alerts

`SERVICE_OVERDUE` when the date has passed; `SERVICE_DUE` within `alerts.service_due_days` (30) **or**
`alerts.service_due_km` (500) of the due odometer — the distance test is what catches a vehicle
300 km short of a service whose calendar date is a year away. `RENEWAL_EXPIRED` and `RENEWAL_DUE`
run the same way over `renewals[].cadence_months`. Ordered soonest first.

### What the forecast is handed

The next service at the midpoint of the predicted range, plus every renewal the user has recorded a
price for, each dated no earlier than today — a forecast cannot spend money yesterday.

## Assumptions

- **Thirty-day months** for rate arithmetic only; calendar intervals use real months.
- **An odometer that goes backwards is tolerated, not refused.** Odometers do not run backwards but
  fingers slip, and the median already absorbs one bad row. Refusing the vehicle would punish the
  user for a fixable typo.
- **A renewal without a recorded price cannot be forecast.** The knowledge base has cadences, not
  premiums, and a national average premium would be an invented figure in a household's forecast
  (P-03).
- **Consumables and the mileage-drop alert are not built.** The KB gives consumables an interval and
  no cost range, and the mileage alert needs litres per fill-up, which nothing records. Both are
  deferred in ADR-0052 with what they need.

## Data it reads

`ai/knowledge/vehicle-maintenance-kb.json` **v1.1**, mirrored as `VehicleKnowledge.BUNDLED` and held
to the file by `VehicleKbDriftTest`. Cited rows: `VEH-KB.service`, `VEH-KB.renewals`, `VEH-PREDICT`,
`VEH-ALERTS`.

**The mirror's test input is declared in `build.gradle.kts`.** Without that line Gradle leaves the
test task `UP-TO-DATE` when only the JSON changes, and the drift gate passes without running — which
is how it was first written, and how two deliberate drifts went undetected.

## Tests

| Suite | What it holds |
|-------|---------------|
| `VehicleEngineTest` (23) | the slope, the typo case, the window, both bases, the index and its clamp, every alert, the forecast handover, the refusals, determinism |
| `VehicleGoldenTest` (1) | five households, line for line against `golden/vehicle_oracle.py` — an **independent** Python implementation from the KB |
| `VehiclePropertyTest` (6 × 300) | never later than the time limit; a distance basis only with a distance; day counts agree with dates; nothing dated in the past or worth nothing; a dearer history never predicts cheaper; determinism |
| `VehicleKbDriftTest` (8) | every interval, cost range, cadence and parameter against the file, plus that the deferred alert is still absent from the engine |

Seven deliberate mutations were each watched go red: mean instead of median, no window, ignoring the
distance limit, an unclamped index, alerting on days only, allowing a past date into the forecast,
and ignoring the minimum service count.

## Version log

| Version | Issue | What changed |
|---------|-------|--------------|
| 1.0 | 10.4 | Created. Theil–Sen rate, dual-limit due date with its basis, personal cost index, alerts, and the outflows the forecast carries. |
