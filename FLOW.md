<!--
  Why:  docs/Architecture.md documents which module may depend on which — static structure. Nothing
        documented how execution actually travels: where the process starts, what runs before the
        first pixel, and what calls what once it does. 198 Kotlin files across 33 modules with no
        runtime map means every reader re-derives the same three paths by grep.
  What: FLOW — entry points, the cold-start spine, and one worked path for each of the four shapes
        the codebase repeats (screen, background worker, engine, home-screen widget).
  Result: A reader can follow a real call chain end to end without opening a file, and knows which
        shape any unfamiliar code belongs to.
  Changelog:
    2026-08-14 — Created. Traced against the code at commit 6ffccc5.
    2026-08-17 — Issue 5.5 added §4.5, Shape D: the widget. The first surface that renders outside
        the app's process and must work while the app is locked, which is why it earned a section
        rather than being a fourth screen.
-->

# Flow — how execution travels

> **Binding rule:** [`CLAUDE.md` §10](CLAUDE.md). Static structure: [`docs/Architecture.md`](docs/Architecture.md).
> Why any of it is this way: [`DECISIONS.md`](DECISIONS.md). What changed in a given session:
> [`docs/sessions/`](docs/sessions/).
> Companion diagram: [FLOW.drawio](FLOW.drawio).

**How to read this.** `→` is a call. Indentation is nesting. Names are real and greppable; if an
arrow here does not match the code, the code is right and this file is stale — fix it in the same
commit that made it stale.

> `ponytail:` this maps the **spine and four shapes**, not all 198 files. Everything else in the
> app is one of these shapes with different nouns, so a per-file call graph would add pages
> and no understanding, and would be stale within a week. **Ceiling:** issue 5.5 built `:widget`,
> which earned §4.5 by being the first surface that renders outside the app's process and must work
> while it is locked. `:sync:backup` and `:ml:llm` still do not exist; a fifth section waits for
> one of them to be a genuinely different shape rather than another worker.

---

## Entry points

There are exactly three ways into this app. Every stack trace starts at one of them.

| # | Entry point | File | Triggered by |
|---|-------------|------|--------------|
| 1 | `CfoApplication.onCreate()` | `app/.../CfoApplication.kt` | Process start — always first, before any UI |
| 2 | `MainActivity.onCreate()` | `app/.../MainActivity.kt` | Launcher icon, or a notification/widget tap |
| 3 | `<Worker>.doWork()` | `app/.../work/*.kt` | WorkManager, on its own schedule — **the app need not be open** |

Entry point 3 is the one that surprises people: seven workers can run with no Activity alive.

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
│   ├─ widgetBlurWatcher.start()            masks the home-screen widget with the app (5.5)
│   └─ schedule() × 7 workers                NetWorthSnapshot · BalanceIntegrity ·
│                                            ScheduledTransaction · SmsScan · BudgetAlert ·
│                                            CardAlert (daily, 6.1) ·
│                                            WidgetRefresh (6-hourly, + one refreshNow now)
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
                                                             (4.7: an undecidable band is simply
                                                              absent here; only a failed *status*
                                                              reaches that catch)
```

`observeAlerts()` is literally `observeBudgets().map { mapNotNull(::alertFor) }`, so calling it
beside `observeBudgets()` opened that three-query `combine()` a second time for data the first
subscription already had — and let one read failure show two different faces (a banner from one
collector, a silently-emptied line from the other). `alertFor` is the same engine call reached
without the second subscription. `:feature:budgets` still calls `observeAlerts()` and is unchanged.

### 2.0 · The privacy blur — one flag, every amount (issue 5.3)

Not a screen: a value that travels **down the whole tree** and is read at the two places money is
rendered. The only path in the app shaped this way, and deliberately so.

```
MainViewModel.isPrivacyBlurred : StateFlow<Boolean>
└─ SettingsStore.observe()                    Proto DataStore — privacy_blur_enabled
    │   (read failure → false: the blur is a display preference, not a security
    │    boundary. The boundary is AppLockGate/SessionLock, which fails CLOSED.)
    ⇣
MainActivity.AppContent
├─ PrivacyCaptureGuard(secure = isBlurred)    DisposableEffect on the Activity window
│   └─ FLAG_SECURE add / clear                → screenshot, screen-record, share = blank
│       (cleared onDispose, or a blurred session leaves every later screen uncapturable)
├─ CfoPrivacyBlurToggle(blurred, onToggle)    app chrome, top-end — one tap from ANY screen
│   ⇡  onToggle → MainViewModel.setPrivacyBlur(enabled)
│       └─ SettingsStore.setPrivacyBlurEnabled()   persists, then re-emits above
└─ CompositionLocalProvider(LocalPrivacyBlur provides isBlurred) {
       CfoNavHost(...)                        every destination, existing or added later
   }
        ⇣  read at exactly two places, in :core:designsystem
        ├─ CfoAmountText(amount)              14 call sites — the component path
        └─ maskedAmount(amount)               ~24 call sites — the `stringResource("%1$s of %2$s")` path
             └─ maskOf(amount) → "-₹•••••••"  FIXED width, sign kept, no digits
```

**Why a `CompositionLocal` and not a parameter.** The alternative is a `Boolean` threaded through
forty call signatures, and the one screen somebody forgets is the one still showing a balance in a
meeting. A local cannot be forgotten.

**Why two read points and not one.** Amounts reach the screen two ways — a composable, and a
formatted string dropped into a `stringResource` placeholder, because a sentence cannot contain a
composable. `DashboardPrivacyBlurTest` sweeps every rendered string for `₹`-plus-digit, so a future
screen that reaches for `MoneyFormatter.format` in a composable fails the build on the dashboard.

**The worker reads the same flag, separately**, because it has no composition:

```
BudgetAlertWorker.doWork()
└─ settingsStore.observe().first()            once per batch, not per alert
    └─ notifier.notify(alert, blurAmounts)
        └─ compose(alert, blurAmounts)        blurred → category + band, NO digits at all
                                              (a lock-screen notification renders without the
                                               app lock — the most exposed surface there is)
```

### 2.05 · Export and import — the only path that can destroy everything (issue 5.4)

Two directions, and the ordering in the second is the whole safety of the feature.

```
EXPORT
DashboardEvent.ExportRequested
└─ ArchiveRepository.export()                        data/repository — ARC-005
    ├─ activeProfileId.first()                       the demo exports itself, never the real profile
    └─ archiveDao().<14 reads>                       SELECT *, tombstones INCLUDED, ORDER BY id
        └─ Json.encodeToString(CfoArchive(...))      entities ARE the format (ADR-0023)
    ⇣  ArchiveUiState.ReadyToWrite(json)
ArchiveHost (STATEFUL half — owns the Uri)
└─ CreateDocument("application/json") → context.writeText(uri, json)
    ⇡ DashboardEvent.ExportWritten(written)  →  Exported | Failed("archive.writeFailed")

IMPORT
ArchiveHost └─ OpenDocument(["application/json"]) → context.readText(uri)
    ⇡ DashboardEvent.ImportPicked(json)
        └─ ArchiveUiState.PendingImport(json)        NOTHING TOUCHED YET
            └─ AlertDialog "Replace everything on this device?"
                ├─ ImportCancelled → Idle            costs nothing
                └─ ImportConfirmed
                    └─ ArchiveRepository.import(json)
                        ├─ decode(json)              PARSE + schemaVersion CHECK **FIRST**
                        │   └─ Err → Validation(field), database untouched
                        └─ database.withTransaction {
                               wipe(profileId)       reuses DemoDao's 14 deletes, FK order
                               restore(archive)      archiveDao inserts, REPLACE
                           }
    ⇣  ArchiveUiState.Imported(rows, exportedAt)
```

**Three orderings carry the risk, and each is asserted.**

*Parse before delete.* `decode` runs outside the transaction. The failure this prevents is a wipe
followed by a parse error, which would be unrecoverable — `ArchiveRepositoryTest` asserts the row
count is unchanged after a refusal.

*Pick is not import.* `ImportPicked` only opens the dialog. `DashboardArchiveTest` asserts the
repository was never called, because wiring those together would look identical on screen until
someone's data was gone.

*One transaction.* Wipe and restore commit together or not at all.

**The Uri never leaves `ArchiveHost`.** The launchers need an `ActivityResultRegistryOwner`, so they
live in the stateful half; the body gets a plain lambda and the ViewModel deals in text. Putting them
in the stateless body broke every Paparazzi baseline at once, which is how the constraint was found.

### 2.1 · The dashboard's headline figure (issue 5.2)

Shape C again, but it is the **first read in the app assembled from other repositories** rather than
straight from DAOs — and the first that can answer "there is no figure":

```
DashboardViewModel.observeSafeToSpend()             the collector that ended the last placeholder
└─ SafeToSpendRepository.observeSafeToSpend()       data/repository — ARC-005
    └─ activeProfileId.flatMapLatest { profileId ->
        ├─ MonthWindow.current(clock.today())        injected Clock (TIM-001), inside the lambda
        └─ combine(
             QuickSetupRepository.observeLatestEnvelopes(profileId)   income basis + savings target
             TransactionRepository.observeMonthCashFlow()             income fallback
             TransactionRepository.observeNatureBreakdown()           §8.3 trueSpend + invested (4.3)
             TransactionRepository.observeUpcoming()                  → scheduledCommitments(monthEnd)
             recurringRuleDao().observeForProfile(profileId)          → billsDue(today, monthEnd)
           ) { … }
             ├─ incomeBasis(envelopes, cashFlow.income)  ?: → emits null   ← no basis, no figure
             ├─ billsDue(...).deduplicatedAgainst(scheduled)   name+date, so rent is not counted twice
             └─ engine.compute(SafeToSpendInput(...))          → shape C
                 └─ SafeToSpend(amount, lines, provenance[RULE-STS v1.0])
       }.flowOn(dispatchers.io)
    ⇣
_uiState.update { copy(safeToSpend = figure, isLoading = false) }
    ⇣
DashboardScreen.SafeToSpendSection(figure)
    ├─ CfoAmountText(figure.amount, showSign = true)     negative is a real answer
    ├─ figure.lines.forEach { … line.signedAmount }      the breakdown IS the result (P-02)
    └─ dashboard_reason_rule(RULE-STS, 1.0)
```

**Three things this path does that no earlier one did.**

*A repository reads other repositories.* `observeNatureBreakdown()` is a five-way join plus a
per-transaction engine call; re-deriving it here would be a second definition of "what this month's
money became". The seam is not new — `RoomReceiptRepository` (3.8) and `RoomSmsRepository` (3.9) both
take a `TransactionRepository` — and ARC-005 holds either way: every DAO touch is still in a
repository.

*The absence is computed, not defaulted.* `incomeBasis` returns `null` when the profile has neither
envelopes nor posted income, and the flow emits `null` rather than calling the engine with a zero.
`SafeToSpendInput` would happily accept `Money.ZERO`, so this rule lives in the repository and its
test, not in a `require`.

*`isLoading` is turned off by this stream and no other.* It clears in the same `update` as the figure,
and in the `.catch` too — `DashboardContent` returns early while loading, so an error behind a raised
flag renders as a permanent "Working out your position…" with no banner underneath it. `Refresh` no
longer re-raises the flag: the collectors are live, and a cold Flow does not re-emit for a button.

---

### 2.2 · A loan's EMI split — the only figure with no row behind it (issue 6.2)

Shape A with one difference worth drawing: **nothing on this path is stored**. The five columns in
`loan` are the terms; every instalment the user ever sees is derived from them at read time
([ADR-0026](docs/adr/0026-amortisation-schedule-is-derived-not-stored.md)).

```
AccountsScreen()                                    feature/accounts/AccountsScreen.kt
└─ hiltViewModel<AccountsViewModel>()
    └─ init { observeAccounts(); observeCards(); observeLoans() }   THREE collectors, no combine()
        └─ observeLoans()
            └─ LoanRepository.observeNextInstalments()      data/repository — ARC-005
                └─ activeProfileId.flatMapLatest { profileId ->
                     database.loanDao().observeForProfile(profileId).map { rows ->
                     │   ┌── the ONE clock read on this path (TIM-001)
                     ├─  today = clock.today()
                     ├─  rows.mapNotNull { entity ->
                     │     ├─ nextInstalmentNumber(loan, today)
                     │     │    first k in 1..tenure where firstEmi.plusMonths(k-1) !isBefore today
                     │     │    └─ null → the loan is repaid → ABSENT from the map, not zeroed
                     │     └─ engine.instalment(LoanInstalmentInput(loan, k, now))   → shape C
                     │         └─ AmortisationRow(number, dueIsoDate, amount,
                     │                            principal, interest, opening, closing)
                     │             └─ init { require(principal + interest == amount) }   ← P-02
                     └─  }.toMap()
                   }.flowOn(dispatchers.io)
        ⇣
    _uiState.update { copy(loans = instalments) }   →  AccountsUiState.loans: Map<id, Row>
        │   .catch { copy(loans = emptyMap()) }     a loan read that fails does NOT blank the list
        ▼
    AccountRow(account, card, instalment = uiState.loans[account.id])
    └─ if (account.type == LOAN) NextInstalment(instalment)
        ├─ instalment == null  → "Add this loan's amount, rate and tenure"   never a ₹0 EMI (P-03)
        └─ else → "Next EMI ₹26,034.70 on 5 Nov 2026"
                  "₹4,784.70 principal · ₹21,250.00 interest"     both halves, they sum (P-02)
                  └─ maskedAmount(...)                            the 5.3 blur reaches this row
```

The write side is the same account-then-terms order issue 6.1 established, with the engine consulted
**before** anything is stored:

```
AccountEditorScreen  ⇡ onEvent(AccountEditorEvent.Save)
└─ AccountEditorViewModel.save()
    ├─ repository.create(draft) / .update(id, draft)      the account row first — loan is keyed by it
    └─ saveTypeTerms(savedId, state)                      the two sections are mutually exclusive
        ├─ saveCardTerms(...)      6.1, unchanged
        └─ saveLoanTerms(...)
            ├─ !showsLoanFields || !hasLoanTerms → Ok(Unit)     blank section is a supported state
            ├─ state.toLoan(id)                                 parsed ONCE, here (MNY-001)
            │   ├─ MoneyFormatter.parse(principalText)          → Money paise
            │   ├─ parseRateBps(annualRateText)                 "8.5" → 850 bps (MNY-002)
            │   └─ runCatching { Loan(...) }.getOrNull()        Loan's requires, caught not thrown
            └─ LoanRepository.save(loan)
                ├─ accountDao().findWithBalance(...) == null      → Err(NotFound)
                ├─ type != LOAN                                   → Err(Validation("account.notALoan"))
                ├─ engine.emi(LoanTermsInput(loan)) is Err        → that Err, NOTHING WRITTEN
                │   └─ EMI ≤ first month's interest ⇒ never amortises
                └─ loanDao().upsert(LoanEntity(...))              createdAt preserved across an edit
                    └─ Room invalidates → observeNextInstalments() re-emits → the row updates
```

**The guard runs before the write, not after.** Terms that produce no schedule are terms the user has
to fix while the form is still open; a row saved first would show an empty schedule with nothing on
screen explaining it.

**No worker, no notifier, no channel.** 6.2 ships the arithmetic, not an EMI reminder — the worker
count is still seven.


### 2.3 · A holding's return — the first figure with no clock behind it (issue 6.3)

Shape A again, with the difference that makes it worth drawing: **this path reads no clock at all**.
A holding's closing cash flow is dated by the day its price was observed, so the same rows give the
same return tomorrow ([ADR-0028](docs/adr/0028-xirr-by-bisection-over-the-daily-growth-factor.md)).
The clock is still read in the repository — for row stamps and provenance — but nothing it returns
reaches the arithmetic.

```
HoldingsScreen(onDone)                              feature/accounts/HoldingsScreen.kt
└─ hiltViewModel<HoldingsViewModel>()
    └─ savedState["accountId"]                      the typed route's one argument (ARC-001)
    └─ init { observeHoldings() }
        └─ InvestmentRepository.observeForAccount(accountId)     data/repository — ARC-005
            └─ activeProfileId.flatMapLatest { profileId ->
                 combine(                                   TWO streams, one engine pass
                   investmentHoldingDao().observeForAccount(accountId),
                   investmentLotDao().observeForProfile(profileId),
                 ) { holdings, lots ->
                 │   ┌── clock read here, and it reaches provenance ONLY (TIM-001)
                 ├─  now = clock.nowUtcMillis()
                 ├─  byHolding = lots.groupBy { it.holdingId }        one pass, not N filters
                 └─  holdings.mapNotNull { entity ->
                       ├─ entity.toHolding()                unknown asset_class → null → dropped
                       ├─ its = byHolding[entity.id].mapNotNull { it.toLot() }
                       │                                     unknown kind → dropped, NEVER defaulted
                       └─ engine.holding(HoldingInput(holding, its, now))          → shape C
                           ├─ CashFlows.netQuantity(lots)    Σ BUY − Σ SELL; INCOME moves no units
                           ├─ CashFlows.currentValue(...)    units x price, ONE HALF_EVEN rounding
                           │   └─ price == null && units > 0 → null    absent, never ₹0 (P-03)
                           ├─ CashFlows.of(holding, lots, value)
                           │   BUY → (date, −amount) · SELL/INCOME → (date, +amount)
                           │   └─ + (pricedOn, +value)       the terminal flow, when there is one
                           └─ Xirr.solve(flows)              ADR-0028
                               ├─ groupBy(day).sum()         same-day flows coalesced FIRST
                               ├─ < 2 distinct days          → TOO_FEW_FLOWS
                               ├─ all one sign               → SAME_SIGN
                               ├─ sign(F(0.98)) == sign(F(1.02)) → NOT_BRACKETED
                               └─ 128 bisections, no early exit → r = x^365 − 1 → bps
                     }
               }.flowOn(dispatchers.io)
        ⇣
    _uiState.update { copy(holdings = priced, isLoading = false) }
        │   .catch { copy(holdings = emptyList(), errorCode = ...) }   stale figures are worse than none
        ▼
    HoldingsList → HoldingRow(holding)
    ├─ HoldingValue   value == null → "Not valued yet"        never ₹0.00 (P-03)
    ├─ HoldingCost    "₹11,500.00 invested" · "₹531.60 gain"  the inputs beside the answer (P-02)
    ├─ HoldingReturn  xirrUnavailable → its own sentence      never an empty cell (P-02)
    │                 else → "15.6% a year"                   built from Int bps, never a Double
    └─ maskedAmount(...)                                      the 5.3 blur reaches every amount
    ▼
    Text(holdings_disclaimer)     §11.1's wording, ALWAYS rendered, editor open or not (P-07)
```

The accounts list consumes the same repository through its sibling read, as a **fourth** collector
beside cards and loans — never a `combine`, for the reason 2.2 gives:

```
AccountsViewModel.init { observeAccounts(); observeCards(); observeLoans(); observeInvestments() }
└─ InvestmentRepository.observeByAccount()   → Map<accountId, List<HoldingPerformance>>
    └─ AccountsUiState.investments           an account with nothing is ABSENT, not zeroed (P-03)
        └─ AccountRow → if (type in INVESTABLE_TYPES) InvestmentSummary(figures.holdings)
            ├─ null/empty → "Add what this account holds"
            └─ else → "₹9,411.60 across 2 holdings"   only PRICED holdings are summed
```

The write side mints ids in the repository and writes the holding **before** its lots:

```
HoldingsScreen  ⇡ onEvent(HoldingsEvent.SaveEditor)
└─ HoldingsViewModel.save()
    ├─ editor.toDraft(accountId)                     parsed ONCE, here (MNY-001)
    │   ├─ MoneyFormatter.parse(unitPrice)           → Money paise
    │   ├─ price XOR date present                    → null → fieldError, NOTHING WRITTEN
    │   └─ HoldingDraft(...)                         a draft, because a saved holding needs an id
    ├─ editor.lots.map { it.toDraftOrNull() }
    │   └─ units via BigDecimal x 10⁹, HALF_EVEN     never toDouble() — a fund quotes 3 decimals
    └─ InvestmentRepository.saveHolding(draft, id)
        ├─ accountDao().findWithBalance(...) == null → Err(NotFound)
        ├─ type !in {INVESTMENT, GOLD, CRYPTO}       → Err(Validation("account.notInvestable"))
        └─ ids.newId("holding") when id == null      injected, never UUID.randomUUID() (P-08)
            └─ upsert → then saveLot(draft.copy(holdingId = written), lotId) per lot
                └─ Room invalidates → both observers re-emit → every figure re-derives
```

**Nothing on this path is cached.** The value, the cost, the gain and the return are recomputed from
the lots and the price on every emission ([ADR-0027](docs/adr/0027-asset-class-is-a-column-on-the-holding.md)),
so correcting a mistyped lot corrects all four with nothing to invalidate.

**No worker, no notifier, no channel.** 6.3 ships the arithmetic and a screen, not a rebalancing
alert — the worker count is still seven. A money-weighted return has no threshold to tune, so *this*
path still cites no rule; §2.4's does, and the `RulebookDriftTest` arrived with it. `_meta.version`
stays `1.13.0` either way — 6.4 reads three rows that already existed and authored none.

---

### 2.4 · How the portfolio is spread — the first figure that cites a rule to accuse something (issue 6.4)

`FR-INV-002` asks a question no single account can answer, so this is the first read path that
combines **three** streams rather than two: an account's balance is part of the answer, because a
gold account tracked as one number has no holdings to price and would otherwise vanish from the
split.

```
AccountsScreen  → actions.onOpenAllocation
  → CfoNavHost: navigate(CfoRoute.Allocation)          data object — no id; it is every account at once
    → AllocationScreen → AllocationViewModel
      → InvestmentRepository.observeAllocation()
        └─ activeProfileId.flatMapLatest
           ├─ clock.today() read ONCE, outside the combine   so one emission describes one day (TIM-001)
           └─ combine(
              │   accountDao.observeWithBalances(profileId, includeArchived = false, asOf)
              │   investmentHoldingDao.observeForProfile(profileId)
              │   investmentLotDao.observeForProfile(profileId))
              ├─ price(holdings, lots) → InvestmentEngine.holding(..)   §2.3's figures, reused
              ├─ positions(accounts, performances)              ADR-0029 — WHAT THE PORTFOLIO IS
              │   ├─ type in INVESTABLE && includeInNetWorth    savings and property are outside it
              │   ├─ account HAS holdings  → one position per holding
              │   ├─ account has NONE      → one position, valued at its balance,
              │   │                          classed by AssetClass.defaultFor(type)
              │   └─ balance <= 0 or no class → dropped         empty is not unpriced
              └─ InvestmentEngine.allocation(AllocationInput(positions, now, rules))
                 → Allocation.compute
                   ├─ priced = positions with a value           unpriced excluded, never ₹0 (P-03)
                   ├─ total <= 0 → NOTHING_PRICED               a reason on an Ok, not an Err
                   ├─ slices: group by class, drop empties, sort by value desc
                   │   └─ distribute: floors + largest remainder → sums to exactly 10 000 bps
                   └─ flags: narrowest rule first
                       ├─ classCapFlags   RULE-GOLD-CAP / RULE-CRYPTO-CAP   cites ITS OWN row
                       ├─ singleClassFlags   RULE-CONC-15-70 single_class_pct
                       └─ holdingFlags       RULE-CONC-15-70 single_holding_pct
      → AllocationUiState(allocation, isLoading = false)
        → AllocationScreen renders
           ├─ CfoProportionBar + legend         one list, so bar and legend cannot disagree
           ├─ coverage line when unvaluedCount > 0            "Based on 8 of 11 holdings" (P-02)
           ├─ one card per flag: measured, threshold, amount, and "Rule RULE-X v1.0"
           └─ §11.1 disclaimer                  analyses and flags; never recommends (P-07)
```

**This is the first path in the app where a rulebook row accuses something.** Every earlier citation
explained a figure the app *proposed* — a suggested budget, a Safe-to-Spend line. Here the citation
is attached to a warning about what the user already owns, so it is rendered on the card rather than
behind a tap: a warning nobody can trace is one they cannot argue with, and P-07 makes arguing with
it the user's prerogative.

**Each flag cites exactly one row; the result cites all three.** The result names every rule that was
*checked*, so a clean portfolio can say what it was found clean of; a flag names only the rule that
decided it, because pointing "why am I seeing this?" at a rule that had no part in the decision is
worse than citing nothing.

**Nothing here is cached and no clock reaches the arithmetic.** `nowUtcMillis` is stamped into
provenance and never read as a date, so the same portfolio splits the same way tomorrow (P-08). The
engine version moved 1.0 → 1.1 and `AI-INV` in `engine-registry.yaml` with it.

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

The other six workers are this same shape: lock check → read → act → `success`/`retry`.

### 3.1 · The card-payment reminder — the same shape, three differences (issue 6.1)

```
CfoApplication.onCreate()
└─ CardAlertWorker.schedule(context)                           app/work/CardAlertWorker.kt
    └─ enqueueUniquePeriodicWork("card-payment-alerts", KEEP, every 1 day)
           ▼
CardAlertWorker.doWork()
├─ sessionLock.isUnlocked.value == false  →  Result.retry()    same SEC-002 guard
├─ repository.get().pendingAlerts()                            CreditCardRepository
│   ├─ creditCardDao().forProfile(profile) + accountDao().findWithBalance(...)
│   ├─ balance is a liability → negated once here, so the engine sees a magnitude
│   ├─ engine.alert(CardAlertInput(card, today = clock.today(), outstanding, rules))   → shape C
│   └─ minus rows already in card_alert for this cycle
├─ settingsStore.observe().first()  →  privacyBlurEnabled      read ONCE per batch (ADR-0022)
└─ for each pending alert:
    ├─ repository.get().markNotified(alert)      CLAIM FIRST
    │   └─ UNIQUE(profile_id, account_id, cycle_start_iso_date, kind)
    └─ notifier.notify(alert, blurAmounts)       ONLY IF THIS RUN CLAIMED IT
        └─ NumericGuardrail.verify(...)          AI-ARC-004, on alert.usedPercent as posted
```

1. **The claim key is the statement date, not a month.** A card billing on the 25th has a cycle
   straddling two calendar months, so a month-keyed claim would let one statement's reminder fire
   twice — the one place this differs structurally from `budget_alert`.
2. **Two kinds can fire on the same day** (`DUE_SOON` and `UTILISATION`), so `pendingAlerts()`
   returns a list per card and the unique index carries `kind`.
3. **The blur flag is read here, not in the notifier**, once per batch: a DataStore read per
   notification would be work for nothing, and a failed read means "not blurred" exactly as it does
   in `MainViewModel`.

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

**What happens when an engine returns `Err`** (issue 4.7). Every `BudgetEngine` method is
`runCatchingToResult`, so every one can. `RoomBudgetRepository` follows the line the engine's own
interface already draws — three of the four document `Ok(null)` as a legitimate answer, and `status`
does not:

```
engine.suggest / .alert / .review  → Err ──→ getOrNull() ──→ null
                                                             └─ no offer / no band / no card;
                                                                the figures beside them survive
engine.status                      → Err ──→ throw BudgetEngineFailure(appError)
                                              ├─ on a Flow path  → consumer's .catch{} → error banner
                                              └─ inside a suspend read (pendingAlerts,
                                                 acceptSuggestion, acceptReviewProposal)
                                                 → runCatchingToResult catches → Err   ← §21.6 holds
```

`BudgetEngineFailure` extends plain `Exception`, **not** `IllegalStateException`, precisely so the
second arm works: `runCatchingToResult` rethrows `ISE`/`IAE` as programmer errors, so `error(...)`
there — the shape `NetWorthRepository.computeFrom` uses — would escape into `viewModelScope` and
`CoroutineWorker` as a crash.

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

## 4.5 · Shape D — the home-screen widget (issue 5.5)

The fourth shape this file reserved a slot for. It is the only surface that renders **outside the
app's process**, and the only one that must work while the app is **locked** — which is what makes
it a shape rather than another screen.

```
WRITE — two paths, deliberately separate                     :app is the only writer
│
├─ CfoApplication.onCreate()
│   ├─ WidgetRefreshWorker.schedule(this)      periodic 6h, KEEP, NO Constraints (P-04)
│   ├─ WidgetRefreshWorker.refreshNow(this)    one-shot REPLACE — this launch's figures
│   └─ widgetBlurWatcher.start()               beside smsConsentWatcher, same seam
│
├─ WidgetRefreshWorker.doWork()                            shape B — the FIGURES path
│   ├─ !sessionLock.isUnlocked.value → Result.retry()      BEFORE Provider<T>.get() (SEC-002)
│   ├─ settingsStore.observe().first()                     blur, same read BudgetAlertWorker does
│   ├─ safeToSpend.get().observeSafeToSpend().first()      null is an answer, never ₹0 (P-03)
│   ├─ netWorth.get().observeCurrent().first()
│   └─ CfoWidget.writeFigures(...)   →  null figure REMOVES its key, never writes 0
│
└─ WidgetBlurWatcher.start()                               the BLUR path — no database at all
    └─ settingsStore.observe().map{ blurEnabled }.distinctUntilChanged()
        └─ CfoWidget.writeBlurred(context, blurred)
                                       ⇣
                    files/datastore/appWidget-<id>.preferences_pb
                    safe_to_spend_minor · net_worth_minor · blurred     ← THE CACHE
                                       ⇣
READ — in the launcher's process, and it may not fail
CfoWidgetReceiver (plain, no Hilt)  →  CfoWidget.provideGlance()
└─ currentState<Preferences>().toWidgetSnapshot()      no DI, no DAO, no suspend that can throw
    └─ GlanceTheme { CfoWidgetContent(snapshot) }      light/dark from the system, not CfoTheme
        ├─ amountText(amount, blurred, pending)        THE only place a digit can reach a launcher
        │   ├─ amount == null → "Not yet worked out"   absence is not zero
        │   ├─ blurred        → MoneyFormatter.mask()  "₹•••••••", fixed width (ADR-0022)
        │   └─ else           → MoneyFormatter.format()
        └─ clickable(actionStartActivity(launchIntent))   resolved from the package manager,
                                                          so :widget never depends on :app
```

**Why the two writers are split, and it is the whole design.** `writeFigures` needs the database
and therefore cannot run while the app is locked. `writeBlurred` needs nothing but a preference
file. A user taps the blur toggle because someone is looking *now* — often having just locked the
phone. Folding the flag into the refresh would make hiding depend on the database, and the amounts
would stay on the home screen at the exact moment they were asked to go.
[ADR-0024](docs/adr/0024-the-widget-renders-from-glance-state-not-the-database.md).

**Glance's state is the cache, not a copy of one.** Net worth had a snapshot table already;
Safe-to-Spend had none — `SafeToSpendRepository` recomputes it live from five Room reads. Either
way a cache had to exist outside SQLCipher, and putting it in Glance's own store means the value
read is the value the redraw was triggered for. There is no second store to drift.

**Nothing here is derived from the clock**, which is what makes the refresh idempotent by
construction: two runs with unchanged data write identical bytes. That is also why the widget shows
no "last updated" line — it would need the profile zone (TIM-001) on the render path.

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
| §21.6 | A hardcoded user-facing string in `:feature:*`, `:core:designsystem` or `:widget` | `CfoHardcodedUiString` (`:lint`) |

---

## 6 · Keeping this file true

Update it **in the same commit** that changes a call path — a flow map that lags is worse than none,
because it is believed. In practice that means: a new entry point, a new worker, a new shape, or a
changed order of calls in the spine. Renaming a private helper inside one of these boxes does not.

Each session also records its own delta in `docs/sessions/`, so the history of *how* the flow got
here stays readable without this file growing a changelog.
