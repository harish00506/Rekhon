# ADR-0034 — The emergency fund mints two rows, defers three of §10.1's terms, and counts only cash

- **Status:** accepted
- **Date:** 2026-09-02
- **Deciders:** Harish G (solo), implementing issue 7.2
- **SRS refs:** §10.1 (AI-EMF), §15, §29 (AI-ARC-006), §8.3, MNY-001, MNY-002, TIM-001, P-02, P-03,
  P-07
- **Refs:** [ADR-0005](0005-quick-setup-thresholds-deferred-rulebook-loader.md),
  [ADR-0017](0017-budget-thresholds-stay-a-typed-mirror.md),
  [ADR-0019](0019-budget-alert-bands-mint-a-new-rule-row.md),
  [ADR-0033](0033-goals-mint-no-rulebook-row.md)

## Context

SRS §10.1 specifies `AI-EMF` precisely, and it is worth quoting because four separate decisions
below all come from reading it against what this codebase actually holds:

```
monthlyEssentials = FixedLoad + median(SemiFixed) + essentialShare(Variable)
multiplier M:  base 6
               income volatility (cv of monthly income): cv<0.10 → +0 | 0.10–0.30 → +1 | >0.30 → +3
               single-earner household with dependents → +1
               no health insurance flag                → +1
               job-stability self-assessment           → −1 / 0 / +1
               M clamped to [3, 12]
target        = monthlyEssentials × M
runwayMonths  = liquidFunds / monthlyEssentials
"Liquid funds" = savings + cash + FDs breakable without major penalty + liquid MF; the mapping of
account→liquidity tier is stored per account and user-editable.
```

Three things had been written down waiting for this engine: `RULE-RUNWAY-M`'s
`multiplier_source: "AI-EMF"`, `RULE-EMERG-FIRST`'s gate on a runway nothing produced, and
`insight-orchestrator.yaml`'s nightly `emergencyfund` entry. `QuickSetupEngine` meanwhile shipped a
**stand-in**: a flat three-month target sized off the onboarding envelope.

The problem is that **§10.1 asks for inputs this app does not have**, and there is more than one way
to be wrong about that.

## Decision

Four decisions, taken together because each constrains the next.

### 1. Two rulebook rows, and only the params the engine applies

`RULE-EMF-MULT` (`base_months`, the two cv edges, the two bumps, the lookback, the minimum months
observed) and `RULE-EMF-COACH` (`urgent_below_months`, `surplus_above_target_months`).

**Two rather than one**, on ADR-0019's and ADR-0025's precedent: *how deep should the fund be* and
*what should we say about where the user stands* are different questions with different severities,
and §10.1 states them in different paragraphs.

**Neither extends `RULE-RUNWAY-M`.** Its params are the clamp and the `multiplier_source` pointer,
both already correct; §10.1 puts the multiplier itself *inside* AI-EMF. So its version stays 1.0 and
ADR-0017's trigger 3 does not fire. This issue makes that row true for the first time without
changing a number in it.

`_meta.version` moves **1.14.0 → 1.15.0**, which forces five unrelated typed mirrors to restate it.
That churn is the cost of the row, and it was paid deliberately.

### 2. Three of §10.1's five multiplier terms are deferred, and their absence is asserted

The dependents, health-cover and job-stability bumps have **no field anywhere in this app** — not in
`cfo_settings.proto`, not on `profile`. Adding params for them would ship three numbers nothing
reads, and **a threshold nothing reads looks exactly like a threshold that works**.

So `RulebookDriftTest` asserts the *absence* of `dependents_bump`, `single_earner_bump`,
`no_health_cover_bump` and `job_stability_bump` from the rulebook — the same inverted guard ADR-0033
used when issue 7.1 minted nothing at all. The issue that adds the fields must add the params in the
same breath, with its own ADR.

**A consequence worth stating: with the shipped params the clamp never fires.** 6 base months plus a
maximum 3-month bump is 9, inside [3, 12]. Even with all three deferred terms the range would be
5..12, so the *floor* stays unreachable by §10.1's own arithmetic. The clamp is implemented and
tested against moved rules in `EmergencyFundEngineTest` — not in the golden file, because a golden
record that clamped would assert something the shipped rulebook never does. It is a guard against a
rulebook edit or a future user override, and it is honest to say so rather than to let a reader
assume it is load-bearing.

### 3. Liquid funds are `BANK` and `CASH` with a positive balance

§10.1 wants a per-account liquidity tier. There is no such column. Guessing one from `AccountType`
would decide **for every user at once** that every `INVESTMENT` row is a breakable FD, or that none
is — and an `INVESTMENT` account in this schema is an FD *or* an equity fund *or* a PPF lock-in.

So only the unambiguous types count. This **understates** the runway, which fails in the direction
that never tells someone they are safer than they are, and the assessment carries
`liquidAccountNames` so the screen can show exactly what was counted. The screen also says plainly
that deposits and investments are excluded, so a user with a fixed deposit is told why it is missing
rather than left to wonder.

The liquidity tier is a separate issue. It will also serve `RULE-IDLE-CASH`'s detector (§11.2), which
needs the same column.

### 4. Essentials are a median over closed months, with the envelope as a fallback

§8.3 already separates essential from discretionary at classification time, so §10.1's three terms
collapse into one query for `NEED`. The **median** is §10.1's own word for the semi-fixed term and it
is what survives one unusual month: an annual insurance premium in one month of six lifts a mean by a
sixth of the premium and lifts a median by nothing.

**The live month is excluded.** It is partly unspent by definition; including it would drag the
target down through every month and jump it back on the 1st — wrong in a way nobody reports as a bug,
because each individual reading looks reasonable.

**Below `min_months_observed` the quick-setup envelope is used**, labelled as such via
`EssentialsBasis`, and when neither exists the answer is `UNKNOWN` — **never a zero**. A zero target
is the dangerous answer: `liquidFunds >= target` would be true for a user with nothing saved, and the
screen would congratulate them.

`QuickSetupEngine`'s three-month stand-in is **left in place and superseded rather than removed**: it
runs at onboarding, before any history exists, and it is what fills the fallback this engine reads.

## Consequences

**Accepted.** CLAUDE.md §6 is, strictly, still violated — the rulebook is the source of truth for a
*test*, not for the running app (ADR-0005, ADR-0017). Two more mirrors now depend on that deferral.

**Accepted.** The runway is systematically low for anyone holding an FD or a liquid fund. The screen
says so; a user cannot mistake it for a complete picture.

**Accepted.** M is less personal than §10.1 intends until the three fields exist. The drill-down says
which term fired, so the figure is explainable even while it is incomplete (P-02).

**Discovered while proving this issue's gates, and fixed here:** `ai/rules/rules-kb.json` was not a
declared input to any Gradle task, so a threshold edit could leave **every** `RulebookDriftTest` in
the repository `UP-TO-DATE` and the build green. Found by bumping `_meta.version` and watching four
of five modules go red while `:domain:engines:goals:test` was skipped; `--rerun-tasks` then failed
it, which is how we know the assertion was fine and only its scheduling was wrong.
`CfoKotlinLibraryConventionPlugin.configureRulebookAsTestInput` now declares it.

This is the **fourth** gate in this repository found to read as present and check nothing — after the
governance audit, issue 6.7's finding, and issue 7.1's discovery that `runMigrationsAndValidate` does
not check index names. The habit that caught all four is the same: never write "this is enforced by
X" without making X fail once.

## Alternatives considered

**Mint one row instead of two.** Rejected on ADR-0019's precedent and on the merits: the coach bands
would then share a version with the sizing params, so retuning the urgent threshold would bump the
multiplier's version and invalidate every stored assessment that cited it (AI-ARC-006).

**Mint the three deferred params now, applied as zero.** Rejected: a param the engine reads and
always finds zero is indistinguishable from one that works, and the drift test could not tell the
difference either.

**Add the three profile fields in this issue.** Rejected as scope: three settings fields, a screen to
capture them, and a privacy review for each, bolted onto an engine issue. §10.1's own wording marks
the job-stability term "optional".

**Add the liquidity tier column in this issue.** Rejected for the same reason — a schema 20 → 21
migration, an account-editor field and a screenshot re-record, inside an issue whose acceptance
criteria are engine-only. It earns its own issue, and it has a second consumer waiting.

**Use the mean rather than the median.** Rejected: it is not what §10.1 says, and it is materially
worse — on the repository test's own fixture the mean is ₹56,666.67 against a median of ₹40,000, a
target 42% too high because of one month somebody replaced a fridge in.
