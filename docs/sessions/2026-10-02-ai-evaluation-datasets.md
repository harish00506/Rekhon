<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: issue 12.2 — versioning the frozen AI-eval sets, making every run report its score, and the
        wrong finding that measuring corrected.
  Result: a reader can see why a version marker matters, what reporting found immediately, and why
          the forecast backtest keeps its own reporter.
  Changelog: 2026-10-02 — Created.
-->

# 2026-10-02 — The accuracy gates now say what they measured (issue 12.2, ADR-0065)

**Branch:** `feature/12-2-ai-evaluation-datasets-categorisation-ocr-forecast` off `dev` (`950948c`)
**Versions:**
- **VERSION** 0.12.1 → **0.12.2**
- **versionCode** 59 → 60
- **Schema** 29 → **29 (unchanged — this issue ships no runtime code)**

---

## 1 · Decisions this session

The full argument for each is in [ADR-0065](../adr/0065-eval-datasets-are-versioned-and-every-run-reports-what-it-measured.md).

- **Every frozen set declares `# dataset-version:` in its header**, and a set without one fails rather
  than reporting an unattributable score. The reason is not bookkeeping: "94% on categorisation" cannot
  be compared with last month's 96% unless both name a revision. And it makes visible the one edit that
  matters — the cheapest fix for a failing accuracy gate is to **relabel the awkward case**, and without
  a marker that leaves no trace in any report. The marker does not prevent it; it makes the diff and the
  number tell the same story.
- **Placement strict, whitespace tolerant.** Header only, declared once — that part carries meaning. But
  `#  dataset-version: 2.1` with an extra space is accepted, because these files are edited by people and
  refusing over a space teaches everyone to distrust the parser.
- **Every run reports score, counts, floor and revision.** Truncated never rounded, because 91.9% must
  not print as 92% beside a 92% floor. The floor is inclusive, because §21.5 says "at least" and an
  exclusive boundary would silently make every threshold a point stricter than the SRS. `0/0` is refused:
  that is not 100%, it is a set that was never loaded.
- **The forecast backtest deliberately keeps its own bps reporter.** Its metrics are a median error and a
  mean coverage in basis points, not a share of correct cases; forcing them through `correct/total` would
  make the printed number mean something it does not. Reusing the class because it existed would have
  been the wrong kind of consistency.
- **`aiEval` names the gate in CI**, additional to `unitTests` which is still what blocks a merge. A
  regression inside five thousand anonymous tests reads as "a test failed", not "categorisation dropped
  below 92%". Issue 10.6 made this argument for `guardrailEval`; this extends it to the other four.
- **No accuracy threshold was changed.** This issue reports and attributes. Retuning a floor is a
  deviation needing its own ADR, and the guide says so.

**What this found.**

1. **A wrong finding of mine, corrected by measuring.** I reported that none of the accuracy gates pinned
   its dataset size — so a set could be gutted to a few easy cases to lift a score. Then I tested it:
   cutting categorisation from 76 records to 5 **fails** ("fewer fixtures than the set is documented to
   hold"). My grep had missed the `MIN_FIXTURES` guards; all four have them. Three of AC1's four words
   were already true, and the real gaps were versioning, reporting and naming.
2. **Reporting paid for itself on its first run.** `forecast-ledgers v1.0 — mean P10-P90 band coverage
   7027 bps (min 7000) — ok`. **0.27% of margin**, and nobody knew, because nothing printed the number.
   That is AC1's "report" justified in one line.
3. **A red test that was a real requirement.** `the version may sit anywhere in the header` failed against
   the first parser, which matched the marker literally — a hand-written `#  dataset-version:` would have
   been rejected.
4. **A trap removed.** Each runner had `percent(hits, total) = if (total == 0) 100 else …` — an empty set
   reporting as 100%. Unreachable behind the size guard, but a hazard. `EvalReport` made it unused,
   detekt flagged it, and it was deleted rather than suppressed.

## 2 · Flow changed this session

A test path — `FLOW.md` §2.28:

```
./gradlew aiEval                 names the gate; unitTests still blocks the merge
└─ each runner
   ├─ EvalDataset.versionFrom    no marker / twice / after the first record ⇒ ERROR
   ├─ MIN_FIXTURES               already present before 12.2 — a set cannot be gutted
   └─ EvalReport.line            truncates · inclusive floor · 0/0 ⇒ ERROR · println + assert

:domain:engines:forecast keeps its OWN bps reporter — a median error is not a share (ADR-0065)
```

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `core/common/testFixtures/EvalDataset.kt` (new) | reads a frozen set's declared revision; errors without one |
| `core/common/testFixtures/EvalReport.kt` (new) | the score line: truncated share, inclusive floor, `0/0` refused |
| `core/common/src/test/{EvalDataset,EvalReport}Test.kt` (new) | 14 tests, 10 mutations |
| 4 × `*/src/test/resources/**/*.txt` | `# dataset-version: 1.0` in each header, above the first record |
| `ClassificationEvalTest`, `ReceiptEvalTest`, `SmsEvalTest` | report through `EvalReport`; the dead `percent` helper gone |
| `ForecastBacktestTest` | reports in bps through its own reporter, with the dataset revision |
| 4 × `domain/engines/*/build.gradle.kts` | the test-only harness dependency |
| `build.gradle.kts` | the `aiEval` aggregate task |
| `.github/workflows/ci.yml` | the named AI-evaluation step |
| `docs/testing/ai-evaluation-datasets.md` (new) | the five sets, their floors, and how to extend one safely |
| `docs/adr/0065-…`, `DECISIONS.md`, `FLOW.md` §2.28, `CHANGELOG.md`, `docs/memory.md`, `VERSION` | the records |
