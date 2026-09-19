# StreamEngine — AI-CLS Stage 2 (stream classification)

**SRS:** §8.2 (and §8.1's two-stage design)  ·  **Pipeline layer:** L2  ·  **Module:** `:domain:engines:stream`
**Version:** 1.0  ·  **Status:** active  ·  **Engine id on results:** `AI-CLS.stream`

## Why this engine exists
§8 opens: "every downstream engine (Safe-to-Spend, emergency fund, forecasting, purchase advisor)
depends on knowing which outflows are obligations." Stage 1 (issue 4.2) files each rupee under a
category and AI-CLS-N (issue 4.3) decides what it became. Neither one says whether the rupee will
come round again. This engine does, per stream: FIXED, SEMI_FIXED or VARIABLE. It also totals the
month's fixed load, the semi-fixed spend to expect, and the flexible spend that can be budgeted. The
forecast (9.2) and the health score (9.4) are its intended consumers. Today the dashboard shows it.

## Contract
```
interface StreamEngine {
    fun classify(input: StreamInput): Result<StreamProfile, AppError>
}
```
- **Input** — `StreamInput`:
  - `windowStart`, `windowEnd`: inclusive `LocalDate`s (TIM-002). The repository passes the last six
    closed months.
  - `streams`: one `StreamHistory` per stream, containing:
    - `streamKey`: the Stage-1 category id;
    - `priorKey`: the `category_defaults` key, or `null`;
    - `occurrences`: date and **positive** `Money` in paise;
    - `isKnownObligation`;
    - `pin`: nullable.
  - `nowUtcMillis`: from the caller's injected clock.
  - `rules`: the `StreamRules` mirror.
- **Output** — `StreamProfile`:
  - `streams`: `StreamVerdict` list in key order. Each verdict holds the class, the `basis` (the
    step that fired), `metrics` (n, cv, cadence, dayLock and score, all in bps), `typicalMonthly`
    (paise) and `provenance`.
  - `fixedLoad`, `semiFixedExpected`, `variableBudgetable`: `Money`.
  - `provenance`: `engineId = "AI-CLS.stream"`, `engineVersion`, `inputWindow = "start..end"`,
    `computedAtUtcMillis`, `confidenceBps` (per verdict), and `evidence` (the `CLS-STR-*` rows and,
    for a prior, its `CLS-CAT-*` row).
- **Refuses** (`Validation`): `stream.window` (end before start), `stream.key` (duplicate),
  `stream.amount` (negative).

## Formula / algorithm
Per stream, over its occurrences inside the window (§8.2 verbatim, then the precision decisions):

```
n       = count(months with activity)
cv      = stdev(monthly totals) / mean(monthly totals)   // population stdev
cadence = MAD(gaps between occurrences, days) / median gap
dayLock = share of occurrences within ±3 days of the modal day-of-month
score   = 0.45*(1 - min(cv,1)) + 0.35*(1 - min(cadence,1)) + 0.20*dayLock
```

First match wins (`stream_classification.steps`):

| Step | Row | Condition | Class | Confidence |
|------|-----|-----------|-------|-----------|
| 1 | CLS-STR-003 | the user pinned it | the pin | 10 000 |
| 2 | CLS-STR-002 | a confirmed recurring outflow names the category | FIXED | 9 500 |
| 3 | CLS-STR-004 | n < 2 | the category's `typical_stream`, else VARIABLE | 4 000 / 2 000 |
| 4 | CLS-STR-001 | score ≥ 0.75 **and** n ≥ 3 → FIXED; score ≥ 0.45 → SEMI_FIXED; else VARIABLE | — | 8 000 |

`typicalMonthly` is the median of the monthly totals (HALF_EVEN on a half paisa). The totals sum
`typicalMonthly` by class.

**Precision.** Everything is exact `BigDecimal` at 34 digits (`MathContext.DECIMAL128`), and the
class is decided on the **exact** score. The bps values in `metrics` are rounded HALF_EVEN, for
display only. `cv = √(n·Σx² − (Σx)²) / Σx` needs one square root and no intermediate division.

## Assumptions & guardrails
- **A stream is a Stage-1 category**, the "category" half of "merchant-series or category". This
  follows ADR-0042. Split payments count by their lines (ADR-0018). Uncategorised expenses form one
  stream with no prior.
- **cv is over months with activity**, not over all six months. A stream seen in three months has
  three totals.
- **Fewer than two gaps, or a median gap of 0 days, is cadence 1 (the worst).** Too few gaps is no
  evidence of a rhythm. Scoring it 0 (the best) would reward a stream seen twice.
- **Day-lock distance is circular over 31 days.** The 30th, the 31st and the 1st are neighbours, so
  rent paid "at month end" is not split across two modes. Ties for the mode go to the earliest day.
- **Cold start without a prior is VARIABLE, never FIXED.** FIXED would tell a forecast to reserve
  money for a commitment nobody has shown exists.
- **Known obligations are recurring rules only**, matched at transaction level by the repository.
  A confirmed detected rule's merchant becomes its own `recurring:<merchant>` stream. A
  quick-setup rule's category is flagged as a whole. The engine just receives `isKnownObligation`.
  §8.2 also names loan EMIs and insurance premia, but neither is linked in this schema. The EMI and
  insurance categories carry a FIXED prior, which applies during cold start.
- **Pins are an engine input with no store yet.** The repository passes none (ADR-0042). The
  precedence is implemented and tested.
- **A quarterly or annual fee** seen in three of six months scores well and reports its full
  amount as the month's typical. Amortising it is not in §8.2 and is not done.
- Numbers only, no prose (P-03). No clock, no randomness, no I/O (P-08).

## Rules / knowledge consumed
| ID / file | What it provides |
|-----------|------------------|
| `stream_classification` in `ai/knowledge/classification-kb.json` v1.4 | weights, thresholds, `window_months`, `day_lock_window_days`, `cold_start_min_months`, the `steps` rows with their confidences |
| CLS-STR-001..004 v1.0 | the four steps, cited on every verdict |
| `category_defaults[].typical_stream` (CLS-CAT-001..015) | the cold-start priors, each cited |

Mirrored as `StreamRules` and guarded by `StreamKbDriftTest`, which checks that the file's decimals
× 10 000 equal the mirror's bps. The knowledge base is a declared test input.

## Evidence shown to the user (P-02)
The dashboard's "Every month, typically" line shows fixed, semi-fixed and flexible amounts, all
masked by the privacy blur. Below them come "Partly an estimate …" when any verdict came from a
prior (§8.2 requires the label), and "Rules: …" listing every row that fired.

## Tests
- **Golden file** (`golden/streams.txt`, 16 records): its expected values come from an
  **independent** Python oracle (float maths, `statistics.median`), not from this engine. It covers
  every class and every basis, month-end wrap, the window edges, HALF_EVEN medians, same-day
  duplicates, and a perfect score in two months that is still not FIXED.
- **Behaviour** (`StreamEngineTest`, 20): threshold edges by injected rules, one basis point either
  side of the exact score; the n ≥ 3 and cold-start floors; the day-lock window; the totals and the
  identity that they account for every stream once; ordering and determinism; provenance; refusals.
- **Drift** (`StreamKbDriftTest`, 7).
- **Watched red:** dropping `n ≥ fixedMinMonths` failed the golden and behaviour tests. Changing a
  KB threshold alone failed the drift test.

## Version log
| Version | Date | Change |
|---------|------|--------|
| 1.0 | 2026-09-19 | Initial implementation from SRS §8.2 (issue 9.1). |
| 1.0 | 2026-09-19 | Issue 9.2: `StreamMetrics.modalDayOfMonth` added (additive, no verdict changes). |
