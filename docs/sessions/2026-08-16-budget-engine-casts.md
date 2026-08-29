<!--
  Why:  CLAUDE.md §10 — every session that changes code leaves one of these, so the reasoning
        behind a change survives the conversation that produced it.
  What: Session record — decisions, flow delta, code changed.
  Result: A reader six months from now can reconstruct why this session did what it did.
  Changelog:
    2026-08-16 — Created.
-->

# Session — 2026-08-16 · Issue 4.7: letting the budget engine fail

**Branch:** `feature/4-7-budget-engine-casts` (off `dev`)
**Touches:** `RoomBudgetRepository`'s four engine call sites, one new test suite, `FLOW.md`,
`VERSION`/`CHANGELOG.md`, and the issue file's own acceptance criteria

---

## 1 · Decisions this session

### 1.1 The rule is inherited from `BudgetEngine`'s interface, not invented by the repository

The issue framed this as a design question with two candidates (widen to `Flow<Result<…>>`, or drop
the row). Neither is what shipped. The line was already drawn, in the engine's own contract:
`suggest`, `alert` and `review` each document `Ok(null)` as a legitimate answer
(`BudgetEngine.kt:35, 58, 78`); `status` (`:48`) does not, because there is no such thing as "no
status". So three sites have a lane a failure can land in and one does not:

> Where the engine documents `Ok(null)` as a legitimate answer, a failure collapses into that same
> absence. Where it does not, the failure terminates the stream carrying the engine's `AppError`.

**A first framing was dropped on review: "advisory results degrade, load-bearing ones surface."** It
produces the identical four edits, but it is a judgement a reviewer cannot check — and it was
demonstrably post-hoc, since a month-end review is not obviously more advisory than a per-category
status. The next engine call added to this file would have been argued into whichever bucket was
cheaper. The engine-contract rule is checkable by opening one file, and is now stated in
`BudgetRepository`'s own interface doc comment so it can be found.

### 1.2 `Flow<Result<…>>` was considered and not taken

No repository in `:data:repository` carries an error inside a stream. The pattern exists only in
`:core:datastore` (`SettingsStore.observe()`), and the one repository that consumes such a stream —
`DemoModeRepository:124` — unwraps it immediately with `.getOrNull()`. Adopting it here would be a
new convention for the layer and would rewrite the contract every `.catch {}` consumer is written
against, across two ViewModels, for a failure that is currently unreachable. Recorded in the issue
as the option not taken rather than silently skipped.

### 1.3 `error(...)` was rejected despite an exact in-repo precedent, because of the rethrow list

`NetWorthRepository.computeFrom:354` does precisely what site 1 needs — a non-nullable engine result
inside a Flow — with `error(...)`, justified as "§21.6 reserves crashes for programmer errors". It
cannot be copied. **`runCatchingToResult` rethrows `IllegalStateException` and
`IllegalArgumentException`** (`AppError.kt:128-133`), so an `error()` at the `status` site would
sail through `pendingAlerts`, `acceptSuggestion` and `acceptReviewProposal` — all three of which read
these flows inside `runCatchingToResult { … }.flatten()` — into `viewModelScope` and
`CoroutineWorker`. That is exactly the §21.6 hole closed on 2026-08-15, reopened.

Hence `BudgetEngineFailure : Exception()`. Plain `Exception` is *caught* there and becomes the `Err`
those signatures promise, and reaches `.catch {}` on the Flow paths.

The same rethrow list also **narrows the issue's own premise**: an engine `require`/`check` crashes
rather than becoming `Err`, and `:domain:engines:budget` is pure Kotlin with no I/O or crypto, so the
entire reachable error set is `Unexpected(<class name>)` — realistically `ArithmeticException` from
`Math.addExact`. The issue's Description said otherwise and was corrected in place.

### 1.4 The carrier is `internal` to `:data:repository`, not `:core:common`

The obvious shape was a public `AppErrorException` in `:core:common` plus a `Throwable.toAppError()`
branch. Rejected. It buys nothing observable — both ViewModels read only `.code`, the engine's error
is `Unexpected` anyway, and there is no logger or crash reporter to read the preserved class name —
while costing a precedent: it would be this codebase's **first** custom `Throwable`, public, sitting
beside the `Result` type whose own KDoc argues that "an exception is an invisible second return
type". The next contributor who finds `Result` inconvenient could cite it. Scoped `internal`, it is
one module's answer to one non-nullable engine call, and promoting it the day a logger exists is a
one-commit change made for a reason that exists.

### 1.5 A worker contract changed, and it is owned rather than shipped as a bonus

`pendingAlerts()` previously returned `Err` when the alert engine failed; `BudgetAlertWorker` maps
that to `Result.retry()`. Since the failure is deterministic given the same rows, the job retried
daily for the rest of the month, notifying nothing and recording nothing. It now returns an empty
list and the worker reports success. Better — but "empty" has acquired a second meaning, so it is
stated in `pendingAlerts`'s KDoc, in the issue's consequences list, and pinned by a test.

### 1.6 The issue's acceptance criteria were rewritten rather than stretched

Three of the four were unsatisfiable by any sane design: AC1 required the sites to "propagate the
engine's own `AppError` instead of throwing" when three of them have nowhere to propagate it to and
the fourth has nothing to do but throw; AC2 required "not silence" when silence is the correct
outcome for three. They were written before the rethrow list and the engine's `Ok(null)` contract
were understood. Amending them honestly — with a note saying so — beat wording the design to fit.

### 1.7 The tests were proven to catch the bug

All four casts were reverted and `BudgetEngineFailureTest` ran **8 of 9 red**, each with a real
`ClassCastException` at the reverted site. The ninth ("a refused status reaches a suspend reader as
`Err`") passes either way by design: it guards the containment property against someone later
reintroducing a rethrown exception type, which is worth keeping and worth labelling as such rather
than counting as a bug-catcher.

---

## 2 · Flow changed this session

```
RoomBudgetRepository — engine call sites
│
├─ engine.suggest / .alert / .review  → Err
│   └─ getOrNull() → null
│       ├─ suggestionFor  → no offer      (observeSuggestions emits a shorter list)
│       ├─ alertFor       → no band       (observeAlerts emits a shorter list; the row's
│       │                                  figures still reach the screen unchanged)
│       └─ reviewFor      → no card       (observeReview emits null — a third meaning
│                                          beside "nothing budgeted" and "dismissed")
│
└─ engine.status                       → Err
    └─ getOrElse { throw BudgetEngineFailure(it) }
        ├─ Flow path      → observeBudgets fails → consumer .catch{} → error banner
        │                   (BudgetsViewModel and DashboardViewModel, both unchanged)
        └─ suspend path   → runCatchingToResult catches → Err          ← §21.6 holds
                            (pendingAlerts, acceptSuggestion, acceptReviewProposal)
```

`FLOW.md` §4 gained this as an annotation to Shape C, and §2's issue-5.1 dashboard block gained a
note that only a failed *status* now reaches its `.catch`.

---

## 3 · Code changed this session

| Path | What it does now |
|------|-------------------|
| `data/repository/.../BudgetRepository.kt` | Four casts replaced by `getOrNull`/`getOrElse`; new `internal class BudgetEngineFailure`; the rule stated in the interface header; failure behaviour documented on `observeBudgets`, `observeSuggestions`, `observeAlerts`, `alertFor`, `observeReview` and `pendingAlerts` |
| `data/repository/src/test/.../BudgetEngineFailureTest.kt` | **New** — nine cases over a `PartlyFailingBudgetEngine` that fails one operation and delegates the rest to the real engine, so "one broke, the rest still work" is what is actually asserted |
| `feature/budgets/.../BudgetsViewModel.kt` | Doc-only: `observeAlerts`'s KDoc claimed a failure "has already reached the banner through `observeBudgets`" — true of the status cast, never of the alert cast, and now moot since an undecidable band no longer throws at all |
| `docs/issues/4.7-…md` | ACs rewritten with a note explaining why; Description's `require` claim corrected; worker consequence added; decisions and verification recorded |
| `FLOW.md` | Shape C engine-failure block; the 5.1 dashboard note |
| `VERSION`, `CHANGELOG.md` | 0.5.0 → 0.5.1 |

## 4 · Left for later, deliberately

- **`NetWorthRepository.computeFrom:354`** — filed as
  [issue 2.9](../issues/2.9-networth-repository-error-escapes-result.md) after being verified end to
  end. It is worse than first suspected: the `error(...)` escapes **three** `Result`-returning APIs,
  not one. `computeAsOf`'s `runCatchingToResult` wraps only the DB read and `computeFrom` runs after
  it; `snapshotUpToToday` and `repairStaleHistory` *do* wrap it and it escapes anyway, because
  `runCatchingToResult` rethrows `IllegalStateException` by design. `NetWorthSnapshotWorker` maps only
  `Err` to a retry, so the throw would fail the nightly job outright and stop the net-worth series
  silently. Not widened into 4.7's diff: different file, different feature, different consumers.

  **This was initially left as a note rather than a ticket, which was wrong.** "Verify before
  fixing" is right; "verify before *filing*" is not — an issue can record a suspicion, and the
  review standard this repo follows says surrounding problems get filed, not mentioned. Corrected
  the same day, once the user pushed on it.
- **A structured logger.** CLAUDE.md §5 names one; none exists, `:data:repository` depends on no
  logging library, and `audit_log` is closed to non-security events by construction. So the three
  degradation sites are genuinely silent, which is stated in their doc comments rather than papered
  over. `Result.onErr` is already documented in `:core:common` as the seam; these three become its
  first callers when one is built.
