<!--
  Why:  CLAUDE.md §5 — any decision or deviation from the SRS needs an ADR.
  What: issue 12.2 — versioning the four frozen AI-eval datasets, making every run report its score,
        and naming the gate in CI.
  Result: a reader can see why a version marker matters, why the share is truncated, and why the
          forecast backtest deliberately does not use the shared reporter.
  Changelog: 2026-10-02 — Created.
-->

# ADR-0065 — Evaluation datasets are versioned, and every run reports what it measured

**Status:** Accepted · **Date:** 2026-10-02 · **Issue:** 12.2 · **SRS:** §21.5, §8, §18.1

## Context

§21.5 requires frozen, labelled evaluation datasets with regression thresholds that block merges:
categorisation ≥ 92%, receipts ≥ 95%, SMS ≥ 95%, plus forecast backtests.

The survey found **more already in place than the issue implies.** All four datasets exist (76
categorisation cases, 46 receipts, 56 SMS messages, 20 forecast ledgers), all four runners assert their
floors, and — checked empirically rather than assumed — **all four already guard their dataset size**, so
a set cannot be shrunk to a handful of easy cases to lift a score. My first grep missed those guards and
I reported the gap wrongly; gutting the categorisation set to five records and watching the run fail is
what corrected it.

So three of AC1's four words were already true. The gaps were: the datasets were **not versioned**, the
runners **did not report** their scores, and there was **no named CI gate**.

## Decision

### 1 · Every dataset declares a revision

`# dataset-version: <v>` in the header, before the first record, read by `EvalDataset.versionFrom`. A
set without one **fails** rather than reporting an unattributable score.

The reason is not bookkeeping. An accuracy figure means nothing if the set behind it may have changed:
"94% on categorisation" cannot be compared with last month's 96% unless both name a revision. And it
makes visible the one edit that matters — the cheapest way to fix a failing accuracy gate is not to
improve the engine but to **relabel the awkward case**, and without a marker that change leaves no trace
in any report. A version does not prevent it; review does. The marker is what makes the diff and the
number tell the same story.

Placement is strict (header only, declared once) because it is metadata, and a reader scanning the top
of the file must find it. Whitespace is tolerated, because these files are edited by people and refusing
`#  dataset-version: 2.1` over one space teaches everyone to distrust the parser.

### 2 · Every run reports its score, its counts, its floor and the revision

Until now these gates asserted their floors and reported nothing, so a score sitting one case above the
floor was invisible until it broke — and three releases later somebody loosens the floor rather than
fixing the regression, because by then there is no record of what it used to be.

`EvalReport.line` prints one line per metric. **The first run after wiring it up found the forecast's
mean band coverage at 7027 bps against a 7000 bps bound** — 0.27% of margin, which nobody knew.

The share is **truncated, never rounded**: 91.9% must not print as 92% beside a 92% floor, or a failing
run reads as a passing one in the single line a reader trusts. The floor is **inclusive**, because §21.5
says "at least" and an exclusive boundary would silently make every threshold a point stricter than the
SRS. `0/0` is refused — that is not 100%, it is a dataset that was never loaded.

### 3 · The forecast backtest deliberately does not use `EvalReport`

Its metrics are a median error and a mean coverage **in basis points**, not a share of correct cases.
Forcing them through a `correct/total` shape would make the printed number mean something it does not. It
gets its own three-line bps reporter with the same intent and the right units. Reusing the class because
it was there would have been the wrong kind of consistency.

### 4 · `aiEval` names the gate in CI

`unitTests` already runs these and is what blocks a merge. But a regression inside five thousand
anonymous tests reads as "a test failed", not "categorisation accuracy dropped below 92%". Issue 10.6
made exactly this argument for `guardrailEval`; `aiEval` extends it to the other four and gives a
developer one command. The CI step is additional to `unitTests`, not a replacement — stated here because
a reader could otherwise think removing it would weaken the gate.

### 5 · A dead helper removed, not suppressed

Each runner had a local `percent(hits, total) = if (total == 0) 100 else …`. That returns **100% for an
empty dataset** — the vacuity hazard, guarded elsewhere by the size floor but still present in the
arithmetic. Replacing it with `EvalReport` made it unused, detekt said so, and it was deleted rather than
suppressed.

## Alternatives rejected

| Alternative | Why not |
|---|---|
| Version the datasets in a side file or in Git alone | A reader looking at the fixture would not see it, and the number in a log could not name it. Git tells you *that* it changed, not which revision a score belongs to. |
| Default a missing version to "unknown" | Makes unversioned sets permanent, which is the state this issue exists to end. |
| Round the share to the nearest percent | 91.9% printing as 92% beside a 92% floor makes a failing run read as passing. |
| An exclusive floor | §21.5 says "at least"; exclusive would make every threshold a point stricter than the SRS without anyone deciding to. |
| Report `0/0` as 100% | The vacuous-gate failure this project has found five times. |
| Force the forecast backtest through `EvalReport` | Its metrics are bps, not a share. The printed number would mean something it does not. |
| Replace `aiEval` for `unitTests` in CI | `unitTests` is what blocks the merge; `aiEval` only names the gate. |
| Add dataset-size guards | **Already present** in all four runners. Verified by gutting a set and watching the run fail. |

## Consequences

- Every accuracy figure in a CI log names the dataset revision that produced it.
- A score drifting toward its floor is visible before it crosses — already true of the forecast's
  0.27% margin.
- Adding a dataset without a declared version fails its runner.
- Extending a dataset is documented (`docs/testing/ai-evaluation-datasets.md`), including the three
  things not to do: relabel a case to go green, lower a floor without an ADR, or regenerate the
  forecast ledgers until the numbers suit.
- **The floors themselves are unchanged.** This issue reports and attributes; it does not retune.
