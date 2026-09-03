# GoalEngine — AI-GOAL · GoalWaterfallEngine — AI-GOAL.waterfall

**SRS:** §10, §15, §15.1, §36 · **Pipeline layer:** L4/L5 · **Module:** `:domain:engines:goals`
**Versions:** `AI-GOAL` 1.0 · `AI-GOAL.waterfall` 1.0 · **Status:** active

Two engines in one module, because the second is only meaningful over the first's output and reuses
its types with no new dependency. `GoalEngine` answers *"what would this goal take?"* one goal at a
time; `GoalWaterfallEngine` answers *"can I afford all of them, and who gets what?"*

---

## Why this engine exists

A goal without a number is a wish. The app already knows what the user owns and what they spend;
§15 asks it to turn a target and a date into the one figure that decides whether the goal happens —
**the monthly contribution** — and to say plainly whether the user's own plan clears it.

The subtraction is trivial. The honesty is not:

- A target date **in the past** still needs an answer, and "divide by zero" is not one.
- A goal **already funded** must report zero required, not a negative instalment the UI would render
  with a minus sign and no explanation.
- A required monthly that quietly **assumed investment growth** would be a number this app invented.
  See *Assumptions* below.
- ₹1,00,000.01 over three months is 33,334 + 33,334 + 33,333 — **never** three times 33,333. Losing
  the odd paise is the classic money bug (MNY-001), and it is avoided here by not writing the
  division at all: `Money.split` already owns it.

---

## Contract

```kotlin
interface GoalEngine {
    fun plan(input: GoalPlanInput): Result<GoalPlan, AppError>
}
```

### Input

| Field | Meaning |
|---|---|
| `goals` | the goals to project, in the order they should come back. May be empty — a user with no goals has nothing wrong with them. |
| `goals[].target` | what the goal needs in total, paise (MNY-001). Zero is legitimate: "no target set yet". |
| `goals[].targetDate` | the day the money is needed (TIM-002). May be in the past. |
| `goals[].saved` | what is set aside now. May exceed `target`. |
| `goals[].plannedMonthly` | what the user says they will contribute. Zero means "no plan yet". |
| `today` | the day to reckon from, **already resolved in the profile's time zone by the caller**. A `LocalDate`, because every question here is a calendar one and TIM-001 forbids this module reading a clock. |
| `nowUtcMillis` | the caller's instant. Stamped onto the provenance and **never read as a clock**. |
| `rules` | the thresholds. Injected, so a test can move a band and assert the engine moves with it. |

### Output

One `GoalProjection` per goal plus the plan's `EngineProvenance`. Field names deliberately mirror
`ai/skills/tool-registry.json`'s `get_goals` contract (`{name, target_minor, current_minor,
eta_date, on_track}`), which the chat layer reads in Epic 10 and which `ai/chat/guardrail.md`
GRD-004 already points at for ETAs.

`GoalPlan.totalRequiredMonthly` is the term Safe-to-Spend subtracts (see *Consumers*).

---

## Formula / algorithm

Per goal, in dependency order:

```
remaining        = max(0, target − saved)
monthsRemaining  = max(0, ChronoUnit.MONTHS.between(today, targetDate))
requiredMonthly  = remaining.split(max(1, monthsRemaining)).max()
shortfallMonthly = max(0, requiredMonthly − plannedMonthly)
onTrack          = shortfallMonthly == 0
etaIsoDate       = today                     when remaining == 0
                   null                      when plannedMonthly == 0, or the answer exceeds 1200 months
                   today + ⌈remaining ÷ plannedMonthly⌉ months   otherwise
horizon          = RULE-HORIZON.bucketFor(monthsRemaining)
status           = NO_TARGET   when target ≤ 0
                   OVER_FUNDED when remaining == 0
                   PAST_DUE    when targetDate ≤ today
                   ON_TRACK    when shortfallMonthly == 0
                   BEHIND      otherwise
```

Three choices in there are not obvious and are load-bearing:

**`monthsRemaining` counts contributions, not duration.** A goal sixteen days away has no whole month
left, so the answer is zero and the whole remainder falls due. Being conservative fails towards *"you
need more now"*, which is the safe direction for a savings target. A target one day short of a year
away counts eleven months, not twelve, because the twelfth contribution would land after the money
was needed.

**`requiredMonthly` is the *largest* instalment.** `Money.split` hands the odd paise to the earliest
parts; quoting the smallest would leave the goal a few paise short.

**`status` order is deliberate.** Over-funded is checked before past-due, because a goal that is
fully saved is finished whatever its date said. No-target is checked before behind, because "you are
short by ₹0 a month" against a target of zero is not a shortfall — it is a goal nobody has filled in.

---

## Assumptions & guardrails

- **No assumed rate of return, and that is an absence rather than a zero.** No return rate exists
  anywhere in `ai/rules/`, and inventing one would be exactly the hardcoded financial number
  CLAUDE.md §6 forbids and P-03 exists to prevent. The horizon band is reported **as advice** — "this
  is a long-horizon goal, equity is eligible" — never compounded into the projection behind the
  user's back. A growth model can arrive later as a reviewed rulebook row.
- **No tolerance band on "on track".** It is an *exact* comparison of two figures the user typed, so
  there is nothing to store. `shortfallMonthly` is reported beside the verdict so the user sees the
  size of the gap rather than a bare boolean — better "show the work" (P-02) than a slack percentage.
- **No feasibility in `GoalEngine`, by design.** Every goal here is projected independently, as
  though it were the only claim on the month — which is the honest answer to "what would this one
  take". Ranking them against a shared surplus is the *second* engine below, added by issue 7.3.
- **It advises, it does not instruct** (P-07). Nothing moves money or schedules a transfer.
- **It decides nothing about which goals exist or what "saved" means.** Both arrive resolved:
  storage is `GoalRepository`'s question (ARC-005). In 7.1 `saved` is hand-entered; **issue 7.4**
  (Linked contributions) replaces it with a derived figure, the way 6.5's fetched price replaced
  6.3's hand-typed one.
- **An ETA further out than 1200 months is reported as `null`.** Not a financial threshold — nothing
  about the advice changes at the boundary. Past a century the difference between "in 400 years" and
  "in 40,000 years" is not something a user acts on, and computing it risks `LocalDate.plusMonths`
  throwing on a year outside its supported range.
- **Errors:** `Result<T, AppError>`, never an exception across a layer boundary (§21.6). The `Err`
  branch is reserved for arithmetic that will not fit in a `Long`, which `Money` raises rather than
  wrapping.

---

## Rules / knowledge consumed

| Rule | Version | What it decides | Engine |
|---|---|---|---|
| `RULE-HORIZON` | 1.0 | the funding bucket: `< 3y` short, `3–5y` hybrid, `> 5y` equity-eligible | `AI-GOAL` |
| `RULE-EMERG-FIRST` | 1.0 | whether any goal may be funded at all this month | `AI-GOAL.waterfall` |

**Issue 7.1 minted no rulebook row**, and that is worth stating because it is unusual. `RULE-HORIZON`
already named `AI-GOAL.funding_buckets` in its `consumed_by` *before this engine existed*; every
other number here is arithmetic. `RulebookDriftTest` asserts both bands, the row's version, that it
is still enabled, that it still claims this engine — and that no goal-planning threshold has appeared
anywhere in the file, so a future slack band has to arrive deliberately rather than by accretion.

**Issue 7.3 minted none either.** `RULE-EMERG-FIRST` had also named `AI-GOAL` in its `consumed_by`
since before either engine existed, and the waterfall itself is `min(remaining, required)` in a fold
— arithmetic, with nothing to parameterise. `rules-kb.json` `_meta.version` stayed at **1.15.0** and
the six typed mirrors elsewhere in the repo were untouched.

**The waterfall holds `RULE-EMERG-FIRST`'s citation but not its number, and the distinction is the
whole of ADR-0035.** `QuickSetupRules.emergencyRunwayMonths` has mirrored `min_runway_months` since
issue 2.3, and ADR-0017's second trigger says a *second* mirror of a shared row is the signal to stop
mirroring and build the runtime rulebook loader instead. So the threshold reaches this engine as
`GoalWaterfallInput.emergencyGateMonths`, resolved by `GoalWaterfallRepository` from the mirror that
already exists; what lives here is only the row's version, for provenance. A citation is not a
mirror: nothing in this module can drift from the rulebook's number, because nothing in this module
holds it. `RulebookDriftTest` guards that directly — it asserts `GoalRules`' **instance fields** never
grow, while leaving the companion's citations free to.

One further rule names AI-GOAL and belongs to a later issue: `RULE-PAY-FIRST` (7.4, contribution
scheduling).

---

## Evidence shown to the user (P-02)

Every `GoalPlan` carries `EngineProvenance(engineId = "AI-GOAL", engineVersion = "1.0",
computedAtUtcMillis, evidence = [RuleCitation("RULE-HORIZON", "1.0")], inputWindow = today)`. The
`init` on `GoalPlan` **requires** the evidence list to be non-empty, so a projection that cannot name
the rule that shaped it cannot be constructed at all — the same guard `SafeToSpend` puts on its own
result.

No `confidenceBps` is set: this is arithmetic, not an inference.

The goal card shows the target, what is saved, the required monthly, **the shortfall**, the horizon
band and the citation — the inputs and the rule, not a bare verdict.

## Consumers

- `:feature:goals` — the list, the editor, and the plan card the waterfall feeds.
- `GoalWaterfallRepository` — composes `GoalRepository`'s projections with the ledger and the
  emergency fund, and is the only thing that resolves the surplus (see below).
- `SafeToSpendRepository` — `GoalPlan.totalRequiredMonthly` is the "goal contributions not yet made"
  term of `RULE-STS`. Until issue 7.1 it substituted the user's whole quick-setup INVEST envelope as
  a stand-in; **ADR-0021 recorded that stand-in and assigned its replacement to this issue.**

---

## GoalWaterfallEngine — AI-GOAL.waterfall

### Why it exists

`GoalEngine` answers each goal as though it were the only claim on the month. Add three goals and the
sum of those honest answers can exceed everything the user earns, and **nothing in the app noticed**.
§15.1 is the check that does:

```
feasibility: Σ requiredMonthly(all active goals) ≤ P50 forecast surplus
  if infeasible → gap analysis + 3 levers per goal (date / amount / contribution)
• Priority waterfall: Emergency Fund → high-priority goals → others
```

### Contract

```kotlin
interface GoalWaterfallEngine {
    fun allocate(input: GoalWaterfallInput): Result<GoalWaterfall, AppError>
}
```

| Field | Meaning |
|---|---|
| `goals` | the projections to fund, **already in the order the user wants them funded**. Ordering is a stored preference (`goal.sort_order`, schema 21); an engine that re-sorted its own input would overrule the one part of this calculation the user controls. |
| `monthlySurplus` | what the month has spare, or **null when it cannot be known**. May be negative. |
| `surplusBasis` | where that figure came from — carried onto the result so the screen never shows an amount with no source (P-02). |
| `emergencyTopUpMonthly` | `EmergencyFundPlan.topUpMonthly`; claimed before any goal while the gate holds. |
| `emergencyRunwayMonthsBps` | `EmergencyFundPlan.runwayMonthsBps` — basis points of a *month* (MNY-002). **Null means unknown, and unknown holds the gate.** |
| `emergencyGateMonths` | `RULE-EMERG-FIRST.min_runway_months`, resolved by the caller. See the rules section on why the number lives outside this module. |
| `today` / `nowUtcMillis` | as `GoalEngine`; no clock is read here (TIM-001). |

### Formula / algorithm

```
gateHolds   = runwayMonthsBps == null  or  runwayMonthsBps < emergencyGateMonths × 10 000
distributable = max(0, monthlySurplus ?: 0)
emergencyAllocated = gateHolds ? min(distributable, emergencyTopUpMonthly) : 0
remaining          = distributable − emergencyAllocated

for each goal, in the caller's order:
    allocated = gateHolds ? 0 : min(remaining, requiredMonthly)
    remaining = remaining − allocated

gapMonthly  = Σ requiredMonthly − Σ allocated
feasibility = UNKNOWN            when monthlySurplus is null
              FEASIBLE           when gapMonthly == 0
              INFEASIBLE         otherwise

per under-funded goal (FR-GOAL-003's three levers, all at the ALLOCATED rate):
    extendByMonths  = ⌈remaining ÷ allocated⌉ − monthsRemaining, at least 1
                      null when allocated == 0, or past 1200 months
    reduceTargetTo  = saved + allocated × monthsRemaining
                      null when monthsRemaining == 0
    increaseContributionBy = shortfallMonthly
```

Four choices in there are load-bearing:

**Strict priority, not pro rata — which is why no rounding rule appears anywhere.** `Money.allocate`
distributes a sum across weights by largest remainder; that is the right tool for a *split* and the
wrong one for a waterfall, which fills each claim in turn and hands on what is left.
`min(remaining, required)` can neither create nor lose a paise, so there is nothing to round.
`GoalWaterfall.init` asserts `emergencyAllocated + Σ allocated + unallocated == max(surplus, 0)` — on
the type rather than in a test, so 7.5's order-of-operations engine is held to it too.

**An unknown surplus is `UNKNOWN`, never zero.** A zero surplus is a real and *different* answer —
"this month has no room" — and the levers are exactly what the user needs to see about it. Treating
missing data as zero would tell a user one month into the app that every goal they own is impossible:
issue 7.2's zero-target mistake wearing another costume.

**An unknown runway holds the gate.** With no evidence the buffer exists, funding a holiday ahead of
it is the expensive direction to be wrong in.

**`RULE-EMERG-FIRST` is cited whichever way the gate goes**, departing from 7.2's rule about citing
only a rule that fired. A clamp that does not bind changed nothing, so citing it would mislead; a
*gate* is evaluated every time and both of its outcomes decide whether goals are funded at all.
`emergencyFirstApplied` on the result says which way it went — clearer than inferring it from whether
a citation is present. `RULE-HORIZON` is **not** cited here: this engine applies no horizon.

### The substitution: §15.1 asks for a forecast this app does not have

`:domain:engines:forecast` contains only the placeholder issue 1.1 scaffolded. **Issue 9.2 (AI-FCT)
was never built** — there is no Epic 9 entry in `CHANGELOG.md` at all — so there is no P50 forecast
surplus to compare against.

`GoalWaterfallRepository` substitutes the **P50 of observed surplus**: the median of
`income − (needs + wants)` across the closed months of `RULE-EMF-MULT`'s six-month window, falling
back to the onboarding INVEST envelope, and reporting `null`/`NONE` when neither exists. It is a
genuine median — of what happened rather than what is projected — and `SurplusBasis` names which on
every result, so the screen says "the middle of your last 6 closed months" rather than implying a
projection. **ADR-0035** records the decision and what changes when 9.2 lands: a fourth
`SurplusBasis` value, and one branch in the repository. The engine does not change at all, which is
the point of keeping the substitution on the far side of this interface.

Three ways to resolve that surplus wrongly, each one guarded by a test in
`GoalWaterfallRepositoryTest`:

- **subtracting `invested` as well.** Investing *is* goal funding; netting it out hides the very money
  the plan allocates.
- **reusing Safe-to-Spend.** `RULE-STS` has `include_goal_contributions: true` and
  `SafeToSpendRepository` already feeds it `GoalPlan.totalRequiredMonthly` — so Safe-to-Spend is the
  surplus *net of* goals. Feeding it back would double-count them and make the answer depend on
  itself.
- **using a mean.** One replaced fridge in six months rewrites the plan.

### Assumptions & guardrails

- **It allocates nothing and moves nothing** (P-07). Every figure is a suggestion the user may drag
  into a different order, ignore, or disagree with.
- **The order is an input, never a derivation.** `sort_order` is the only part of this calculation the
  user sets directly, which is why it is the only part that is stored.
- **The three levers are computed at the *allocated* rate**, not at what the user planned: they answer
  "given what this plan can spare for you, what would have to give?"
- **A null lever means the lever does not exist**, not that it was skipped. No amount of time reaches
  a target at ₹0 a month; a goal with no whole month left has nothing to spread a smaller target over.

### Evidence shown to the user (P-02)

`EngineProvenance(engineId = "AI-GOAL.waterfall", engineVersion = "1.0", computedAtUtcMillis,
evidence = [RuleCitation("RULE-EMERG-FIRST", "1.0")], inputWindow = today)`. The dotted sub-id follows
the house convention (`AI-GOAL.funding_buckets`, `AI-MKT.capacity_gate`) and keeps a stored waterfall
distinguishable from a stored projection when their versions diverge (AI-ARC-006). No `confidenceBps`.

The plan card shows the verdict **and the gap**, the surplus with its basis named in words, the
emergency fund's claim when the gate fires, what is left unspoken for, and the citation. Each goal
shows what the plan gives it, whether the buffer is what is holding it, and the three levers.

---

## Tests

| File | What it gates |
|---|---|
| `GoalGoldenTest` + `golden/goals.txt` | thirteen fixed goals; asserts the required monthly **and** the months, status, horizon and ETA, because the same figure is correct for over-funded and no-target, and for past-due and a date inside this month — only the verdict tells them apart |
| `GoalEngineTest` | what the golden file cannot express: the batch contract, input validation, provenance, the injected thresholds, and the no-clock guarantee |
| `GoalPropertyTest` | 500 seeded goals per property: the instalments reach the goal, the required monthly is not padded, nothing is ever negative, `onTrack` never contradicts the shortfall, saving more never asks for more, and the same seed answers the same twice (P-08) |
| `RulebookDriftTest` | `RULE-HORIZON`'s bands and `RULE-EMERG-FIRST`'s version, enabled flag, `consumed_by` and `severity: fail`; that neither 7.1 nor 7.3 minted a threshold; and that `GoalRules` gained no instance field, which is what would make the gate a second mirror |
| `GoalWaterfallGoldenTest` + `golden/goal-waterfall.txt` | fifteen scenarios of **competing goals**, each fixing all seven outputs at once — the verdict, both totals, the gap, the leftover, the per-goal split and the three levers. ₹0.00 allocated is correct for a goal the money ran out above, one the gate held, a month with no surplus and a month whose surplus is unknown; only the fields beside it tell them apart. Runs the real pipeline, `GoalEngine` then the waterfall |
| `GoalWaterfallEngineTest` | order preservation, the two kinds of zero, the citation on both sides of the gate, contradictory inputs refused at construction, and the sum invariant refusing a hand-built result |
| `GoalWaterfallPropertyTest` | 500 seeded scenarios: the surplus is placed exactly, no goal is overfilled, nothing is negative, more surplus never leaves a goal worse off, reordering moves money without inventing any, the date lever is the *smallest* extension that works, and the same seed answers the same twice (P-08) |

Coverage: **100% line, 100% branch.** Every gate here has been watched go red before being trusted.
7.1's golden file was made to fail on a wrong figure *and* on a right figure with a wrong verdict.
7.3 repeated the drill three times: a mis-labelled golden lever, a `RULE-EMERG-FIRST` softened from
`fail` to `warn`, and a `surplus_lookback_months` minted into the rulebook — all three failed the
build, and all three **without `--rerun-tasks`**, which is what confirms 7.2's rulebook-as-test-input
fix is still holding.

---

## Version log

| Version | Date | Change |
|---|---|---|
| `AI-GOAL` 1.0 | 2026-08-30 | Created for issue 7.1. Required monthly, ETA, horizon and status. No rulebook row minted; no growth assumed. |
| `AI-GOAL.waterfall` 1.0 | 2026-09-03 | Created for issue 7.3. Feasibility against a shared surplus, the priority waterfall, and FR-GOAL-003's three levers. No rulebook row minted; the P50 *forecast* surplus §15.1 asks for is substituted by the P50 of observed surplus until issue 9.2 exists (ADR-0035). |
