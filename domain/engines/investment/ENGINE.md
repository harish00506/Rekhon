# InvestmentEngine — AI-INV (issues 6.3 and 6.4)

**SRS:** §11  ·  **Pipeline layer:** L2  ·  **Module:** `:domain:engines:investment`
**Requirements:** §11.2 (XIRR, allocation, diversification), FR-INV-002, MNY-001/002,
TIM-002, P-02, P-03, P-07, P-08, AI-ARC-003
**Version:** 1.1  ·  **Status:** active

## Why this engine exists

An investment account's balance answers "how much is in there" and nothing else. The question a
person actually has is "did this do well", and the only honest answer is money-weighted. A SIP that
put ₹5,000 in every month for a year has most of its money invested for far less than a year, so the
naive cost-to-value ratio understates the return by roughly a factor of two — the golden file's
`sip-12` case reports **15.67%** where the ratio would say 8.3%. §11.2 names XIRR for exactly this.

Issue 6.4 (allocation and diversification) and issue 6.6 (net-worth history) both build on the
figures here. §11.1 draws the line this engine sits on: it **measures**, and never recommends a
security or a fund (P-07).

## Contract

```kotlin
interface InvestmentEngine {
    fun xirr(input: CashFlowSeriesInput): Result<XirrRate, AppError>
    fun holding(input: HoldingInput): Result<HoldingPerformance, AppError>
    fun allocation(input: AllocationInput): Result<PortfolioAllocation, AppError>
}
```

- **Input** — `CashFlowSeriesInput`: `flows` (ISO `yyyy-MM-dd` day + signed paise; any order),
  `nowUtcMillis` (provenance only). `HoldingInput`: the `InvestmentHolding`, its `InvestmentLot`s,
  `nowUtcMillis`.
- **Output** — `XirrRate`: `rateBps` (integer basis points, 1000 = 10%), `flowCount` (distinct days
  **after** coalescing), `spanDays` (ACT), `provenance`. `HoldingPerformance`: `netQuantity`,
  `currentValue`, `invested`, `realised`, `gain`, `xirrBps`, `xirrUnavailable`, `assetClass`,
  `provenance`.
- **Failures** — `xirr` returns `Err(AppError.Validation(field))` for `flows.tooFew`,
  `flows.sameSign`, `flows.notBracketed`. `holding` **never** fails for those; the reason lands in
  `xirrUnavailable` and every other figure is still computed, because an unpriced position is the
  ordinary state of a new holding rather than a fault. `allocation` never fails either: an empty or
  wholly unpriced portfolio reports `AllocationUnavailable.NO_POSITIONS` or `NOTHING_PRICED` on an
  `Ok`, so the screen has something to explain rather than an error to swallow.
- **Allocation input** — `AllocationInput`: `positions` (a `PortfolioPosition` per holding, or one
  per investable account counted whole; `value` null means unpriced), `nowUtcMillis`, `rules`
  (injected `InvestmentRules`, defaulting to the shipped rulebook values).
- **Allocation output** — `PortfolioAllocation`: `total` (the denominator), `slices`
  (`assetClass`, `value`, `shareBps`, largest first, summing to exactly 10 000), `flags`
  (`ConcentrationFlag`, each citing the **one** row that decided it), `valuedCount`,
  `unvaluedCount`, `unavailable`, `provenance` (evidence = all three rows; `confidenceBps` =
  coverage).

**What counts as the portfolio is the repository's decision, not this engine's** (ADR-0029). The
engine divides whatever positions it is handed; `InvestmentRepository.observeAllocation` is what
decides those are the investment, gold and crypto accounts and not the user's savings.

## Formula / algorithm

XIRR is normally `NPV(r) = Σ cf_i·(1+r)^(−d_i/365) = 0`, solved by Newton-Raphson from a guess. Both
halves are hostile to P-08: the fractional exponent has no exact decimal form, so implementations
reach for `Double`; and Newton depends on its guess, can diverge on a series with one large early
outflow, and stops on a tolerance test.

This engine substitutes the **daily growth factor** `x = (1+r)^(1/365)`, so `(1+r)^(d/365) = x^d`
with `d` a whole number of days, then multiplies through by `x^dMax` to clear every division:

```
F(x) = Σ cf_i · x^(dMax − d_i)          d_i = ACT days from the earliest flow
```

A polynomial in `x` with non-negative integer exponents, sharing NPV's positive roots. The solve
therefore uses only `BigDecimal.pow(Int, MathContext)`, `multiply` and `add` — which is what makes
determinism provable rather than asserted.

```
1. group flows by day and SUM same-day amounts        -> answer is order-independent
2. fewer than 2 distinct days                          -> TOO_FEW_FLOWS
3. all coalesced amounts one sign                      -> SAME_SIGN
4. sign(F(X_LOW)) == sign(F(X_HIGH))                   -> NOT_BRACKETED
5. bisect [X_LOW, X_HIGH], ITERATIONS times, no early exit
6. r = x^365 − 1, then ONE HALF_EVEN rounding to basis points
```

**Bisection, not Newton**: slower, and it does not care. There is no guess to depend on and no
tolerance branch to drift, so the same flows give the same basis points on every machine and every
build — the only property that matters for a figure the app will still be showing in five years.

A holding's figures:

```
netQuantity  = Σ BUY.quantity − Σ SELL.quantity          (INCOME moves no units)
currentValue = netQuantity × unitPrice                    (BigDecimal, ONE HALF_EVEN rounding)
invested     = Σ BUY.amount
realised     = Σ SELL.amount + Σ INCOME.amount
gain         = realised + currentValue − invested
flows        = BUY(−), SELL(+), INCOME(+), then (pricedOn, +currentValue) when there is one
```

## Assumptions & guardrails

- Money is `Long` paise (MNY-001); the rate is integer basis points (MNY-002). No `Double` anywhere.
  `BigDecimal` appears only inside `Xirr` and `CashFlows.currentValue`, never on the public surface.
- **Reads no clock.** The terminal flow is dated by `InvestmentHolding.pricedOnIsoDate`, never by
  today, so the same holding reports the same return tomorrow (P-08, TIM-001). There is deliberately
  no `asOf` parameter to supply one.
- **ACT/365 fixed.** Chosen because it is what Excel's `XIRR` uses, so a user checking the app
  against a spreadsheet gets the same number. A 366-day span is therefore 366/365 of a year —
  the `leap-span` golden case pins it at 997 bps where a 365-day equivalent gives 1000.
- **Absent is never zero (P-03).** A holding that still holds units and has never been priced
  reports `currentValue = null` and `gain = null`. Substituting zero would report the user's entire
  cost as a loss — wrong, alarming, and perfectly plausible. A *fully exited* holding is worth
  exactly zero without needing a price, because no units are held.
- **Cold start.** No lots → every figure zero or absent, `xirrUnavailable = TOO_FEW_FLOWS`.
- **Multiple sign changes** (buy → full sell → re-buy → value) admit more than one mathematical
  root, so XIRR is not uniquely defined. This returns the root bisection finds in the fixed bracket:
  deterministic, though not unique. Excel's answer there is guess-dependent; this one is not.
- **Allocation is over the priced positions only.** An unpriced holding is excluded from the
  denominator and counted in `unvaluedCount`, never treated as ₹0 — the same P-03 rule the value
  path keeps. Coverage travels on `provenance.confidenceBps` so a partial split can say so.
- **A position at exactly the threshold is not flagged.** The rulebook says "gold <= 10%" and
  "single holding <= 15%", so the comparison is strictly greater. Flagging at the line would accuse
  a user who is inside the rule.
- **The shares are apportioned, not rounded.** Floors plus largest-remainder distribution, so they
  sum to exactly 10 000 bps. Rounding each slice independently would show a portfolio adding to
  99.97%.
- **What it does not do:** no market prices (issue 6.5); no target band, so no `RULE-AGE-EQUITY`
  and no `RULE-5-25` rebalancing signal — both need the user's age or the §11.2 risk profile, and
  the app collects neither yet; no 0–100 diversification score, because §11.2 defines it partly from
  "overlap of goals on same asset" and goals are Epic 7; no per-lot LTCG/STCG matching
  (`GRW-TAX-003`, growth phase), no advice of any kind (P-07).

## Rules / knowledge consumed

| ID / file | What it provides |
|-----------|------------------|
| `RULE-GOLD-CAP` v1.0 | `cap_pct: 10` — the share of the portfolio above which gold is flagged |
| `RULE-CRYPTO-CAP` v1.0 | `cap_pct: 5` — the same for crypto |
| `RULE-CONC-15-70` v1.0 | `single_holding_pct: 15`, `single_class_pct: 70` |

**Only `allocation` reads a rule.** `xirr` and `holding` still consume none: a money-weighted return
has no threshold to tune, only arithmetic to get right — the same argument `:domain:engines:loan`
makes for amortisation.

All three rows shipped before issue 6.4 and all three already named `AI-INV.diversification` in
their `consumed_by` — the rulebook was written expecting this engine. So every threshold is **read,
not authored**, no row is touched, and `_meta.version` in `ai/rules/rules-kb.json` stays `1.13.0`.
That is what keeps 6.4 clear of ADR-0017's trigger 3, exactly as issue 6.1 stayed clear of it by
leaving `RULE-CC-UTIL` alone.

The numbers are mirrored in `InvestmentRules` rather than loaded, the §6 deferral ADR-0005 records
and ADR-0017 restates. `RulebookDriftTest` closes the gap that matters, and
`build.gradle.kts` declares the rulebook a test input so editing it actually re-runs that gate.

The five AI-INV rows that *do* exist — `RULE-AGE-EQUITY`, `RULE-5-25`, `RULE-GOLD-CAP`,
`RULE-CRYPTO-CAP`, `RULE-CONC-15-70` — are issue 6.4's, and `evidence` on an investment result
arrives with them.

## Evidence shown to the user (P-02)

`XirrRate` carries `flowCount` and `spanDays` beside the rate, and `HoldingPerformance` carries
`invested` and `realised` beside `gain`, so the drill-down can say *"16.06% — 4 cash flows over 888
days"* rather than a bare percentage. `provenance` carries `engineId = "investment-xirr"` and
`engineVersion`, with **empty `evidence`** for the reason above: citing a rule that decided nothing
would be a false citation (AI-ARC-006).

## Tests

- **Golden** (`src/test/resources/golden/investment.txt`, 8 blocks): a hand-checkable full year at
  10%, a −10% year, a break-even, a twelve-instalment SIP, two purchases on one day, a 91-day hold,
  a multi-year case with a partial sale and a dividend, and a leap-year span. **Expectations come
  from an independent 60-significant-digit decimal implementation** that solves NPV directly with
  exp/ln — a different formulation from the engine's, so agreement is evidence the answer is right
  rather than merely unchanged.
- **Property** (500 seeded cases each): order-independence; recovery of a known rate to ±1 bps from
  a terminal value the *test* computes as `cost × (1+r)^k`; monotonicity in the closing value; sign
  agreement; every rate inside the documented band; the solver always terminates; determinism.
- **Boundaries** (`InvestmentEngineTest`): all three refusals, provenance, the derived position,
  income not reducing cost, the unpriced case, the fully-exited case, no lots, banker's rounding in
  both directions, and the rate-or-reason invariant.
- **Allocation golden** (`src/test/resources/golden/allocation.txt`, 9 records): the boundary pair
  sitting exactly on and one basis point past both ceilings, all three flag kinds at once, an
  unpriced exclusion, an account counted whole, the thirds case where a leftover basis point has to
  be handed out, and the three empties. **Expectations come from an independent Python
  implementation** written from the apportionment spec rather than from this Kotlin, so agreement is
  evidence the shares are right rather than merely unchanged. A meta-test pins the record count so a
  deletion cannot pass unnoticed.
- **Allocation property** (1 000 seeded portfolios): shares sum to exactly 10 000 bps; slice values
  sum to the total; every position is counted or explicitly excluded; no flag is ever raised at or
  under its threshold; slices ordered largest first; plus monotonicity, order-independence and
  determinism.
- **Allocation boundaries** (`AllocationTest`): both cap boundaries and both concentration
  boundaries from either side, the uncapped-class case, the unpriced exclusion, coverage, evidence,
  both unavailable reasons, and the invariants the value types refuse to be built without. Every
  threshold is read from `InvestmentRules`, never written as a literal.
- **Drift** (`RulebookDriftTest`): every mirrored threshold and citation matches
  `ai/rules/rules-kb.json`, the rulebook revision the engine names is the one on disk, both class
  caps name two different classes, and neither row grew the other's parameters. **Verified by
  breaking it**: editing `cap_pct` in the JSON with no Kotlin change turned it red, which also
  proves the `inputs.file` wiring.
- **Coverage:** ≥ 85% enforced by `koverVerify`; the `:core:model` types it consumes are at 100%.

## Version log

| Version | Date | Change |
|---------|------|--------|
| 1.0 | 2026-08-24 | Initial implementation from SRS §11 for issue 6.3. |
| 1.1 | 2026-08-28 | Added `allocation` for issue 6.4 (FR-INV-002). No existing answer moved — the XIRR golden file, computed independently, still agrees to the basis point — but a result stored under 1.0 came from an engine that could not have produced an allocation, and AI-ARC-006 needs that to stay tellable. |

**The version contract.** `PRECISION` (34), `X_LOW` (0.98), `X_HIGH` (1.02), `ITERATIONS` (128) and
`DAYS_PER_YEAR` (365) are part of `ENGINE_VERSION`, not tuning knobs. Changing any one of them
changes every historical answer, so it is a version bump (AI-ARC-006) — the same discipline
`Emi.kt` records for its pinned `MathContext`.
