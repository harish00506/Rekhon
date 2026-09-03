# ADR-0035 — The waterfall substitutes an observed P50, mirrors nothing, and stores only the order

**Status:** Accepted · **Date:** 2026-09-03 · **Issue:** 7.3 — Goal feasibility & waterfall
**Supersedes:** nothing · **Related:** [ADR-0017](0017-budget-thresholds-stay-a-typed-mirror.md),
[ADR-0021](0021-safe-to-spend-buffer-and-goal-stand-in.md),
[ADR-0033](0033-goals-mint-no-rulebook-row.md),
[ADR-0034](0034-emergency-fund-mints-two-rows-and-defers-three-terms.md)

---

## Context

SRS §15.1 defines goal feasibility in one line:

```
feasibility: Σ requiredMonthly(all active goals) ≤ P50 forecast surplus
  if infeasible → gap analysis + 3 levers per goal (date / amount / contribution)
• Priority waterfall: Emergency Fund → high-priority goals → others; monthly surplus
  allocation suggestion follows the waterfall and is shown as a draggable plan.
```

Three things about the codebase this landed in shaped every decision below.

**The declared dependency does not exist.** `docs/issues/7.3-*.md` lists 9.2 (cash-flow forecast,
AI-FCT) as a hard dependency. `:domain:engines:forecast` contains exactly two files, both the
`ModulePlaceholder` scaffolded by issue 1.1 in commit `6d5a54f`, and `CHANGELOG.md` has no Epic 9
entry at all. There is no forecast, and therefore no P50 forecast surplus.

**`RULE-EMERG-FIRST` has been in the rulebook since day one with no reader.** `severity: fail`, its
`name` literally contains the word "waterfall", and its `consumed_by` already listed `AI-GOAL`. This
is the same shape as `RULE-HORIZON`, which named the goals engine before the goals engine existed.

**Nothing in this app stores a goal priority.** A waterfall pours in order, and `GoalEntity` had no
column that says which order.

---

## Decision

### 1. The surplus is the P50 of *observed* surplus, named as such on every result

`GoalWaterfallRepository` computes the median of `income − (needs + wants)` across the closed months
of `RULE-EMF-MULT`'s six-month window, falls back to the onboarding INVEST envelope below three
observed months, and reports `null` when neither exists. `SurplusBasis` — `OBSERVED_MEDIAN`,
`DECLARED_ENVELOPE`, `NONE` — travels on the result, and the screen renders it in words: *"₹30,000
spare a month — the middle of your last 6 closed months."*

It is a genuine P50. What differs from §15.1 is the population: what happened, not what is projected.
Saying so on the result rather than in a comment is what keeps it a substitution rather than a quiet
lie (P-02).

**When 9.2 lands:** a fourth `SurplusBasis` value, and one branch in the repository. The engine does
not change, because the substitution lives entirely on the far side of `GoalWaterfallEngine`'s
interface — which is the reason the surplus is an input rather than something the engine resolves.

### 2. Nothing is minted, and the gate's threshold is not mirrored twice

Issue 7.3 adds no row to `ai/rules/rules-kb.json`. `_meta.version` stays at **1.15.0** and the six
typed mirrors elsewhere in the repository are untouched. The waterfall is `min(remaining, required)`
in a fold and one exact comparison; the gate is a row that already exists; the ordering is a user
preference, not a threshold. The history window and the observed-months floor are **borrowed** from
`RULE-EMF-MULT` rather than minted again — same ledger read, same shape of question.

The sharper half: **`GoalWaterfallEngine` holds `RULE-EMERG-FIRST`'s citation but not its number.**
`QuickSetupRules.emergencyRunwayMonths` has mirrored `min_runway_months` since issue 2.3. ADR-0017's
second trigger says that when a *second* engine needs the same rule row, "that is the point at which
the drift tests stop being sufficient" and the runtime rulebook loader should be built instead.

That trigger is real and building the loader would swamp this issue — so the design avoids creating
the second mirror at all. The threshold reaches the engine as
`GoalWaterfallInput.emergencyGateMonths`, resolved by the repository from the mirror that already
exists; `GoalRules.EMERGENCY_FIRST` carries only the row's version, for provenance.

**A citation is not a mirror.** Nothing in `:domain:engines:goals` can drift from the rulebook's
number, because nothing in it holds the number. This is the distinction a reviewer should press on,
so it is guarded mechanically rather than by assertion: `RulebookDriftTest` asserts `GoalRules`'
**instance fields** are exactly `{shortYearsMax, hybridYearsMax}` and never grow, while leaving the
companion's static citations free to. Adding `minRunwayMonths` here fails the build with a message
naming this ADR.

The loader is still deferred, and the trigger list is unchanged. If a third consumer needs this row's
*value*, build it.

### 3. `RULE-EMERG-FIRST` is cited whichever way the gate goes

Issue 7.2 established that a rule which did not fire should not be cited — citing `RULE-RUNWAY-M`'s
clamp when it did not bind would tell the user a threshold shaped their number when it did not.

The waterfall departs from that, deliberately. A clamp that does not bind changed nothing. A **gate**
is evaluated every time, and *both* of its outcomes decide whether goals are funded at all; passing a
gate is an outcome of the gate. `emergencyFirstApplied` on the result says which way it went, which
is a clearer signal than the presence or absence of a citation. `RULE-HORIZON` is not cited by the
waterfall, because the waterfall applies no horizon.

### 4. An unknown surplus is `UNKNOWN`; a zero surplus is `INFEASIBLE`

`Feasibility` has three values, not two. Zero is a finding — "this month has no room" — and the
levers on each line are exactly what the user needs to see about it. Missing data is not a finding.
Collapsing the two would tell a user one month into the app that every goal they own is impossible:
issue 7.2's zero-target mistake in another costume, where `liquidFunds >= target` congratulated
somebody with nothing saved.

An unknown **runway** goes the other way and holds the gate: with no evidence the buffer exists,
funding a holiday ahead of it is the expensive direction to be wrong in.

### 5. The order is one defaulted column, and it is the only thing stored

`goal.sort_order INTEGER NOT NULL DEFAULT 0` at schema 21, with both `GoalDao` list queries reading
`ORDER BY sort_order, target_date_iso, name`.

Zero means "never dragged". Every pre-existing row gets it, the tie-break is the old ordering, and an
upgraded profile therefore sees **exactly the list it saw yesterday** until the user moves something.
A migration that silently reshuffled somebody's goals would be a data change wearing a schema
change's clothes.

A nullable `priority` would say the same thing while forcing every read to decide what null meant. No
index: `sort_order` leads the `ORDER BY`, but a profile holds a handful of goals and the existing
`profile_id, deleted_at_utc_millis` index already covers the filter — the trade `MIGRATION_19_20`
made for `target_date_iso`.

Nothing else about the plan is stored. An allocation written to the database would outlive the
surplus that produced it and go stale because a month closed — the one input the user never edits.

---

## Alternatives considered

**Build a minimal forecast inside 7.3.** Rejected: it would pre-empt 9.2's design (whose registry
contract is `P10/P50/P90 per day, plus crunch days` — a different shape from a monthly surplus), and
9.2 would then have to reconcile with whatever 7.3 guessed. Its own accuracy and backtest gates would
have to be met by an issue that is not about forecasting.

**Reuse `SafeToSpendEngine` as the surplus.** Rejected, and it is the trap the next reader will fall
into. `RULE-STS` has `include_goal_contributions: true`, and `SafeToSpendRepository.kt` already feeds
it `maxOf(envelopes.savingsPlanned(), goalsRequired)` from `GoalPlan.totalRequiredMonthly`.
Safe-to-Spend is the surplus **net of** goals; using it here would double-count them and make the
answer depend on itself.

**Subtract `invested` from the surplus as well as `needs` and `wants`.** Rejected: investing *is*
goal funding. Netting it out would hide the very money the plan allocates and tell the user to find a
surplus they had already found.

**Build the runtime rulebook loader now, as ADR-0017 trigger 2 asks.** Deferred, by avoiding the
trigger rather than ignoring it — see decision 2. Worth restating plainly: the trigger is still armed
for the next issue that needs this row's value.

**Mint `RULE-GOAL-FEAS` with a tolerance band.** Rejected on ADR-0033's precedent. §15.1 states an
exact comparison; a slack percentage would hide a gap the user could act on, and minting it would
bump `_meta.version` and force six unrelated mirrors to restate it — churn to buy a band nobody asked
for.

**A new `:domain:engines:goalplan` module.** Rejected: `engine-registry.yaml` already pre-committed
the contract as `"goals -> {...}; feasibility in 7.3"` under `AI-GOAL`, and `AI-FOO` is separately
registered to `:domain:engines:orchestrator` for issue 7.5. A second interface in the existing module
reuses `GoalProjection` with no new Gradle wiring and no engine-to-engine dependency.

**Drag only, with no keyboard or screen-reader path.** Rejected: a long-press drag is unusable with
TalkBack and with a switch device, and the Definition of Done's accessibility scan would be right to
fail it. Every card carries "Move up" / "Move down" as semantic custom actions, and the card merges
its descendants so those actions land on the goal they move. The Compose test drives the actions
rather than the gesture — which means it proves the accessible path works, rather than proving a
mouse can do it.

**A reorderable Compose library.** Rejected: none is in `gradle/libs.versions.toml`, adding one needs
a `DECISIONS.md` row naming what it was chosen over, and the list is a plain `Column` of a handful of
cards with no virtualisation or autoscroll to fight. Roughly forty lines of
`detectDragGesturesAfterLongPress` does it.

---

## Consequences

- **The feasibility verdict is only as good as the history behind it.** Below three closed months it
  rests on a figure the user typed at onboarding and never revisits; below that it says so rather
  than guessing. Both states are visible on the card.
- **Issue 9.2 inherits a named seam.** `SurplusBasis` is the extension point, and
  `GoalWaterfallRepository.surplusFrom` is the one function that changes.
- **ADR-0017's trigger 2 stays armed.** This issue routed around it; the next consumer of
  `RULE-EMERG-FIRST`'s value should build the loader.
- **Schema is v21.** Every future migration test starts from there.
- **`RULE-EMERG-FIRST` finally has a reader**, after being enforced by nothing since the rulebook was
  written. Its `severity: fail` is now load-bearing, and `RulebookDriftTest` asserts it has not been
  softened to `warn` — which would leave the engine's hard gate implementing a rule the data no
  longer states.
