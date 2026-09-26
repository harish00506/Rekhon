<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: issue 10.4 — AI-VEH, §12's vehicle maintenance prediction, folded into the forecast.
  Result: a reader can see why the rate is a median, why a renewal without a recorded price cannot
          be forecast, and why two gates in this session turned out to be measuring nothing.
  Changelog: 2026-09-26 — Created.
-->

# 2026-09-26 — When the car is due, and what it will cost (issue 10.4, ADR-0052)

**Branch:** `feature/10-4-vehicle-maintenance-prediction-ai-veh` off `dev` (`5cbdf69`)
**Versions:**
- **VERSION** 0.10.2 → **0.10.3**
- **versionCode** 46 → 47
- **Schema** 26 → **27** (`vehicle`, `vehicle_odometer`, `vehicle_service`, `vehicle_renewal`)
- **vehicle-maintenance-kb.json** 1.0 → **1.1** (the method and alert windows become typed rows)
- `AI-VEH` 1.0 (new)

---

## 1 · Decisions this session

The full argument for each is in ADR-0052.

- **The knowledge base moves to 1.1 so the method is data.** `"30 days / 500 km before due"` was
  prose inside `_meta`, and an engine cannot consume a sentence. It is now `alerts.service_due_days`
  and `alerts.service_due_km`, beside a `prediction` block holding the window, the minimum counts
  and the index clamp. No interval, cost range or consumable changed.
- **The rate is the median of the pairwise slopes (Theil–Sen).** A mean, or a first-to-last
  difference, lets one mistyped odometer reading rewrite a household's whole maintenance plan. A
  test types `112,000` where `12,000` was meant and asserts the estimate stays at 1,000 km a month.
- **A month is thirty days for a rate and a real month for a date.** A rate has to come from a day
  count, because two readings three weeks apart still describe one.
- **The due date is the earlier of the distance and time limits, and says which.** "You are driving
  it there" and "it has been sitting a year" are different facts about the same date.
- **The price is the book range moved by the median of this household's own bills**, clamped — and
  **absent below two services**, where the book range stands and the screen says so rather than
  passing a one-sample guess off as the household's prices.
- **A renewal is forecast only with a price the user recorded.** The KB has cadences, not premiums,
  and a national average premium would be a fabricated figure inside somebody's forecast (P-03).
- **A backwards reading is tolerated, not refused.** Odometers do not run backwards but fingers
  slip, and the median already absorbs one bad row.
- **Readings are rows, unique per vehicle per day.** A "current odometer" column would have made the
  robust slope impossible and a typo permanent.
- **The forecast takes the predictions as one-offs** under a new `ItemSource.VEHICLE_PREDICTION`, so
  a screen can say the line is a prediction rather than something the user scheduled.
- **Deferred:** consumables (the KB gives them no cost range), the mileage-drop alert (nothing
  records a fuel volume), RULE-20-4-10's gate (it belongs to AI-PA and needs a purchase *kind*),
  appliances, and a combined running-cost screen.

**Two gates in this session turned out to be measuring nothing, and both were found by breaking
them on purpose:**

1. **The drift test never ran.** `VehicleKbDriftTest` reads `ai/knowledge/vehicle-maintenance-kb.json`,
   which lives outside the module — so Gradle left the test task `UP-TO-DATE` when only the JSON
   changed, and two deliberate drifts (an altered interval, an added class) both passed. The project
   already had the answer in eight other modules: declare the file as a test input in
   `build.gradle.kts`. I had simply not copied that line. With it, both drifts fail, and so does a
   version bump made in the file alone.
2. **A horizon filter in `ForecastRepository` was unreachable.** I filtered predicted outflows to the
   ninety-day window before handing them to the engine; breaking that filter passed every test,
   because the engine builds only the days inside its own horizon and had already dropped them. It
   was a second definition of the window, so it is gone — and the test that exposed it stayed.

**A self-inflicted one, recorded because the next person will be tempted too:** I ran
`git checkout` on `ForecastRepository.kt` to undo a mutation, on a file whose 10.4 changes were not
yet committed. It reverted all of them. The `.bak` copy I had used for every other mutation is the
only safe way to do this.

**Also repaired while it was open:** seeding four more tables made it obvious that `purchase_trace`,
`purchase_trace_gate`, `wishlist_item` and `interview_answer` — from 10.1 and 10.2 — were in neither
the demo wipe nor `countRowsFor`. A demo session that asked the advisor or added a wish left both
behind while the residue check called the profile clean. All eight tables are now wiped and counted.

**What the device run showed:** upgrading the existing install migrated 26 → 27 with the profile
intact. A hatchback serviced on 2025-10-05 predicts its next service on 2026-10-05 — "due on the
calendar before the odometer" — with "Service due in 9 days", a ₹4,000–7,000 range and 2,000 km a
month from two readings. The dashboard's ninety-day card then read **"Lowest point: ₹2,44,500.00 on
Oct 5, 2026"** with the line **"Oct 5, 2026 · Swift −₹5,500.00 (predicted from your vehicle)"**.
Checked in dark mode and in airplane mode; the five class chips wrap onto two rows, which is what
keeps the last of them tappable on a phone.

## 2 · Flow changed this session

```
DashboardScreen → "Your vehicles" → VehiclesScreen
├─ AddVehicle / LogOdometer / LogService / SetRenewal → the four tables
└─ observeVehicles() → VehicleEngine.predict()        domain/engines/vehicle — pure (AI-VEH)
      rate = median pairwise slope over 12 months            (VEH-PREDICT)
      due  = min(lastService + interval_months, reading + (dueOdo − km) ÷ rate)
      cost = KB range × median(paid ÷ midpoint), clamped
      alerts at 30 days / 500 km, renewals at 30 and 7        (VEH-ALERTS)

ForecastRepository.observeForecast()
└─ + vehicles.observePredictedOutflows() → ScheduledItem(source = VEHICLE_PREDICTION)
```

`FLOW.md` §2.16 holds the full chain.

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `ai/knowledge/vehicle-maintenance-kb.json` | 1.1 — typed `prediction` and `alerts` blocks; the prose kept beside them for a reader |
| `domain/engines/vehicle/` (new) | AI-VEH 1.0: the engine, `VehicleMath`, the KB mirror, `ENGINE.md`, 38 tests, the golden file and its Python oracle |
| `core/database/**` | schema 27: four tables, `VehicleDao`, `MIGRATION_26_27`, the round-trip case, and the wipe/residue repair for all eight of Epic 10's tables |
| `data/repository/VehicleRepository.kt` (new), `RepositoryFactory.kt`, `Archive.kt`, `ArchiveRepository.kt`, `DemoModeRepository.kt`, `DrillFixture.kt` | the household's vehicles, and the four tables through backup, restore, wipe and drill |
| `data/repository/ForecastRepository.kt` | predicted costs join the horizon as one-offs (§12 into 9.2) |
| `domain/engines/forecast/ForecastEngine.kt` | `ItemSource.VEHICLE_PREDICTION` — a prediction, not a commitment |
| `feature/vehicle/` (new), `feature/dashboard/**` | the screen, its 19 tests, and the dashboard action |
| `app/.../di/RepositoryModule.kt`, `CfoRoute.kt`, `CfoNavHost.kt`, `app/build.gradle.kts` | AI-VEH provided; the route to the screen |
| `docs/adr/0052-…`, `DECISIONS.md`, `FLOW.md` §2.16, `ai/orchestrator/engine-registry.yaml`, `CHANGELOG.md`, `docs/memory.md` | the records |
