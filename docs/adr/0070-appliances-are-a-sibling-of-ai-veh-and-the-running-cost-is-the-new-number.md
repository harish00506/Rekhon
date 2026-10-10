<!--
  Why:  CLAUDE.md §5 — any decision or deviation from the SRS needs an ADR. §12 says appliances are
        the "same engine"; this is a sibling engine, which is a deviation and has to be argued.
  What: issue 13.2 — why AI-APP is its own module, what the running cost is and what it deliberately
        does not model, what the flag holds back, and a registry drift the work uncovered.
  Result: a reader can see why the two maintenance engines are not one class, why electricity is
          costed from minutes rather than hours, and what turning the flag on would need.
  Changelog: 2026-10-03 — Created.
-->

# ADR-0070 — Appliances are a sibling of AI-VEH, and the running cost is the new number

**Status:** Accepted · **Date:** 2026-10-03 · **Issue:** 13.2 · **SRS:** §12 · **Rules:** P-02, P-03, P-07, P-08, MNY-001, TIM-002, ARC-002

## Context

§12 is titled "Vehicle **& Appliance** Maintenance Prediction (AI-VEH)" and ends with a single line:

> Appliances (Phase 4): same engine, different knowledge base (AC service pre-summer,
> water-purifier filters, extended-warranty expiry).

Issue 10.4 built the vehicle half and the engine registry has said ever since that "appliances are
not built — the name is the registry's, from §12." This issue builds them.

The word to take seriously is **same**. Taken literally it means one Kotlin class; taken as the SRS
plainly intends it, it means one *architecture* — a knowledge-base-driven predictor that turns
intervals into dates and ranges into rupees.

## Decision

### 1 · A sibling engine, not the same class — and this is the deviation

`:domain:engines:appliance` is a new module. The **shape** of AI-VEH is copied deliberately: pure
Kotlin, KB-driven with a drift-tested mirror, a cost *range* rather than a point, alerts that cite
the row that raised them, and future-dated outflows for the forecast.

The **arithmetic** is not copied, because it cannot be. A vehicle's whole prediction turns on
`kmPerMonth = robustSlope(odometer readings)`: the due date, the cost adjustment and the alerts all
hang off that slope. **An appliance has no odometer.** Folding appliances into `VehicleEngine` would
produce an engine whose principal input is always empty and whose principal algorithm never runs,
and every caller would then have to know which half of the result was meaningful.

What the two genuinely share is vocabulary — a cost range, a dated outflow, a due date with a reason.
Those are **not** shared today: AI-APP declares its own. Three options were weighed:

- *Depend on `:domain:engines:vehicle`* — engine-to-engine dependencies exist here already (five of
  them), so it is allowed. Rejected because the dependency would say "appliances need vehicles",
  which is false, and because AI-VEH's `PredictedOutflowLabel` is `SERVICE/INSURANCE/PUC` — adding
  `CONSUMABLE` to it would pollute a shipped enum for a flagged-off consumer.
- *Move the shared types to `:core:model`* — the right long-term home, and rejected for now because
  it refactors shipped public API (six call sites across `VehicleRepository`, `:feature:vehicle` and
  chat) for a feature that is switched off.
- *Declare them here* — chosen. **Nothing consumes both engines yet**, so there is no type to
  unify; the moment something does — the forecast carrying both vehicle and appliance outflows — is
  the moment to lift the vocabulary into `:core:model`, with a caller to prove the shape against.

### 2 · The running cost is the number this engine adds

A vehicle's costs arrive as events. An appliance's third cost does not: electricity arrives once a
month, for everything at once, so *no* appliance has a bill of its own. Putting a rupee figure on one
is what makes "the geyser costs more than the fridge" something a household can act on.

```
paise/month = watts x minutes_per_day x days_per_month x tariff_paise_per_kwh / 60 000
```

**Minutes, not hours.** A geyser runs about forty minutes a day, and in hours that is a decimal —
which MNY-001 keeps out of money arithmetic. Minutes are an integer the whole way to paise.

**One division, at the end.** Converting to kWh first and then multiplying by the tariff would round
twice. The intermediate is exact in `BigDecimal` and only the final paise are rounded, `HALF_EVEN`,
the rule `Money.percentOf` already applies.

**What it does not model:** tariff slabs that change with monthly consumption, star ratings, inverter
versus non-inverter, and standby draw. Each would move the figure and each needs an input the app
does not hold. A flat tariff the user can override is an honest approximation; a slab table the app
cannot fill would be a fabricated one (P-03). The KB records this in `_meta.not_modelled` so the
limitation is published rather than discovered.

**It is not a scheduled outflow.** It is a standing monthly cost the everyday-spending pool already
contains; dating it into the forecast would count the same electricity twice — the double-count
ADR-0043 names for scheduled payments.

### 3 · A seasonal class ignores its own service history

An AC serviced in November is still due again before summer. So where the KB gives a class a
`seasonal_month`, the due date is the next occurrence of that month — **on or after today**, because
a service due today is due today, and rolling it forward would hide it through the summer the
service exists for. The `basis` field says which rule fired, so the screen can explain a date the
user might otherwise argue with (P-02).

### 4 · The golden file is generated by an independent oracle

`appliance_oracle.py` re-implements the whole prediction in Python, reading the same knowledge-base
JSON, using Python's date arithmetic and `Decimal` rounding. Nothing in the fixture came from the
engine under test, so a disagreement means one of the two is wrong and neither can be quietly tuned
to match the other — the same arrangement issue 10.4 used for AI-VEH.

It paid for itself immediately in the *other* direction: the two agreed on all eight scenarios at the
first run, which is meaningful evidence precisely because they were written independently.

### 5 · The flag, and what turning it on needs

`ApplianceMode.IS_ENABLED` is `false`. The engine is pure and inert — it computes only when called —
so what the flag holds back is the decision to *show* appliances. Shipping it off costs nothing and
makes turning it on a wiring job rather than a design job.

Before it goes on: a repository reading the three new tables, a screen, and a decision about whether
appliance outflows join vehicle outflows in the forecast — which is the moment §1's deferred type
unification comes due.

## Consequences

- Schema **31** adds `appliance`, `appliance_service` and `appliance_consumable`, all profile-scoped
  and all carrying tombstones, so they need no exemption from the per-row invariants.
- The three tables are in the archive from the start, read by `profile_id` like every other
  profile-scoped table. Issue 13.1's two pins — the scoped-table count and the archive key count —
  both fired and were raised deliberately, which is what they are for.
- A mutation showed the golden file cannot test the midpoint's rounding mode, because every cost
  range in the shipped KB sums to an even number. That rule is tested in `ApplianceMathTest` on odd
  sums instead — the general lesson being that a golden file tests the engine against the data that
  ships, not against the data that could.

## A registry drift this work uncovered, and did not fix

> **Corrected and closed on 2026-10-10 by [ADR-0076](0076-the-engine-registry-is-reconciled-and-now-checked.md).**
> The count below is wrong: it was **nine**, not ten. `chat` *was* registered, under a compound
> `module:` field spanning three modules, which the one-off regex used here could not parse. The
> text is left as written — it is the account of what was believed on 2026-10-03 — and the drift
> itself is now fixed and guarded by `EngineRegistryDriftTest`.

`ai/orchestrator/engine-registry.yaml` is described as the index of the AI pipeline, and **nothing
checks it**. Measured while adding AI-APP's row:

- **10 of 28 engine modules have no entry** — `budget`, `card`, `chat`, `loan`, `nature`,
  `networth`, `quicksetup`, `receipt`, `recurring` and `sms`.
- **One entry names a module that does not exist**, `:domain:engines:growth`.

This is not fixed here. Writing the obvious drift test would turn the build red immediately, and the
only honest way to green is to write ten accurate contract lines — one per engine, each needing that
engine's formula read and summarised. That is its own issue, not a side-effect of this one. It is
recorded here so the next person finds the measurement rather than repeating it.

## Alternatives considered

- **Generalise `VehicleEngine` to take a knowledge base** — the most literal reading of §12.
  Rejected, §1: the odometer slope has no appliance analogue.
- **Model the running cost from a bill the user enters** — more accurate, and rejected because it
  needs a per-appliance submeter nobody has. The plate rating and a usage estimate are figures a
  household can actually supply.
- **Hours with a decimal, rather than minutes** — rejected, §2: MNY-001.
- **Alert on a high running cost** — rejected: "this geyser costs ₹480 a month" is a fact, not an
  event. It is true every day, so an alert would fire for ever or need a threshold the KB does not
  have. It belongs on a screen.
