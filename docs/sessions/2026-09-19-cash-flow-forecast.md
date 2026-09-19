<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: issue 9.2 — AI-FCT, the 90-day cash-flow forecast (§9); replaces issue 1.1's placeholder.
  Result: a reader can see what the forecast schedules and why, how it predicts the rest, how its
          accuracy is gated, and everything §9.1 asks for that it does not yet do.
  Changelog: 2026-09-19 — Created.
-->

# 2026-09-19 — The cash-flow forecast (issue 9.2, ADR-0043)

**Branch:** `feature/9-2-cash-flow-forecast-ai-fct` off `dev` (`d0e043e`) · **VERSION** 0.9.1 → **0.9.2** ·
**versionCode** 37 → 38 · **Schema** 22, unchanged · **rules-kb.json** 1.15.0 → **1.16.0** · `AI-FCT` 1.0

---

## 1 · Decisions this session

The argument for each is in ADR-0043; the short form:

- **Scheduled items** are confirmed recurring rules, 9.1's FIXED streams (projected on their modal
  day) and future-dated rows. This is where classification feeds the forecast: rent is scheduled
  before the detector has proposed it.
- **Everyday spend** is liquid outflow with the scheduled rows removed, so nothing is counted twice.
  Transfers between bank and cash are excluded. Card purchases count only when the bill is paid.
- **One liquid definition** (`LIQUID_ACCOUNT_TYPES`) is shared with the emergency fund. The forecast
  keeps negative balances; the runway drops them.
- **§9.2's method** is followed literally; where it was vague, the gaps are closed simply:
  - a ratio of medians for the weekend;
  - three named pay-cycle zones as ratios of means;
  - a multiplier of 1 for anything empty;
  - days before the ledger starts counted as unknown;
  - nearest-rank bands;
  - the epoch day as the seed;
  - crunch measured on P50.
- **The numbers are rulebook rows** (RULE-FCT-METHOD, RULE-FCT-CRUNCH, rules-kb 1.16.0). Six mirrors
  restated the version.
- **Accuracy is gated on a frozen synthetic backtest**, with both thresholds fixed before the first
  run.
- **Deferred with triggers:**
  - per-account forecasts and the Pro horizon;
  - AI-FCT-004 snapshots and MAPE;
  - the crunch alert (9.6);
  - a user buffer setting;
  - seasonality (9.3);
  - irregular income;
  - loan EMIs not confirmed as a rule;
  - switching the goals' surplus to the forecast.

## 2 · What the numbers say, honestly

**The backtest passes, one gate only just.** The median 90-day spend error is **12.6%** (gate ≤ 15%).
Mean P10–P90 coverage is **70.3%** (gate ≥ 70%). The worst ledgers are the drift and regime-change
ones, which §9.2's method does not model by design, and several well-behaved ledgers over-cover at
94–100%. The gate stays where it was set. A future model change that lowers coverage by a third of
a point will fail it, and that is the gate working.

The engine, golden, property and drift tests all passed on their first run. So before trusting them
I checked they can fail:
- Removing the trim failed the golden, backtest and behaviour tests.
- Changing the rulebook buffer alone failed the drift test.

**On the device, the card showed what the design predicts, including its weak spot.** Before any
repeat was confirmed, the rent was still in the everyday-spend pool. The trimmed base mostly
ignored it, but the untrimmed pay-cycle ratio and the residuals did not. Everyday spend read ₹22,018
and the band was wide. After the landlord and the salary were confirmed, both moved to scheduled
items. Everyday spend fell to ₹11,716 and the band narrowed. So an unconfirmed obligation shows up
as a pessimistic, wide forecast, not a wrong one. Confirming repeats is what sharpens it.

Hand arithmetic caught two of my own test expectations before the first run. One was a "31st" case
that fell past the horizon; the other was an assertion that checked nothing.

## 3 · Flow changed this session

New `FLOW.md` §2.08:

```
DashboardViewModel.observeForecast()
└─ ForecastRepository.observeForecast()
    ├─ accounts (opening) · streams §2.07 (FIXED → commitments) · confirmed rules · categories
    ├─ first booked date (history start) · ledger today−90 … today+90 (spend, one-offs)
    └─ ForecastEngine.forecast() → project · SpendModel.fit · Bands.simulate (seeded) · crunch
  ⇣ uiState.forecast → ForecastSection
```

## 4 · Code changed this session

| Path | What it does now |
|------|------------------|
| `domain/engines/forecast/**` | **Replaces the placeholder.** `ForecastEngine` + types, `ForecastRules`, `HeuristicForecastEngine` (`SpendModel`, `Bands`), `ENGINE.md`; tests: behaviour (19), property (11 × 200 cases), golden (oracle), drift (5), backtest (3) + frozen `backtest/ledgers.txt` with its generator |
| `ai/rules/rules-kb.json`, `ai/rules/rulebook.md` | `RULE-FCT-METHOD`, `RULE-FCT-CRUNCH`; 1.16.0 |
| six `*Rules.kt` mirrors | `RULEBOOK_VERSION` 1.16.0 (no mirrored row changed) |
| `domain/engines/stream/**` | `StreamMetrics.modalDayOfMonth` (+ test); engine stays 1.0 |
| `domain/engines/goals/.../GoalWaterfallEngine.kt` | Two comments no longer claim the forecast is unbuilt |
| `core/database/.../Daos.kt` | `observeFirstBookedIsoDate` (read only) |
| `data/repository/.../ForecastRepository.kt` (+ test, 8), `LiquidAccounts.kt`, `EmergencyFundRepository.kt`, `RepositoryFactory.kt`, `build.gradle.kts` | The join; the shared liquid set |
| `app/.../di/RepositoryModule.kt` | Provides the engine and the repository |
| `feature/dashboard/.../ForecastSection.kt` (+ test, 6), `DashboardUiState/ViewModel/Screen`, `strings.xml` | "The next 90 days" card |
| `feature/dashboard/src/test/**` | `FakeForecastRepository` (real engine), fixture, VM tests (+2), 5 baselines |
| `ai/orchestrator/engine-registry.yaml` | AI-FCT's contract and inputs restated |
| `docs/adr/0043-…`, `DECISIONS.md`, `FLOW.md` | The decision, its row, §2.08 |

## 5 · Quiz

**Outcome: not yet taken.** The answers in italics are the author's. Record the developer's own pass
here, fails included.

1. Why is a payment to a confirmed recurring merchant removed from everyday spend? *(It is already
   scheduled on its day. Leaving it in would count it twice.)*
2. Why doesn't a card purchase lower the forecast, when paying the card bill does? *(The liquid
   balance only moves when the bill leaves the bank.)*
3. You add ₹10,000 to the opening balance. What happens to P10 on day 60, and why exactly? *(It rises
   by exactly ₹10,000, because the draws don't depend on the balance.)*
4. Why is a crunch day measured on P50 rather than P10? *(P10 would flag one day in ten as a crisis.)*
5. The backtest's coverage is 70.3%. What should happen if a change makes it 69.9%? *(The build
   fails. The threshold is not relaxed to let it through.)*
