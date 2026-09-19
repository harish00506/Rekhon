# SeasonalityEngine — AI-SEAS (seasonal index)

**SRS:** §9.3 (and §9.2's `seasonalAdjustment(d)`)  ·  **Pipeline layer:** L4  ·  **Module:** `:domain:engines:seasonality`
**Version:** 1.0  ·  **Status:** active  ·  **Engine id on results:** `AI-SEAS`

## Why this engine exists
The forecast (AI-FCT) predicts everyday spend from the last ninety days. That is right for an
ordinary month and wrong for Diwali. It is also wrong the other way for October after a monsoon
that inflated the lookback. §9.3's answer is a seasonal index per category and month:
- from the user's own months across years where they exist;
- from the Indian calendar knowledge base where they don't;
- shrunk toward "no change" while the history is short.

This engine computes those indices, and turns them into one factor per month for the forecast's
everyday spend. It also owns the one typed mirror of `ai/knowledge/calendar-seasonality.json`,
which the budget suggestion (issue 4.4) reads too.

## Contract
```
interface SeasonalityEngine {
    fun index(input: SeasonalityInput): Result<SeasonalityResult, AppError>
}
```
- **Input** — `SeasonalityInput`:
  - `history`: spend per category per **closed** month, in paise. A month with no row for a
    category cost nothing, and every month with any row is observed.
  - `lookback`: the forecast's everyday spend per category over `lookbackStart..lookbackEnd`
    (inclusive).
  - `months`: the months to give a factor for.
  - `nowUtcMillis`, and `rules` (SEAS-INDEX).
- **Output** — `SeasonalityResult`:
  - `indices`: one `SeasonalIndex` per category per requested month, with `rawBps`, `indexBps`,
    `source` (`OWN_HISTORY` / `CALENDAR_PRIOR` / `NONE`) and `eventId`.
  - `factors`: one `SpendFactor` per month, with `factorBps` and the `rising` / `easing` events and
    `fromOwnHistory` flag behind it.
  - `monthsObserved`, and provenance:
    - `engineId` AI-SEAS and `engineVersion` 1.0;
    - `inputWindow`: `history · lookback → months`;
    - confidence = `k` in bps;
    - evidence: SEAS-INDEX, then each calendar event that was named, at the KB's version.
- `Err(Validation("seasonality.spend"))` for a negative amount;
  `Err(Validation("seasonality.lookback"))` for a lookback that ends before it starts.

## Formula / algorithm
```
own raw(c, m)  = HALF_EVEN( median(c's spend in calendar month m, over observed years)
                            / median(c's spend over all observed months) × 10 000 )
                 — used only if month m was observed, both medians are > 0 (ADR-0044 §1–2)
prior raw(c, m)= the strongest KB event for c's name in m (max, never a product), else 10 000
index(c, m)    = 10 000 + trunc((raw − 10 000) × min(n, 24) / 24)   n = months observed
L_c            = mean of index(c, month(d)) over the lookback's days d
factor(m)      = HALF_EVEN( (Σ_c s_c × index(c, m) / L_c + s_uncategorised) / S × 10 000 )
effect(c, m)   = s_c / S × (index(c, m) / L_c − 1) × 10 000
named          = rising: m's prior event when effect ≥ 100; easing: the lookback's other prior
                 events when effect ≤ −100; own: an OWN_HISTORY index with |effect| ≥ 100
noise          = |factor − 10 000| < 100 → factor is 10 000 and nothing is named
```
All arithmetic is exact `BigDecimal` (34 digits), rounded once. A lookback indexed to zero
throughout gives a ratio of ×1. An own ratio saturates where `excess × k` would leave an `Int`
(thousands of times a typical month).

## Assumptions & guardrails
- **The lookback's season is divided out** (ADR-0044 §3), so a monsoon-heavy base does not carry
  the monsoon into October, and a festival already inside the base is not counted twice. A factor
  below ×1 is a saving, and is named ("after the monsoon").
- **A zero own index is not a season** (ADR-0044 §2): it falls back to the calendar, which errs
  toward more spend.
- **An effect under 1% is noise** (`min_effect_bps`): the month is ×1, and nothing is named. This
  was found on the device (ADR-0044 §4).
- **Priors are shrunk too**, by the same `k` the budget uses. A young install sees small
  adjustments.
- **Category names match the KB exactly** (case aside). The demo's "Dining Out" doesn't match
  "Dining"; synonyms are deferred.
- **Month windows, not dates:** exact festival dates need `/v1/knowledge/calendar` (§22.2), which
  is deferred.
- Numbers only (P-03). No clock, no randomness, no I/O (P-08).

## Rules / knowledge consumed
| ID / file | What it provides |
|-----------|------------------|
| `ai/knowledge/calendar-seasonality.json` 1.1 — `events` | nine events, their month windows, the categories they inflate, their priors (bps) |
| — `method` **SEAS-INDEX v1.0** | `shrinkage_denominator_months` 24, `history_months` 36, `min_effect_bps` 100 |

These are mirrored as `SeasonalityPriors` and `SeasonalityRules`, and guarded by
`SeasonalityKbDriftTest`. The file is a declared Gradle test input.

## Evidence shown to the user (P-02)
Through the forecast card "The next 90 days":
- The components line gains "Seasonal extra ₹X" or "Seasonal saving ₹X".
- Each month the season moves gets its own line, for example: *"October 2026: 20.0% more everyday
  spending than the last 90 days (₹1,240.00 extra) — Diwali"*.
- SEAS-INDEX joins the rules line.

All amounts are masked by the privacy blur.

## Tests
- **Golden** (`golden/seasonality.txt`): 30 months, five categories, twelve target months
  (Sep 2026 to Aug 2027). The categories cover a festival Shopping, a Dining with gaps (which hits
  the zero fallback), a monsoon Transport, a once-a-year Education (median zero, so the prior
  applies), and a young Utilities. The values come from an **independent** oracle
  (`seasonality_oracle.py`), which reads the KB itself and uses exact `Fraction`s.
- **Property** (`SeasonalityPropertyTest`, 8 × 200 cases):
  - an index lies between ×1 and its raw value;
  - scaling every amount changes nothing;
  - a factor that moved lies between its categories' ratios;
  - a factor is exactly ×1 or at least the minimum effect away, and only a moved factor names
    events;
  - input order doesn't matter;
  - there is one index per category per month;
  - named events are never both rising and easing, and follow KB order;
  - with no everyday spend every factor is ×1.
- **Behaviour** (`SeasonalityEngineTest`, 20): cold start, prior shrinkage, own over prior, one-year
  shrinkage, the `k` cap, the zero-median and zero-month fallbacks, unknown names, weighting,
  dividing out the lookback, the minimum effect, observed months, refusals, provenance, event
  citations, injected rules, ordering.
- **Drift** (`SeasonalityKbDriftTest`): every event, re-parsed from the KB, plus SEAS-INDEX's id,
  version and parameters.
- **Watched red:**
  - removing the zero-month fallback failed the golden and behaviour tests;
  - removing the lookback division failed the golden, behaviour and property tests;
  - editing `min_effect_bps` in the KB alone failed the drift test.

## Version log
| Version | Date | Change |
|---------|------|--------|
| 1.0 | 2026-09-19 | Initial implementation from SRS §9.3 (issue 9.3, ADR-0044). Took over the calendar mirror from `:domain:engines:budget`. |
