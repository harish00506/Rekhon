# AI-MKT — the opportunity score

> `:domain:engines:marketsignal` · version **1.0** · layer **L4** · issue 10.7 · SRS **§30** ·
> [ADR-0055](../../../docs/adr/0055-market-signals-score-what-can-be-measured-and-say-what-cannot.md)

## Contract

One public interface, one call.

```kotlin
MarketSignalEngineFactory.create().assess(MarketSignalInput) : Result<OpportunityAssessment, AppError>
```

**In:** the instrument, its cached daily closes, the two context percentiles (valuation and VIX, or
`null`), the household's capacity, the profile's `todayIsoDate`, `nowUtcMillis`, and the library
mirror.
**Out:** `outcome`, `score`, **`possibleScore`**, `band`, one `SignalContribution` per signal with
the number it measured, `hitRate`, `tranches`, `staleness`, `provenance`.

**Refusals** (`AppError.Validation`, by field): `market.close` for a negative price, `market.date`
for an unparseable date, `market.percentile` for one outside 0–100.

## Formula

### The score

```
score = min(Σ signal points, bands.cap)          per ai/knowledge/market-signals.json v1.1
possibleScore = Σ max_points of the signals that could be evaluated
```

`possibleScore` is the honest half. This app has no valuation or volatility feed, so those two
signals — **40 of the library's 100 points** — are usually not evaluated. Scoring them zero would
read as "valuation says this is expensive"; reporting them as unevaluated says what is true.

### Each signal

| Signal | Measured from | Ladder (KB v1.1) |
|--------|---------------|------------------|
| `SIG-VALUATION` | a percentile the caller supplies | ≤25th → 13, ≤10th → 25 |
| `SIG-DRAWDOWN` | % below the 52-week high | −5 → 5, −8 → 10, −12 → 15, −20 → 20 |
| `SIG-VIX` | a percentile the caller supplies | ≥75th → 8, ≥90th → 15 |
| `SIG-MA200` | % below the 200-day mean | −2 → 5, −5 → 10, −10 → 15 |
| `SIG-RSI` | Wilder's RSI(14) | <30 → 10, <25 → 15 |
| `SIG-RARITY` | mid-rank percentile in the trailing year | ≤25th → 2, ≤10th → 5 |
| `SIG-STREAK` | consecutive lower closes | ≥4 → 3, ≥6 → 5 |

**The percentile is a mid-rank** — everything below plus half of everything equal. The obvious
definition (strictly below) puts a perfectly flat series at the 0th percentile, which reads as "the
cheapest day of the year" when it is the most ordinary one. The first draft did exactly that and
scored a flat market five points.

### The hit rate (§30.3)

```
for each past day d in [minimum_days_for_any_score, size − horizon_days):
    score d using only closes[0..d]        ← walk-forward
    if band(d) == band(today): sample; hit when close[d + horizon] > close[d]
rate = hits ÷ samples,  withheld entirely below hit_rate.min_samples (20)
```

Scoring a past day with the whole series would measure a machine that can see the future, and would
flatter every verdict. **The context percentiles are held at today's values** — the app has no
history for them, which is a limitation ADR-0055 records rather than one the code hides.

### The tranche ladder (§30.4)

`GOOD_DAY` → 1, `STRONG_BUY_DAY` → 2, **zero unless every capacity gate passes**:
`RULE-IDLE-CASH` (is there anything spare), `RULE-RUNWAY-M` (is the emergency fund at target),
`AI-FCT` (no crunch day in the horizon). Every gate is reported, passed or not, so a suggestion of
nothing explains itself.

## Assumptions

- **The history is the app's own cached closes.** One row per instrument per day, appended by the
  price refresh. A fresh install has none, and the engine says `NOT_ENOUGH_HISTORY` rather than
  scoring three weeks as though it were three years.
- **No price at all is a history problem, not a staleness one.** `TOO_STALE` would imply there is a
  price and it is old, which is a different thing to tell a user.
- **A month-old close gets no verdict.** Labelling it would be a hedge rather than an answer.
- **Nothing here fetches anything** (P-04), and nothing here buys anything (P-07).

## Data it reads

`ai/knowledge/market-signals.json` **v1.1**, mirrored as `MarketKnowledge.BUNDLED` and held to the
file by `MarketKbDriftTest` — with the file declared a test input in `build.gradle.kts`, without
which Gradle leaves the gate `UP-TO-DATE` and it passes without running.

## Tests

| Suite | What it holds |
|-------|---------------|
| `MarketSignalEngineTest` (20) | each ladder, the possible score, the bands, the history and staleness gates, the walk-forward rate and its minimum, the tranche gates, the refusals, determinism — and the empty history the first draft crashed on |
| `MarketGoldenTest` (1) | eight fixed histories, line for line against `golden/market_oracle.py` — an **independent** Python implementation from the library |
| `MarketPropertyTest` (6 × 300) | the cap; an unevaluated signal counts for nothing; the band always matches the score; **a cheaper day never scores worse**; no suggestion with a gate closed; determinism |
| `MarketKbDriftTest` (11) | every weight and ladder step, the bands, the minimums, the hit-rate policy, the staleness limits, the gates, that the weights still sum to the cap, and that the context-only signals are still unscored |

Seven mutations were each watched go red: scoring a missing input as zero, ignoring the history
minimum, answering from a month-old price, a hit rate that sees the future, ignoring the minimum
sample count, ignoring the capacity gates, and a percentile that ignores ties. Four library drifts
too. **The minimum-samples mutation survived the first round** — no case had between one and
nineteen samples — and the case that catches it now exists.

## Version log

| Version | Issue | What changed |
|---------|-------|--------------|
| 1.0 | 10.7 | Created. Seven ladders over cached closes, the possible-score honesty, the walk-forward hit rate, and §30.4's gated tranche ladder. |
