# 2026-09-03 — Issue 7.3: goal feasibility and the contribution waterfall

The user asked to "work on 7.3 with rules". Three scope calls were put to them before any code was
written; two came back as the recommended option and **one did not** — the UI question, where they
chose the deeper answer. That third choice is why this session includes a schema bump.

Before either: **7.1 and 7.2 were both still unmerged.** `dev` had neither `:domain:engines:goals`
nor `:domain:engines:emergencyfund`, and all twelve commits sat on 7.2's branch. Last session
predicted this ("7.1 must merge first") and it had not happened. The user chose to land both on `dev`
before starting, which is what the branch model says and what nobody had done for two issues running.
`gh` is not installed on this machine, so the two merges were done locally with `--no-ff` after
`./gradlew unitTests` went green on the merged tree, and `dev` was pushed.

---

## 1 · Decisions this session

### 1.1 The declared dependency does not exist, and that is the whole shape of the issue

`docs/issues/7.3-*.md` names 9.2 (cash-flow forecast, AI-FCT) as a hard dependency, and acceptance
criterion 1 reads "Feasibility uses the cash-flow forecast (9.2)". SRS §15.1 is equally specific:
`Σ requiredMonthly(all active goals) ≤ P50 forecast surplus`.

**`:domain:engines:forecast` contains two files, both `ModulePlaceholder`, both from issue 1.1's
skeleton commit `6d5a54f`.** There is no Epic 9 entry in `CHANGELOG.md` at all. The tracker for 9.2
still reads "Todo".

Three ways forward, and the tempting two were both wrong. Building a minimal forecast inside 7.3
would pre-empt 9.2's design — whose registry contract is `P10/P50/P90 per day, plus crunch days`, a
different shape from a monthly surplus — and 9.2 would then have to reconcile with whatever 7.3
guessed, while meeting accuracy and backtest gates that belong to it. Stopping to build 9.2 properly
is a large separate issue in an epic that is entirely unbuilt.

So: **apply what the app can measure, and name the substitution on the result.** The median of
`income − (needs + wants)` across the closed months of the ledger read 7.2 built. It is a genuine
P50; what differs is the population — what happened rather than what is projected. `SurplusBasis`
carries that onto every result and the card renders it in words, so the user is never shown a number
whose provenance the app is being coy about (P-02). The engine takes the surplus as an *input*, which
is what makes 9.2 a one-branch change in the repository rather than a rewrite.

This is ADR-0033/0034's pattern for the third time, and the shape is now familiar enough to name:
when the spec asks for an input this codebase does not have, the answer is not to invent it and not
to stop — it is to substitute something honest, say so on the result, and record what replaces it.

### 1.2 Safe-to-Spend is not the surplus, and it is the trap the next reader will fall into

The obvious in-repo substitute is `SafeToSpendRepository`, and it would be a bug. `RULE-STS` has
`include_goal_contributions: true`, and `SafeToSpendRepository.kt:181` already feeds the engine
`maxOf(envelopes.savingsPlanned(), goalsRequired)` from `GoalPlan.totalRequiredMonthly`.

Safe-to-Spend is the surplus **net of** goals. Using it as the surplus *for* goals would
double-count them and, worse, make the answer depend on itself — a goal's required monthly would
reduce the surplus it is then measured against. It is written into the ADR, the repository's KDoc and
the ENGINE.md, because the mistake is invisible in every test that looks at one number.

`invested` is likewise not subtracted from the surplus. Investing *is* goal funding; netting it out
would hide the very money the plan allocates and tell the user to find a surplus they had found.

### 1.3 ADR-0017 trigger 2 was armed, and the design routed around it rather than ignoring it

`RULE-EMERG-FIRST` has been in the rulebook since day one — `severity: fail`, its `name` literally
containing "waterfall", `AI-GOAL` already in its `consumed_by` — and **nothing has ever read it.**
7.3 is its first consumer.

But `QuickSetupRules.emergencyRunwayMonths` has mirrored `min_runway_months` since issue 2.3, and
ADR-0017 says in its own words that a *second* mirror of a shared row "is the point at which the
drift tests stop being sufficient" and the runtime rulebook loader should be built instead. Building
that loader would swamp this issue.

So the second mirror was never created. The threshold reaches the engine as
`GoalWaterfallInput.emergencyGateMonths`, resolved by `GoalWaterfallRepository` from the mirror that
already exists; `GoalRules.EMERGENCY_FIRST` holds only the row's version, for provenance.

**A citation is not a mirror**, and that claim is the one a reviewer should press on — so it is
guarded mechanically rather than asserted. `RulebookDriftTest` reads `GoalRules`' *instance* fields
by reflection and requires them to be exactly `{shortYearsMax, hybridYearsMax}`, while leaving the
companion's statics free to grow. Adding `minRunwayMonths` to this module fails the build with a
message naming ADR-0035.

Reaching across to the quick-setup engine for the number looks odd for exactly one moment. The
alternative is a third copy of the number three, which is how thresholds drift.

### 1.4 Citing a gate that did not fire — a deliberate departure from 7.2

Last session's rule: cite only a rule that changed the answer, because "citing a rule that did not
fire survives every test that looks at amounts, and tells the user a threshold shaped their number
when it did not."

The waterfall cites `RULE-EMERG-FIRST` on **every** plan, including the ones where the gate let the
goals through. A clamp that does not bind genuinely changed nothing. A *gate* is different: it is
evaluated every time, and both of its outcomes decide whether goals are funded at all — passing a
gate is an outcome of the gate. `emergencyFirstApplied` on the result says which way it went, which
is a clearer signal than the presence or absence of a citation. `RULE-HORIZON` is **not** cited here,
because this engine applies no horizon; `GoalEngine` cites it on the projections that do.

### 1.5 Strict priority, which is why no rounding rule appears anywhere

`Money.allocate` is Hamilton largest-remainder and was the obvious tool. It is the wrong one: it
distributes proportionally, and a waterfall fills each claim in turn. `min(remaining, required)` in a
fold can neither create nor lose a paise, so **there is nothing to round** — the one money-shaped
engine in this repo with no rounding decision in it.

The invariant that makes that trustworthy sits on the type, not in a test:
`emergencyAllocated + Σ allocated + unallocated == max(surplus, 0)` in `GoalWaterfall.init`, copying
what `SafeToSpend` requires of its own lines. On the type means 7.5's order-of-operations engine is
held to it too, not only this engine's code path.

### 1.6 Three values of `Feasibility`, and the third is the point

A zero surplus is `INFEASIBLE`; an unknown surplus is `UNKNOWN`. Collapsing them would tell a user one
month into the app that every goal they own is impossible — 7.2's zero-target mistake in another
costume, where `liquidFunds >= target` congratulated somebody with nothing saved.

An unknown *runway* goes the other way and holds the gate: with no evidence the buffer exists,
funding a holiday ahead of it is the expensive direction to be wrong in.

### 1.7 The user chose the deeper UI, so there is a schema bump

The recommendation was a read-only plan. The user chose **drag-to-reorder**, which needs a stored
order: `goal.sort_order INTEGER NOT NULL DEFAULT 0` at schema 21.

Zero means "never dragged", and both list queries tie-break on `target_date_iso, name` — so an
upgraded profile sees exactly the list it saw yesterday until it moves something. The round-trip test
inserts two goals *against* their date order and asserts they come back in date order, because a
migration that silently reshuffled somebody's goals would be a data change wearing a schema change's
clothes.

**Drag alone would have failed the Definition of Done's accessibility scan, and rightly.** A
long-press drag is unusable with TalkBack and with a switch device. Every card carries "Move up" /
"Move down" as semantic custom actions, and the card merges its descendants so those actions land on
the goal they move rather than on a container nobody focuses. The Compose test drives the *actions*,
not the gesture — so it proves the accessible path works, rather than proving a mouse can do it.

Hand-rolled, not a library: there is no reorderable dependency in `libs.versions.toml`, adding one
needs a `DECISIONS.md` row naming what it was chosen over, and the list is a plain `Column` of a
handful of cards with no virtualisation or autoscroll to fight.

FOO-001's "cost of deviation" framing was **left out on purpose**. That is `AI-FOO`,
`:domain:engines:orchestrator`, issue 7.5. 7.3 shows the consequence in its own terms: which goals
fall below the surplus line in the order you chose.

---

## 2 · What proving the gates taught us

Three gates were deliberately broken, all three were real, and all three went red **without
`--rerun-tasks`** — which is the second confirmation that 7.2's `configureRulebookAsTestInput` fix is
holding, this time from an issue that did not write it.

1. **A mis-labelled golden lever.** `expect_levers` for the second goal changed from `6|…` to `7|…`;
   `GoalWaterfallGoldenTest` failed naming the record. Reverted.
2. **`RULE-EMERG-FIRST` softened from `fail` to `warn`.** `RulebookDriftTest` failed. This assertion
   was added because the engine implements the row as a hard gate — if the data ever says "warn", the
   code is implementing a rule the rulebook no longer states, and no other test would notice.
3. **`surplus_lookback_months` minted into the rulebook.** The new absence guard failed. It is the
   inverted tripwire ADR-0033/0034 established, now covering the four keys somebody would reach for
   first when adding a threshold to this engine.

One gate had to be **rewritten before it could be trusted**, and that is worth recording. The first
attempt at the mirror guard grepped this module's sources for the string `min_runway_months` — and
failed immediately, on its own documentation, because `GoalRules.EMERGENCY_FIRST`'s KDoc explains why
the parameter is *not* here. A guard that a comment can trip is a guard that will be suppressed. The
replacement asserts the mirror's **instance field list** by reflection instead, which is what a
mirror actually *is*, and which draws the citation/mirror line exactly where ADR-0035 draws it:
statics may grow, instance fields may not.

**One design change came out of a test rather than a review.** The Compose test for the reorder
action failed with `Key not present: CustomActions` — `onNodeWithText` was finding the `Text` inside
the card, not the card carrying the semantics. The fix was `mergeDescendants = true`, which is not a
test accommodation: a goal card with actions *should* be one accessibility node rather than eleven
unrelated text nodes with no actions between them. The test found a real accessibility defect by
being unable to reach the action the way TalkBack would.

---

## 2b · What running the app taught us — two defects no test could have caught

§9 says a green build does not close an issue, and this session is the third in a row where that
turned out to be load-bearing. Both defects below survived a full green `unitTests`, `koverVerify`,
`ktlintCheck`, `detekt` and `lintDebug`.

### 2b.1 The drag did nothing at all, silently

`DRAG_ROW_HEIGHT` was `96.dp` — chosen as "roughly a card's height" without measuring one. On the
emulator (440dpi, 2.75×) that is **264px**, and a goal card is roughly **900px**. Dragging a card up
by one position therefore computed `900 ÷ 264 ≈ 3` rows; with two goals the target index was `-2`,
which the ViewModel correctly ignores as out of range. **Nothing happened, and nothing said so.**

Every layer behaved exactly as designed, which is why nothing failed:

- the ViewModel is *right* to ignore an out-of-range index — that is what makes a drag released off
  the end of the list harmless;
- the Compose test drives the semantic custom actions rather than the gesture, deliberately, because
  that is what proves the accessible path works;
- and no unit test can know what a card measures on a device it never runs on.

The fix is to stop guessing: `Modifier.onSizeChanged` records the card's own height and the drag
divides by that. Still approximate — cards differ in height with how many levers they carry — but
approximate around the right number rather than around a number nobody checked.

**The general lesson is about the constant, not the arithmetic.** `96.dp` was a plausible-looking
figure with no source, in a file otherwise careful to say where every number came from. It is the
same class of mistake as a threshold in an engine: a magic number that looks like it works.

### 2b.2 A card asserted two opposite things, and explained neither

The laptop card read, on consecutive lines:

```
₹15,000.00 a month short of that
Fully covered by this plan
```

Both true. `GoalProjection.shortfallMonthly` (7.1) compares the required monthly against the
**monthly the user typed**; `GoalAllocation` (7.3) compares it against **what the surplus can
spare**. Before this issue there was only one comparison on the card and "short of that" was
unambiguous; adding a second made the old wording a contradiction *retroactively*, without changing
a line of 7.1's code.

`goals_shortfall` now reads "%1$s a month more than your own monthly plan" — naming which comparison
it is. A one-string fix, and P-02's own standard: a figure the user cannot attribute to an input is a
figure they cannot act on.

**Worth generalising:** adding a second measurement of the same quantity can invalidate the wording
of the first. Nothing in the build can notice that, because both strings are still correct in
isolation.

### 2b.3 What was confirmed working

| Checked on the device | Result |
|---|---|
| Two goals whose claims exceed the surplus | "Your goals need **₹6,000.00** a month more than you have spare" — exact |
| The surplus and its basis | "₹19,000.00 spare a month — what you said you'd save at setup". The demo profile has two closed months, so the **`DECLARED_ENVELOPE` fallback fired on its own**, unprompted |
| The three levers, on the goal that went short | "Push the date out by 18 months", "Aim at ₹48,000.00 instead", "Find ₹6,000.00 more a month" — all three match the hand-derived arithmetic |
| Drag to reorder (after the fix) | the laptop moved to the top, was fully covered, and the trip fell to the ₹4,000 left |
| The reorder invariant | the gap stayed ₹6,000 either way — the property test's "reordering moves money between goals but never invents any", confirmed on a real database |
| Cold start after the reorder | the order survived; `sort_order` persisted through schema 21 |
| Airplane mode | the whole screen still computes (P-04) |
| Dark mode | correct; the infeasible verdict and the shortfall use the negative token, the rest the surface tokens |
| The one goal that fits | "Fully covered by this plan", with no levers offered |

`RULE-EMERG-FIRST` firing was **not** reachable on the demo profile — its balances give a runway well
past three months, so the gate cleared, which is itself the branch that was exercised. The holding
branch is covered by `GoalWaterfallRepositoryTest`, the golden file and the flow test.

---

## 3 · Flow changed this session

New section `FLOW.md` §2.6 — still Shape A, but the first read in the app assembled from **four**
repositories, and the first write driven by a gesture:

```
GoalsScreen → GoalsViewModel.uiState
├─ GoalRepository.observeGoals()                     §2.5, unchanged
└─ GoalWaterfallRepository.observeWaterfall()
    └─ combine(goals, transactions.observeMonthlyLedger(6), emergencyFund, quickSetup)
        ├─ surplusFrom(history, envelopes) → OBSERVED_MEDIAN | DECLARED_ENVELOPE | NONE
        ├─ emergencyGateMonths = QuickSetupRules().emergencyRunwayMonths
        └─ GoalWaterfallEngine.allocate(...) → GoalWaterfall
```

```
GoalsEvent.MoveUp / MoveDown / MoveGoal
└─ GoalsViewModel.reorder(from, to)
    └─ GoalRepository.reorder(everyGoalIdInOrder)
        └─ withTransaction { goalDao.setSortOrder(id, index, now) }
```

Two flows into one `GoalsUiState`, not one combined flow: the goal list is a plain table read while
the plan needs six months of ledger, the emergency fund and the onboarding envelopes. Combining them
would let a problem resolving the surplus blank a list that is perfectly readable — and the list is
the half the user needs in order to fix anything.

---

## 4 · Code changed this session

| Path | What it does now |
|---|---|
| `core/database/.../entity/Entities.kt` | `GoalEntity.sortOrder`, defaulted to zero |
| `core/database/.../dao/Daos.kt` | both `GoalDao` list queries lead on `sort_order`; `setSortOrder` writes it |
| `core/database/.../migration/Migrations.kt` | `MIGRATION_20_21` — one `ADD COLUMN`, never a rebuild |
| `core/database/.../CfoDatabase.kt` | `VERSION = 21` |
| `core/database/schemas/…/21.json` | the exported fixture |
| `core/database/src/androidTest/.../MigrationRoundTripTest.kt` | 20→21, asserting the list order does **not** move |
| `domain/engines/goals/.../GoalWaterfallEngine.kt` | the interface, `GoalWaterfall`, `GoalAllocation`, `GoalLevers`, `Feasibility`, `SurplusBasis`, and the exact-sum invariant |
| `domain/engines/goals/.../DefaultGoalWaterfallEngine.kt` | the gate, the fold, the three levers |
| `domain/engines/goals/.../GoalRules.kt` | `EMERGENCY_FIRST` — a citation, deliberately not a mirror |
| `domain/engines/goals/src/test/.../GoalWaterfall{Golden,Engine,Property}Test.kt`, `WaterfallFixtures.kt` | 15 golden scenarios, the contract, 500 seeded cases per property |
| `domain/engines/goals/src/test/resources/golden/goal-waterfall.txt` | the competing-goals fixture the acceptance criterion asked for |
| `domain/engines/goals/src/test/.../RulebookDriftTest.kt` | `RULE-EMERG-FIRST`'s version, enabled flag, consumer and `severity: fail`; 7.3's absence guard; the instance-field mirror guard |
| `domain/engines/goals/ENGINE.md` | a second contract; the "No feasibility" paragraph deleted, being now false |
| `data/repository/.../GoalWaterfallRepository.kt` | the surplus substitution and the gate resolution |
| `data/repository/.../GoalRepository.kt` | `reorder()` — the whole list, one transaction, one clock stamp |
| `data/repository/.../RepositoryFactory.kt` | `goalWaterfall(...)` |
| `data/repository/src/test/.../GoalWaterfallRepositoryTest.kt` | the three bases, the negative median, the live-month exclusion, both sides of the gate, a reorder round-trip |
| `app/.../di/GoalEngineModule.kt`, `RepositoryModule.kt` | the engine and repository bound |
| `feature/goals/.../GoalWaterfallCard.kt` | the verdict, the surplus with its basis, the gate line, the levers |
| `feature/goals/.../GoalsScreen.kt` | the plan card, per-goal allocation, `Modifier.reorderable` (self-measuring — see §2b.1) and the two custom actions |
| `feature/goals/.../GoalsUiState.kt`, `GoalsViewModel.kt` | `waterfall`, the three move events, the reorder write |
| `feature/goals/src/main/res/values/strings.xml` | fifteen strings, the ICU plural for the date lever, and `goals_shortfall` reworded so two comparisons on one card no longer contradict each other (§2b.2) |
| `feature/goals/src/test/.../{GoalsViewModelTest,GoalsFlowTest,FakeGoalRepository,FakeGoalWaterfallRepository}.kt` | the plan states, the reorder, the levers, and the accessible move driven as TalkBack would |
| `ai/orchestrator/engine-registry.yaml` | `AI-GOAL.waterfall` registered; AI-GOAL's "feasibility in 7.3" note retired |
| `docs/adr/0035-*.md`, `DECISIONS.md`, `FLOW.md` | the records |
| `VERSION`, `CHANGELOG.md` | `0.7.3` |

**`ai/rules/rules-kb.json` is not in that table, and that is the decision.** No row minted,
`_meta.version` still 1.15.0, the six typed mirrors untouched.

---

## 5 · Quiz

1. §15.1 says feasibility is measured against the **P50 forecast surplus**. What does this app
   actually compare against, and where does a reader find out which of the two they are looking at?
2. `SafeToSpendRepository` already computes a monthly surplus. Why would using it here be a bug
   rather than a shortcut?
3. `RULE-EMERG-FIRST` is cited on plans where the gate did not fire, which contradicts the rule issue
   7.2 established. What distinguishes the two cases?
4. `GoalRules` gained `EMERGENCY_FIRST` but `RulebookDriftTest` does not assert
   `min_runway_months`. What does it assert instead, and which ADR would be violated if it did?
5. A goal is allocated ₹0.00. Name the four different situations that produce that figure, and what
   the card says differently about each.
6. The migration adds `sort_order` with a default of zero. What would break if the default were the
   row's insertion index instead?
7. Why does `GoalsViewModel` observe two flows rather than combining them into one?
8. The drag was broken and every test was green. Name the three separate design decisions that were
   each individually correct and together hid it.
9. Issue 7.1's `goals_shortfall` string became misleading without 7.1's code changing. What made it
   misleading, and what class of change should make you re-read wording you did not touch?
