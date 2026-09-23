# ADR-0048 — One guardrail engine: an allowlist of renderings, rounding that stops at the rupee, and the minimal subset retired

- **Status:** accepted
- **Date:** 2026-09-23
- **Deciders:** Harish G (solo)
- **SRS refs:** AI-ARC-004, AI-ARC-003, P-02, P-03, P-08, §19.1, §19.3 (CHT-001), MNY-001/002,
  TIM-002, CLAUDE.md §6; `ai/chat/guardrail.md` (GRD-001 … GRD-006); issue 9.7. Builds on
  ADR-0017 (typed mirrors), ADR-0019 (which wrote down the subset's limits)

## Context

AI-ARC-004: LLM output must pass a rules-engine guardrail before display; numeric claims are
verified against engine results, and unverifiable numbers are stripped and the response regenerated
or refused. `ai/chat/guardrail.md` specifies it: extract every claim, resolve each against
`tool_results ∪ engine_results` through approved display transforms, classify, then PASS,
REGENERATE (up to N = 2) or REFUSE, and log the decision without amounts.

Issue 4.5 needed a guardrail for a budget notification long before any of this, and built the
honest minimum: `NumericGuardrail` in `:core:model`, matching rupee amounts, percentages and
one-to-three-digit counts against a caller-supplied list. Its own documentation names what it does
not do — dates, scores, lakh/crore, four-digit numbers, and the regenerate/refuse ladder — and says
issue 9.7 owns the rest.

There is still no LLM in the app. The chat layer is epic 10. So this issue builds the gate that
epic will need, and moves the three senders that exist today (budget, card and insight
notifications) onto it.

## Decision

**1. One engine, `:domain:engines:guardrail` (AI-GRD 1.0, L3), and the subset is retired.**
- The full gate supersedes `:core:model`'s `NumericGuardrail`, which is **deleted** along with its
  18 tests; every case they covered is represented in the new suite, and the three notifiers now
  take the engine by constructor injection (ARC-003) instead of reaching for an object.
- Two extractors would have been the alternative, and they would have drifted. This repository has
  a rule for a reason: one question, one answer.
- The engine is pure Kotlin with no clock and no I/O (ARC-002, P-08), and it never consults a model
  about a model's output (GRD-001).

**2. Verification is an allowlist of renderings, not a tolerance.**
A claim resolves when it *equals* one of the strings an evidence value may legitimately be written
as. The permitted transforms are `RULE-GRD-TRANSFORMS`:
- the app's own money formatting, taken from `MoneyFormatter` itself so the allowlist cannot drift
  from the screen;
- display rounding, to at most two decimals;
- Indian lakh and crore wording;
- paise, for an amount spelled in minor units;
- basis points shown as a percentage (MNY-002);
- an engine date as ISO, a localised day, a month with its year, or that year alone (GRD-004).

Because it is an allowlist, **arithmetic fails by construction** (GRD-003): the product of two
evidence values is not a rendering of either, so "₹500 × 12 = ₹6,000" cannot pass unless an engine
published ₹6,000.

**3. Rounding stops at the rupee.**
An amount may be shown to the rupee or finer — ₹1,23,456.78 as "₹1,23,457" hides less than a rupee
and reads better. It may **not** be rounded at lakh or crore granularity: "₹2 lakh" for ₹1.5 lakh is
a third of the figure. So lakh and crore wording is offered only where it is **exact**, and
truncation is never a rendering: "₹1,23,456" for ₹1,23,456.78 is a different, smaller number.

**4. Grouping is not normalised; whitespace and case are.**
"₹ 1,000.00" is the same claim as "₹1,000.00", and a gate that refused the first is one a team
learns to route around. But "₹123,456.78" is Western grouping, which this app never produces, so it
is not a rendering of anything.

**5. The ladder is data, and the engine hands back spans rather than a sentence.**
`RULE-GRD-LADDER` holds `max_attempts` (2, the contract's own N) and `refuse_on_exhaustion`. A
verdict is `Pass`, `Regenerate(unverifiable, verified, attemptsLeft)` or `Refuse(unverifiable,
verified)`. The engine writes no English: what to say to the model, and what fallback to show the
user (GRD-005), belong to the chat layer, whose words live in `strings.xml` (§21.6). Every verdict
carries the verified claims, because GRD-005 says a fallback may repeat those and nothing else.

**6. Names are declared, and that hole is stated.**
A user's category may be "Zone 3 parking", so callers pass their own interpolated names as
`evidence.names` and those are struck out before any digit is read. Whatever a caller puts there is
not checked, so it must only ever be the verbatim value it interpolated — never a fragment chosen
to make a failing check pass. Each struck span is replaced by **spaces**, not removed, so offsets
survive and two separated digit runs are never welded into a number neither claim contained.

**7. Scores are integers.** §19's "scores (0..1000)" needs no category of its own: a caller passes
the score as a count. Saying so is cheaper than a field that means the same thing.

## What this changes for the figures already on screen

The three notifiers verify the same figures as before, through a wider gate. Two differences are
worth naming:
- a **four-digit** bare number is now checked, which `NumericGuardrail` could not do because a year
  and a count were indistinguishable to it; dates are extracted first now;
- **lakh/crore wording and rounded amounts now pass**, where the subset refused them. Copy that
  reads better is no longer blocked, and the figure behind it is still the engine's.

## Deferred, and why

- **The `convert_currency` FX transform** named in GRD-002. No such tool exists, and a transform
  whose input cannot be produced is a hole rather than a feature.
- **Regeneration itself.** The engine says "ask again"; nothing asks, because there is no model yet
  (epic 10). The ladder is built and tested so the chat layer inherits a decided policy instead of
  inventing one.
- **The refusal fallback's words** (GRD-005) and **the audit-log event** for a refusal. The words
  belong with the chat UI; the audit row belongs with the chat session that produced it, and writing
  one from a pure engine would break ARC-005. What the engine guarantees today is that a refusal is
  silence, not a warning label — and the app logs nothing, since a span is an amount (§21.6).
- **PERSONAL_MODE verdict language** (GRD-006). It concerns the market advisor's wording, which
  does not exist yet; the numeric half of GRD-006 is exactly what this engine already does.

## Consequences

- No figure reaches a user that an engine did not produce, and the rule holds for the notifications
  that exist today as well as for the chat that does not.
- The gate's settings are a data edit. Disallowing lakh/crore wording, or tightening the app to zero
  regeneration attempts, is a rulebook change with a drift test to catch a mirror that lags.
- A false refusal is now the likelier failure than a false pass, which is the correct direction: the
  property tests pin both — anything the app itself formatted always survives, and anything no
  engine produced never does.
- Writing the oracle first paid for itself: it caught the extractor swallowing the sentence comma in
  "₹1,23,457, which is…", which would have refused the app's own correct text.
