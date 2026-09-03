# GoalEngine — AI-GOAL

**SRS:** §10, §15, §36 · **Pipeline layer:** L4/L5 · **Module:** `:domain:engines:goals`
**Version:** 1.0 · **Status:** active

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
- **No feasibility.** The registry's contract for AI-GOAL mentions it, but feasibility means ranking
  goals against a shared surplus, which needs a number this engine is not given. That is **issue
  7.3**, together with `RULE-EMERG-FIRST`. Every goal here is projected independently.
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

| Rule | Version | What it decides |
|---|---|---|
| `RULE-HORIZON` | 1.0 | the funding bucket: `< 3y` short, `3–5y` hybrid, `> 5y` equity-eligible |

**Issue 7.1 minted no rulebook row**, and that is worth stating because it is unusual. `RULE-HORIZON`
already named `AI-GOAL.funding_buckets` in its `consumed_by` *before this engine existed*; every
other number here is arithmetic. `RulebookDriftTest` asserts both bands, the row's version, that it
is still enabled, that it still claims this engine — and that no goal-planning threshold has appeared
anywhere in the file, so a future slack band has to arrive deliberately rather than by accretion.

Two further rules name AI-GOAL and belong to later issues: `RULE-EMERG-FIRST` (7.3, feasibility) and
`RULE-PAY-FIRST` (7.4, contribution scheduling).

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

- `:feature:goals` — the list and editor.
- `SafeToSpendRepository` — `GoalPlan.totalRequiredMonthly` is the "goal contributions not yet made"
  term of `RULE-STS`. Until issue 7.1 it substituted the user's whole quick-setup INVEST envelope as
  a stand-in; **ADR-0021 recorded that stand-in and assigned its replacement to this issue.**

---

## Tests

| File | What it gates |
|---|---|
| `GoalGoldenTest` + `golden/goals.txt` | thirteen fixed goals; asserts the required monthly **and** the months, status, horizon and ETA, because the same figure is correct for over-funded and no-target, and for past-due and a date inside this month — only the verdict tells them apart |
| `GoalEngineTest` | what the golden file cannot express: the batch contract, input validation, provenance, the injected thresholds, and the no-clock guarantee |
| `GoalPropertyTest` | 500 seeded goals per property: the instalments reach the goal, the required monthly is not padded, nothing is ever negative, `onTrack` never contradicts the shortfall, saving more never asks for more, and the same seed answers the same twice (P-08) |
| `RulebookDriftTest` | `RULE-HORIZON`'s bands, version, enabled flag and `consumed_by`; and that 7.1 minted no threshold |

Coverage: **100% line, 100% branch.** The golden gate was deliberately made to fail — a wrong figure
*and* a right figure with a wrong verdict — and confirmed red before being trusted.

---

## Version log

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-08-30 | Created for issue 7.1. Required monthly, ETA, horizon and status. No rulebook row minted; no growth assumed. |
