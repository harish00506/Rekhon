<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: issue 9.7 — AI-GRD, AI-ARC-004's numeric guardrail, and the retirement of the minimal
        NumericGuardrail it supersedes.
  Result: a reader can see why verification is an allowlist rather than a tolerance, why rounding
          stops at the rupee, and what of `ai/chat/guardrail.md` waits for a model to exist.
  Changelog: 2026-09-23 — Created.
-->

# 2026-09-23 — The numeric guardrail (issue 9.7, ADR-0048)

**Branch:** `feature/9-7-guardrail-ai-arc-004` off `dev` (`82c18ee`)
**Versions:**
- **VERSION** 0.9.6 → **0.9.7**
- **versionCode** 42 → 43
- **rules-kb.json** 1.19.0 → **1.20.0**
- `AI-GRD` 1.0 (new) · schema unchanged at 24

---

## 1 · Decisions this session

The full argument for each is in ADR-0048.

- **One engine, and the subset is retired.** `:core:model`'s `NumericGuardrail` — built for issue
  4.5's budget alert, and explicit in its own documentation that 9.7 owned the rest — is deleted
  along with its 18 tests, every case of which is represented in the new suite. The three notifiers
  take `GuardrailEngine` by constructor injection instead of reaching for an object. Two extractors
  would have drifted, which is the failure this repository keeps writing rules against.
- **Verification is an allowlist of renderings, not a tolerance.** A claim must *equal* a string an
  evidence value may legitimately be written as. That is what separates "the same number, written
  for a reader" from "a new number", and it makes GRD-003 free: the product of two evidence values
  is not a rendering of either, so model arithmetic cannot pass.
- **Rounding stops at the rupee.** ₹1,23,456.78 may be shown as "₹1,23,457" — less than a rupee
  hidden, and it reads better. It may not be rounded at lakh granularity, because "₹2 lakh" for
  ₹1.5 lakh is a third of the figure. So lakh and crore wording verifies **only where it is
  exact**, and truncation is never a rendering.
- **Whitespace and case are normalised; grouping is not.** "₹ 1,000.00" is the same claim as
  "₹1,000.00", and a gate that refused it is one a team learns to route around. "₹123,456.78" is
  Western grouping, which this app never produces.
- **The ladder is data and the engine writes no English.** `RULE-GRD-LADDER` holds the contract's
  own N = 2. A verdict hands back spans; what to say to the model, and what fallback to show the
  user (GRD-005), belong to the chat layer, whose words live in `strings.xml`.
- **Scores are integers.** §19's "scores (0..1000)" needs no category of its own; a caller passes
  the score as a count.
- **Every verdict carries the verified claims**, because GRD-005 says a refusal's fallback may
  repeat those and nothing else.
- **Deferred:** the `convert_currency` FX transform (no such tool exists), the refusal's wording and
  its audit-log event (both belong with the chat session), and regeneration itself — the engine says
  "ask again", and nothing asks until epic 10 brings a model.

**What the users of the app can now say that they could not:** a rounded amount and lakh/crore
wording. And a four-digit bare number is now checked, which the old subset could not do — it stopped
at three digits because a year and a count were indistinguishable to it. Dates are extracted first
now, so the rest of the digits can be judged.

## 2 · Flow changed this session

```
BudgetAlertNotifier · CardAlertNotifier · InsightNotifier
└─ compose from engine fields only (P-03)
   → GuardrailEngine.verify(GuardrailInput(text, GuardrailEvidence(...), attemptsMade))
       ├─ strike out names (spaces, so offsets survive)
       ├─ extract  rupees → paise → percentages → dates → any remaining number
       ├─ resolve each against the allowed renderings   (RULE-GRD-TRANSFORMS)
       └─ Pass | Regenerate(spans, attemptsLeft) | Refuse   (RULE-GRD-LADDER)
   → Pass: notify(...)   · anything else: nothing posted, nothing logged
```

`FLOW.md` §2.12 holds the full chain.

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `ai/rules/rules-kb.json`, `rulebook.md` | 1.20.0: RULE-GRD-LADDER, RULE-GRD-TRANSFORMS |
| `ai/chat/guardrail.md` | records that AI-GRD implements it, and what is not implemented |
| `domain/engines/guardrail/` (new) | AI-GRD: the interface and verdicts, the claim extractor, the rendering allowlist, the mirror, `ENGINE.md`, and 43 tests with a golden file and its oracle |
| `core/model/NumericGuardrail.kt`, `NumericGuardrailTest.kt` | **deleted** — superseded (ADR-0048) |
| `app/.../notification/BudgetAlertNotifier.kt`, `CardAlertNotifier.kt`, `InsightNotifier.kt` | verify through the injected engine; the insight notifier hands over **dates**, not pre-rendered date strings |
| `app/.../di/RepositoryModule.kt`, `app/build.gradle.kts` | provides the engine; the module is on the graph |
| `app/src/test/.../InsightNotifierTest.kt` | a figure the engine did not produce reaches the phone as silence |
| `docs/adr/0048-…`, `DECISIONS.md`, `FLOW.md` §2.12, `ai/orchestrator/engine-registry.yaml` | the records |
