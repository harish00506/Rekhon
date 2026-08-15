<!--
  Why:  CLAUDE.md §10 — every session that changes code leaves one of these, so the reasoning
        behind a change survives the conversation that produced it.
  What: Session record — decisions, flow delta, code changed.
  Result: A reader six months from now can reconstruct why this session did what it did.
  Changelog:
    2026-08-15 — Created.
-->

# Session — 2026-08-15 · Issue 5.1: the dashboard's three remaining figures

**Branch:** `feature/5-1-home-dashboard-v1` (branched from `feature/4-6-monthly-budget-review`,
not from `dev` — see §1.2)
**Touches:** issue 5.1 (implemented end to end), a pre-existing non-standard branch topology
(flagged, not fixed), one real usability bug found on-device

---

## 1 · Decisions this session

### 1.1 Issue 5.1 is "Home dashboard v1", not the monthly-budget-review work the user first named

The user asked to "work on 5.1 with rules." The most recent commits were all budget-review work
(4.5/4.6/5.5-labelled), so the natural guess was that "5.1" continued that thread. It does not:
issue 5.1 is Epic 5's "Home dashboard v1" (`docs/issues/5.1-home-dashboard-v1.md`), unrelated to
budgets except as a consumer. Confirmed with the user before planning. "With rules" was confirmed
to mean **both** the binding engineering rules (CLAUDE.md, ARC-\*) and the `ai/` financial rulebook
— which mattered later: the budget-alert section cites `RULE-BUD-ALERT` on screen (§1.4).

### 1.2 Branched from the current branch tip, not from `dev` — a real gap, flagged not fixed

`dev` has no budgets code at all: issues 4.4 through 5.5 (categories, nature, budgets CRUD, alerts,
review) exist only on `feature/4-6-monthly-budget-review`, never merged. Issue 5.1 depends on 4.4.
Branching from `dev` (the GitFlow-lite default) would have silently dropped that dependency and
made the branch unbuildable against its own stated requirement. Branched from the 4.6 branch's tip
instead, after committing its outstanding work (three commits: the CLAUDE.md quiz-gate removal,
issue 2.8's filing, and the 4.6 implementation itself — see that branch's own history). **This is
non-standard for this repo's model and needs reconciling when the 4.x→5.5 chain eventually merges
to `dev`.** Recorded here rather than silently worked around.

### 1.3 The dashboard was already three-quarters real; the gap was three sections, not a rebuild

`feature/dashboard` was scaffolded in issue 1.10 as the ARC-004 reference implementation and had
already been wired to real net worth (2.6), the needs/wants/savings plan (2.3), and the true-spend
breakdown (4.3) by earlier issues — confirmed by reading `DashboardViewModel.kt`'s own doc comment:
"The figures are placeholders until issues 5.1/5.2." 5.1's actual scope was the three sections that
comment named as still owed: budget status, this month's cash flow, and recent activity.

### 1.4 Budget status and alerts reuse `BudgetRepository` unchanged — no new repository API

`observeBudgets()` and `observeAlerts()` already existed for `:feature:budgets`. The dashboard's
`observeBudgetStatus()`/`observeBudgetAlerts()` collectors are a second consumer of the identical
mechanism, not a new one. The summary card folds only *budgeted* categories' `status.budgeted`/
`status.spent` — an unbudgeted category carries `budgeted = Money.ZERO` by construction but real
spend, and including it would inflate "spent" past "budgeted" by exactly the amount nobody planned
for. The alert line cites its rule (`RULE-BUD-ALERT`, P-02), copying `BudgetReviewCard.citation()`'s
pattern from `:feature:budgets` — the same alert, the same citation, on a second screen.

### 1.5 Cash flow shipped as one SQL statement after `combine()` broke under the test dispatcher

First attempt: `TransactionRepository.observeMonthCashFlow()` called the existing
`TransactionDao.observeDayTotals` extension twice (once per `TransactionType`) and combined the two
`Flow`s. `TransactionRepositoryTest`'s three new cases failed with
`kotlinx.coroutines.test.TestCoroutineScheduler`'s "Detected use of different schedulers" —
`combine()`'s internal `yield()` collided with Room's own query-flow coroutine under
`UnconfinedTestDispatcher`, something no existing code in this repo does with two *Room* flows
inside one `combine`. Rewritten as a single new DAO query,
`TransactionDao.observeMonthCashFlow(profileId, fromIsoDate, toIsoDate)`, summing both totals with
one `CASE WHEN` — sidesteps the scheduler issue entirely, costs the database one pass instead of
two, and needed no `combine()` import. `CashFlowSummary.income`/`.expense` are both non-negative
magnitudes (the sign lives only in `.net`), matching `BudgetStatus.spent`'s existing convention.

### 1.6 Recent activity is bounded by count, matching `AuditLogDao.observeRecent`'s existing idiom

Issue 3.6 removed a fixed **30-day window** `observeRecent` because it stranded old data behind an
unreachable boundary. A dashboard preview of five rows is a different shape — bounded by *count*,
not by *time* — so it does not reopen that problem: the full ledger stays exactly as reachable as
after 3.6, through `observeFiltered`/the Transactions screen. Named it `observeRecent(limit)`
anyway, rather than inventing a new name to dodge the collision, because `AuditLogDao` already uses
that exact name for the identical count-bounded shape (`ORDER BY ... DESC LIMIT :limit`) — the
removed method and this one were never going to be confused by an IDE search on type, and matching
an existing idiom beat avoiding a word.

### 1.7 A real scrolling bug was found by running the app, not by any test

The dashboard's `Column` has never had a scroll modifier — issue 1.10's four-row screen fit on
screen without one. This issue's three added sections pushed the bottom of the content, including
**every navigation button**, off-screen and permanently unreachable on the emulator. No Compose UI
test in this codebase measures a real screen height against real content, so nothing caught it
before `/run`. Fixed with `.verticalScroll(rememberScrollState())`. Re-ran the full lint/test suite
and the Paparazzi baselines afterward — unchanged, since Paparazzi's unconstrained-height snapshot
renders identically whether or not the content is scrollable.

---

## 2 · Flow changed this session

```
DashboardScreen()
├─ hiltViewModel<DashboardViewModel>()
│   └─ init { …; observeCashFlow(); observeBudgetStatus(); observeBudgetAlerts();     NEW
│              observeRecentActivity() }
│       ├─ TransactionRepository.observeMonthCashFlow()                              NEW
│       │   └─ TransactionDao.observeMonthCashFlow(profileId, monthStart, actualsEnd)
│       │       (one CASE WHEN SQL sum — no engine, no provenance; see §1.5)
│       │
│       ├─ BudgetRepository.observeBudgets() / observeAlerts()          reused from 4.4/4.5
│       │   (identical to the Shape A worked example in FLOW.md §2 — no new call path)
│       │
│       └─ TransactionRepository.observeRecent(limit = 5)                             NEW
│           └─ TransactionDao.observeRecent(profileId, today, limit)   count-bounded, not
│                                                                       time-windowed (§1.6)
│           ⇣
│       _uiState.update { … }  →  uiState: StateFlow<DashboardUiState>
│
└─ collectAsStateWithLifecycle()  →  recomposition
    ├─ CashFlowSection(uiState)                                                       NEW
    ├─ BudgetStatusSection(uiState) → BudgetAlertsLine(alerts)                        NEW
    │    (cites RULE-BUD-ALERT via alert.provenance.evidence, P-02)
    └─ RecentActivitySection(uiState)                                                 NEW
```

Full diagram addendum in `FLOW.md` §2 (Shape A note extended in place, not redrawn — the dashboard
reuses the existing Shape A mechanism, it does not add a fourth shape).

---

## 3 · Code changed this session

| Path | What it does now |
|------|-------------------|
| `core/database/.../dao/Daos.kt` | `TransactionDao.observeRecent` (count-bounded, mirrors `pagedFiltered`'s columns); `TransactionDao.observeMonthCashFlow` + `CashFlowTotalsRow` (one `CASE WHEN` sum) |
| `data/repository/.../TransactionFilter.kt` | `CashFlowSummary` |
| `data/repository/.../TransactionRepository.kt` | `observeRecent(limit)`, `observeMonthCashFlow()` interface methods + `RoomTransactionRepository` impls |
| `data/repository/src/test/.../TransactionRepositoryTest.kt` | Six new cases: income/expense summed separately, month-boundary exclusion, transfer exclusion, newest-first + limit, future-dated exclusion |
| `feature/dashboard/build.gradle.kts` | `alias(libs.plugins.paparazzi)` — the module had none before this issue |
| `feature/dashboard/.../DashboardUiState.kt` | `cashFlow`, `budgets`, `budgetAlerts`, `recentActivity`; `budgetTotals` getter + `BudgetTotals` |
| `feature/dashboard/.../DashboardViewModel.kt` | `BudgetRepository` injected; four new collectors (§2); `RECENT_ACTIVITY_LIMIT = 5` |
| `feature/dashboard/.../DashboardScreen.kt` | `CashFlowSection`, `BudgetStatusSection`, `BudgetAlertsLine`, `RecentActivitySection`; `Column` gained `.verticalScroll(rememberScrollState())` (§1.7) |
| `feature/dashboard/src/main/res/values/strings.xml` | Cash-flow, budget-status, `dashboard_budget_alerts` plural, `dashboard_reason_rule`, recent-activity copy |
| `feature/dashboard/src/test/.../{DashboardViewModelTest,FakeTransactionRepository}.kt` | New collector cases; fake gained independent `failOnCashFlow`/`failOnRecent` and `setCashFlow`/`setRecent` |
| `feature/dashboard/src/test/.../FakeBudgetRepository.kt` | **New** — narrow fake, throws for anything the dashboard does not read (mirrors `FakeTransactionRepository`'s bargain) |
| `feature/dashboard/src/test/.../DashboardScreenshotTest.kt` | **New** — loaded state (light/dark/200%) and all-empty state |
| `feature/dashboard/src/test/snapshots/images/*.png` | **New** — four committed Paparazzi baselines |
| `feature/transactions/src/test/.../Fakes.kt` | Its `FakeTransactionRepository` gained real (not `unsupported()`) `observeRecent`/`observeMonthCashFlow` implementations |
| `app/src/test/.../ScheduledTransactionWorkerTest.kt` | `RecordingTransactionRepository` gained the two new interface methods (compile fix) |
| `docs/issues/5.1-home-dashboard-v1.md`, its tracker | Acceptance criteria, Files Changed, Verification Log |
| `FLOW.md` | Shape A note extended (§2) |
| `VERSION`, `CHANGELOG.md` | 0.4.5 → 0.5.0 |
