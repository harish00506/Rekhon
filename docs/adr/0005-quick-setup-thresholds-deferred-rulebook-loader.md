# ADR-0005 — Quick-setup thresholds are typed Kotlin defaults, not `ai/` rows (for now)

- **Status:** accepted, with a named trigger to revisit
- **Date:** 2026-07-27
- **Deciders:** Harish G (solo), implementing issue 2.3
- **Refs:** CLAUDE.md §6, `00-issue-workflow.md` step 5, SRS §29 (AI-ARC-006), `ai/README.md`

## Context

CLAUDE.md §6 is unambiguous:

> The rulebook, classification, market signals, and tax parameters are **data rows** in `ai/`, not
> hardcoded logic. […] **never** hardcode a financial number in an engine.

`QuickSetupEngine` (issue 2.3) is the **first engine in this project**, and it needs five numbers:
the 50/30/20 bands, the metro flex ceiling, the emergency runway, its clamp, and the two obligation
thresholds. Every one of them already exists as a row in `ai/rules/rules-kb.json` — `RULE-50-30-20`,
`RULE-EMERG-FIRST`, `RULE-RUNWAY-M`, `RULE-EMI-40`.

The obstacle is that **nothing in the app loads `ai/` at all.** There is no asset packaging step, no
JSON reader, no `rules_knowledge_base` table (§29.1 describes one; no issue has built it), and no
`RuleEngine.evaluate(ruleId, snapshot)` seam. Honouring §6 literally inside issue 2.3 would mean
first building:

- a Gradle step copying `ai/rules/*.json` into app assets (and keeping one source of truth),
- a parser and a `Rulebook` type — but `:domain:*` is pure Kotlin with no serialisation dependency
  (ARC-002), so the loader belongs in a new `:core:rules` or `:data:*` module,
- a Hilt binding, an error path for a malformed rulebook, and tests for all of it.

That is a larger, more consequential change than the feature it would serve, and it would be
designed against exactly one consumer. §29 also says thresholds are **user-editable at runtime**,
which implies the loader's real shape is a database table plus an override layer — a design worth
doing once, deliberately, rather than as a side effect of onboarding.

## Decision

**Ship the thresholds as a typed `QuickSetupRules` data class**, one field per rulebook parameter,
each documented with the `rule_id` and `version` it was copied from, and **injected** into the
engine rather than read from it — so the eventual loader replaces the argument and nothing in the
engine changes.

**The duplication is guarded by a test, not by discipline.** `RulebookDriftTest` reads
`ai/rules/rules-kb.json` from the repository, parses out each cited rule, and asserts every
threshold *and every version string* still matches. Edit a number in the rulebook and the build goes
red until the engine agrees.

That test was **verified to fail before being trusted**: changing `needsPctMax` from 50 to 51 was
confirmed to turn `the budget split matches RULE-50-30-20` red. This project has already shipped one
gate that passed vacuously (governance audit G-01, the 0%-coverage `koverVerify`), so a guard is not
counted as a guard here until it has been seen to bite.

The citations the engine puts in `EngineProvenance.evidence` are the **real** rule ids and versions,
so P-02's "show the rule that fired" and AI-ARC-006's reproducibility hold today regardless of where
the numbers are read from.

## Trigger to revisit

Build the loader at **whichever of these comes first**:

1. **A user-editable threshold is required** — §29's governance clause (change recorded in
   `audit_log` with who/when/why) cannot be satisfied by a Kotlin constant at all. Realistically
   issue 4.4 (budgets) or 9.4 (financial health score).
2. **A second engine needs the same rules.** One engine copying four rows is a duplicate; two
   engines copying overlapping rows is a synchronisation problem, and the drift test only checks
   engine-against-rulebook, not engine-against-engine.
3. **Any rule the engine cites gains a version bump.** The drift test forces the change to be
   noticed; that is the moment to ask whether copying is still the right answer.

Until then, `RulebookDriftTest` is what makes this deferral safe.

## Consequences

**Good.** Issue 2.3 ships without inventing an architecture for a subsystem it is not the right
place to design. The numbers stay attributable — every one names its rule and version in code, in
the plan's evidence, and in the persisted `budget.rule_id` / `rule_version` columns. The seam for
the loader (`QuickSetupRules` as an injected argument) already exists.

**Bad.** CLAUDE.md §6 is, strictly, violated: there are financial numbers in an engine. Anyone
grepping for compliance will find them, which is why this record exists and why the class doc points
straight at it. The rulebook is also not yet the *source* of truth at runtime — it is the source of
truth for a test, which is weaker.

**Neutral.** `ai/rules/rules-kb.json` gains its first automated consumer, even if that consumer is a
test. The regex parsing in `RulebookDriftTest` is deliberately strict and fails loudly if the
rulebook's shape changes, which is itself a useful signal about a file nothing else reads yet.

## Alternatives considered

**Build the loader now.** Rejected on scope: it is a new module, an asset pipeline and a parser,
designed against one consumer, in an issue whose acceptance criteria say nothing about it. It would
also very likely be redesigned when §29's user-editable-threshold requirement lands.

**Hardcode without the drift test.** Rejected outright — that is the version of this decision that
rots. The whole basis for accepting the deferral is that divergence is impossible to do quietly.

**Put the numbers in the SRS-cited comments only, with no typed defaults.** Rejected: it makes the
values unoverridable, so a test could not move a threshold and assert the engine follows — which is
how `a runway outside the clamp is clamped and the clamping rule is cited` is tested today.
