# Session — 2026-08-16 — Issue 5.2, Safe-to-Spend card

**Branch:** `feature/5-2-safe-to-spend-card` → `dev` · **VERSION:** 0.5.1 → 0.5.2
**Issue:** [5.2](../issues/5.2-safe-to-spend-card.md) · **SRS:** §5.2, §14, AI-STS

The dashboard's headline figure was a literal — `Money(12_500_00L + today.dayOfMonth)`, written in
issue 1.10 and shipped in every build since. This session replaced it with an engine, a rulebook row
and a breakdown, and closed the last P-03 breach on the app's home screen.

---

## 1 · Decisions this session

### 1.1 `RULE-STS` is a new rulebook row, and it fixes three things §5.2 leaves open

Recorded in full as **[ADR-0021](../adr/0021-safe-to-spend-buffer-and-goal-stand-in.md)**; the short
version is that §5.2's "income minus commitments, bills, and goal contributions" needs three
decisions before it can be code:

- **`income_basis = budget_envelopes_then_actual`.** Month-to-date ledger income alone reads deeply
  negative for twenty-seven days and jumps on payday — it measures the salary calendar, not the
  user's position. The declared budget total is stable from the 1st; the ledger is the fallback for a
  profile that skipped quick setup.
- **`buffer_pct = 5`** — the actual deviation. The deductions cover only commitments the app has been
  told about; the ones that end a month short are the ones it has not. `RULE-IDLE-CASH` has referred
  to a "Safe-to-Spend needs + buffer" since this file was created, so the term was already the
  rulebook's — this gives it a value. It is a **labelled line on the card**, never folded into
  another term.
- **`include_goal_contributions = true`**, standing in as the quick-setup INVEST envelope **in full**
  until AI-GOAL (issue 7.1) exists — see §1.6(b) for why netting it is wrong. The engine's contract
  does not change when 7.1 lands; only what the repository puts in the field.

Minted as a new row rather than added to `RULE-50-30-20`'s params, for ADR-0019's reason: that is a
shipped row at v1.0 cited in stored provenance, and a params change is ADR-0017's trigger 3.
`RulebookDriftTest` asserts the parameters stay off it.

### 1.2 The breakdown is the result, not a rendering concern

`SafeToSpend` carries `lines` and its constructor **asserts they sum to `amount`**. The engine builds
the lines first and folds the figure from them, so the card's arithmetic is literally the headline's.

The alternative — return the amount, let the screen re-derive the terms to explain it — was rejected
because the two can then disagree, which is exactly the failure P-02 exists to prevent: a
plausible-looking fiction beside a correct number is worse than no breakdown at all.

The same reasoning forced the `SHORTFALL` component. `RULE-STS.floor_at_zero` clamps an overcommitted
month to ₹0; a clamp that dropped the shortfall would leave the lines summing to −₹8,000 beside a
headline of ₹0. Naming it keeps the invariant absolute. It never appears in the shipped rulebook
(`floor_at_zero = false`) — it exists so the parameter is honoured rather than decorative, which this
repo has shipped once before.

### 1.3 A zero term is omitted from the breakdown; a zero *figure* is an absence

Zero deductions are left out entirely — a card reading "Bills due ₹0 · Scheduled ₹0 · Savings ₹0" is
noise around the one line that matters, and a breakdown the user stops reading has stopped satisfying
P-02.

Separately, a profile with **no income basis at all** gets `null`, not a computed ₹0. The rule lives
in `SafeToSpendRepository` rather than in a `require`, because "is there an income basis?" is a data
question — `SafeToSpendInput` would happily accept `Money.ZERO`. The card renders the absence, the
same choice `netWorth` and `budgetTotals` already make.

### 1.4 Deductions render **signed**

Found by looking at the first recorded Paparazzi baseline. As magnitudes the column read as six
additions that plainly did not sum to the headline, so a user could not check the figure — which is
the entire reason for showing it. `line.signedAmount` makes the arithmetic verifiable at a glance.

### 1.5 `SafeToSpendRepository` reads other repositories

Three of its five terms are already exposed by `TransactionRepository` and `QuickSetupRepository`,
and `observeNatureBreakdown()` in particular is a five-way join plus a per-transaction engine call
(issue 4.3). Re-deriving it here to keep every read a direct DAO call would create a second
definition of "what this month's money became", and the two would drift.

**Not a new pattern** — `RoomReceiptRepository` (3.8) and `RoomSmsRepository` (3.9) both take a
`TransactionRepository`. ARC-005 holds either way: every DAO touch is still inside a repository.
No `DECISIONS.md` row, because nothing new was decided.

### 1.6 Two bugs found by re-reading my own diff, and one by the E2E suite

**(a) A double-count.** `spentToDate` comes from `observeNatureBreakdown()` and `scheduled` from
`observeUpcoming()`. The nature read ran to the **month's last day**, so every future-dated row
inside the month was in both — a bill scheduled for the 28th came off Safe-to-Spend twice. The first
version of the scheduled-payments test asserted only the SCHEDULED line and passed happily beside it.

Fixed on the 4.3 side, because that read was independently wrong: it feeds a card captioned "This
month, **actually**", and FR-TXN-010 says future-dated rows are excluded from actuals. It is now
bounded at `MonthWindow.actualsEndIsoDate` — the bound `observeMonthCashFlow` three methods below has
always used — which makes the two sets disjoint by construction rather than by memory. The test now
asserts the SPENT line is *absent* and checks the total.

**(b) Netting the savings envelope was backwards.** The first draft used
`max(0, investEnvelope − invested)`, reasoning that money already saved must not be deducted twice.
It is deducted *once* — §8.3's `trueSpend` is `NEED + WANT` and already excludes every conversion, so
a rupee in an SIP is in **no** other term. Netting left it deducted from nothing at all, so
Safe-to-Spend would **rise** by exactly the amount the user had just saved. Now the full envelope,
with a test that makes a ₹9,000 saving and asserts the figure does not move. The string went from
"Savings still to make" to "Planned savings" to match.

**(c) Gating the whole screen on one stream broke `CfoSmokeTest`.** An earlier draft cleared
`isLoading` from `observeSafeToSpend`'s first emission, so the loading state would be observable in a
Turbine test. But `DashboardContent` returns early while loading, so the entire screen — net worth,
budgets, recent activity and **every navigation button** — stayed hidden until five Room flows had
each emitted. The smoke test waits for the visible "Dashboard" title (rendered *outside* the loading
guard) and then clicks "View transactions", which no longer existed yet.

`load()` clears the flag again, as it did before. Every section already renders its own absence,
including the Safe-to-Spend card, so there was nothing for a screen-wide flag to protect. An
observable loading state is not worth hiding a working screen for.

### 1.7 `DashboardViewModel` lost its `Clock`

The clock was injected in 1.10 so the placeholder could vary by day, and kept in 2.6 and 5.1 so the
injected clock stayed genuinely on the path rather than decorative. With the placeholder gone the
ViewModel has no calendar question left: every window is resolved inside the repository that owns the
query (TIM-001), which is where `CfoWallClockInDomain` expects it.

### 1.8 Both new gates were watched go red before being trusted

A mis-labelled golden record (wrong figure, and separately a transposed breakdown) and an edited
`buffer_pct` each failed the build, then passed again on restore. The repo has already shipped one
governance gate that nothing ever ran; a gate nobody has seen fail is not a gate.

### 1.9 Two edits that are consequences, not decisions

- `BudgetRules.RULEBOOK_VERSION` → `1.12.0`. `_meta.version` describes the **file**, so every typed
  mirror restates it whenever any rule is added. No `RULE-BUD-*` threshold or version changed; the
  constant says "copied from that revision".
- `RepositoryModule` gained `@Suppress("TooManyFunctions")`. The count there is simply the number of
  repositories the app has, and the seam that *would* be real — `CoreModule`'s platform primitives
  versus these data readers — was already cut. Same argument `RepositoryFactory` and `CfoDatabase`
  already carry.

---

## 2 · Flow changed this session

New section **[FLOW.md §2.1](../../FLOW.md)** — the dashboard's headline figure:

```
DashboardViewModel.observeSafeToSpend()
└─ SafeToSpendRepository.observeSafeToSpend()
    └─ activeProfileId.flatMapLatest { MonthWindow.current(clock.today()) →
        combine(
          QuickSetupRepository.observeLatestEnvelopes(profileId)      income basis + savings target
          TransactionRepository.observeMonthCashFlow()                income fallback
          TransactionRepository.observeNatureBreakdown()              trueSpend + invested (4.3)
          TransactionRepository.observeUpcoming()  → scheduledCommitments(monthEnd)
          recurringRuleDao().observeForProfile()   → billsDue(today, monthEnd)
        )
        ├─ incomeBasis(...) == null  →  emits null            ← no basis, no figure (P-03)
        ├─ billsDue(...).deduplicatedAgainst(scheduled)        name+date, rent not counted twice
        └─ SafeToSpendEngine.compute(...) → SafeToSpend(amount, lines, [RULE-STS v1.0])
      }.flowOn(dispatchers.io)
⇣
_uiState.update { copy(safeToSpend = figure, isLoading = false) }
⇣
DashboardScreen.MoneySummary → SafeToSpendSection(figure)
    ├─ CfoAmountText(amount, showSign = true)
    ├─ lines.forEach { label(component) + MoneyFormatter.format(signedAmount) }
    └─ dashboard_reason_rule(RULE-STS, 1.0)
```

**Removed:** `DashboardViewModel.load()` no longer launches a coroutine or computes a figure; it
clears `errorCode` and nothing else. `Clock` is out of the constructor.

---

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `ai/rules/rules-kb.json` | Adds `RULE-STS` v1.0; `_meta.version` → 1.12.0 |
| `ai/orchestrator/engine-registry.yaml` | Adds the missing **AI-STS** row (L5) |
| `settings.gradle.kts` | Registers `:domain:engines:safetospend` |
| `domain/engines/safetospend/build.gradle.kts` | Pure-Kotlin module; declares the rulebook as a test input so the drift gate cannot report green against a file Gradle never saw it read |
| `…/safetospend/SafeToSpendEngine.kt` | The interface, `SafeToSpendInput`, `SafeToSpend`, `SafeToSpendLine`, `SafeToSpendComponent`, the factory |
| `…/safetospend/SafeToSpendRules.kt` | Typed mirror of `RULE-STS`; `income_basis`/`horizon` mirrored as constants although no field applies them |
| `…/safetospend/DefaultSafeToSpendEngine.kt` | Builds the breakdown, folds the figure from it, stamps provenance |
| `…/safetospend/ENGINE.md` | Contract, formula, the goal-contribution limitation, the gates |
| `…/safetospend/src/test/**` | Engine (17), golden (3 + a 12-record fixture), property (5 × 500 months), drift (5) |
| `data/repository/SafeToSpendRepository.kt` | Resolves the five terms; emits `null` for no income basis; dedupes bills against scheduled rows; savings envelope taken **in full** |
| `data/repository/TransactionRepository.kt` | `observeNatureBreakdown` bounded at `actualsEndIsoDate` (FR-TXN-010) — fixes the "This month, actually" card and makes the Safe-to-Spend terms disjoint |
| `data/repository/RepositoryFactory.kt` | `safeToSpend(...)` factory |
| `data/repository/build.gradle.kts` | `api(:domain:engines:safetospend)` — `SafeToSpend` is on the public surface |
| `data/repository/src/test/…/SafeToSpendRepositoryTest.kt` | 18 cases against in-memory Room and the real engines |
| `app/di/EngineModule.kt`, `app/di/RepositoryModule.kt` | Bind the engine and the repository; `TooManyFunctions` suppressed with its reason |
| `feature/dashboard/DashboardUiState.kt` | `safeToSpend: SafeToSpend?` replaces `Money` |
| `feature/dashboard/DashboardViewModel.kt` | `observeSafeToSpend()`; placeholder, `Clock` and the `load()` coroutine gone |
| `feature/dashboard/DashboardScreen.kt` | `SafeToSpendSection` — figure, signed breakdown, citation, pending state; `labelRes` maps each component to `strings.xml` |
| `feature/dashboard/res/values/strings.xml` | Seven component labels, the pending line, the line format |
| `feature/dashboard/src/test/**` | `FakeSafeToSpendRepository` (no-replay `SharedFlow`, so the loading state is observable); ViewModel + screenshot fixtures; 4 baselines re-recorded |
| `domain/engines/budget/BudgetRules.kt` | `RULEBOOK_VERSION` → 1.12.0 (file revision, not a threshold change) |
| `VERSION`, `app/build.gradle.kts`, `CHANGELOG.md` | 0.5.2, `versionCode` 19 |
| `DECISIONS.md`, `docs/adr/0021-*.md`, `FLOW.md` | ADR-0021 and its index row; FLOW.md §2.1 |

---

## 4 · Verification

Full log in the [tracker](../issues/5.2-safe-to-spend-card-tracker.md#verification-log). Headlines:

- `./gradlew ktlintCheck detekt lintDebug` — OK, no new warnings
- `./gradlew unitTests koverVerify` — OK; the new engine is 93.5% instruction / 92.6% line
- `./gradlew verifyPaparazziDebug` — OK, 4 baselines re-recorded and eyeballed in all three configs
- **Emulator (`CfoTest`, airplane mode)** — pending state on a profile with no income; ₹56,421 after
  adding ₹60,000 income (60,000 − 3,000 buffer − 579 spent, exact); ₹55,421 after a ₹1,000 expense,
  with "Already spent" moving 579 → 1,579 in the same emission.

**Known, pre-existing:** the first cold launch after install crashed with the SEC-002 unlock race
already filed as [issue 2.8](../issues/2.8-app-lock-disabled-unlock-race-sec-002-crash.md). Not a 5.2
regression — the racing code is issue 2.2's — and it did not recur on relaunch, exactly as that
ticket documents. Left for 2.8 rather than fixed in passing.
