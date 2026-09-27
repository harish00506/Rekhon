# ADR-0054 — The guardrail is measured against a frozen set, and one of its two thresholds is absolute

- **Status:** accepted
- **Date:** 2026-09-27
- **Deciders:** Harish G (solo)
- **SRS refs:** §21.5 (AI evaluation, "regression thresholds block merges"), §7.1 (AI-ARC-004),
  §19.3 (CHT-001), §6 (data, not code), P-03, P-08; issue 10.6. Evaluates ADR-0048 (AI-GRD) as it
  is used by ADR-0053 (AI-CHAT).

## Context

Issue 9.7 built the numeric guardrail; issue 10.5 put it between a model and a user. Both are
covered by unit tests, and those tests were written by the same author as the code they check —
which is exactly the arrangement §21.5 distrusts for an AI component. "The guardrail works" is a
claim, and the SRS says a claim like that is measured against a frozen labelled set whose threshold
blocks merges.

## Decision

**1. The set is one frozen file, `ai/eval/guardrail-eval.json`, and it lives in `ai/`.**
Every other file in `ai/` is loaded by the app and this one is not — it is read by a test at build
time. It lives there anyway because it is AI-subsystem data that has to be reviewed and versioned
the way the rulebook is, and `_meta.note_test_only` says so in the file. `ai/README.md`'s index
carries the same note.

**2. Three kinds of case, and the adversarial ones are the point.**
*Honest* (a figure a tool returned, stated as the app formats it), *fabricated* (a number nobody
computed), and *adversarial* — the middle where a model does something that looks like language and
is actually arithmetic: adding two verified figures, subtracting one from another, rounding
"helpfully", negating, deriving a percentage, stating a count as rupees, or putting a figure inside
a refusal. Twenty-eight cases: eight honest, seven fabricated, thirteen adversarial.

**3. The two thresholds are asymmetric, and the asymmetry is the design.**
`fabricated_blocked_pct` is **100**, because "almost never states an invented figure" is not a claim
worth making — one leak is the failure this app cannot survive. `honest_answered_pct` is also 100
today, but for a different reason: a false block costs silence, which is bad and recoverable, so
that number has room to be argued down **in a commit** if a future guardrail change trades a false
block for a real catch. Both live in the file, not in the harness (§6).

**4. Cases run through `ChatEngine.compose`, not through AI-GRD directly.**
What is being evaluated is what reaches a user, which includes the chat layer's decision to drop a
blocked reply *and everything it carries*. Testing the guardrail alone would measure a component
the user never meets.

**5. Ids are permanent; a case is added, never edited.**
A frozen set that quietly changes is not a regression test. A duplicate id fails the harness, so a
row cannot be replaced under its own name, and a failure can be discussed in a commit by id.

**6. The gate has a name in CI.**
`./gradlew guardrailEval` runs it. It is inside `unitTests`, which is what actually blocks a merge;
the separate task and the separate CI step exist so that a failure reads as "the guardrail
regressed" rather than as one of four thousand anonymous tests.

**7. The harness parses JSON with `kotlinx-serialization-json`, test-only.**
The drift tests in this project read their files with regular expressions, deliberately. This set's
rows are nested objects and arrays, where regex would be the wrong tool. The library is already the
project's JSON choice, this is the runtime only (no compiler plugin, no `@Serializable` classes),
and it is `testImplementation` in one module. `DECISIONS.md` carries the row.

## What the first run found

Nineteen of nineteen blocked cases were blocked — including every adversarial one. **One "honest"
case was blocked too, and the guardrail was right.** The draft read *"Your lowest point in the next
90 days is ₹2,44,500.00, on 2026-10-05"*, and `90` is a claim: no tool had returned it. The
template sentence had hardcoded the horizon, exactly as the health sentence had hardcoded `/ 1000`
a day earlier — a defect that device run caught and this set would have caught first.

The fix is the one the design demands: the forecast tool publishes `horizonDays` from
`ForecastRules`, and the sentence slots it in like any other figure. The set gained **GE-A13**,
which freezes the failing shape — the same sentence with no window figure — so the bug cannot come
back quietly. `GE-H04` carries the figure the fixed tool returns.

Two bugs of one shape, found by two different gates, is a pattern rather than a coincidence: **any
number a template writes into a sentence is a claim, and a constant in a string resource has no
engine behind it.** Every remaining template slot was checked against its tool's figures.

## Deferred, and why

- **Datasets for the other AI components.** §21.5 also names categorisation (≥ 92%), receipt
  extraction (≥ 95%) and forecast backtests. Each needs labelled data of a different kind — real
  transactions, real receipts, real histories — and none of that can be invented here without
  measuring the invention rather than the model. Issue 12.2 owns them.
- **A model in the loop.** The set holds drafts, not prompts: it evaluates the *gate*, which is the
  deterministic half. When a neural model exists, a second set can evaluate what it tends to write —
  a different question, with a different shape of answer.
- **Per-case latency or cost.** Nothing in this pipeline is slow enough for that to be the thing
  worth measuring.

## Consequences

- Removing the guardrail's verdict, relaxing a threshold in the file, gutting the set, or reusing a
  case id all fail the build — each was tried.
- The adversarial cases are now the honest description of what AI-ARC-004 does and does not catch,
  in a form someone can read without reading the engine.
- The eval found a real defect in its first run, in a sentence that had passed every unit test and
  had shipped the day before.
