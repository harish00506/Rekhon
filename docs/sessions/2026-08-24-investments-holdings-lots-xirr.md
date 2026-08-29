# 2026-08-24 — Issue 6.3: investment holdings, lots and XIRR

**Branch:** `feature/6-3-investments-holdings-lots-xirr` (off `dev`) · **VERSION:** 0.6.2 → 0.6.3
**Asked for:** issue 6.4 (allocation & diversification). **Delivered:** issue 6.3, which 6.4 depends
on and which had not been started.

---

## 1 · Decisions this session

### 1.1 · 6.4 was blocked, so 6.3 was built instead

The workflow's step-4 dependency check stopped 6.4 before any code: it needs holdings to allocate,
and a repo-wide search found **zero** declarations for holdings, lots, XIRR, asset class, allocation,
diversification or concentration. 6.3 was `Todo`, 0/9 phases, nothing on disk.

6.3 is also the higher priority (High vs 6.4's Medium) and unblocks 6.4, 6.5 and 13.4. The user chose
to build it first, to full Definition of Done.

Two product decisions were the user's:

- **Valuation shape.** Lots carry units and cash; the holding carries a user-entered **price per
  unit** plus the day it was observed. Value is `units × price`, computed. The alternative — one
  stored total value — was rejected because it discards the units, so issue 6.5's per-unit market
  prices could not be slotted in without a migration.
- **The §11.1 disclaimer ships in 6.3**, with the first screen that renders an investment figure,
  rather than waiting for 6.4's advice.

### 1.2 · Asset class is a column, not a derivation → [ADR-0027](../adr/0027-asset-class-is-a-column-on-the-holding.md)

`AccountType` already has `INVESTMENT`, `GOLD` and `CRYPTO`, so deriving the asset class from the
account looks free. It is not: one broker account legitimately holds an equity fund *and* a liquid
debt fund, so a derived class puts both in one bucket and makes 6.4's headline allocation
**structurally wrong** rather than imprecise — `RULE-AGE-EQUITY` would be comparing the whole
portfolio against an equity band.

So: a seven-member `AssetClass` enum in `:core:model`, stored as `investment_holding.asset_class`,
with `defaultFor(AccountType)` as both the editor's default and 6.4's fallback for accounts that hold
value without lots. An enum rather than free text for the reason `AccountType`'s own doc gives — the
column has no CHECK constraint, so only a closed set stops a typo becoming a row every query misses.

Two of the `storedValue` strings are **already load-bearing**: `rules-kb.json` shipped
`RULE-GOLD-CAP` with `"gold"` and `RULE-CRYPTO-CAP` with `"crypto"` before any Kotlin consumed them.
`AssetClassTest` pins both so 6.4 cannot discover a mismatch while capping a class that matches
nothing.

The same ADR records why the **price** is stored at all when ADR-0007 refused stored balances and
ADR-0026 refused stored schedules: a market price is the one figure on this path the device cannot
compute from anything it holds. Everything derived from it — value, cost, gain, return — still is.

### 1.3 · XIRR by bisection over the daily growth factor → [ADR-0028](../adr/0028-xirr-by-bisection-over-the-daily-growth-factor.md)

The textbook `NPV(r) = Σ cf_i·(1+r)^(−d_i/365) = 0` has a fractional exponent, which `BigDecimal`
cannot do exactly — so implementations reach for `Double`, which MNY-001 bans on a money path and
which the numeric guardrail (AI-ARC-004) cannot verify exactly. And Newton-Raphson is
guess-dependent, divergent on a large early outflow, and tolerance-terminated: three places a
harmless-looking refactor changes what the app said last year (AI-ARC-006).

Substituting `x = (1+r)^(1/365)` and multiplying through by `x^dMax` gives
`F(x) = Σ cf_i · x^(dMax − d_i)` — a polynomial in **integer** powers, sharing NPV's positive roots.
The solve then needs only `BigDecimal.pow(Int, MathContext)`, `multiply` and `add`. That is what
makes P-08 provable here rather than asserted.

Bisection over a literal `[0.98, 1.02]` bracket, **128 halvings, no early exit** — slower than
Newton and deliberately so: no guess, no tolerance branch, nothing machine-dependent. All five
constants (`PRECISION`, the two bracket ends, `ITERATIONS`, `DAYS_PER_YEAR`) are part of
`ENGINE_VERSION`.

ACT/365 fixed, because that is what Excel's `XIRR` uses and a user will check the app against a
spreadsheet. A 366-day span is therefore 366/365 of a year — the `leap-span` golden case pins it at
997 bps where the 365-day equivalent gives 1000.

### 1.4 · Two deviations from the approved plan, both deliberate

- **No `ZERO_SPAN` refusal.** The plan listed four; the code has three. After same-day coalescing,
  two distinct days always span at least one, so a zero-span arm could never fire — it would sit in
  the enum looking like a case somebody had thought about. `TOO_FEW_FLOWS` covers both "one purchase"
  and "several on one morning", and its doc says so.
- **No `asOf` parameter, and no `Clock` in the engine at all.** The plan had the repository pass
  `clock.today()` as a fallback terminal date. It is not needed: the terminal flow is dated by
  `priced_on_iso_date`, which is non-null exactly when there is a value to date. Removing it makes
  the return a function of stored rows alone, so the same untouched holding reports the same number
  tomorrow. The repository still reads the clock — for row stamps and provenance only.

### 1.5 · A draft type, because the model refuses a blank id

`InvestmentHolding` requires a non-blank `id`, but a form the user is still filling in has no id yet.
Rather than weaken the invariant so an unsaved holding could borrow the type, the unsaved state got
its own — `HoldingDraft` and `LotDraft`, following `AccountDraft`. `saveHolding(draft, id?)` mints
the id from the injected `IdGenerator` when `id` is null.

### 1.6 · No rulebook row, and no `_meta.version` bump

A money-weighted return has no threshold to tune, only arithmetic to get right — the argument
`:domain:engines:loan` already makes for amortisation. So `:domain:engines:investment` has **no**
`inputs.file(rules-kb.json)` block and no `RulebookDriftTest`, `rules-kb.json` stays at `1.13.0`, and
the three `RULEBOOK_VERSION` constants stay green. The five AI-INV rows remain issue 6.4's, and so
does any `evidence`/`RuleCitation` on an investment result.

---

## 2 · Flow changed this session

New section **[FLOW §2.3](../../FLOW.md)** — "A holding's return — the first figure with no clock
behind it". The read path:

```
HoldingsScreen → HoldingsViewModel.observeHoldings()
  → InvestmentRepository.observeForAccount(accountId)
      → activeProfileId.flatMapLatest { combine(holdingDao.observeForAccount, lotDao.observeForProfile) }
          → CashFlows.netQuantity / currentValue / of(...)
              → Xirr.solve(flows)   coalesce → 3 refusals → 128 bisections → r = x^365 − 1 → bps
  → HoldingsUiState.holdings → HoldingRow → value · cost · gain · return + §11.1 footer
```

The accounts list gains a **fourth** collector beside cards and loans — never a `combine`, for the
reason §2.2 gives:

```
AccountsViewModel.init { observeAccounts(); observeCards(); observeLoans(); observeInvestments() }
  → InvestmentRepository.observeByAccount() → Map<accountId, List<HoldingPerformance>>
      → AccountsUiState.investments → InvestmentSummary  (absent ≠ zero, P-03)
```

The write path, holding before lots so a lot never orphans:

```
HoldingsEvent.SaveEditor → HoldingsViewModel.save()
  → editor.toDraft(accountId)          price XOR date → fieldError, nothing written
  → InvestmentRepository.saveHolding(draft, id)
      → account type ∉ {INVESTMENT, GOLD, CRYPTO} → Err(Validation("account.notInvestable"))
      → ids.newId("holding") → upsert → saveLot(...) per lot
```

New nav destination `CfoRoute.Holdings(accountId)`; `CfoNavHost` gained a
`accountsDestinations(navController)` extension, following `captureDestinations`.

---

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `core/model/.../AssetClass.kt` | **new** — seven classes, `fromStored`, `defaultFor(AccountType)`; the taxonomy 6.4 consumes |
| `core/model/.../Investment.kt` | **new** — `Quantity` (nano-units, exact, overflow-checked), `LotKind`, `InvestmentHolding`, `InvestmentLot` |
| `core/model/.../DateFormatter.kt` | gained `isCalendarDate`, lifted from `Loan.kt`'s private copy; three types now share one definition of "a date" |
| `core/model/.../Loan.kt` | calls the lifted helper; its private copy and now-unused import deleted |
| `core/database/.../entity/Entities.kt` | **new** `InvestmentHoldingEntity` (first Epic-6 table keyed on a surrogate id, not `account_id`) and `InvestmentLotEntity` |
| `core/database/.../dao/Daos.kt` | **new** `InvestmentHoldingDao`, `InvestmentLotDao`; `DemoDao` wipes both (children first); `ArchiveDao` reads and restores both |
| `core/database/.../CfoDatabase.kt` | `VERSION = 18`, two entities, two accessors, history sentence |
| `core/database/.../migration/Migrations.kt` | **new** `MIGRATION_17_18`, split into `createInvestmentHolding` / `createInvestmentLot` to stay under 40 lines |
| `core/database/schemas/.../18.json` | **new** — Room's export; the hand-written migration matches its canonical DDL exactly |
| `domain/engines/investment/InvestmentEngine.kt` | **new** — the interface, `CashFlow`, `XirrRate`, `HoldingPerformance`, `XirrUnavailable`, inputs, factory |
| `domain/engines/investment/Xirr.kt` | **new** — coalesce, three refusals, the bracket, 128 bisections, one rounding |
| `domain/engines/investment/CashFlows.kt` | **new** — lots → position, value, cost, realised and the signed flow series |
| `domain/engines/investment/DefaultInvestmentEngine.kt` | **new** — `internal`, zero-arg, provenance with **empty evidence** (no rule decided anything) |
| `domain/engines/investment/ENGINE.md` | **new** — contract, the substitution, the version contract, what it deliberately does not do |
| `.../test/resources/golden/investment.txt` | **new** — 8 cases, expectations from an independent 60-digit implementation |
| `data/repository/.../InvestmentRepository.kt` | **new** — the only DAO toucher; `HoldingDraft`/`LotDraft`; type guard; delete cascades to lots |
| `data/repository/.../Archive.kt`, `ArchiveRepository.kt` | both tables in the export, the restore and the demo wipe (the piece easiest to miss) |
| `app/.../di/WealthEngineModule.kt`, `RepositoryModule.kt` | engine and repository bound; `@Suppress("LongParameterList")` matching the card precedent |
| `app/.../navigation/CfoRoute.kt`, `CfoNavHost.kt` | `Holdings(accountId)`; `accountsDestinations` extracted |
| `feature/accounts/.../HoldingsUiState.kt` | **new** — `@Immutable` state, editor state, sealed `HoldingsEvent` |
| `feature/accounts/.../HoldingsViewModel.kt` | **new** — the collector, the editor cycle, and the one place text becomes `Money`/`Quantity` |
| `feature/accounts/.../HoldingsScreen.kt`, `HoldingEditorFields.kt` | **new** — the screen, the inline lot editor, §11.1's always-rendered footer |
| `feature/accounts/.../AccountsActions.kt` | **new** — `AccountsActions`, `AccountFigures`, `INVESTABLE_TYPES` |
| `feature/accounts/.../AccountRow.kt`, `AccountsInvestmentSummary.kt` | **new** — extracted from `AccountsScreen.kt`, which was at detekt's 11-function ceiling |
| `feature/accounts/.../AccountsUiState.kt`, `AccountsViewModel.kt`, `AccountsScreen.kt` | the `investments` map, the fourth collector, the summary line and the Holdings action |
| `feature/accounts/src/main/res/values/strings.xml` | every new string, ICU plurals for counts, the disclaimer |
| `feature/accounts/build.gradle.kts` | `HoldingsScreenTest` excluded from the release variant, as its two siblings are |

---

## 4 · What the tests caught

- **A real bug of mine.** `editEditor` cleared `fieldError` unconditionally, and `save()` set the
  error *through* it — so every refused save silently closed with no message. Three ViewModel tests
  went red on it. Split into `editEditor` (clears) and `setFieldError` (sets).
- **A crash on real devices.** `lintDebug` caught `BigInteger.longValueExact()` — API 31, against a
  minSdk of 26. It compiled and every unit test passed; it would have thrown on any phone older than
  Android 12. Replaced with `BigDecimal.setScale(0, HALF_EVEN).longValueExact()`.
- **Two bad property-test generators**, mine again: an unconstrained value ratio over a short span
  annualises outside the engine's bracket in *both* directions — losing 90% in a month is worse than
  −99.94% a year, and tripling in a month is better than +136 000%. The generator produced honest
  refusals and the test called them failures. Both now bound the ratio and the span, with a comment
  saying why.
- **My own wrong expectation.** A test asserted `SAME_SIGN` on a holding with one lot; the engine
  correctly said `TOO_FEW_FLOWS`, because one flow is one flow. The test was fixed, not the engine.

---

## 5 · Not done

- ~~The emulator gate (§9).~~ **Done on 2026-08-25** — see §6 below. The 2026-08-24 claim that no
  emulator was available was **wrong**: an AVD named `CfoTest` and two system images were already on
  the machine, and nobody had looked.
- **Nothing committed or pushed.** CLAUDE.md §7 and workflow step 12: not without being asked.
- **`/pre-merge` and `/money-time-audit`** have not been run as skills, though their substance
  (ktlint, detekt, lintDebug, unitTests, koverVerify) is green and logged in the tracker.

---

## 6 · The device gate (2026-08-25)

The previous session recorded "no emulator or device available" and skipped §9. That was not true —
`emulator -list-avds` shows `CfoTest`, with android-34 and android-35 system images already
installed. It booted headless in 41 seconds. **The skip was an unchecked assumption, not a
constraint**, and it is worth recording as the kind of thing a verification log exists to catch.

What the device then proved that nothing on the JVM could:

| Gate | Result |
|---|---|
| `:core:database:connectedDebugAndroidTest` | 22 tests, 0 failures — including `migrate17To18_...` and `currentSchemaMatchesItsExportedFixture` against real SQLite |
| `:app` + `:feature:onboarding` connected | 9 + 1, 0 failures (one flaky first run, below) |
| `/run` + `/verify` | A holding entered by hand — 100 units at ₹82.50, bought for ₹7,500 exactly 365 days earlier — renders **₹8,250.00**, **₹7,500.00 invested**, **₹750.00 gain**, **10.0% a year**. That is the golden file's hand-checkable case, arrived at through the real UI, the real parse, SQLCipher, the repository and the engine |
| P-03 prompts | "Add what this account holds" on the accounts row; the holdings invitation — never a ₹0 |
| §11.1 disclaimer (P-07) | On screen, list and editor alike |
| P-04 airplane mode | Every figure identical with `airplane_mode_on=1` after a force-stop and relaunch |
| Privacy blur (5.3) | "₹••••••• across 1 holding"; invested, gain and value all masked — and **the rate deliberately is not**, because a percentage says nothing about how much money is involved |

Three things the device run turned up:

1. **A crash that was not mine.** A cold start on a cleared app threw SEC-002 ("the encrypted
   database was opened while the app was locked"). Rather than assume, the 6.3 work was **stashed**
   and the identical sequence run against a clean `dev` build. Neither build reproduced it on a
   retry — it was fallout from a SystemUI ANR during the emulator's first boot. Worth the rebuild:
   the alternative was asserting "I didn't touch that code", which is exactly the claim that needs
   evidence.
2. **A flaky pre-existing test.** `CfoSmokeTest.theRecurringSectionProposesASeriesFromTheRealLedger`
   timed out at 20s once and passed on re-run. It is unrelated to 6.3, and the 20-second Compose wait
   is tight for a headless swiftshader emulator.
3. **The two gates catch different things.** This AVD is API 34, so it could never have caught the
   `BigInteger.longValueExact()` bug — that call only fails below API 31. `lintDebug` caught it and
   the device structurally cannot. Neither gate subsumes the other, which is why §9 asks for both.
