# 2026-08-30 — Issue 7.1: the goals engine (AI-GOAL)

The user asked to "work on 6.8". **There is no 6.8** — Epic 6 runs 6.1–6.7 and all seven are now
built, so the next issue is 7.1. They confirmed, and chose the two scope calls this session turned on.

Before starting, issue 6.7's work was committed to its own branch and `feature/7-1-goals-engine-ai-goal`
was cut from `dev`.

---

## 1 · Decisions this session

### 1.1 The issue ships an engine, a table **and a screen**

7.1's written acceptance criteria are engine-only. Building only the engine would have produced a
complete stack with no way to enter a goal — which is exactly what issue 6.7 found in 6.5, where the
column, migration, DAO, repository, worker, client and even the string resources shipped and the
editor field did not. `:feature:goals` exists so that cannot happen again, and `GoalsFlowTest`
asserts the editor renders every field a goal needs.

### 1.2 No rulebook row was minted

Three things were already written down and unclaimed: `engine-registry.yaml` declared `AI-GOAL` at
`:domain:engines:goals` v1.0; `RULE-HORIZON` named `AI-GOAL.funding_buckets` in its `consumed_by`;
and `tool-registry.json` fixed the result shape Epic 10 will read. So the module path, the version
and the rule were **reconciled rather than invented**.

Every other figure is arithmetic. "On track" is an *exact* comparison of two numbers the user typed,
so there is nothing to store — and the card shows the **shortfall** instead of a tolerance band,
which is better "show the work" (P-02). Minting a row would have bumped `rules-kb.json`'s
`_meta.version` from 1.14.0, forcing all six existing typed mirrors to restate `RULEBOOK_VERSION`.

`RulebookDriftTest` asserts the **absence** as well as the presence: no goal-planning parameter has
appeared anywhere in the file. A future slack band has to arrive deliberately.

### 1.3 No rate of return — an absence, not a zero

None exists in `ai/rules/`, and inventing one would be the hardcoded financial number CLAUDE.md §6
forbids. The horizon band is reported *as advice*, never compounded into the projection (P-03).

### 1.4 Three arithmetic choices that are not obvious

- **`monthsRemaining` counts contributions, not duration.** A goal sixteen days out has no whole
  month left, so the whole remainder falls due — conservative in the safe direction.
- **`requiredMonthly` is the *largest* instalment** `Money.split` produces, because the odd paise go
  to the earliest parts and quoting the smallest would leave the goal short. The division is not
  written in the engine at all.
- **Status order**: over-funded before past-due, no-target before behind.

### 1.5 The `goal` table lands in 7.1, not 7.2

ADR-0004 expected it with the emergency fund. It arrives here because 7.1 ships storage; 7.2 reuses
it. Schema 19 → 20, non-destructive.

### 1.6 Safe-to-Spend takes the **greater** of two savings terms

`goalContributionsRemaining = maxOf(INVEST envelope, goals' required monthly)`.

ADR-0021 asked for a straight replacement. That would have made Safe-to-Spend jump **upwards** by the
whole envelope for every existing user without a goal — optimistic in the one direction §5.2 exists
to prevent. **And it fails four tests, two of them older than this issue**, including `saving does
not increase what is safe to spend`. The suite had already encoded the invariant; it was found by
making the change and reading what broke. ADR-0021 now records the resolution and why it differs from
what it asked for.

---

## 2 · What proving the gates taught us

Three gates were deliberately broken to check they were real. Two were; one was not.

### 2.1 The golden gate is real

Broken two ways — a wrong figure, and a *right* figure with a wrong verdict — and confirmed red both
times. The second matters: `₹0.00 a month` is correct for an over-funded goal *and* for one with no
target, and "the whole remainder" is correct for a past-due goal *and* for one whose date is inside
this month. Only the verdict beside the figure tells those pairs apart.

### 2.2 The Safe-to-Spend term is real, both halves

Envelope-only fails one test; goals-only fails four. Each was run red on purpose.

### 2.3 **`runMigrationsAndValidate` does not check index names**

Renaming `index_goal_profile_id` in the migration left all twenty round-trip tests green on Room
2.7.1. An upgraded installation would then carry a differently-named index from a fresh one, for
ever, and nothing would say so.

A doc comment I had just written claimed Room caught this. It is corrected, and `migrate19To20` now
asserts the index names against `sqlite_master` itself — verified red with the wrong name and green
with the right one. **That assertion, not Room, is what makes the migration correct.**

This is the third instance in this repository of a gate that reads as present and checks nothing
(see the governance audit and issue 6.7's finding). The habit that caught it is the one worth
keeping: never write "this is enforced by X" without making X fail once.

### 2.4 Two defects only the running app could show

Every test passed, and the goal card still said the wrong thing twice:

- the card's button read **"Save"** — the editor's string, reused — so a list of goals showed a
  column of Save buttons that saved nothing;
- a fully-funded goal read **"At ₹0.00 a month you get there 2026-08-30"**. The engine dates an
  already-funded goal today, which is *true*; the sentence built from it was absurd.

Both are wording, and no assertion about a figure could have caught either. The engine was right in
both cases, so the fixes are in the screen. Each has a regression test now. This is what §9's "the
app must be run and observed" is for.

### 2.5 The Paparazzi gate did its job

Adding the dashboard button turned the baseline stale and the build went red — the same gate issue
6.5 found had been doing nothing. Re-recorded; the diff is that one button.

---

## 3 · Flow changed this session

New — `FLOW.md` §2.5:

```
GoalsScreen → GoalsViewModel → GoalRepository.observeGoals()
└─ clock.today() READ ONCE PER EMISSION (TIM-001), a corrupt stored date DROPS one goal (P-04)
    └─ GoalEngine.plan(...)
        remaining       = max(0, target − saved)
        monthsRemaining = max(0, MONTHS.between(today, targetDate))
        requiredMonthly = remaining.split(max(1, months)).max()
        etaIsoDate      = today + ceil(remaining ÷ planned), or null
        horizon         = RULE-HORIZON.bucketFor(months)
        status          = NO_TARGET → OVER_FUNDED → PAST_DUE → ON_TRACK → BEHIND
        provenance.evidence = [RuleCitation("RULE-HORIZON","1.0")]  ← required by the type

SafeToSpendRepository.observeSafeToSpend()
└─ combine(combine(5 terms) → MonthTerms, goals.observeGoals())
    goalContributionsRemaining = maxOf(envelope, goals' required monthly)
```

The nesting exists because `combine`'s typed overloads stop at five and this now needs six sources;
the array form would trade every parameter's type for an `Array<Any?>` and a cast per term, in the
one function where a transposed term is a wrong headline figure nobody would notice.

---

## 4 · Code changed this session

| Path | What it does now |
|---|---|
| `settings.gradle.kts` | **New.** `:domain:engines:goals`, `:feature:goals` |
| `domain/engines/goals/**` | **New.** `GoalEngine` + input/result + factory, `DefaultGoalEngine`, `GoalRules` (mirrors `RULE-HORIZON`), `ENGINE.md`, four test files + golden resource |
| `core/database/.../Entities.kt`, `Daos.kt`, `Migrations.kt`, `CfoDatabase.kt` | `goal` table, DAO, `MIGRATION_19_20`, `VERSION = 20`; `Migrations`' `TooManyFunctions` suppression documented in its KDoc |
| `core/database/schemas/.../20.json`, `MigrationRoundTripTest.kt` | The fixture, and a round-trip that **asserts the index names Room does not check** |
| `data/repository/.../GoalRepository.kt`, `RepositoryFactory.kt` | **New.** Entities in, projections out; nothing derived is stored |
| `data/repository/.../SafeToSpendRepository.kt` | The real goal term, as `maxOf`; the five prior terms extracted into `MonthTerms` so the flows can nest |
| `app/.../di/GoalEngineModule.kt` | **New.** Epic 7's engines — `EngineModule` is at detekt's ceiling, and the cut is on a real seam: Epic 6 describes what the user *has*, Epic 7 what they are *reaching for* |
| `app/.../di/RepositoryModule.kt`, `navigation/CfoRoute.kt`, `CfoNavHost.kt` | The goals binding, the typed route, the destination |
| `feature/goals/**` | **New.** UiState/Event, ViewModel, screen, editor, labels, strings, 24 tests |
| `feature/dashboard/**` | A "Your goals" button, its string, and a re-recorded screenshot baseline |
| `.gitignore` | Issue 6.7's dev-TLS rules carried onto this branch — without them a `git add -A` here sweeps in two PKCS#12 keystores, which it did once |
| `docs/adr/0033-*.md`, `docs/adr/0021-*.md`, `DECISIONS.md`, `FLOW.md`, `CHANGELOG.md`, `VERSION`, `ai/orchestrator/engine-registry.yaml` | The records |

---

## 5 · Quiz — what a reader should be able to answer

1. **Why does 7.1 ship a screen when its acceptance criteria are engine-only?**
   Because an engine nobody can put a goal into cannot be exercised, and that is the bug 6.7 found in
   6.5 — every layer shipped, the editor field did not, and no test noticed because the test fake
   dropped the field too.
2. **Why is the required monthly the *largest* of the instalments rather than the average?**
   `Money.split` gives the odd paise to the earliest parts. Quoting the smallest leaves the goal a
   few paise short.
3. **A goal sixteen days from its date reports zero months remaining. Why is that right?**
   The count is of contributions the user can still make, not of elapsed time. Zero means the whole
   remainder is due now — conservative in the direction that does not end a month short.
4. **Why did 7.1 mint no rulebook row, when every other engine has one?**
   `RULE-HORIZON` already named this engine, and everything else is arithmetic — "on track" is an
   exact comparison, not a threshold. Minting one would bump `_meta.version` and force six unrelated
   mirrors to restate it.
5. **ADR-0021 said to replace the Safe-to-Spend goal term. Why is it a `maxOf` instead?**
   Replacing it makes the headline figure jump upwards for every user without a goal, and fails four
   tests — two of which predate the issue. The suite already knew.
6. **What does `runMigrationsAndValidate` not check, and how do you know?**
   Index names. A wrong one was inserted on purpose and all twenty round-trip tests stayed green.
