<!--
  Why:  docs/Architecture.md documents which module may depend on which — static structure. Nothing
        documented how execution actually travels: where the process starts, what runs before the
        first pixel, and what calls what once it does. 198 Kotlin files across 33 modules with no
        runtime map means every reader re-derives the same three paths by grep.
  What: FLOW — entry points, the cold-start spine, and one worked path for each of the three shapes
        the codebase repeats (screen, background worker, engine).
  Result: A reader can follow a real call chain end to end without opening a file, and knows which
        shape any unfamiliar code belongs to.
  Changelog:
    2026-08-14 — Created. Traced against the code at commit 6ffccc5.
-->

# Flow — how execution travels

> **Binding rule:** [`CLAUDE.md` §10](CLAUDE.md). Static structure: [`docs/Architecture.md`](docs/Architecture.md).
> Why any of it is this way: [`DECISIONS.md`](DECISIONS.md). What changed in a given session:
> [`docs/sessions/`](docs/sessions/).

**How to read this.** `→` is a call. Indentation is nesting. Names are real and greppable; if an
arrow here does not match the code, the code is right and this file is stale — fix it in the same
commit that made it stale.

> `ponytail:` this maps the **spine and three shapes**, not all 198 files. Everything else in the
> app is one of these three shapes with different nouns, so a per-file call graph would add pages
> and no understanding, and would be stale within a week. **Ceiling:** a fourth shape gets its own
> section when one is actually built — `:sync:backup`, `:widget` and `:ml:llm` do not exist yet.

---

## Entry points

There are exactly three ways into this app. Every stack trace starts at one of them.

| # | Entry point | File | Triggered by |
|---|-------------|------|--------------|
| 1 | `CfoApplication.onCreate()` | `app/.../CfoApplication.kt` | Process start — always first, before any UI |
| 2 | `MainActivity.onCreate()` | `app/.../MainActivity.kt` | Launcher icon, or a notification/widget tap |
| 3 | `<Worker>.doWork()` | `app/.../work/*.kt` | WorkManager, on its own schedule — **the app need not be open** |

Entry point 3 is the one that surprises people: five workers can run with no Activity alive.

---

## 1 · Cold start — the spine

```
process start
│
├─ CfoApplication.onCreate()                                  app/CfoApplication.kt
│   ├─ @HiltAndroidApp                       builds the object graph (ARC-003)
│   ├─ profileZoneProvider.start()           profile time zone → every Clock read (TIM-001)
│   ├─ smsConsentWatcher.start()             erases drafts when consent is revoked (P-01)
│   ├─ CfoNotifications.createChannels()     before anything can post into them
│   └─ schedule() × 5 workers                NetWorthSnapshot · BalanceIntegrity ·
│                                            ScheduledTransaction · SmsScan · BudgetAlert
│                                            all KEEP, so an existing job survives relaunch
│
└─ MainActivity.onCreate()                                    app/MainActivity.kt
    ├─ enableEdgeToEdge()                    BEFORE super.onCreate + setContent
    └─ setContent
        └─ CfoTheme                          design tokens; a screen never themes itself
            └─ Scaffold                      owns the window insets — the only one that does
                └─ AppLockGate { … }         SEC-002
                    │   Wraps the graph, is NOT a destination inside it. No deep link,
                    │   restored back stack or otherwise reaches a screen without passing it.
                    │
                    └─ MainViewModel.startDestination : StateFlow<CfoRoute?>
                        │   null until the stored onboarding flag is read — the surface stays
                        │   empty rather than guessing, so a returning user never sees the
                        │   welcome screen flash and vanish
                        │
                        └─ AppContent(startDestination, viewModel)
                            ├─ CfoDemoBanner          when a demo profile is loaded (FR-ONB-004)
                            ├─ CfoNavHost(...)        the typed graph — ARC-001
                            └─ CfoAddTransactionFab   hidden on Onboarding + AddTransaction
```

**The lock is load-bearing, not cosmetic.** `CoreModule.provideDatabase` runs
`check(sessionLock.isUnlocked.value)` and *throws* while the lock is closed. Anything that reaches
for the database before unlocking takes the process down — which is why workers check the lock
before they inject (see shape B).

> **Known limitation, recorded in [ADR-0003](docs/adr/0003-app-lock-gate-and-deferred-user-auth-key.md).**
> Hilt caches that provider per `@Singleton`, so the check runs on the **first** resolution, not on
> every later access. It proves "no financial data was read before the first unlock" — the property
> that matters on a cold start — but it does not re-assert after an idle re-lock. Closing that gap
> needs the auth-bound Keystore key from SEC-001, which is deferred.

**Navigation** is `CfoNavHost` matching a `CfoRoute` sealed type to a `composable<T> { }`. Feature
modules never call each other; they emit a route and `:app` resolves it (ARC-001).

---

## 2 · Shape A — a screen

Data flows **down** as one immutable `StateFlow`; events flow **up** through one sealed interface
(ARC-004). Budgets, as the worked example:

```
CfoNavHost      composable<CfoRoute.Budgets> { BudgetsScreen() }
│
└─ BudgetsScreen()                             feature/budgets/BudgetsScreen.kt
    ├─ hiltViewModel<BudgetsViewModel>()
    │   └─ init { observeBudgets(); observeSuggestions(); observeAlerts(); observeReview() }
    │       ├─ BudgetRepository.observeBudgets()      data/repository — ARC-005
    │       │   ├─ MonthWindow.current(clock.today())          injected Clock (TIM-001)
    │       │   ├─ database.budgetDao().observeCategoryBudgets(profileId, month.startIsoDate)
    │       │   └─ engine.status(...)                          → shape C
    │       │       └─ CategoryBudget(category, budgeted, spent, status)
    │       │
    │       └─ BudgetRepository.observeReview()      issue 4.6, §5.5
    │           ├─ reviewedMonth(clock.today())        last CLOSED month, one before .current()
    │           ├─ rawReview(profileId): combine(categories, budgets, actuals, history spend)
    │           │   └─ engine.review(...)                      → shape C
    │           │       └─ BudgetReview(totals, categories, provenance)?
    │           └─ .combine(budgetReviewDao().observeForMonth(...)) { review, claimed ->
    │                  if claimed != null → null }            once-per-month claim folded in
    │           ⇣
    │       _uiState.update { … }  →  uiState: StateFlow<BudgetsUiState>
    │
    └─ collectAsStateWithLifecycle()  →  recomposition
        ├─ BudgetAlertBanner(uiState)
        ├─ BudgetReviewSection(uiState, onEvent)          issue 4.6
        │    ⇡  onEvent(BudgetsEvent.AcceptReviewProposal) / DismissReview
        │    └─ BudgetsViewModel.onEvent(event)
        │        ├─ acceptReviewProposal() → repository.acceptReviewProposal(categoryId)
        │        │   └─ write(...) targeting MonthWindow.current — the month ahead, not reviewed
        │        └─ dismissReview() → repository.dismissReview()
        │            └─ budgetReviewDao().insertIfNew(...)     OnConflictStrategy.IGNORE
        │                └─ UNIQUE(profile_id, month_start_iso_date)
        │                    → observeReview() re-emits null next tick (Room invalidation)
        └─ BudgetCard(budget, band, onEvent)
             ⇡  onEvent(BudgetsEvent.Save)
             └─ BudgetsViewModel.onEvent(event)
                 └─ save() → repository.setBudget(categoryId, amount, rollover)
                     └─ database.budgetDao().upsert(...)
                         └─ Room invalidates → the Flow above re-emits → UI updates
```

**The loop closes through the database, never by hand.** A write does not push new state into the
`UiState`; it writes a row, Room invalidates the query, the `Flow` re-emits. There is exactly one
path by which the screen learns anything.

**The ViewModel computes no money** (P-03). Every figure on that screen arrived from an engine.

**Issue 5.1 reuses this identically, no new shape.** `DashboardViewModel` reads the same
`BudgetRepository.observeBudgets()` shown above — a second consumer of the exact mechanism, not a
new one — plus two new `TransactionRepository` reads that are plain repository sums, not engine
calls, so neither belongs in Shape C: `observeMonthCashFlow()` (one `CASE WHEN` SQL statement, no
`combine()` — an earlier version combined two `observeDayTotals` calls and hit
`kotlinx-coroutines-test`'s "different schedulers" error when two Room query flows met inside
`combine` under `UnconfinedTestDispatcher`; one query sidesteps it) and `observeRecent(limit)` (a
count-bounded `LIMIT` query, not the time-windowed `observeRecent` issue 3.6 removed — the full
ledger stays reachable through `observeFiltered`).

**The dashboard does *not* call `observeAlerts()`** — the one place its budget path differs from the
budgets screen's:

```
DashboardViewModel.observeBudgetStatus()                 ONE collector, not two
└─ BudgetRepository.observeBudgets()                     the combine() above, subscribed once
    └─ rows.mapNotNull(budgetRepository::alertFor)       synchronous, same engine.alert call
        └─ _uiState.update { copy(budgets, budgetAlerts) }   both figures, one emission, one catch
```

`observeAlerts()` is literally `observeBudgets().map { mapNotNull(::alertFor) }`, so calling it
beside `observeBudgets()` opened that three-query `combine()` a second time for data the first
subscription already had — and let one read failure show two different faces (a banner from one
collector, a silently-emptied line from the other). `alertFor` is the same engine call reached
without the second subscription. `:feature:budgets` still calls `observeAlerts()` and is unchanged.

---

## 3 · Shape B — a background worker

The app is usually not open when a band is crossed. Budget alerts, as the worked example:

```
CfoApplication.onCreate()
└─ BudgetAlertWorker.schedule(context)                        app/work/BudgetAlertWorker.kt
    └─ WorkManager.enqueueUniquePeriodicWork(
           "budget-threshold-alerts", KEEP, every 1 day)
           │  KEEP, not REPLACE: rescheduling on every launch would reset the period, so a
           │  user who opens the app daily would never reach the first run.
           ⋮  (up to a day later, app may be closed)
           ▼
HiltWorkerFactory → BudgetAlertWorker.doWork()
│
├─ sessionLock.isUnlocked.value == false  →  Result.retry()   ← BEFORE the repository injects
│                                            provideDatabase throws while locked (SEC-002).
│                                            retry, not failure: nothing is wrong, and the band
│                                            stays crossed, so no alert is lost.
│
├─ repository.get().pendingAlerts()                            Provider<T> — resolved only here
│   ├─ database.budgetDao().observeCategoryBudgets(...)
│   ├─ engine.alert(...)                                       → shape C
│   └─ minus rows already in budget_alert for this month
│
└─ for each pending alert:
    ├─ repository.get().markNotified(alert)      CLAIM FIRST
    │   └─ budgetAlertDao().insertIfNew(...)     OnConflictStrategy.IGNORE
    │       └─ UNIQUE(profile_id, budget_id, month_start_iso_date, band)
    │           returns false if another run already claimed it → stay silent
    │
    └─ notifier.notify(alert)                    ONLY IF THIS RUN CLAIMED IT
        ├─ POST_NOTIFICATIONS not granted (API 33+)      →  return false
        ├─ areNotificationsEnabled() == false            →  return false
        │   Both checks are inlined here rather than extracted into a helper: Android lint's
        │   MissingPermission only follows a permission check within a single method, so a
        │   helper would turn a build-blocking error into a suppression.
        ├─ compose(alert)                        strings.xml + MoneyFormatter
        ├─ NumericGuardrail.verify(text, allowedAmounts, allowedPercents)   AI-ARC-004
        │   └─ not Pass  →  return false, post nothing
        └─ NotificationManagerCompat.from(context).notify(...)
```

**Claim before notify, and the claim is a database constraint.** The unique index is the mechanism;
the `notify_once_per_band_per_month` flag in the rule row only documents the intent. A crash between
the two steps costs one notification, never a duplicate.

The other four workers are this same shape: lock check → read → act → `success`/`retry`.

---

## 4 · Shape C — an engine

Pure Kotlin, no Android, no clock, no I/O. **Fixed input → fixed output** (P-08).

```
repository (owns the Clock, the DAO and the profile)
│
└─ assembles BudgetAlertInput(categoryId, categoryName, budgeted, spent, nowUtcMillis, rules)
    │   The repository is the only layer that may read a clock or a row. The engine is handed
    │   everything and reaches for nothing.
    │
    └─ BudgetEngine.alert(input) : Result<BudgetAlert?, AppError>       ← the one public interface
        └─ DefaultBudgetEngine.alert                                    internal impl (ARC-003)
            └─ BudgetAlertBands.evaluate(input)
                ├─ budgeted == ZERO      → null          a legitimate answer, not an error
                ├─ usedBps = spent.minor * 10_000 / budgeted.minor      integer only (MNY-002)
                ├─ usedBps >= exceededPct * 100 → EXCEEDED
                │  usedBps >=     warnPct * 100 → WARN
                │  else                          → null
                └─ BudgetAlert(band, usedBps, budgeted, spent, overspentBy, provenance)
                    └─ provenance.evidence = [ RuleCitation("RULE-BUD-ALERT", "1.0") ]
                        └─ init { require(evidence.isNotEmpty()) }   ← P-02, enforced in the type
```

**Thresholds come from data, not code.** `BudgetRules` is a typed mirror of the row in
`ai/rules/rules-kb.json`; `RulebookDriftTest` reads the real JSON and fails the build when the two
disagree (see [ADR-0017](docs/adr/0017-budget-thresholds-stay-a-typed-mirror.md)).

**A result that cannot cite a rule cannot be constructed.** The `require` in `init` is why "show the
work" is not a convention someone can forget — it is a precondition on the type.

**The review is the same shape, one call further.** `BudgetEngine.review(input)` walks every
budgeted category, decides materiality the identical way `alert` decides a band (`varianceBps`
against `RULE-BUD-REVIEW.min_variance_pct`), and for each material row calls **its own `suggest`**
to price a proposal — the same `BudgetEngine.suggest` a plain suggestion card calls, so a reviewed
proposal is provably the same number. See `domain/engines/budget/ENGINE.md` for the full formula;
it is not re-drawn here because it is the alert diagram above with one more internal call.

---

## 5 · Where the layers are enforced

Not by review — by the build. Each of these fails compilation or the `:lint` task.

| Rule | What it stops | Enforced by |
|------|---------------|-------------|
| ARC-001 | `feature` → `feature` dependency | Gradle module graph; there is no dependency to import |
| ARC-002 | An Android import in `:core:model` / `:domain:*` | Those are pure-Kotlin JVM modules — `android.*` is not on the classpath |
| ARC-005 | A ViewModel touching a DAO | Room types are `internal` to `:core:database`; only `:data:repository` depends on it |
| MNY-001 | `Double totalAmount` | `CfoMoneyAsFloatingPoint` (`:lint`) |
| TIM-001 | `System.currentTimeMillis()` in domain code | `CfoWallClockInDomain` (`:lint`) |
| ARC-006 | `GlobalScope.launch` | `CfoGlobalScope` (`:lint`) |
| §21.6 | An amount or a name in a log line | `CfoPiiInLogs` (`:lint`) |
| §21.6 | A hardcoded user-facing string in `:feature:*` | `CfoHardcodedUiString` (`:lint`) |

---

## 6 · Keeping this file true

Update it **in the same commit** that changes a call path — a flow map that lags is worse than none,
because it is believed. In practice that means: a new entry point, a new worker, a new shape, or a
changed order of calls in the spine. Renaming a private helper inside one of these boxes does not.

Each session also records its own delta in `docs/sessions/`, so the history of *how* the flow got
here stays readable without this file growing a changelog.
