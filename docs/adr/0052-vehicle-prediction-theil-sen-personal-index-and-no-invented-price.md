# ADR-0052 — AI-VEH predicts with a median slope, prices with the household's own bills, and invents nothing

- **Status:** accepted
- **Date:** 2026-09-26
- **Deciders:** Harish G (solo)
- **SRS refs:** §12 (AI-VEH, the vehicle-maintenance KB), §6 (data, not code), AI-ARC-001/003/006,
  P-02, P-03, P-07, P-08, MNY-001/002, TIM-001/002, DB-002, ADR-0017 (mirrors and drift tests),
  ADR-0006 (a table the demo wipe cannot reach is residue); issue 10.4. Feeds issue 9.2's forecast.

## Context

§12 asks for "service due ~Aug 20, expect ₹3,000–4,500" from an odometer and a service history,
with the prediction folded into the cash-flow forecast. The knowledge base
`ai/knowledge/vehicle-maintenance-kb.json` shipped at v1.0 with the intervals and cost ranges, and
with the method and the alert windows written as prose inside `_meta`: `"30 days / 500 km before
due"`, and a `prediction_note` describing a robust slope.

**The issue file cites §38; the registry, the KB and the engine id all say §12.** §38 is the tax
engine. This work follows §12, and the issue file's line is treated as a slip rather than a second
requirement.

## Decision

**1. The knowledge base moves to v1.1, and the method becomes typed data.**
An engine cannot consume a sentence. `"30 days / 500 km before due"` became `alerts.service_due_days`
and `alerts.service_due_km`; the `prediction_note` became a `prediction` block with the window, the
minimum counts and the index clamp. No interval, cost range or consumable changed, and the prose is
kept beside them as `alerts_note` for a human reader. Changing when the app speaks is now an edit to
one JSON file (§6).

**2. The rate is the median of the pairwise slopes (Theil–Sen), not a mean.**
Readings are typed by a person on whatever day they happen to look at the dial. A mean of the deltas,
or a first-to-last difference, lets a single mistyped reading rewrite a household's whole maintenance
plan; the median of the n(n−1)/2 slopes between readings does not, because a typo poisons only the
slopes it takes part in. A test asserts exactly that: with `112,000` typed where `12,000` was meant,
the estimate stays at 1,000 km a month.

**3. A month is thirty days when a rate is derived, and a real month when a date is.**
A rate has to come from a day count, because two readings three weeks apart still describe a rate.
Calendar intervals — the service's twelve months, insurance's twelve, the PUC's six — use real
months through `LocalDate.plusMonths`. The constant lives in one place and is stated on screen
wherever a distance-based date is shown.

**4. The due date is the earlier of the distance and the time limit, and the basis is reported.**
A commercial driver reaches 10,000 km in four months; a weekend car takes three years. Using either
limit alone is wrong for half the country. `basis` is part of the result because "you are driving it
there" and "it has been sitting a year" are different facts about the same date, and the screen says
which.

**5. The price is the knowledge base's range moved by what this household actually pays.**
The median ratio of (paid ÷ class midpoint), in basis points, clamped to the KB's floor and ceiling.
The clamp is what stops one ₹60,000 clutch job predicting the next oil change. **Below two services
there is no index at all** — the book range stands and `personalIndexBps` is null, so the screen can
say which of the two it is showing rather than passing a one-sample guess off as a household's
prices.

**6. A renewal is forecast only with a price the user recorded.**
The knowledge base has cadences, not premiums — correctly, because a premium depends on the vehicle,
the city and the claim history. A national average would be a fabricated figure inside somebody's
ninety-day forecast (P-03). So `vehicle_renewal.last_cost_minor` is the household's own number: with
it, the renewal joins the forecast; without it, it raises its date alert and nothing more.

**7. An odometer that goes backwards is tolerated, not refused.**
Odometers do not run backwards, but fingers slip. Refusing the whole vehicle over one bad row would
punish the user for a typo the median already absorbs. Only negatives — a distance or a bill below
zero — are refused, by field.

**8. The readings are rows, not a column.**
`vehicle_odometer` holds one row per reading, unique on `(profile, vehicle, day)`. A "current
odometer" column on the vehicle would have made the robust slope impossible and a mistyped reading
permanent; the unique index makes a correction replace rather than accumulate, which matters because
a duplicated reading would quietly weight the median towards that day.

**9. The forecast takes the predictions as one-offs, and the horizon stays the engine's.**
`ForecastRepository` maps each predicted outflow to a `ScheduledItem` with a new
`ItemSource.VEHICLE_PREDICTION` — a distinct source, so a screen can say the line is a *prediction*
rather than something the user scheduled. **The first draft also filtered by the horizon here.** A
deliberate break of that filter passed every test, which is how it was found to be unreachable: the
engine builds only the days inside its own window, so an item beyond it is already dropped. The
filter was a second definition of the horizon and was removed.

**10. The demo wipe and the residue count are repaired while they are open.**
Seeding four more tables made it obvious that `purchase_trace`, `purchase_trace_gate`,
`wishlist_item` and `interview_answer` — from issues 10.1 and 10.2 — were in neither the demo wipe
nor `countRowsFor`. A demo session that asked the advisor or added a wish left both behind, and the
residue check called the profile clean: the exact false assertion that query's own comment warns
about. All eight tables are now wiped and counted.

## Deferred, and why

- **Consumables** (tyres, battery, brake pads). The KB gives them an interval and **no cost range**,
  so a predicted change could carry a date and never a rupee — which cannot enter a forecast, and
  would be an invented figure if it did. Needs cost ranges in the KB, or the user's own last price,
  the way renewals work.
- **The mileage-drop alert** the KB describes. It needs litres per fill-up, and nothing in the app
  records a fuel volume. `VehicleKbDriftTest` asserts the alert is still absent from the engine, so
  the deferral cannot be forgotten quietly.
- **RULE-20-4-10's vehicle gate** (≥ 20% down, ≤ 4 years, ≤ 10% of income). Issue 10.1 deferred it
  to "the vehicle path" and this issue does not take it: it belongs in the Purchase Advisor, which
  has no notion of *what kind of thing* is being bought. Implementing it here would put a second
  affordability answer in the app, beside AI-PA's. It needs a `kind` on `PurchaseRequest` first.
- **Appliances**, which the registry's name for this engine mentions. §12 pairs them with vehicles;
  they have no knowledge base and issue 13.2 owns them.
- **Linking a vehicle to its loan account** beyond storing the id — the running-cost picture that
  would combine EMI, fuel, insurance and servicing is a screen of its own.

## Consequences

- The costs that arrive as surprises are now dated and priced, on the household's own figures, and
  they show up in the ninety-day forecast where the crunch-day warning can see them.
- Every figure names the KB row behind it, and the screen says which limit decided the date and
  whether the price came from the book or from the user's own bills.
- The knowledge base is the place to change the method: window, minimum counts, clamp and alert
  windows are all rows, and a drift test fails the build if the mirror disagrees.
- One new database version (27) with four tables, all soft-deleting, all archived, all wiped, all
  seeded in the restore drill.
