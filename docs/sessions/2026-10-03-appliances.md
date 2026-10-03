<!--
  Why:  CLAUDE.md §10 — one session file per working session, holding the full reasoning the root
        records only point at.
  What: issue 13.2 — AI-APP, why it is a sibling of AI-VEH rather than the same class, the running
        cost, and the two things measuring turned up.
  Result: a reader can see where "same engine" stops being true, why electricity is costed in
          minutes, and what a golden file cannot test.
  Changelog: 2026-10-03 — Created.
-->

# 2026-10-03 — Appliances (issue 13.2)

Branch `feature/13-2-appliances-maintenance` off `dev` (`635a96e`). Version 0.13.0 → **0.13.1**
(versionCode 65), schema **v30 → v31**.

---

## 1 · Decisions this session

### 1.1 Where "same engine" stops being true

§12 ends with one sentence: *"Appliances (Phase 4): same engine, different knowledge base (AC
service pre-summer, water-purifier filters, extended-warranty expiry)."* Everything here follows
from how literally to take **same**.

Reading AI-VEH first settled it. Its whole prediction turns on
`kmPerMonth = robustSlope(odometer readings, last 12 months)` — the due date is derived from it, the
cost is adjusted by a personal index built from service history, and the alerts hang off the date.
**An appliance has no odometer.** Folding appliances into `VehicleEngine` would give an engine whose
principal input is always empty and whose principal algorithm never runs, and every caller would then
have to know which half of the result meant anything.

So: the *shape* is copied exactly — KB-driven with a drift-tested mirror, a cost **range** not a
point, alerts citing the row that raised them, future-dated outflows for the forecast — and the
arithmetic is not. That is a deviation from the SRS's wording, which is why it needed ADR-0070.

### 1.2 The shared vocabulary is declared again, not shared

A cost range and a dated outflow are genuinely common to both engines. Three options:

- **Depend on `:domain:engines:vehicle`.** Allowed — five engine-to-engine dependencies already
  exist, so this was checked rather than assumed. Rejected on meaning: the dependency would say
  "appliances need vehicles", and AI-VEH's `PredictedOutflowLabel` is `SERVICE/INSURANCE/PUC`, so
  appliances would need `CONSUMABLE` added to a shipped enum for a consumer that is switched off.
- **Move the types to `:core:model`.** The right long-term home, and rejected for now because it
  refactors shipped public API across six call sites for a feature nobody can reach.
- **Declare them here.** Chosen. **Nothing consumes both engines**, so there is no type to unify
  yet; the forecast carrying both vehicle and appliance outflows is the moment it comes due, and
  ADR-0070 §1 says so.

### 1.3 The running cost, and why minutes

A vehicle's costs arrive as events. An appliance's third cost does not — electricity arrives once a
month for everything at once, so no appliance has a bill of its own. Putting a figure on one is what
makes "the geyser costs more than the fridge" actionable.

```
paise/month = watts × minutes_per_day × days_per_month × tariff_paise_per_kwh ÷ 60 000
```

**Minutes, not hours.** A geyser runs about forty minutes a day; in hours that is a decimal, and
MNY-001 keeps decimals out of money arithmetic. Minutes are an integer the whole way to paise.

**One division, at the end.** Converting to kWh first and multiplying by the tariff would round
twice. `BigDecimal`, `HALF_EVEN` — the rule `Money.percentOf` already applies.

**Not modelled, and the KB says so:** tariff slabs that move with consumption, star ratings,
inverter vs non-inverter, standby draw. Each would change the figure and each needs an input the app
does not hold. A flat tariff the user can override is an honest approximation; a slab table the app
cannot fill would be a fabricated one (P-03).

**Not a scheduled outflow.** It is a standing monthly cost the everyday-spending pool already
contains; dating it into the forecast would count the same electricity twice — the double-count
ADR-0043 already names for scheduled payments.

### 1.4 A seasonal class ignores its own service history

An AC serviced in November is still due before summer, because the rule was never "twelve months
since" but "before it gets hot". So a class with a `seasonal_month` is due at the next occurrence of
that month — **on or after** today, not strictly after: a service due today is due today, and
rolling it forward would hide it through the summer it exists for. `basis` says which rule fired, so
the screen can explain a date the user might argue with (P-02).

---

## 2 · What measuring turned up

### 2.1 A golden file tests the data that ships, not the data that could

Mutation G5 changed the midpoint's rounding from half-even to **half-up**. Every golden scenario
stayed green — because every cost range in the shipped knowledge base happens to sum to an even
number, so the rounding mode never applies. A KB row added next year with an odd sum would then
round the other way with nothing to notice.

The fix is not a bigger golden file; it is putting the rule where it can be tested on inputs that
distinguish it. `ApplianceMathTest` exercises the midpoint on odd sums directly, along with
running-cost linearity and monotonicity over seeded cases, the overflow bound, the month-end clamp
and the seasonal boundary.

This generalises: **a golden file pins an engine against a specification over the data that exists.
Rules that the shipped data cannot distinguish need their own unit test.**

### 2.2 The engine registry is missing ten of twenty-eight engines

Adding AI-APP's row meant reading `ai/orchestrator/engine-registry.yaml`, described as the index of
the AI pipeline. Measured:

- **10 of 28 engine modules have no entry** — `budget`, `card`, `chat`, `loan`, `nature`,
  `networth`, `quicksetup`, `receipt`, `recurring`, `sms`.
- **One entry names a module that does not exist**: `:domain:engines:growth`.
- **Nothing checks any of it.**

Deliberately **not fixed here.** The obvious drift test turns the build red immediately, and the only
honest way to green is ten accurate contract lines — each needing that engine's formula read and
summarised. That is its own issue. Recorded in ADR-0070 so the next person finds the measurement
instead of repeating it.

### 2.3 Both of 13.1's pins fired, one day later

The profile-scoped table count (33 → 36) and the archive key count (38 → 41) each failed the build
and each had to be raised by hand. That is the whole point of a pin: the numbers are not
maintenance, they are the moment somebody confirms the new tables were looked at. For 13.2 they
were — all six new queries filter on `profile_id`, so none needed a device-wide marker.

---

## 3 · Flow changed this session

```
EXPORT  ArchiveRepository.export()
          └─ archiveDao().<38 reads>
              ├─ .withHousehold(dao, profileId)
              │   + appliances · applianceServices · applianceConsumables   (13.2, schema 31)
              └─ Json.encodeToString(CfoArchive(...))

IMPORT  restore(archive)
          └─ restoreAppliances(dao, archive)        the three 13.2 tables, after the accounts
```

AI-APP itself has **no runtime caller**: `ApplianceMode.IS_ENABLED` is false and no screen exists, so
the backup is the only flow this issue changed. `FLOW.md` records that rather than drawing a box
nothing reaches.

---

## 4 · Code changed this session

| Path | What it does now |
|------|------------------|
| `ai/knowledge/appliance-maintenance-kb.json` | **New.** Five classes: cadence, seasonal anchor, cost ranges, warranty, consumables with their own ranges, power draw; `APP-PREDICT` and `APP-ALERTS` |
| `domain/engines/appliance/` | **New module.** `Appliances.kt` (contract + `ApplianceMode`), `ApplianceKnowledge.kt` (the mirror), `ApplianceMath.kt` (dates + money), `KbApplianceEngine.kt` |
| `…/appliance/src/test/resources/golden/appliance_oracle.py` | **New.** The independent Python oracle that generates `appliance.txt` |
| `…/appliance/src/test/…/Appliance{Golden,Math,Engine,KbDrift}Test.kt` | **New.** 41 tests |
| `domain/engines/appliance/ENGINE.md` | **New.** Contract, formula, assumptions, data, tests, version log |
| `core/database/…/entity/Entities.kt` | `ApplianceEntity`, `ApplianceServiceEntity`, `ApplianceConsumableEntity` |
| `core/database/…/dao/Daos.kt` | `ApplianceDao`; three archive reads and three archive writes |
| `core/database/…/migration/Migrations.kt` | `MIGRATION_30_31` — three tables and six indices, purely additive |
| `core/database/…/CfoDatabase.kt` | `VERSION = 31`; three entities + the DAO registered |
| `core/database/schemas/…/31.json` | Exported schema fixture |
| `core/database/src/androidTest/…/MigrationRoundTripTest.kt` | The 30 → 31 case, asserting a null `cost_minor` stays null |
| `core/database/src/test/…/scoping/ProfileScopingTest.kt` | Pin 33 → 36 |
| `data/repository/…/Archive.kt`, `ArchiveRepository.kt` | The three tables in the envelope; `restoreAppliances` |
| `data/repository/src/test/…/ArchiveFormatTest.kt` | Key-count pin 38 → 41 |
| `data/repository/src/sharedTest/…/DrillFixture.kt` | Seeds the three tables, one with a tombstone and one with a null cost |
| `ai/orchestrator/engine-registry.yaml` | AI-APP added; AI-VEH's "appliances are not built" note corrected |
| `docs/adr/0070-*.md` | The ADR |

---

## 5 · Quiz

1. **Why isn't AI-APP the same class as AI-VEH, when the SRS says "same engine"?** Because AI-VEH's
   whole prediction hangs off a robust slope through odometer readings, and an appliance has none —
   the shape is shared, the arithmetic cannot be.
2. **Why is running time stored in minutes?** Forty minutes of geyser is a decimal in hours, and
   MNY-001 keeps decimals out of money arithmetic.
3. **What did the golden file fail to test, and why?** The midpoint's rounding mode — every cost
   range in the shipped KB sums to an even number, so half-even and half-up agree on all of them.
4. **Why is the running cost not a forecast line?** It is a standing monthly cost the everyday pool
   already contains; dating it in would count the same electricity twice.
5. **What is wrong with `engine-registry.yaml`?** It is missing 10 of 28 engine modules, names one
   that does not exist, and nothing checks it.
