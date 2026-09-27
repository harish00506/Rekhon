<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: issue 10.6 — the frozen evaluation set that measures AI-ARC-004.
  Result: a reader can see why one of the two thresholds is absolute, and what the set caught on
          its first run — in a sentence that had passed every unit test and shipped the day before.
  Changelog: 2026-09-27 — Created.
-->

# 2026-09-27 — Measuring the guardrail (issue 10.6, ADR-0054)

**Branch:** `feature/10-6-chat-guardrail-eval` off `dev` (`8f8df0d`)
**Versions:**
- **VERSION** 0.10.4 → **0.10.5**
- **versionCode** 48 → 49
- **Schema** 28 → **28 (unchanged — an eval set stores nothing)**
- **guardrail-eval.json** 1.0 (new)

---

## 1 · Decisions this session

The full argument for each is in ADR-0054.

- **The set is frozen, lives in `ai/eval/`, and is the one file there the app does not load.** It is
  AI-subsystem data that has to be reviewed and versioned like the rulebook; a test reads it at
  build time. The file says so about itself, and `ai/README.md`'s index repeats it.
- **Three kinds of case, and the adversarial ones are the point.** Honest, fabricated, and the
  middle where a model does something that looks like language and is arithmetic: adding two
  verified figures, subtracting, rounding "helpfully", negating, deriving a percentage, stating a
  count as rupees, hiding a figure in a refusal. Twenty-eight cases — 8 / 7 / 13.
- **The two thresholds are asymmetric and that is the design.** `fabricated_blocked_pct` is **100**,
  because "almost never states an invented figure" is not a claim worth making. `honest_answered_pct`
  is the one with room: a false block costs silence, which is bad and recoverable, and lowering it
  has to be argued in the commit that does it. Both live in the file (§6).
- **Cases run through `ChatEngine.compose`, not AI-GRD directly.** What is evaluated is what reaches
  a user, which includes the chat layer dropping a blocked reply *and its figures*.
- **Ids are permanent.** A frozen set that quietly changes is not a regression test; a duplicate id
  fails the harness.
- **The gate has a name in CI** — `./gradlew guardrailEval`, inside `unitTests`, so a failure reads
  as "the guardrail regressed" rather than as one of four thousand anonymous tests.
- **One test-only dependency**, `kotlinx-serialization-json` in `:domain:engines:chat`, because the
  cases are nested objects where this project's usual regex file-reading would be the wrong tool.
  `DECISIONS.md` carries the row.
- **Deferred:** §21.5's other datasets (categorisation, receipts, forecast backtests) to issue 12.2,
  because they need real labelled data and inventing it would measure the invention; and a
  model-in-the-loop set, which is a different question.

**What the first run found.** Nineteen of nineteen blocked cases were blocked, including every
adversarial one. **And one "honest" case was blocked too — correctly.** The draft read *"Your lowest
point in the next 90 days is ₹2,44,500.00, on 2026-10-05"*, and `90` is a claim: no tool had
returned it. The template had hardcoded the horizon.

That is the **second** bug of exactly this shape in two days — the health sentence's `/ 1000` was
the first, caught by a device run. Two is a pattern: **any number a sentence contains is a claim,
and a constant in a string resource has no engine behind it.** So the forecast tool now publishes
`horizonDays` from `ForecastRules`, the sentence slots it in, GE-A13 freezes the failing shape so it
cannot come back, and every remaining template was checked — there are no bare numbers left in any
sentence the assistant can say.

Verified on the device afterwards: *"Your lowest point in the next 90 days is ₹2,44,500.00, on
2026-10-05."*

## 2 · Flow changed this session

No new call path. Two existing tool results gained a figure, because their sentences say a number
out loud:

```
get_forecast → AI-FCT   + horizonDays    (ForecastRules)
get_health_score → AI-FHS + scoreMax     (HealthRules, issue 10.5's device run)
```

`FLOW.md` §2.17 notes both, and that the path is now measured by the frozen set.

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `ai/eval/guardrail-eval.json` (new) | 28 frozen cases and the two thresholds |
| `ai/README.md` | the index row, and the note that this one file is not loaded by the app |
| `domain/engines/chat/src/test/**/GuardrailEvalTest.kt` (new) | the harness: 7 tests, the rates, the per-case check and the report |
| `domain/engines/chat/build.gradle.kts` | the eval set as a declared test input; the `guardrailEval` task; the test-only JSON dependency |
| `.github/workflows/ci.yml` | the named gate step |
| `data/repository/ChatRepository.kt` | the forecast tool publishes its window |
| `ml/llm/**` | the forecast sentence slots the window in, and is pluralised |
| `docs/adr/0054-…`, `DECISIONS.md`, `FLOW.md`, `CHANGELOG.md`, `docs/memory.md` | the records |
