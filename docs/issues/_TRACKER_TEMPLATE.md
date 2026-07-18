<!--
  TEMPLATE — the reference-format tracker file. It is normally generated alongside its issue by
  `python scripts/gen_issue_docs.py`; copy this by hand only for an ad-hoc issue not in the CSV.
  Created/updated when work starts and REQUIRED when the issue is Done (workflow step 11). It is
  the evidence trail: what was run, when, and the result. Timestamps are IST (UTC+5:30). Delete
  this comment block when filled.
-->

# Issue <id> — <Title> Tracker

**Parent issue:** [<id>-<slug>.md](<id>-<slug>.md)  
**Strategy:** <one line — the approach for this issue>  
**Branch (integration):** `main`  
**Branch (implementation):** `feature/<id-with-dashes>-<slug>` → merged to `main`

---

## Status Summary

| Field | Value |
|-------|-------|
| **Status** | Todo (not started) |
| **Progress** | 0 / <N> phases |
| **Last verified** | — |
| **VERSION at start** | see repo-root `VERSION` |
| **Depends on** | <deps or None> |
| **Branch** | `feature/<id-with-dashes>-<slug>` |

---

## Phase Checklist

Add/remove rows to fit the issue (a database issue adds a migration phase, a UI issue a
screenshot phase, a core flow an E2E phase). One-line verification per row.

| # | Phase | Status | Verification |
|---|-------|--------|--------------|
| 1 | Dependencies clear or waived | [ ] | deps: <deps or None> |
| 2 | Branch created | [ ] | `git branch --show-current` → feature/... |
| 3 | Failing tests written (TDD) | [ ] | new tests are red before implementation (`./gradlew testDebugUnitTest`) |
| 4 | Implementation to acceptance criteria | [ ] | all acceptance criteria in the issue are met |
| 5 | Static analysis clean | [ ] | `./gradlew ktlintCheck detekt lintDebug` — no new warnings |
| 6 | Unit + coverage gate | [ ] | `./gradlew testDebugUnitTest koverVerify` — engine ≥ 85%, money 100% |
| 7 | Run on emulator (real gate) | [ ] | `/run` + `/verify` — changed behaviour observed on a device |
| 8 | Docs + VERSION + CHANGELOG | [ ] | ENGINE.md/ADR as needed; `VERSION` + `CHANGELOG.md` bumped |
| 9 | Merge to `main` + push | [ ] | merged to `main`; Verification Log complete; pushed |

---

## Verification Log

Append one row per verify step (lint, unit, coverage, screenshot, emulator run, E2E, git push).
Use `OK`, `SKIP — <reason>`, or `FAIL — <reason>`. `SKIP` must name why (never to go green).

| Date & time (UTC+5:30) | Phase | Command | Result |
|------------------------|-------|---------|--------|
| — | — | (not started) | — |

---

## Notes / deviations

<Any SRS deviation links its ADR here. Any SKIP explains why. Any follow-up issue is linked.>
