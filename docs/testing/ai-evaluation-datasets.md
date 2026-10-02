<!--
  Why:  issue 12.2 — AC3 asks for extending a dataset to be documented, and AC1 for the datasets to
        be frozen and versioned with runners reporting accuracy against thresholds.
  What: the five frozen sets, their floors, how a run reports itself, and how to extend one.
  Result: someone adding cases knows what to bump and what not to do.
  Changelog: 2026-10-02 — Created for issue 12.2.
-->

# The frozen AI-evaluation datasets

Run them all, named, with `./gradlew aiEval`. They also run inside `unitTests`, which is what actually
blocks a merge; `aiEval` exists so a regression reads as *which dataset regressed* rather than as one
of five thousand anonymous test failures.

---

## 1 · The five sets

| Dataset | Floor (§21.5 / SRS) | Cases | Runner |
|---|---|---|---|
| `classification/.../eval/categorisation.txt` | accuracy **≥ 92%** (SRS §8) | 76 | `ClassificationEvalTest` |
| `receipt/.../eval/receipts.txt` | total-amount **≥ 95%**, field-complete **≥ 80%** (§18.1) | 46 | `ReceiptEvalTest` |
| `sms/.../eval/sms.txt` | amount **≥ 95%**, direction **≥ 95%** | 56 | `SmsEvalTest` |
| `forecast/.../backtest/ledgers.txt` | median 90-day spend error **≤ 1500 bps**, mean P10–P90 coverage **≥ 7000 bps** | 20 ledgers | `ForecastBacktestTest` |
| `ai/eval/guardrail-eval.json` | every fabricated or adversarial figure blocked (AI-ARC-004) | — | `GuardrailEvalTest` |

Each runner also asserts a **minimum dataset size**, so a set cannot be quietly shrunk to a handful of
easy cases to lift a score. That guard predates issue 12.2 and is the reason this issue did not have to
add one.

## 2 · Every run reports itself

Each runner prints one line per metric:

```
categorisation v1.0 — accuracy 96.3% (53/55), floor 92% — ok
receipts v1.0 — total-amount accuracy 100.0% (46/46), floor 95% — ok
receipts v1.0 — field-complete 95.6% (44/46), floor 80% — ok
sms v1.0 — amount accuracy 100.0% (36/36), floor 95% — ok
sms v1.0 — direction accuracy 100.0% (36/36), floor 95% — ok
forecast-ledgers v1.0 — median 90-day spend error 1264 bps (max 1500) — ok
forecast-ledgers v1.0 — mean P10-P90 band coverage 7027 bps (min 7000) — ok
```

Before issue 12.2 these gates asserted their floors and **reported nothing**, so a score sitting one
case above the floor was invisible until it broke. The first run after wiring this up showed the
forecast's band coverage at **7027 bps against a 7000 bps bound** — 0.27% of margin, which nobody knew.

The share is **truncated, never rounded**: 91.9% must not print as 92% beside a 92% floor.
`EvalReport` refuses `0/0` — that is not 100%, it is a dataset that was never loaded.

The forecast backtest deliberately does **not** use `EvalReport`: its metrics are a median error and a
mean coverage in basis points, not a share of correct cases, and forcing them through a `correct/total`
shape would make the printed number mean something it does not.

## 3 · Extending a dataset

1. **Add the case to the fixture**, in the file's existing format. Keep the labelled answer honest —
   write what the right answer *is*, not what the engine currently produces.
2. **Bump `# dataset-version:`** in the header. One number, no scheme beyond "it changed":
   `1.0` → `1.1` for added cases, `2.0` if you removed or relabelled any.
3. **Raise the runner's `MIN_FIXTURES`** if the set grew, so the new size is the new floor and a later
   deletion cannot pass.
4. **Run `./gradlew aiEval`** and read the printed lines. If accuracy fell, that is the finding — the
   new case is one the engine gets wrong.
5. Commit the fixture, the version bump and the size floor **together**. A version bump in a separate
   commit from the cases it describes is worse than none.

### What not to do

- **Do not relabel a case to make a gate pass.** This is the one edit the version marker exists to
  make visible: a diff that changes an expected answer and bumps the revision is reviewable; the same
  change with no marker is not. If the engine is wrong, fix the engine or lower the floor *on the
  record* with its own ADR.
- **Do not lower a floor to go green.** The floors come from the SRS (§8, §18.1, §21.5). Changing one
  is a deviation and needs an ADR, like any other.
- **Do not regenerate the forecast ledgers to make a test pass.** They were generated once by a seeded
  script (`generate_ledgers.py`, seed 20260919) and committed; the header says so. Regenerating until
  the numbers suit is fitting the test to the model.
- **Do not add a case drawn from real user data.** P-01: nothing of anyone's finances exists off the
  device. The sets are synthetic or public by construction, and the forecast ledgers' header records
  which shapes they model *and which they deliberately do not*, so the gate is not measuring the model
  against its own assumptions.

## 4 · Adding a new dataset

Declare `# dataset-version:` in the header **before the first record**, and read it with
`EvalDataset.versionFrom(this, "/eval/yours.txt")`. A set with no declared revision fails there rather
than reporting an unattributable score. Add a `MIN_FIXTURES` floor, report through `EvalReport`, and
add the module's test task to `aiEval` in the root `build.gradle.kts`.
