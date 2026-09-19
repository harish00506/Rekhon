# ForecastEngine — AI-FCT (cash-flow forecast)

**SRS:** §9.1, §9.2  ·  **Pipeline layer:** L4  ·  **Module:** `:domain:engines:forecast`
**Version:** 1.1  ·  **Status:** active  ·  **Engine id on results:** `AI-FCT`

## Why this engine exists
Every other figure in the app describes the past or the current month. The forecast says what the
next ninety days will look like: the day the balance dips, and the day a payment lands on an empty
account. It says this while there is still time to act (AI-FCT-002). §9.2 calls the method
"deliberately simple, explainable, upgradeable". Every rupee on a forecast day is either a
scheduled item the user can name or a predicted everyday spend whose multipliers are on record
(AI-FCT-003).

## Contract
```
interface ForecastEngine {
    fun forecast(input: ForecastInput): Result<CashFlowForecast, AppError>
}
```
- **Input** — `ForecastInput`:
  - `today`: the last day of actuals. The forecast starts tomorrow.
  - `openingBalance`: `Money`, the consolidated liquid balance.
  - `commitments`: repeating items as label, signed amount, cadence (WEEKLY / MONTHLY / YEARLY),
    `nextDue` and source.
  - `oneOffs`: dated items, each after today.
  - `dailySpend`: positive paise per day.
  - `historyStart`: the ledger's first day, or `null`.
  - `seed`, `nowUtcMillis`, and `rules` (`ForecastRules`).
- **Output** — `CashFlowForecast`:
  - `openingBalance`.
  - `days`: per day, P10, P50 and P90, `scheduledNet`, `predictedSpend` and `expected`.
  - `scheduled`: the inspectable list, in date order.
  - `scheduledIncome`, `scheduledOutflow`, `predictedSpend`, `dailyBase`.
  - `crunchDays`, `buffer`, `lowest`, `historyDays`.
  - `provenance`: engine, version, the lookback and horizon window, a confidence equal to history
    days over lookback, and evidence citing RULE-FCT-METHOD and RULE-FCT-CRUNCH.
- **Refuses** (`Validation`): `forecast.spend` (a negative spend) and `forecast.item` (a one-off
  dated on or before today).
- **Stable contract (§9.2 upgrade path):** a Phase-4 learned model replaces `HeuristicForecastEngine`
  behind this interface, and the interface does not change.

## Formula / algorithm
§9.2, verbatim where it is specific:
```
forecast(d)   = opening + Σ scheduled(≤ d) − Σ (predictedVariableSpend + seasonal)(≤ d)
predicted(d)  = base × dowAdj(d) × domAdj(d)                      HALF_EVEN to the paisa
seasonal(d)   = predicted(d) × (AI-SEAS factor(month(d)) − 1)      HALF_EVEN; ×1 with no factor (1.1)
base          = trimmed mean of daily everyday spend over the lookback, trim ⌊n·10%⌋ each end
dowAdj        = median(weekend days) / median(all days)   or   median(weekdays) / median(all)
domAdj        = mean(bucket) / mean(all), buckets 1–5 (spike), 6–24, 25–31 (trough)
residual(d)   = actual(d) − predicted(d) over the lookback
bands         = 500 paths; each path = expected − running sum of residuals drawn with replacement
                (kotlin.random.Random(seed)); P10/P50/P90 by nearest rank ⌈p·n/100⌉ − 1
crunch day    = P50 < buffer (₹5,000)
```
- **Commitments** are projected from their anchor (`nextDue.plusMonths(k)`), so the 31st returns to
  the 31st. A stale anchor rolls forward.
- **Exact arithmetic:** the ratios are exact `BigDecimal` (34 digits). A zero denominator or an
  empty class gives a multiplier of exactly 1.

## Assumptions & guardrails
- **Days before `historyStart` are unknown, not zero.** Inside the ledger's life, a day without a
  row is a zero.
- **Today is not part of the history:** it isn't over.
- **The spread is the user's own:** no distribution is assumed. A history with no surprises gives
  bands that collapse onto the expected path.
- **Crunch is measured on P50,** so it is a real expectation rather than a one-in-ten scare. P10 is
  shown beside it.
- **What the repository decides** (ADR-0043):
  - Liquid means bank and cash, shared with the emergency fund.
  - Scheduled means confirmed recurring rules, FIXED streams from 9.1, and future-dated rows.
  - Everyday spend is liquid outflows with the scheduled ones removed. Transfers between liquid
    accounts are excluded, and card purchases don't count until the bill is paid.
- **Seasonality (1.1, ADR-0044):** AI-SEAS's monthly factor, which divides out the lookback's own
  season, is applied to each day's rounded prediction as a separate term. The bands move with it
  exactly, and `predicted + seasonal` is never negative.
- **Not modelled** (ADR-0043):
  - irregular income (§9.2's Persona 3);
  - loan EMIs not confirmed as a rule;
  - per-account forecasts and the Pro 12-month horizon (AI-FCT-001);
  - on-device accuracy snapshots (AI-FCT-004).
- Numbers only (P-03). No clock and no I/O. Randomness comes only from the seed (P-08).

## Rules / knowledge consumed
| ID / file | What it provides |
|-----------|------------------|
| RULE-FCT-METHOD v1.0 (`ai/rules/rules-kb.json` 1.16.0) | horizon 90, lookback 90, trim 1 000 bps, 500 simulations, bands 10/50/90, pay-cycle edges 5 and 25 |
| RULE-FCT-CRUNCH v1.0 | buffer 500 000 paise, measured on P50 |

Mirrored as `ForecastRules` and guarded by `ForecastRulebookDriftTest`. The rulebook is a declared
test input. Since 1.1, AI-SEAS's evidence (SEAS-INDEX and any named calendar events) follows these
whenever a seasonal adjustment applies. AI-SEAS's `ENGINE.md` owns the calendar file.

## Evidence shown to the user (P-02)
The dashboard card "The next 90 days" shows:
- the lowest point, with its date and the P10–P90 range;
- the crunch line (the day count and the first date), or "stays above your buffer";
- "Coming in · Going out · Everyday spending", plus "Seasonal extra" or "Seasonal saving" when there
  is one (1.1);
- one line per month the season moves, giving the percentage against the last 90 days, the amount,
  and why (1.1);
- the next three scheduled items, each with its source ("repeats", "fixed each month",
  "scheduled");
- how many days of history the estimate rests on, and that income counts only confirmed repeats;
- the rules.

All amounts are masked by the privacy blur.

## Tests
- **Golden** (`golden/forecast.txt`): a realistic scenario with every multiplier active. Its values
  come from an **independent** Python `decimal` oracle (`forecast_oracle.py`).
- **Property** (`ForecastPropertyTest`, 11 × 200 cases):
  - P10 ≤ P50 ≤ P90;
  - adding to the opening balance shifts every band by exactly that amount;
  - a bill lowers every band from its day onward by exactly the bill;
  - the components sum to the path, and the path steps correctly;
  - crunch days equal the days with P50 below the buffer;
  - output is deterministic, the seed moves only the spread, and input order doesn't matter.
- **Behaviour** (`ForecastEngineTest`, 19): cadence projection and the 31st, stale anchors, the
  horizon edge, history edges, trimming, the buffer boundary, provenance, refusals.
- **Backtest** (`ForecastBacktestTest`): 20 frozen synthetic ledgers, with thresholds fixed before
  the first run. At 1.0 the median 90-day spend error is **12.6%** (gate ≤ 15%) and mean P10–P90
  coverage is **70.3%** (gate ≥ 70%). **The coverage margin is thin.** The worst ledgers are the
  drift and regime-change ones the method does not model.
- **Seasonal** (`ForecastSeasonalityTest`, 9, 1.1): the per-day amount, the path, savings and the
  non-negative floor, the month totals inside the horizon, no factor versus ×1, the evidence order,
  the version, and the bands moving exactly. The property cases also carry random factors in half
  the runs, and the path and component identities include the seasonal term.
- **Watched red:** removing the trim failed the golden, backtest and behaviour tests. Changing the KB
  buffer alone failed the drift test.

## Version log
| Version | Date | Change |
|---------|------|--------|
| 1.0 | 2026-09-19 | Initial implementation from SRS §9.2 (issue 9.2). Replaces issue 1.1's placeholder. |
| 1.1 | 2026-09-19 | §9.2's `seasonalAdjustment(d)` from AI-SEAS (issue 9.3, ADR-0044): `ForecastDay.seasonal`, `seasonalAdjustment`, `seasonalMonths`, and AI-SEAS's evidence. With no seasonal input every figure is 1.0's; the golden file and the backtest are unchanged. |
