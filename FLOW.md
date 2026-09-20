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
    2026-09-06 — Issue 7.4 added §2.7: goal progress derived from linked movements, and the write
    path that creates the links. Still Shape A.
    2026-09-18 — Issue 8.1 added §2.06: the encrypted backup. Shape A, wrapped around §2.05's export.
        Issue 8.2 added its restore, wrapped around §2.05's import, and the import's profile check.
    2026-09-19 — Issue 9.1 added §2.07: AI-CLS Stage 2 on the dashboard.
    2026-09-19 — Issue 9.2 added §2.08: the 90-day forecast.
    2026-09-19 — Issue 9.3: §2.08 runs AI-SEAS before AI-FCT, over closed-month category history.
    2026-09-20 — Issue 9.4 added §2.09: the health score, assembled from seven repositories.
    2026-09-03 — Issue 7.3 added §2.6, the goal waterfall. Still Shape A — a screen — but the first
        read assembled from four repositories, and the first write driven by a gesture, so it is
        traced beside §2.5 rather than folded into it.
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
> while it is locked. `:sync:backup` (still a placeholder — issue 8.1's backup is a screen, not a worker) and `:ml:llm` still do not exist; a fifth section waits for
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
                        ├─ decode(json, profileId)   PARSE + schemaVersion + profile CHECK **FIRST**
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

### 2.06 · The encrypted backup and its restore (issues 8.1, 8.2)

Shape A again, from the settings screen. It reuses §2.05's export verbatim and adds two gates and a
cipher around it; the database is never read a second way.

```
SettingsEvent.CreateBackup
└─ BackupActions.createBackup()                       canCreateBackup re-checked, never trusted
    ├─ passphrase = passphraseText.toCharArray()
    ├─ BackupUiState(status = Sealing)               BOTH FIELDS CLEARED in the same update
    └─ BackupRepository.create(passphrase)            data/repository — ARC-005
        ├─ consents.observe(CLOUD_BACKUP).first()    P-01 gate **FIRST**; unreadable = refused
        │   └─ not granted → Validation("backup.consent")   archive never read
        ├─ ArchiveRepository.export()                exactly §2.05's export
        ├─ withContext(dispatchers.default)
        │   └─ BackupCipher.seal(bytes, passphrase)  core/crypto — SEC-003, SEC-005
        │       ├─ salt ← SecureRandom (16 B)
        │       ├─ BackupKdf.derive → Argon2id        BouncyCastle, the ONLY call into it (ADR-0039)
        │       └─ AesGcmJce(key).encrypt(pt, aad = header)   Tink draws the nonce; key zeroed after
        ├─ plaintext.fill(0)
        ├─ audit.record(BACKUP_CREATED)              best-effort; never undoes a backup
        └─ finally: passphrase.fill('\u0000')        every path, success or refusal
    ⇣  BackupStatus.ReadyToWrite(bytes)
BackupFileHost (STATEFUL half — owns the Uri)
└─ CreateDocument("application/octet-stream") → context.writeBytes(uri, bytes)   "wt" truncates
    ⇡ SettingsEvent.BackupWritten(written)  →  Written | Failed("backup.writeFailed")

REVOCATION
ConsentToggled(CLOUD_BACKUP, false) → observeConsents → ReadyToWrite dropped to Idle
```

**The file:** `"CFOB" | v1 | memoryKib | iterations | parallelism | saltLen | salt` (31 bytes, the
GCM associated data) then Tink's `nonce | ciphertext | tag`.

**Restore (issue 8.2)** — every refusal lands before the transaction opens:

```
BackupFileHost └─ OpenDocument(["*/*"]) → context.readBytes(uri)
    ⇡ SettingsEvent.RestoreFilePicked(bytes)  →  RestoreStatus.Picked(bytes)      NOTHING TOUCHED
        └─ RestorePassphraseChanged … ConfirmRestore   ("Replace everything with this backup")
            └─ BackupActions.confirmRestore()          passphrase cleared from state
                └─ BackupRepository.restore(bytes, passphrase)    NO consent (data comes IN)
                    ├─ BackupCipher.open               format + KDF bounds → GCM tag
                    │   └─ Err → Crypto("backup.open") | Validation("backup.*")   passphrase zeroed
                    ├─ ArchiveRepository.import(json)  §2.05's import:
                    │   ├─ decode(json, activeProfile) parse · schemaVersion · **profile**   FIRST
                    │   └─ withTransaction { wipe; restore }
                    └─ audit.record(BACKUP_RESTORED)   only on Ok
    ⇣  Restored(rows)  |  Picked(bytes, failure)  — a wrong passphrase keeps the file for a retry
```

### 2.07 · What recurs — AI-CLS Stage 2 (issue 9.1)

Shape A. The ledger read is §4.3's split-aware one. The engine only scores.

```
DashboardViewModel.observeStreams()
└─ StreamRepository.observeStreams()                 data/repository — ARC-005
    ├─ window = the 6 closed months before today     MonthWindow (TIM-001)
    └─ combine(
         transactionDao().observeNatureCandidates()  one row per unsplit txn / live split line
         recurringRuleDao().observeForProfile()      confirmed, live, outflow → Obligations.of()
       ) { rows, rules ->
         streams = expense rows (amount < 0) grouped by:
                   a confirmed rule's merchant → "recurring:<merchant>" (FIXED, CLS-STR-002)
                   else category id ("uncategorised" if none); a rule-named category → obligation
                   priorKey = "<profile>:category:<key>" → key, else null
         StreamEngine.classify(StreamInput)          domain/engines/stream — pure, exact BigDecimal
           └─ per stream: pin → obligation → cold start (n < 2: prior) → §8.2 score
       }
    ⇣  Result<StreamProfile>  →  uiState.streamProfile  →  StreamLoadSection
       "Fixed · Semi-fixed · Flexible", estimate note, "Rules: CLS-STR-… CLS-CAT-…"   (masked when blurred)
```

### 2.08 · The next 90 days — AI-FCT (issue 9.2)

Shape A. The one read that joins four repositories' worth of sources — and it takes balances and
streams from their own repositories rather than re-deriving them (ADR-0007, ADR-0043).

```
DashboardViewModel.observeForecast()
└─ ForecastRepository.observeForecast()              data/repository — ARC-005
    ├─ today = clock.today(); seed = today.toEpochDay()                  (TIM-001, P-08)
    └─ combine(
         AccountRepository.observeAccounts()          opening = Σ live bank + cash balances
         StreamRepository.observeStreams()            §2.07 — FIXED streams → monthly commitments
         recurringRuleDao().observeForProfile()       confirmed rules → commitments; Obligations.of
         categoryDao().observeForProfile()            names for FIXED streams / one-offs
         transactionDao().observeFirstBookedIsoDate() where "unknown" ends
         transactionDao().observeNatureCandidates(today−90 … today+90)
           ├─ ≤ today: liquid outflows − liquid↔liquid transfers − scheduled rows → everyday rows
           └─ > today: liquid rows → one-offs (future-dated)
         transactionDao().observeMonthlyCategorySpend(36 closed months)       (issue 9.3)
       ) → forecastOf()
          ├─ SeasonalityEngine.index(SeasonalityInput)  domain/engines/seasonality — pure (AI-SEAS)
          │    history → index per category/month (own first, else calendar; shrunk by k)
          │    everyday rows by category → factor per month, lookback's season divided out
          └─ ForecastEngine.forecast(ForecastInput + seasonality)   domain/engines/forecast — pure
               ├─ project commitments by cadence from their anchors
               ├─ SpendModel.fit: trimmed mean × weekend ratio × pay-cycle ratio; residuals
               ├─ seasonal(d) = predicted(d) × (factor − 1)                   (AI-FCT 1.1)
               └─ Bands.simulate: 500 seeded paths → P10/P50/P90; crunch = P50 < buffer
    ⇣  Result<CashFlowForecast> → uiState.forecast → ForecastSection → ComponentsLine, SeasonalLines
                                                                        (masked when blurred)
```

### 2.09 · Financial health — AI-FHS (issue 9.4)

Shape A, and the **widest read in the app**: seven repositories, no DAO. Each source is the one the
screen beside it uses, so the score cannot disagree with the card it summarises (ADR-0007, ADR-0045).

```
DashboardViewModel.observeHealthScore()
└─ HealthScoreRepository.observeHealthScore()        data/repository — ARC-005
    └─ combine(
         EmergencyFundRepository.observeEmergencyFund()   runway bps + personal M      (§10)
         TransactionRepository.observeMonthlyLedger(3)    income, needs/wants/liabilities → saved
         StreamRepository.observeStreams()                §2.07 — FIXED streams = fixed obligations
         LoanRepository.observeNextInstalments()          each loan's EMI
         TransactionRepository.observeCategories()        LIABILITY ids — drop an EMI counted twice
         CreditCardRepository.observeCardStatuses()       statement balance / limit
         BudgetRepository.observeBudgets()                set, and not overspent
         GoalRepository.observeGoals()                    with a target, on track or funded
       ) → HealthSignals.* → HealthScoreEngine.score()    domain/engines/healthscore — pure
            ├─ each signal on its line between two rulebook anchors
            ├─ pillar = mean of its signals; pillars with no signal are "—" and re-weighted
            └─ total, band, apportioned contributions, the biggest lever
    ⇣  Result<HealthScore> → uiState.health → HealthSection
```

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

### 2.6 · How long the money would last — the runway and the target (issue 7.2)

```
EmergencyFundScreen → EmergencyFundViewModel.uiState        :feature:emergencyfund
└─ EmergencyFundRepository.observeEmergencyFund()           :data:repository (ARC-005)
    └─ combine(                                             three flows, so combine stays TYPED
        TransactionRepository.observeMonthlyLedger(6),      ← NEW read, issue 7.2
        AccountRepository.observeAccounts(),
        QuickSetupRepository.observeLatestEnvelopes(),
       )
        ├─ clock.today()  READ ONCE PER EMISSION (TIM-001); the engine reads no clock
        ├─ essentials = median(closed months' NEED) if >= min_months_observed
        │               else the quick-setup NEED envelope
        │               else null  → UNKNOWN, never a zero target (a zero reads as "funded")
        ├─ liquid     = accounts where type ∈ {BANK, CASH} ∧ inNetWorth ∧ balance > 0
        │               └─ an FD or a liquid fund is NOT counted — no liquidity tier exists
        │                  (ADR-0034). Understates the runway, which errs safely.
        └─ EmergencyFundEngine.assess(...)          :domain:engines:emergencyfund
            incomeCvBps     = integerSqrt(population variance) ÷ mean × 10 000
                              └─ Newton's method on Long: NO Math.sqrt, so no Double and no
                                 platform-dependent rounding (MNY-002, P-08)
                              └─ null below min_months_observed → bump 0, NOT the maximum
            M               = (base_months + RULE-EMF-MULT.bumpFor(cv))
                                .coerceIn(RULE-RUNWAY-M.clamp_months)
                              └─ with the shipped params the clamp NEVER fires (6+3 = 9 ∈ [3,12])
            target          = essentials × M
            shortfall       = max(0, target − liquid)
            topUpMonthly    = shortfall.split(M).max()
                              └─ Money owns the division; the odd paise go to the earliest
                                 parts, so the LARGEST is the one quoted
            runwayMonthsBps = liquid ÷ essentials × 10 000     10 000 bps = ONE MONTH
            status          = UNKNOWN → SURPLUS → FUNDED → BUILDING → URGENT
                              (order matters: unknown first, or a zero target makes an empty
                               fund read as funded; surplus before funded, because a surplus
                               is also funded and the specific verdict is the useful one)
        └─ provenance.evidence = [ RULE-EMF-MULT, RULE-EMF-COACH ] + RULE-RUNWAY-M iff clamped
            └─ init { require(evidence.isNotEmpty()) }   ← P-02, enforced in the type
```

**`observeMonthlyLedger` is new, and it lives on `TransactionRepository` on purpose.** The nature of
a row is decided by a precedence — stored override, then category, then account type — that class
already owns. A second copy in `EmergencyFundRepository` would be a second answer to "is this rupee a
need?", and the two would disagree the first time either changed.

**Nothing derived is stored.** The target is recomputed on every read, because a figure written to
the database would outlive the spending that produced it — and would go stale simply because a month
closed, which is the one input the user never edits.

**§10.1's coach behaviour is a *wording* step, not an engine step.** The engine returns an
`EmergencyStatus`; `EmergencyFundLabels` turns it into "it is usually worth pausing non-essential
goals until this has some depth". Nothing on the screen acts on any of it (P-07).

### 2.5 · What a goal takes each month — and the figure it moves elsewhere (issue 7.1)

```
GoalsScreen → GoalsViewModel.uiState                       :feature:goals
└─ GoalRepository.observeGoals()                           :data:repository (ARC-005)
    └─ activeProfileId.flatMapLatest { goalDao.observeForProfile(it) }
        └─ project(rows)
            ├─ clock.today()  READ ONCE PER EMISSION, not per goal, so every figure in one
            │                 emission describes the same day (TIM-001)
            ├─ a row whose stored date will not parse is DROPPED, not thrown on — only reachable
            │  from a hand-edited database, and losing one goal beats a screen that cannot
            │  render at all (P-04)
            └─ GoalEngine.plan(GoalPlanInput(goals, today))     :domain:engines:goals
                └─ per goal:
                    remaining       = max(0, target − saved)          never negative
                    monthsRemaining = max(0, MONTHS.between(...))     contributions, not duration
                    requiredMonthly = remaining.split(max(1, months)).max()
                                      └─ Money owns the division; the odd paise go to the
                                         earliest parts, so the LARGEST is the one quoted
                    etaIsoDate      = today + ceil(remaining ÷ planned) months, or null
                    horizon         = RULE-HORIZON.bucketFor(months)
                    status          = NO_TARGET → OVER_FUNDED → PAST_DUE → ON_TRACK → BEHIND
                                      (order matters: a funded goal is finished whatever its
                                       date said; "short by ₹0" against a zero target is not
                                       a shortfall)
                └─ GoalPlan.provenance.evidence = [ RuleCitation("RULE-HORIZON", "1.0") ]
                    └─ init { require(evidence.isNotEmpty()) }   ← P-02, enforced in the type
```

**Nothing derived is stored.** The required monthly is recomputed on every read, because a figure
written to the table would outlive the goal that produced it — and would go stale simply because a
day passed, which is the one input the user never edits.

**Issue 7.1 mints no rulebook row.** `RULE-HORIZON` already named `AI-GOAL.funding_buckets` before
this engine existed, and everything else here is arithmetic. `RulebookDriftTest` asserts the absence
as well as the presence, so a future threshold has to arrive deliberately (ADR-0033).

**The one figure this moves elsewhere** is the Safe-to-Spend goal term (§2.1). Since issue 5.2 it
substituted the whole quick-setup INVEST envelope (ADR-0021); it is now:

```
SafeToSpendRepository.observeSafeToSpend()
└─ combine(                                    nested: combine's typed overloads stop at five
    ├─ combine(envelopes, cashFlow, nature, upcoming, recurringRules) → MonthTerms
    └─ goals.observeGoals()
   ) { terms, goalProjections →
       goalContributionsRemaining = maxOf(envelopes.savingsPlanned(),
                                          goalProjections.requiredMonthly())
                                    └─ the GREATER, not a replacement. Replacing outright makes
                                       the headline jump UPWARDS for every user without a goal —
                                       and fails four tests, two older than issue 7.1.
   }
```

### 2.6 · Whether the goals fit, and who gets the surplus first (issue 7.3; rewired 2026-09-18)

§2.5 answers each goal as though it were the only claim on the month. This is the path that shares
**what §36 leaves** between all of them. Until 2026-09-18 it derived the month's surplus itself and
poured all of it — which is why this screen and the dashboard disagreed past the emergency gate
(ADR-0038). §2.8's ranking is now the base; this is the per-goal split of its remainder.

```
GoalsScreen → GoalsViewModel.uiState                       :feature:goals
├─ GoalRepository.observeGoals()          §2.5, unchanged — the list half of the state
└─ GoalWaterfallRepository.observeWaterfall()              :data:repository (ARC-005)
    │   TWO FLOWS INTO ONE STATE, NOT ONE COMBINED FLOW. The list is a plain table read; the
    │   plan needs the whole household ranked. Combining them would let a surplus problem blank
    │   a list that is perfectly readable.
    └─ combine(
        ├─ goals.observeGoals()                            → List<GoalProjection>, in sort_order
        ├─ ranking.observe()                               → §2.8's eight stages (AI-FOO)
        └─ emergencyFund.observeEmergencyFund()            → §2.4's runway (for the gate wording)
       ) {
        ├─ clock.today()  READ ONCE PER EMISSION (TIM-001)
        ├─ forGoals = max(surplus, 0) − Σ amount of every stage ABOVE GOAL_INVESTING
        │   │   the starter buffer, high-interest debt and the emergency fund, already filled.
        │   │   NULL STAYS NULL: an unknown surplus keeps feasibility UNKNOWN rather than
        │   │   becoming INFEASIBLE (7.2's lesson, unchanged).
        │   └─ claimedBeforeGoals + grossSurplus travel with it, as ECHOES the card reads —
        │      without them a smaller figure here looks like money lost between two screens
        ├─ emergencyTopUpMonthly = ZERO
        │   └─ Stage 3 already claimed AI-EMF's pace; claiming it again here would hide a
        │      month of it from the goals (ADR-0038)
        ├─ emergencyGateMonths = QuickSetupRules().emergencyRunwayMonths
        │   └─ the repository's ONE mirror of RULE-EMERG-FIRST. The engine holds the citation,
        │      not the number — a second mirror is ADR-0017 trigger 2 (ADR-0035).
        └─ GoalWaterfallEngine.allocate(...)               :domain:engines:goals
            ├─ gateHolds = runwayBps == null || runwayBps < gateMonths × 10 000
            │              └─ UNKNOWN HOLDS THE GATE: no evidence of a buffer is not evidence.
            │                 Below the gate §2.8 has already left nothing, so this decides the
            │                 WORDING — blockedByEmergencyFund — rather than the amount
            ├─ emergencyAllocated = gateHolds ? min(distributable, topUp) : 0   → now always 0
            ├─ per goal, IN THE CALLER'S ORDER:
            │      allocated = gateHolds ? 0 : min(remaining, requiredMonthly)
            │      └─ strict priority, never pro rata. Money.allocate is a SPLITTER and would
            │         be the wrong tool; min() in a fold cannot create or lose a paise, so no
            │         rounding rule appears anywhere in the engine.
            ├─ levers per under-funded goal, at the ALLOCATED rate (FR-GOAL-003)
            └─ init { require(emergencyAllocated + Σ allocated + unallocated == max(surplus,0)) }
                └─ the invariant lives on the TYPE; it now holds over the REMAINDER §2.8 left
   }
```

**The order is the only stored part of the plan**, because it is the only part the user decides:
`goal.sort_order` at schema 21, defaulted to zero so an upgraded profile's list does not move.

```
GoalsEvent.MoveUp / MoveDown / MoveGoal      ← drag, or the semantic custom actions TalkBack uses
└─ GoalsViewModel.reorder(from, to)
    │   out-of-range indices ignored here, so a drag released off the end is harmless
    └─ GoalRepository.reorder(everyGoalIdInOrder)
        └─ withTransaction { goalDao.setSortOrder(id, index, now) }   ONE clock stamp for the lot
            └─ sort_order is POSITIONAL, so the WHOLE list is written, never the moved pair —
               a partial write leaves the rest sharing a rank and the tie-break decides the plan
```

**`RULE-EMERG-FIRST` finally has a reader.** It has been in the rulebook since day one, `severity:
fail`, with `AI-GOAL` in its `consumed_by` and nothing consuming it. It is cited on **every** plan,
not only when it fires — a gate is evaluated every time and both outcomes decide whether goals are
funded, which is the one place this departs from 7.2's "cite only what fired" (ADR-0035).

### 2.7 · What a goal's progress is actually made of (issue 7.4)

§2.5 and §2.6 both read `goal.saved_minor` — a number the user typed. This is the path that derives
most of it from their own ledger instead, and the path that lets them point at a movement and say
*that one was for this*.

**The read.** `saved` stops being a column and becomes a column plus a query:

```
GoalsScreen → GoalsViewModel.uiState                       :feature:goals
└─ GoalRepository.observeGoals()                           :data:repository (ARC-005)
    └─ activeProfileId.flatMapLatest { profileId ->
        │   THREE FLOWS NOW, because progress has three moving sources. Linking a movement has to
        │   move the figure on this screen with nobody re-reading anything.
        combine(
        ├─ goalDao.observeForProfile(profileId)             → the rows, in sort_order
        ├─ goalDao.observeEvidenced(profileId, today)       → ONE `UNION ALL`, one `GROUP BY`
        │   ├─ goal_contribution ⋈ transactions            → ABS(amount_minor)
        │   │      the user pointed at ONE movement, so the act of linking asserts the direction:
        │   │      a ₹5,000 SIP debit is stored negative and still funds the goal
        │   ├─ goal_funding_account ⋈ transactions         → SIGNED amount_minor
        │   │      a claim about a POT, so an outflow reduces it and both legs of an internal
        │   │      transfer net to zero instead of counting twice
        │   │      bounded below by linked_from_iso_date — what stops a newly dedicated account
        │   │      crediting the goal with two years of history the instant it is linked
        │   ├─ AND NOT EXISTS (the same txn explicitly linked TO THIS GOAL)
        │   │      the dedupe. Per goal, because the same movement may evidence a different one —
        │   │      that is a separate claim
        │   └─ both halves bounded by booked_on_iso_date <= today
        │          a future-dated transaction (3.4) has not happened, so it cannot be progress
        └─ recurringRuleDao.observeIncomeDueDate(profileId) → RULE-PAY-FIRST's anchor day
               the quick-setup `income` seed (2.3) already carries it; null when there is none,
               and null is silence rather than an invented payday (P-03)
       ) { rows, evidence, incomeDue ->
        └─ per row:
            declared   = Money(row.saved_minor)             ← §15's ghost progress, UNCHANGED
            evidenced  = max(evidence[row.id] ?: 0, −declared)
            │              THE PAIR IS FLOORED TOGETHER, not the total at zero: clamping the total
            │              would make `saved − savedEvidenced` report a figure the user never typed
            saved      = declared + evidenced
            └─ GoalEngine.plan(GoalPlanInput(specs, today, contributionAnchorDay))
                └─ GoalProjection.savedEvidenced / .savedDeclared
                    require(evidenced + declared == saved)  ← the split reconciles, or nothing renders
       }
```

**The write.** A second repository, because it owns different tables — ARC-005 asks that exactly one
class touch a DAO, not that one class touch every DAO:

```
GoalDetailScreen → GoalDetailViewModel                     :feature:goals
│                  route CfoRoute.GoalDetail(goalId), typed (ARC-001)
├─ GoalRepository.observeGoals()  ── filtered to this id, so the two screens cannot disagree
├─ GoalContributionRepository.observeContributions(goalId)
│   └─ goalContributionDao.observeForGoal(goalId)
│       └─ flatMapLatest → transactionDao.observeByIds(linkedIds)
│              THE LINKS ARE THE INDEX; THE LEDGER IS THE TRUTH. Resolving through the rows on
│              every emission is what stops an edited amount leaving a stale contribution behind,
│              and what makes a soft-deleted transaction stop counting with nothing unlinked.
├─ GoalContributionRepository.observeLinkable(goalId)
│   └─ transactions.observeRecent(100) minus already-linked minus the transfer_out leg
│          a transfer is ONE movement stored as two rows (ADR-0008); offering both would invite
│          the user to link the same money twice
└─ GoalContributionRepository.link / unlink / linkAccount / unlinkAccount
    ├─ link:   findIncludingDeleted(goalId, txnId) → REVIVE that row, or mint one
    │            the unique index forbids a duplicate, so re-linking something once unlinked is
    │            an ordinary action rather than a failure — and the original created stamp stays
    └─ unlink: softDelete(goalId, txnId, now)
                 the progress reverses to the paise AND `when this was linked` survives. A hard
                 DELETE would do the first and destroy the second, and 7.4 asks for both.
```

**Clearing the ghost figure is an ordinary goal edit**, which is why it goes through `save` rather
than reaching for the DAO:

```
GoalDetailEvent.ClearGhostProgress
└─ GoalRepository.save(GoalDraft(…, saved = ZERO), id = goalId)
       the DECLARED half is what is zeroed; the evidenced half is not this screen's to touch and
       could not be zeroed without unlinking the movements that produced it
```

**One figure this moves elsewhere, and it was not obvious.** `GoalsViewModel.openEditor` filled the
editor's *Saved so far* field from `goal.saved` — correct until 7.4, and from 7.4 the **total**. It
now loads `goal.savedDeclared`; loading the total would have folded the evidenced half into
`saved_minor` on every edit and doubled it on the next read. Nothing in 7.1's code changed; what it
meant did.

Safe-to-Spend moves too, by design: `requiredMonthly` falls as `saved` rises, and `RULE-STS`
subtracts the total (§2.1). Linking a contribution now raises the headline figure.

### 2.8 · Where the next rupee should go — the whole household in one order (issue 7.5)

§2.5–§2.7 each answer one question — what a goal needs, how long the buffer lasts, who gets the
surplus. This is the path that ranks all of them, plus the user's debts, into §36's single order.

**The read.** A third repository built on the other two, adding only what nobody resolved before:

```
DashboardScreen → DashboardViewModel.uiState.orderOfOperations        :feature:dashboard
│                 NextBestRupeeCard — FOO-002's single top action
│                 "See the full order" → CfoRoute.OrderOfOperations (typed, ARC-001)
│                   → OrderOfOperationsScreen → OrderOfOperationsViewModel
└─ OrderOfOperationsRepository.observe()                               :data:repository (ARC-005)
    └─ combine(
        ├─ SurplusRepository.observeMonthlySurplus()                   extracted 2026-09-18
        │      the P50 of observed surplus, else the declared envelope, else unknown — ADR-0035's
        │      stand-in for the forecast 9.2 never built. Owned by neither screen, so §2.6 can
        │      consume THIS path's remainder without a cycle (ADR-0038)
        ├─ GoalRepository.observeGoals()                               §2.5
        │      Σ requiredMonthly and the count ← Stage 5's need, before anything decides who gets it
        ├─ EmergencyFundRepository.observeEmergencyFund()               §2.6 (7.2)
        │      essentials, liquid               ← Stage 0
        │      shortfall, topUp, runway         ← Stage 3 and the gate
        │      status == UNKNOWN → shortfall = null
        │             EMF reports ₹0 when it cannot size the fund; passed on, that would read "done"
        └─ observeDebts()                                               THE NEW READ
            └─ activeProfileId.flatMapLatest { profileId ->
                combine(
                ├─ accountDao.observeWithBalances(profileId, includeArchived = false, today)
                ├─ creditCardDao.observeForProfile(profileId)   → apr_bps (nullable)
                └─ loanDao.observeForProfile(profileId)         → annual_rate_bps
                ) → per account:
                    CREDIT_CARD → DebtPosition(CARD, −balance floored at 0, apr or null)
                    LOAN        → DebtPosition(LOAN, …) ONLY when a loan row exists
                                  no terms = no rate = no band that is not a guess (P-03)
                    anything else → not a debt this engine can place
            }
       ) { plan, fund, debts ->
        emergencyGateMonths = QuickSetupRules().emergencyRunwayMonths   RULE-EMERG-FIRST's ONE mirror
        └─ OrderOfOperationsEngine.rank(input)                         :domain:engines:orderofoperations
            │   eight stages, in §36's order, each min(remaining, claim)
            │   bands decided once: fire ≥ 1350 bps or unrated card · grey 1000..1349 · low < 1000
            │   gate = runway unknown || runway < gate × 10 000 bps → Stages 5–7 BLOCKED
            └─ Err (a sum past Long.MAX_VALUE) → rank again WITHOUT the surplus, debts and goals
                   a flow that stopped emitting would leave the card "working it out" for ever
       }
```

**Nothing is written.** The ranking is advice (P-07): no table, no worker, no stored insight yet.
Editing a card's APR in Accounts is what moves a debt between stages — the flow re-emits because
`credit_card` changed. The loop is closed on screen:

```
OrderOfOperationsScreen ─ "Add the card's rate in Accounts"   (only under a card with no rate)
└─ CfoRoute.Accounts → AccountEditorScreen → "Annual interest rate (%)"
    └─ AccountEditorViewModel.save
        ├─ hasPartialCardTerms → Err(validation)   a rate with no limit/days has nowhere to go —
        │                                          reported, never silently dropped
        └─ toCreditCard: parseRateBps("42") = 4200 → CreditCardRepository.save → credit_card.apr_bps
            └─ creditCardDao.observeForProfile re-emits → the ranking moves the card between stages
```

**Rules reach it two ways.** Stage thresholds come from `OrderOfOperationsRules`, a typed mirror of
`financial-order-of-operations.json` held by a drift test. `RULE-EMERG-FIRST`'s number comes from the
repository, never from the engine's own mirror (ADR-0035, ADR-0037).

**Known divergence, recorded.** Past the gate this path gives the emergency fund its monthly pace
before the goals; §2.6's waterfall gives it nothing. Re-pointing §2.6 at what this path leaves after
Stage 3 is ADR-0037's first follow-up.

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

### 3.2 · The price refresh — the only path in the app that can open a socket (issue 6.5)

```
CfoApplication.onCreate()
└─ MarketPriceWorker.schedule(context)                         app/work/MarketPriceWorker.kt
    └─ enqueueUniquePeriodicWork("market-price-refresh", KEEP, every 1 day,
                                 Constraints(NetworkType.CONNECTED))
                                 │  THE ONLY CONSTRAINT IN THE APP. The other seven workers are
                                 │  pure local computation; gating one of them on connectivity
                                 │  would break the app in airplane mode (P-04).

MainActivity.onCreate() → AppLockGate { LaunchedEffect(Unit) }
└─ MarketPriceWorker.refreshNow(context)                       API-002, once per unlock
    └─ enqueueUniqueWork("market-price-refresh-now", KEEP, one-time, same constraint)
           ▼
MarketPriceWorker.doWork()
├─ sessionLock.isUnlocked.value == false  →  Result.retry()     same SEC-002 guard
│
└─ repository.get().refresh()                                  MarketPriceRepository
    │
    ├─ GATE 1  consents.observe(MARKET_DATA).first()           P-01
    │          not Ok, or not granted  →  Ok(0)                 ← an unreadable store is NOT a grant
    │          NOTHING BELOW THIS LINE RUNS. No request is built, no socket is opened.
    │
    ├─ GATE 2  holdingDao.distinctPriceKeys(profile)            EXT-003
    │          empty  →  Ok(0)                                  a null price_key is the opt-in switch
    │          This query CANNOT RETURN ANYTHING BUT A PRICE KEY — the request payload is
    │          identifier-only by construction, not by review.
    │
    ├─ GATE 3  holdingDao.forProfile(profile)                    read stays on the device
    │          └─ engine.priceFreshness(...)                     → shape C, per row
    │              └─ PriceFreshnessRules.refreshMinutesFor(class)   RULE-PRICE-STALE (rulebook)
    │          nothing refreshDue  →  Ok(0)
    │
    ├─ api.quotes(keys ∩ due)                                    :core:network
    │   ├─ MarketDataFactory.create(NetworkConfig.UNCONFIGURED)
    │   │   └─ baseUrl blank → UnconfiguredMarketDataApi
    │   │       NO OkHttpClient IS CONSTRUCTED. No pool, no DNS, no socket.  ← the shipping build
    │   │       └─ Err(AppError.Network(retryable = false))
    │   └─ (configured build) Retrofit → pinned OkHttp → GET /v1/market/prices?ids=…
    │       └─ RetrofitMarketDataApi drops a quote that is unasked-for, non-positive, or malformed
    │   Err  →  Ok(0)                                            P-04: keep the cached price
    │
    └─ holdingDao.updatePriceByKey(profile, key, paise, asOf, fetchedAt)
        UPDATE of four named columns. `name` and `asset_class` DO NOT OCCUR IN THE STATEMENT,
        so a refresh landing during a rename cannot revert it — the guarantee is in the SQL.
```

**Every failure is `Ok(0)`, not `Err`.** No consent, no keys, nothing due, and no backend are all the
feature correctly doing nothing. `Err` is reserved for the database failing, which is the only thing
`retry()` can help with — reporting a missing proxy as a failure would have WorkManager backing off
for ever on every install, since today that is all of them.

The staleness the user sees comes from the same engine call on the read path (§2.3), so the label and
the refresh decision cannot disagree.

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
