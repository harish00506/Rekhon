# HealthScoreEngine — AI-FHS (Financial Health Score)

**SRS:** §14 (and FR-AI-001)  ·  **Pipeline layer:** L3  ·  **Module:** `:domain:engines:healthscore`
**Version:** 1.0  ·  **Status:** active  ·  **Engine id on results:** `AI-FHS`

## Why this engine exists
Every other screen answers one question about money. The score answers "how am I doing, overall?"
§14 only allows that if the answer can be opened all the way down:
- five weighted pillars;
- each pillar built from named signals;
- each signal a straight line between two rulebook anchors.

A pillar with no data is shown as "—" and its weight is shared out, never guessed. The number is
arithmetic (P-03).

## Contract
```
interface HealthScoreEngine {
    fun score(input: HealthInput): Result<HealthScore, AppError>
}
```
- **Input** — `HealthInput`. Six optional signals; `null` means "no data", which is not a bad
  score:
  - `runway` (bps of a month, and M);
  - `obligations` (paise a month, the median income, and the months of income behind it);
  - `cards` (statement balance and limit, summed);
  - `savings` (closed months of income and kept);
  - `budgets` and `goals` (good of total);
  - plus `window`, `nowUtcMillis` and `rules`.
- **Output** — `HealthScore`:
  - `score` (0..1000, or `null`) and `band`;
  - all five `PillarScore`s in SRS order, each with its weight, effective weight, points (0..10 000
    = 0..100, or `null`), contribution and signals;
  - the `lever`;
  - provenance: evidence is RULE-FHS-PILLARS, RULE-FHS-BANDS and RULE-FHS-SIGNALS, plus
    RULE-CC-UTIL / RULE-SAVE-RATE when those signals were scored. Confidence is the rulebook weight
    that has data.
- `Err(Validation("health.<signal>"))` only for an impossible input: a negative amount, a target of
  0 months, or more budgets kept than set.

## Formula / algorithm
```
signal points (0..10 000; each rounding half-even, from an exact fraction, off the rounded measure shown):
  runway       min(10 000, runwayBps / M); at ≥ 1 month, at least runway_floor_points × 100
  obligations  r = obligations / income (bps); 10 000 at r ≤ 30%, 0 at r ≥ 55%, linear
  cards        r = max(used, 0) / limit; 10 000 at r ≤ RULE-CC-UTIL 30%, 0 at r ≥ 100%, linear
  savings      r = Σ kept / Σ income; 0 at r ≤ 0, 10 000 at r ≥ RULE-SAVE-RATE 30%, linear
  budgets/goals  good / total
pillar         mean of its signals (equal sub-weights — §14 names signals but no sub-weights)
score          Σ w_p × P_p × 1000 / (Σ w over pillars with data × 10 000)
contribution   apportioned: floors of the exact shares, spare points to the largest remainders
effective w    the same apportionment of 10 000 over the pillars with data
lever          max over signals of w_p × (10 000 − pts) × 1000 / (Σw × n_p × 10 000)
band           800 Excellent · 650 Good · 500 Fair · 350 Needs Attention · below At Risk
```

## Assumptions & guardrails
- **Missing is not zero** (§14, and the repository applies the same rule to its sources: with no
  fixed stream and no loan, obligations are unknown rather than 0% — found on the device): a pillar with no signal is "—", and its weight is shared out.
  Confidence records how much weight rested on data.
- **The parts add up exactly:** contributions sum to the total, and effective weights sum to
  10 000.
- **Signals v1.0 scores** (ADR-0045): runway, obligations, card utilisation, savings rate, budget
  adherence and goals on track.
- **Not yet scored** (ADR-0045), because the app holds no data for them or §14 gives them no
  number:
  - the revolving-interest flag and the no-debt bonus;
  - overspend-alert frequency and spend volatility;
  - retirement contribution and the funding streak;
  - the whole Protection pillar: insurance flags, diversification, idle cash, single income.
- **Not yet done:** weekly cadence, movement with a cause list, and the what-if slider. They need
  stored snapshots (ADR-0045).
- Numbers only (P-03). No clock, no randomness, no I/O (P-08). Integers and exact fractions only
  (MNY-002).

## Rules / knowledge consumed
| ID / file | What it provides |
|-----------|------------------|
| RULE-FHS-PILLARS v1.0 (`rules-kb.json` 1.17.0) | weights 2500/2000/2000/2000/1500, scale 1000, lookback 3 months, minimum 1 month of signal |
| RULE-FHS-BANDS v1.0 | 800 / 650 / 500 / 350 |
| RULE-FHS-SIGNALS v1.0 | runway floor 25, obligations 30→55, utilisation zero at 100 |
| RULE-CC-UTIL v1.0 | `max_utilisation_pct` 30, where utilisation scores full marks |
| RULE-SAVE-RATE v1.0 | `excellent_pct` 30, where the savings rate scores full marks |

These are mirrored as `HealthRules` and guarded by `HealthRulebookDriftTest`, which also asserts
that the two borrowed anchors are **not** restated in RULE-FHS-SIGNALS.

## Evidence shown to the user (P-02)
The dashboard card "Financial health" shows:
- "765 of 1000 · Good";
- each pillar's points, the points it adds and its effective weight, or "— not enough data yet (its
  15.0% weight is shared among the others)";
- each signal against where it scores full marks;
- "Biggest lever: … — up to +N points";
- "Based on N of 5 pillars";
- the rules.

## Tests
- **Golden** (`golden/health.txt`): seven cases — established, new user, nothing yet, the exact
  anchors, the runway floor, overspending, and thin income. They are compared line for line with an
  **independent** oracle (`health_oracle.py`), which reads the rulebook itself and uses exact
  `Fraction`s.
- **Property** (`HealthPropertyTest`, 6 × 300 cases):
  - the score stays on its scale and agrees with its band;
  - contributions and effective weights add up;
  - more runway and less card debt never lower the score;
  - month order doesn't matter;
  - data and weight go together.
- **Behaviour** (`HealthScoreEngineTest`, 18): every curve at its anchors and between them, the
  absence rules, the pillar mean, re-weighting, the band edges, the lever, the refusals, provenance
  and the injected weights.
- **Drift** (`HealthRulebookDriftTest`, 7).
- **Watched red:**
  - removing the runway floor failed the golden and behaviour tests;
  - removing the re-weighting failed the golden, behaviour and property tests;
  - editing the KB's `obligation_zero_pct` alone failed the drift test.

## Version log
| Version | Date | Change |
|---------|------|--------|
| 1.0 | 2026-09-19 | Initial implementation from SRS §14 (issue 9.4, ADR-0045). |
