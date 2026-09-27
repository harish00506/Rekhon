<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: issue 10.7 — AI-MKT, the buy-day verdict, and the screen that shows its working.
  Result: a reader can see why the score is out of a moving total, why the hit rate is sometimes
          withheld, where the price history came from, and what two of the tests caught that the
          example tests had passed.
  Changelog: 2026-09-27 — Created.
-->

# 2026-09-27 — Is today a good day to invest? (issue 10.7, ADR-0055)

**Branch:** `feature/10-7-market-signal-engine-ai-mkt-opportunity-screen` off `dev` (`0b11a90`)
**Versions:**
- **VERSION** 0.10.5 → **0.10.6**
- **versionCode** 49 → 50
- **Schema** 28 → **29** (`market_close`)
- **`ai/knowledge/market-signals.json`** 1.0 → **1.1**

---

## 1 · Decisions this session

The full argument for each is in [ADR-0055](../adr/0055-market-signals-score-what-can-be-measured-and-say-what-cannot.md).

- **The history is the app's own cached closes.** Issue 6.5 fetches a quote per instrument, not a
  series, so every refresh now leaves one row behind in `market_close` (schema 29, unique per
  instrument per day) and the series accumulates locally. That is what makes the screen work in
  airplane mode, and it means **nothing extra leaves the device** to build it (P-01, P-04). The
  cost is honest and visible: a fresh install has no history, and the engine says so rather than
  scoring three weeks.
- **A signal that cannot be evaluated is reported, never scored zero.** Valuation (25 points) and
  implied volatility (15) need feeds this app does not have — 40 of the library's 100. So
  `possibleScore` travels beside `score`: *41 out of a possible 60* is a different claim from *41
  out of 100*, and a zero for valuation would read as "valuation says this is expensive". The
  screen shows each unmeasured signal as *"not measured, so it counts for nothing either way"*.
- **The library's prose became numbers, in the file** (§6: change data, not code). "Bottom quartile
  scores, bottom decile maxes" cannot be read by an engine, so each signal gained an explicit
  `score_points` ladder, beside three blocks the engine needs before it may say anything at all:
  `history` (how much is enough), `hit_rate` (what "measured" means) and `staleness` (when to label
  and when to refuse). **No weight, tier or lookback changed** — a drift test holds the mirror to
  the file, and the weights still sum to exactly 100.
- **The percentile is a mid-rank** — everything below, plus half of everything equal.
- **The hit rate is walk-forward, or it is not shown.** Each past day is scored from the closes
  available *on that day*; scoring it with the whole series would measure a machine that can see
  the future. Below twenty comparable days no rate is shown at all, because a rate from four
  samples persuades without informing — the opposite of §30.3.
- **Never all-in, and never at all when the household cannot afford it.** One tranche on a good
  day, two on a strong one, and zero if idle cash is short (`RULE-IDLE-CASH`), the emergency fund
  is behind (`RULE-RUNWAY-M`) or AI-FCT sees a tight day in the next ninety. AI-MKT re-derives none
  of those verdicts; it reads them and names the one that closed the gate.
- **Deferred, with reasons** (ADR-0055): the valuation and VIX feeds; the three context-only
  signals §30.2 keeps out of the score; per-signal backtests, which need a rule editor;
  `market_status` and `opportunity_check` as chat tools; and backfilling history from a provider —
  which would make the screen useful on day one instead of in a year, and is a network path, a
  licensing question and a source of numbers this device never observed.

**What the tests caught, that the examples had passed.**

1. **A flat market scored five points for rarity.** The first percentile was the fraction *strictly
   below*, which puts a perfectly flat series at the 0th percentile — "the cheapest day of the
   year", when it is the most ordinary one. Caught by the property that a cheaper day must never
   score worse, and fixed by choosing the mid-rank convention deliberately.
2. **An instrument the app has never priced crashed the engine.** Every price signal reads the last
   close, and there isn't one. The engine suite never asked — the **repository** test did, on the
   most ordinary situation there is: a holding added today. `NOT_ENOUGH_HISTORY` is now decided
   before anything reads the series, and ahead of staleness, because "the price here is too old"
   would imply there is a price.
3. **A mutation survived**: raising `min_samples` from 20 changed no test, because every fixture
   had either plenty of samples or none. A three-sample case now pins the withholding.

## 2 · Flow changed this session

One new read path, and one write bolted onto an existing one — `FLOW.md` §2.18:

```
MarketPriceRepository.refresh()  → market_close        (one row per instrument per day)

DashboardScreen → "Good day to invest?" → OpportunityScreen
└─ MarketSignalRepository.observeOpportunities()
   ├─ held instruments with a price key + their cached closes
   ├─ capacityFlow(AI-STS, AI-EMF, AI-FCT)
   └─ MarketSignalEngine.assess()   →  verdict · score out of what was measurable · each signal's
                                        number · the verdict's own hit rate · the tranche plan and
                                        its gates · how old the price is
```

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `ai/knowledge/market-signals.json` | 1.1: the point ladders, and the history / hit-rate / staleness blocks |
| `domain/engines/marketsignal/**` (new) | AI-MKT: the contract, the KB mirror, the maths, the engine, `ENGINE.md` |
| `domain/engines/marketsignal/src/test/**` | 20 behaviour tests, 6 properties × 300 markets, the golden file and its Python oracle, 11 drift tests |
| `core/database/**` | `market_close`, its DAO, migration 28 → 29 and schema `29.json` |
| `data/repository/MarketSignalRepository.kt` (new) | the held instruments, their series, and the capacity gates |
| `data/repository/MarketPriceRepository.kt` | each fetched quote also leaves a close behind |
| `data/repository/{Archive,ArchiveRepository,DemoModeRepository,RepositoryFactory}.kt` | the new table in backup, restore, wipe and the demo |
| `feature/market/**` (new) | the Opportunity screen, its state holder, and 11 + 4 tests |
| `feature/dashboard/**` | the "Good day to invest?" entry point |
| `app/**` | the route, the nav entry, and the repository's Hilt binding |
| `docs/adr/0055-…`, `DECISIONS.md`, `FLOW.md`, `CHANGELOG.md`, `docs/memory.md`, `ai/orchestrator/engine-registry.yaml` | the records |
