# ADR-0033 — Goals mint no rulebook row, assume no growth, and take the *greater* of two savings terms

**Status:** Accepted · 2026-08-30 · Issue 7.1 (goals engine, AI-GOAL)

**Requirements:** §10, §15, §36, AI-GOAL, AI-ARC-003, AI-ARC-006, MNY-001, MNY-002, TIM-001,
TIM-002, DB-003, ARC-001, ARC-004, ARC-005, P-02, P-03, P-04, P-07, P-08

---

## Context

§15 asks the app to turn a target and a date into a required monthly contribution. Three things were
already written down and unclaimed when this issue started:

- `ai/orchestrator/engine-registry.yaml:104` declared `AI-GOAL` at layer L4/L5, module
  `:domain:engines:goals`, version 1.0 — before the module existed.
- `RULE-HORIZON` named `AI-GOAL.funding_buckets` in its `consumed_by` — before the engine existed.
- `ai/skills/tool-registry.json:67` fixed the shape the chat layer will read in Epic 10:
  `{name, target_minor, current_minor, eta_date, on_track}`.

And one debt was outstanding: [ADR-0021](0021-safe-to-spend-buffer-and-goal-stand-in.md) recorded
that Safe-to-Spend substitutes the user's whole quick-setup INVEST envelope for the goal-contribution
term of `RULE-STS`, and assigned its replacement to this issue.

## Decision

### 1 · The issue ships an engine, a table, **and a screen**

The written acceptance criteria are engine-only. Building only the engine would have produced a
complete stack with no way to enter a goal — which is exactly what issue 6.7 found in 6.5, where the
column, migration, DAO, repository, worker, client and even the string resources shipped and the
editor field did not, so the whole path was unreachable and no test noticed. `:feature:goals` exists
so that cannot happen again, and `GoalsFlowTest` asserts the editor renders every field a goal needs.

`saved_minor` is hand-entered, and temporary by design: issue 7.4 (Linked contributions) derives it,
the way 6.5's fetched price replaced 6.3's hand-typed one.

### 2 · No rulebook row was minted, and that is the decision

Every number the engine produces is either arithmetic or comes from `RULE-HORIZON`:

- **Required monthly** is `remaining.split(months).max()` — division, not a threshold.
- **"On track"** is an *exact* comparison of two figures the user typed. There is nothing to store,
  and the card shows `shortfallMonthly` beside the verdict so the user sees the size of the gap
  rather than a bare boolean — better "show the work" (P-02) than a tolerance band.
- **The horizon** comes from the row that already named this engine.

**What was rejected:** minting `RULE-GOAL-REQ` with `min_months`, `round_to_inr`,
`assumed_return_pct` and `on_track_tolerance_pct`. Adding any row bumps `rules-kb.json`'s
`_meta.version` from 1.14.0, which forces `RULEBOOK_VERSION` to be restated in **all six** existing
typed mirrors (`SafeToSpendRules`, `InvestmentRules`, `PriceFreshnessRules`, `CardRules`,
`BudgetRules`, `QuickSetupRules`) or their drift tests go red. That is a lot of cross-cutting churn
to buy a slack band nobody asked for.

`RulebookDriftTest` asserts the absence as well as the presence: no goal-planning parameter has
appeared anywhere in the file. A future slack band therefore has to arrive deliberately, with its own
ADR, rather than by accretion.

### 3 · No rate of return is assumed — an absence, not a zero

No return rate exists anywhere in `ai/rules/`, and inventing one would be precisely the hardcoded
financial number CLAUDE.md §6 forbids and P-03 exists to prevent. The horizon band is reported **as
advice** — "over 5 years, equity is eligible" — never compounded into the projection behind the
user's back. A growth model can arrive later as a reviewed rulebook row.

### 4 · Three arithmetic choices that are not obvious

- **`monthsRemaining` counts contributions, not duration.** A goal sixteen days away has no whole
  month left, so the whole remainder falls due. Being conservative fails towards *"you need more
  now"*, which is the safe direction for a savings target. A target one day short of a year away
  counts eleven months, not twelve, because the twelfth contribution would land after the money was
  needed.
- **`requiredMonthly` is the *largest* instalment.** `Money.split` hands the odd paise to the
  earliest parts, so quoting the smallest would leave the goal a few paise short. The division is not
  written in this engine at all — `Money` already owns it (MNY-001).
- **Status order.** Over-funded before past-due, because a fully saved goal is finished whatever its
  date said. No-target before behind, because "short by ₹0 a month" against a zero target is not a
  shortfall — it is a goal nobody has filled in.

### 5 · The `goal` table lands in 7.1, not 7.2

[ADR-0004](0004-quick-setup-persists-budgets-and-recurring-rules.md) said the `goal` table would
arrive with issue 7.2 (the emergency fund). It arrives here because 7.1 now ships storage and a
screen rather than an engine alone. 7.2 reuses it rather than adding a second.

Schema 19 → 20, non-destructive (DB-003), with the profile scoping and soft delete
`MigrationSafetyTest` enforces.

### 6 · Safe-to-Spend takes the **greater** of the envelope and the goals figure

This is the part that differs from what ADR-0021 asked for, and it is the most important decision
here.

`goalContributionsRemaining = maxOf(INVEST envelope, sum of goals' required monthly)`.

A straight replacement — the goals figure alone, which is what ADR-0021's wording implies — would
have made Safe-to-Spend jump **upwards** by the whole envelope for every existing user who has not
set a goal. That is optimistic in exactly the direction §5.2 exists to guard against; the figure's
entire purpose is to not end the month short.

**This is not a judgement call made after the fact.** The straight replacement fails four tests in
`SafeToSpendRepositoryTest`, two of which predate this issue — including `saving does not increase
what is safe to spend` and `the envelope total is the income basis`. The existing suite had already
encoded the invariant; it was found by trying the change and reading what broke.

A user's declared monthly saving does not stop being planned saving because they have not named a
goal for it. Taking the greater of the two counts each planned rupee exactly once and can only ever
hold the figure down. Both halves are proven load-bearing: envelope-only fails *"a goal needing more
than the envelope raises the deduction"*, goals-only fails the three above. Each was run red on
purpose before being trusted.

---

## What this cost, and what it bought

### Bought

The first number in Epic 7. A goal card shows what it takes each month, the shortfall against the
user's own plan, where that plan actually gets them, and the rule behind the advice — inputs and rule
rather than a verdict (P-02). `GoalPlan.totalRequiredMonthly` retires ADR-0021's stand-in.

100% line and branch coverage on the engine; the golden gate was made to fail on purpose, both on a
wrong figure and on a right figure with a wrong verdict, before being trusted.

### Found while proving a gate, and worth recording

**`runMigrationsAndValidate` does not check index names.** Renaming `index_goal_profile_id` in the
migration left all twenty round-trip tests green on Room 2.7.1. An upgraded installation would then
carry a differently-named index from a fresh one, for ever, with nothing to say so. A doc comment in
`Migrations.kt` claimed Room caught this; it is corrected, and `migrate19To20_...` now asserts the
index names against `sqlite_master` itself. That assertion, not Room, is what makes the migration
correct — and it is the third instance in this repository of a gate that reads as present and checks
nothing.

### Not delivered, stated plainly

- **Feasibility is not here.** The registry's contract for AI-GOAL mentions it, but ranking goals
  against a shared surplus needs a number this engine is not given. That is **issue 7.3**, with
  `RULE-EMERG-FIRST`.
- **`saved_minor` is hand-entered.** Until 7.4, a user who saves towards a goal must tell the app.
- **No goal is linked to an account or a transaction.** Also 7.4.
- **`RULE-PAY-FIRST` is unconsumed.** Scheduling contributions on the salary-credit day is 7.4's.

## Alternatives considered

| Option | Why not |
|---|---|
| Engine only, per the written acceptance criteria | a complete stack with no way in — the 6.5 bug, deliberately |
| Mint `RULE-GOAL-REQ` for a tolerance and a return rate | bumps `_meta.version`, forcing six unrelated mirrors to restate it, to buy a band nobody asked for |
| Assume a rate of return | a financial number invented in an engine (§6, P-03) |
| Replace the Safe-to-Spend term outright | makes the figure jump upwards for every user without a goal; fails four existing tests |
| Quote the smallest instalment | leaves the goal a few paise short |
| A `goal_contribution` table now | builds 7.4's data model before 7.4 has decided what links to what |
| Store the required monthly on the row | it would outlive the goal that produced it, and go stale simply because a day passed |
| Paparazzi screenshots for `:feature:goals` | only `:core:designsystem` and `:feature:dashboard` apply that plugin; `:feature:accounts` and `:feature:budgets` use Robolectric Compose tests, and this follows them |

## References

- SRS §10, §15, §36; AI-GOAL
- [ADR-0021](0021-safe-to-spend-buffer-and-goal-stand-in.md) — the stand-in this retires, and why not
  the way it asked
- [ADR-0004](0004-quick-setup-persists-budgets-and-recurring-rules.md) — expected the table in 7.2
- [ADR-0017](0017-budget-thresholds-stay-a-typed-mirror.md) — why bumping a shipped rule's version is
  the trigger this issue avoided
- `domain/engines/goals/ENGINE.md` — the contract, the formula and the version log
- `ai/rules/rules-kb.json` — `RULE-HORIZON` v1.0, unchanged
