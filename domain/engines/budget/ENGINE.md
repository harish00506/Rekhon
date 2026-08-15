# BudgetEngine — what a category should cost, and whether the month is on track

**SRS:** §5.5, §29.2 · **Pipeline layer:** L3 (rules) · **Module:** `:domain:engines:budget`
**Version:** 1.0 · **Status:** active

## Why this engine exists

FR-BUD-002 and FR-BUD-003 are the two questions a budget screen has to answer — *"what should this
cost?"* and *"am I on track?"* — and both are arithmetic. P-03 puts them here so the numbers exist
before any words are put around them, and P-08 keeps them in a module with no clock and no database
so both are provable on the JVM.

**The median, not the mean, is the whole design of the suggestion.** A mean over three months hands
a single ₹40,000 hospital bill to next month's health budget as if it were normal. Budgeting is a
question about a *typical* month, and the median is the statistic that answers it.

**The seasonality adjustment exists because the median alone is wrong in a predictable direction.**
A Diwali shopping budget built from July, August and September is a cut the user never asked for.
§9.3 supplies labelled Indian calendar priors precisely so this works in year one, before the app
has enough of the user's own history to learn a seasonal index from.

Nothing here writes, and nothing here decides: a suggestion is offered, and only the user's tap
turns it into a budget (P-07).

## Contract

```
interface BudgetEngine {
    fun suggest(input: BudgetSuggestionInput): Result<BudgetSuggestion?, AppError>
    fun status(input: BudgetStatusInput): Result<BudgetStatus, AppError>
    fun alert(input: BudgetAlertInput): Result<BudgetAlert?, AppError>
    fun review(input: BudgetReviewInput): Result<BudgetReview?, AppError>
}
```

- **Suggestion input** — `BudgetSuggestionInput`: `categoryId`, `categoryName` (matched against the
  seasonality KB, case-insensitively), `monthlySpends` (oldest first), `targetMonth` (1–12),
  `monthsObserved`, `nowUtcMillis` (**passed in, never read for calendar logic** — TIM-001),
  `rules`.
- **Suggestion output** — `BudgetSuggestion?`: the `amount`, the unadjusted `medianAmount`, the
  `seasonalEventId` and `seasonalIndexBps` that moved it, and `provenance`. **`null` is a valid
  answer**, returned when there is less history than `min_months_required`.
- **Status input** — `BudgetStatusInput`: `plannedAmount`, `carriedOver`, `spent`, `daysInPeriod`,
  `daysElapsed`, `nowUtcMillis`, `rules`.
- **Status output** — `BudgetStatus`: `budgeted` (planned + carried), `remaining` (**signed** — an
  overspend is a negative, not a clamped zero), `safePaceToDate`, `projectedEndOfMonth` (nullable),
  and the three derived flags `isOverspent` / `isAheadOfPace` / `isProjectedToOverspend`.

- **Alert input** — `BudgetAlertInput`: `categoryId`, `categoryName` (carried through for the
  message, never matched against anything), `budgeted` (rollover already included), `spent`,
  `nowUtcMillis`, `rules`. Deliberately narrower than `BudgetStatusInput`: a band is crossed at a
  ratio, on any day, so asking for the month's shape would suggest it changed the answer.
- **Alert output** — `BudgetAlert?`: the `band` (`WARN` | `EXCEEDED`), `usedBps`, `budgeted`,
  `spent`, `overspentBy` (present for `EXCEEDED` only), and `provenance`. **`null` is a valid
  answer** — below the warn band, and for an unset (zero) budget, which is also the division-by-zero
  guard.

- **Review input** — `BudgetReviewInput`: `monthStartIsoDate` (the **reviewed**, already-closed
  month), `categories` (one `ReviewedCategoryInput` per budgeted category — `budgeted`, `actual`,
  `monthlySpends`), `targetMonth` (1–12, the month a proposal would apply to — the one the user is
  standing in, **not** the reviewed one), `monthsObserved`, `nowUtcMillis`, `rules`.
- **Review output** — `BudgetReview?`: `monthStartIsoDate`, `totalBudgeted`/`totalActual` (summed
  here — P-03 does not exempt addition), `categories` (every budgeted category, including on-plan
  ones), and `provenance` citing `RULE-BUD-REVIEW`. **`null` is a valid answer** — nobody budgeted
  anything last month. Each `ReviewedCategory` carries `variance` (signed), `varianceBps`
  (magnitude), `direction` (`OVER`/`UNDER`/`ON_PLAN`), and `proposal` — priced by calling [suggest]
  internally, so a reviewed proposal is provably the same number a plain suggestion would show for
  the same category, `null` for `ON_PLAN` rows and also for a material row with too little history.

**This engine performs no calendar arithmetic.** `daysInPeriod` and `daysElapsed` arrive already
resolved by the repository, which owns the injected `Clock` and the profile time zone (TIM-001).

## Formula / algorithm

### FR-BUD-002 — the suggestion

| Step | What happens | Result |
|------|--------------|--------|
| 1 | Take the last `lookback_months` (3) monthly totals | the window |
| 2 | If fewer than `min_months_required` (2), stop | `null` — no opinion |
| 3 | Median: middle value, or the **exact midpoint** of the two middle ones via `Money.split` | the typical month |
| 4 | Find the seasonal priors matching (category, target month); take the **maximum** | the raw multiplier, or none |
| 5 | Shrink it: `index = 1 + k(raw − 1)`, `k = min(monthsObserved, 24) / 24`, in integer bps | the applied index |
| 6 | Multiply, then round to the nearest `round_to_minor` (₹100), **ties up** | the suggestion |

Confidence is `windowSize × 10 000 / lookback_months` — a full window is worth full confidence, the
bare minimum proportionally less.

### The maximum, never the product

October is both Diwali (1.38) and Dussehra (1.20) for Shopping; June is both monsoon and summer.
Multiplying would claim 1.66 for an October shopping budget — a number no row in the knowledge base
supports, and one that would **grow every time a future editor adds an event to the same month**.
Taking the maximum keeps the applied figure bounded by something a reviewer can point at.

### FR-BUD-003 — the status

| Figure | Formula |
|--------|---------|
| `budgeted` | `planned + carriedOver` |
| `remaining` | `budgeted − spent`, signed |
| `safePaceToDate` | `budgeted × (daysElapsed × 10 000 / daysInPeriod)` bps |
| `projectedEndOfMonth` | `spent × (daysInPeriod × 10 000 / daysElapsed)` bps, or `null` below the floor |

### FR-BUD-004 — the alert bands

| Step | What happens | Result |
|------|--------------|--------|
| 1 | If `budgeted` is zero, stop | `null` — an unbudgeted category is not an overspent one |
| 2 | `usedBps = spent × 10 000 / budgeted`, integer, **truncating**, saturated at `Int.MAX_VALUE` | the share used |
| 3 | If `usedBps ≥ exceeded_pct × 100` → `EXCEEDED`; else if `≥ warn_pct × 100` → `WARN`; else stop | the band |
| 4 | For `EXCEEDED`, `overspentBy = max(spent − budgeted, 0)` | how far past the plan |

**Truncation is the honest direction.** 79.99% of a budget is not 80% of it, so a user is never
interrupted a rupee early. **The exceeded band is tested first** because the bands overlap by
definition — everything past 100% is also past 80%, and testing warn first would report every
overspend as a warning.

**`overspentBy` is floored at zero, and not defensively.** `exceeded_pct` is a rule row, so a profile
may set it below 100 — at which point the band is reached while the budget still has money in it, and
the honest amount past the budget is nothing.

**The band is a decision, not a notification.** Whether one is *sent* is the repository's and the
worker's question, because only they know what the user has already been told.

### §5.5 — the monthly review

| Step | What happens | Result |
|------|--------------|--------|
| 1 | For each budgeted category, `variance = actual − budgeted` (signed) | the gap |
| 2 | `varianceBps = \|variance\| × 10 000 / budgeted`, integer, saturated at `Int.MAX_VALUE`, `0` for a zero budget | the magnitude |
| 3 | If `varianceBps ≥ min_variance_pct × 100` → `OVER` or `UNDER` by the sign of `variance`; else `ON_PLAN` | the direction |
| 4 | For `OVER`/`UNDER` rows only, call [suggest] with `targetMonth`/`monthsObserved`/`rules` from the review input | the proposal, or `null` for want of history |
| 5 | Sum every category's `budgeted`/`actual` | `totalBudgeted`/`totalActual` |
| 6 | If no category was budgeted, stop | `null` — not an error, a user who has not budgeted yet |

**The materiality threshold is the same shape as the alert bands, one rule row over.** Both compare
a spend to a plan as basis points against a rule-supplied percentage; `RULE-BUD-REVIEW` is the
review's own row rather than a param on `RULE-BUD-ALERT`, for the identical reason `RULE-BUD-ALERT`
is its own row and not a param on `RULE-BUD-PACE` — different questions, edited for different
reasons, and a shipped row's version must not move to answer a second one (ADR-0017's trigger 3).

**A proposal prices the month ahead, not the month reviewed.** `targetMonth` is read from the review
input, never derived from `monthStartIsoDate` — pricing a finished month's seasonal prior onto the
month the user is about to spend in would be a subtly wrong number that looked plausible.

**Two rules stand behind one finding, at two levels.** The review's own `provenance` cites
`RULE-BUD-REVIEW`, which decided the month was worth reviewing and this category worth mentioning.
Each `ReviewedCategory.proposal`, when there is one, carries its own `provenance` citing
`RULE-BUD-SUGGEST` — the same rule an ordinary suggestion cites — because it *is* the same
calculation, called from a different caller.

## Assumptions & guardrails

- **No `Double` or `Float` appears anywhere in this module** (MNY-001). The median of an even window
  is an exact `Money.split`; the seasonal index is integer basis points (MNY-002); both pace figures
  go through `Money.percentOf`, which rounds `HALF_EVEN`.
- **Rounding ties go up.** On a spending cap the generous direction is the one that does not set the
  user up to fail by a rupee.
- **The shrinkage `k` is capped at 1.** More than 24 months of history must never *amplify* a prior
  past what the knowledge base claims.
- **A withheld projection is `null`, not a hedge.** A run rate taken on day 1 turns one grocery run
  into an implausible month; `isProjectedToOverspend` is `false` while the projection is withheld,
  because an unknown is not a warning.
- **Landing exactly on the budget is not an overspend.** The comparison is strict, so a user who
  spent precisely what they planned is not nagged for it.

### Known limits, stated rather than hidden

- **A sustained step change takes two months to move a three-month median.** A user who moves house
  sees a stale rent suggestion once. This is the direct cost of choosing the median, and it is the
  right trade — the alternative reacts to every one-off as if it were the new normal.
- **Seasonal matching is by category *name*.** The knowledge base indexes by names like `Shopping`
  and `Travel`, which line up with `CategorySeed`, but a user who renames a category or creates
  `Weekend fun` gets no seasonal adjustment. The failure is silent and in the safe direction — no
  adjustment rather than a wrong one.
- **`Gifts`, `Gold` and `Vehicle` appear in the knowledge base and not in `CategorySeed`.** Those
  priors are inert until a user creates a category with a matching name.
- **The alert bands live on their own rule row, permanently.** `RULE-BUD-ALERT`, not
  `RULE-BUD-PACE`, and `RulebookDriftTest` asserts they never appear on the latter. Two reasons, both
  binding: adding a param to a shipped row bumps its version, which is ADR-0017's trigger 3 and
  forces the runtime rules loader; and pace answers "am I on track?" while alerting answers "should
  this person be interrupted?" — different questions, edited for different reasons. See
  [ADR-0019](../../../docs/adr/0019-budget-alert-bands-mint-a-new-rule-row.md).
- **`notify_once_per_band_per_month` is documented here and enforced elsewhere.** The engine returns
  a band every time it is asked; the once-per-month promise is a `UNIQUE(profile_id, budget_id,
  month_start_iso_date, band)` index on `budget_alert`. The flag records the intent, the index is the
  mechanism.
- **`review_once_per_month` is the identical pattern, one level coarser.** The engine returns a
  review every time it is asked; the once-per-month promise is a `UNIQUE(profile_id,
  month_start_iso_date)` index on `budget_review` — no band, no category, because a review is a
  single card for the whole month rather than one status per band (ADR-0020).

## Rules / knowledge consumed

| ID / file | What it provides |
|-----------|------------------|
| `RULE-BUD-SUGGEST` (`ai/rules/rules-kb.json` v1.10.0) | `lookback_months`, `min_months_required`, `seasonality_enabled`, `shrinkage_denominator_months`, `round_to_minor` |
| `RULE-BUD-PACE` (`ai/rules/rules-kb.json` v1.10.0) | `projection_basis`, `min_elapsed_days_for_projection` |
| `RULE-BUD-ALERT` (`ai/rules/rules-kb.json` v1.10.0) | `warn_pct`, `exceeded_pct`, `notify_once_per_band_per_month` |
| `RULE-BUD-REVIEW` (`ai/rules/rules-kb.json` v1.11.0) | `min_variance_pct`, `proposal_basis`, `review_once_per_month` |
| `ai/knowledge/calendar-seasonality.json` v1.0 | the nine calendar events, their windows, the categories they inflate, their priors, and the shrinkage rule |

Both are **typed mirrors** (`BudgetRules`, `SeasonalityPriors`), not loaded at runtime — the
deferral ADR-0005 opened and [ADR-0017](../../../docs/adr/0017-budget-thresholds-stay-a-typed-mirror.md)
restates for this engine. `RulebookDriftTest` and `SeasonalityKbDriftTest` are what make the
deferral cost nothing; both files are declared as Gradle test inputs so the gates cannot report
green against a file they never read.

## Evidence shown to the user (P-02)

Every result carries `EngineProvenance` with `engineId = budget-planner`, the version, the caller's
instant and the cited rows:

- an ordinary suggestion cites `RULE-BUD-SUGGEST`;
- a seasonal one cites `RULE-BUD-SUGGEST` **then** the calendar event's own id (`diwali`,
  `wedding_season`, …), in that order, so the screen renders the rule that fired and then what it
  claimed;
- a status cites `RULE-BUD-PACE`;
- an alert cites `RULE-BUD-ALERT` **alone** — the bands come from that row and nothing on that path
  reads pace, so citing both would point the user's "why am I seeing this?" at a rule that had no
  part in the decision.
- a review cites `RULE-BUD-REVIEW` on the whole result; each material category's `proposal`, when
  there is one, carries its **own** provenance citing `RULE-BUD-SUGGEST` (and any seasonal event),
  because materiality and pricing are different claims made by different rows.

A suggestion is the only result in this app that states its `inputWindow` — `"2026-05-01..2026-07-31"`
— because "median of three months" is only checkable if the three months are named.

## Tests

| Test | What it holds |
|------|---------------|
| `BudgetGoldenTest` | 20 frozen records over `golden/budget.txt`; asserts amounts, medians, all four status figures, the derived flags and the **ordered** citations. A meta-test asserts the fixture still covers the seasonal, unadjusted, no-suggestion, wrapping-window and withheld-projection paths, plus 8 `kind=review` records covering the mixed/threshold/unpriceable/zero-budget review paths |
| `BudgetEngineTest` | both thresholds at / just below / just above their boundary, read from `BudgetRules` rather than literals; median and rounding edges; rollover; provenance; the review's own boundary cases (79/80/99.99/100/101%, zero budget, zero variance, unpriceable-but-reported row, empty-categories → `null`, target-month-not-reviewed-month seasonal correctness, dual citation) |
| `SeasonalityPriorsTest` | both window shapes including the four that wrap the year end, max-not-product, and the three points of the shrinkage curve |
| `RulebookDriftTest` | every `RULE-BUD-*` threshold and version against the real rulebook, plus the permanent assertions that the alert bands are **not** on `RULE-BUD-PACE` and the review's variance/proposal-basis params are **not** on `RULE-BUD-SUGGEST` |
| `SeasonalityKbDriftTest` | every event's id, window (**re-parsed from the KB's own `"Oct-Nov"` strings**, not copied), inflated categories and multiplier |

Coverage: module ≥ 85% (`koverVerify` gate), money math 100%.

**Every gate above was watched to fail on purpose before being trusted** (2026-08-11): the rulebook
threshold moved 3 → 4, the Diwali prior 1.38 → 1.42, the wedding window `Nov-Feb` → `Nov-Jan`, and
the median swapped for a mean. The first three were edits to `ai/` files **only**, which also proves
the Gradle `inputs.file` wiring — the specific vacuous-gate failure this repo has hit before.

## Version log

| Version | Date | Change |
|---------|------|--------|
| 1.0 | 2026-08-11 | Created for issue 4.4 (FR-BUD-001/002/003) |
| 1.0 | 2026-08-13 | Added `alert` for issue 4.5 (FR-BUD-004). **Version unchanged, deliberately**: no existing figure moved, so every result computed under 1.0 is still reproducible under 1.0 (AI-ARC-006). `BudgetPace` and `BudgetAlertBands` were split out of `DefaultBudgetEngine` in the same commit — a pure move, guarded by the golden file |
| 1.0 | 2026-08-15 | Added `review` for issue 4.6 (§5.5). **Version unchanged, for the same reason**: `review` calls the unchanged `suggest` internally rather than duplicating its math, so nothing already shipped moved. `BudgetMonthReview` was added as a new pure calculator alongside `BudgetPace`/`BudgetAlertBands`, same shape |
