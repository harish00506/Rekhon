# ADR-0044 — Seasonality reads the user's own months first, divides the lookback's season out of the forecast, and owns the calendar mirror

- **Status:** accepted
- **Date:** 2026-09-19
- **Deciders:** Harish G (solo)
- **SRS refs:** §9.2 (`seasonalAdjustment(d)`), §9.3, P-02, P-03, P-08, MNY-002, AI-ARC-003/006,
  CLAUDE.md §6; issue 9.3. Builds on ADR-0017 (typed mirrors), ADR-0043 (the forecast)

## Context

§9.3 gives three bullets:
- a monthly index per category, `median(month m across years) / median(all months)`, shrunk toward
  ×1 when there are under two years of data (`1 + k(raw − 1)`, `k = months_observed / 24`);
- an Indian calendar knowledge base that contributes expectations even in year one;
- outputs that feed the forecast and the budget suggestions, in the style *"October festival
  spending typically +38% for you — plan ₹6,500 extra"*.

Issue 4.4 already mirrors the calendar file in `:domain:engines:budget` and shrinks its priors with
the same `k`. §9.2 puts `seasonalAdjustment(d)` into the forecast as its own term, and issue 9.2
left that term at zero.

Five things were left open:
1. When does the user's own history beat the calendar?
2. What does a zero own index mean?
3. How does a per-category index become a single adjustment on a forecast whose everyday spend is
   one daily base?
4. Which calendar events should the user be told about?
5. Where does the mirror live now that two engines read it?

The issue cites §17 and §38. §17 is notifications and §38 is the tax engine. Neither says anything
about the seasonal index. §17's "seasonal warnings" belong to the notification engine (9.6), and
§38's tax-saving season is already the KB's `tax_saving_rush` event.

## Decision

**1. The user's own months come first, and the calendar fills the gaps. One formula covers both.**
- The **own index** is the median of calendar month *m* across the observed years, divided by the
  median of all observed months. A month with no row counts as zero.
- The own index is used only when it is evidence:
  - month *m* has occurred at least once in the history;
  - the category's median month is above zero;
  - the category's median for month *m* is above zero.
- Otherwise the **raw** value is the strongest KB event for the category and month (the maximum,
  never a product, as in 4.4), or ×1 when no event applies.
- Both kinds of raw value are shrunk by `1 + k(raw − 1)`, with `k = min(months, 24) / 24` truncated
  toward ×1, using the helper the budget already uses. This is the KB's own reading: "priors are
  the 'raw' expectation used until the user's own median-based index dominates".
- **Consequence:** a prior is weak in year one. At six months, Diwali's +38% becomes +9.5%. That is
  consistent with the budget suggestion, and it follows the SRS's instruction to shrink when history
  is short.

**2. A zero own index falls back to the calendar.** Take a category the user spends in two months
out of three, but never in either October. §9.3's literal ratio for October is 0, which forecasts
no spending in it at all. The golden scenario hit exactly this case, and it dragged a whole month's
factor to ×0.78. Such a zero is a gap in the data, not a season. Falling back to the prior errs
toward more spending, which is the safe direction for a balance forecast.

**3. The forecast gets one factor per month, with the lookback's own season divided out.**
- `factor(m) = Σ_c w_c × index_c(m) / L_c`, where:
  - `w_c` is category *c*'s share of the forecast's own everyday pool over the lookback
    (uncategorised spend is weighted at ×1);
  - `L_c` is the category's index averaged over the lookback's days.
- The forecast adds `seasonal(d) = predicted(d) × (factor − 1)`, rounded HALF_EVEN, as its own term.
  `predicted + seasonal` can never be negative.
- **Why divide the lookback out:** the daily base is measured on the last ninety days. If those
  days were monsoon, the base already contains the monsoon. Applying October's index as it stands
  would keep the monsoon in October and double-count any festival that fell inside the lookback.
- **What it costs:** after a seasonal lookback, a factor can be below ×1, which is a saving. That
  is honest, and the screen names it ("after the monsoon").
- **AI-FCT becomes 1.1** (AI-ARC-006). With no seasonal input, every output is identical to 1.0.

**4. An effect under 1% of a month's everyday spend is noise: it is neither applied nor named.**
- A category's effect is `w_c × (index_c(m) / L_c − 1)`. When `|effect| ≥ min_effect_bps` (100),
  its event is named:
  - **rising:** the month's own prior event;
  - **easing:** prior events from the lookback months that no longer apply.
- Own-history effects of that size are flagged as "as in your past years".
- A **month** whose factor moves less than `min_effect_bps` is exactly ×1, with nothing named.
- **Why:** ten June days of "summer" in a September lookback would otherwise label every later
  month "summer easing" over a 0.2% effect.
- **Why the month rule too (found on the device):** the demo has three months of history, so `k` is
  1/8, and every factor came within a few bps of ×1. The card showed four lines like *"0.0% more
  everyday spending … (₹0.39 extra) — the season"*. Withholding the name had not been enough; the
  number itself was noise.

**5. The numbers are data, and AI-SEAS owns the only mirror.**
- The calendar file gains a `method` block, **SEAS-INDEX v1.0**: `shrinkage_denominator_months` 24,
  `history_months` 36, `min_effect_bps` 100. The file moves to 1.1; no event changed.
- `SeasonalEvent`, `SeasonalityPriors` and their two tests move from `:domain:engines:budget` to
  `:domain:engines:seasonality`. Budget now depends on it, following the orderofoperations → goals
  precedent, and its figures are unchanged (golden-guarded).
- The budget's `RULE-BUD-SUGGEST` keeps its own denominator. A new assertion in budget's drift test
  requires it to equal AI-SEAS's.
- **Forecast depends on AI-SEAS** and takes its result in `ForecastInput.seasonality`, carrying its
  evidence after RULE-FCT-* whenever an adjustment applies (AI-ARC-003 lineage). The repository runs
  AI-SEAS on the same everyday rows the forecast predicts from, so the two engines cannot disagree
  about what "everyday" means.

**6. Category names match the KB exactly (case aside), as the budget's do.** The demo's "Dining
Out" does not match the KB's "Dining". A synonym map is deferred (see below) rather than guessed.

## Deferred (each with what it needs)

- **Exact festival dates** from `/v1/knowledge/calendar` (§22.2). This needs the network feature and
  consent (P-01). Month windows are what the KB holds today.
- **Category synonyms** ("Dining Out" → Dining) or matching on the seed key. This needs a KB column
  (`CLS-CAT` keys on each event), not a string heuristic.
- **Budget suggestions reading AI-SEAS's own-history index.** The budget still uses only the
  prior, as 4.4 shipped it. Switching it changes suggestion figures, which needs a `budget-planner`
  version bump and a new golden file.
- **Card-paid seasonal spend** (found on the device). Weights come from the forecast's liquid
  everyday pool, and a card purchase leaves the bank only as the bill (ADR-0043). A Diwali spree on
  a card therefore reaches the forecast as a November card bill weighted ×1, uncategorised, and the
  demo's all-card Shopping gets no seasonal weight at all. Fixing this needs the bill attributed to
  the categories it paid for, which is the per-account forecast work ADR-0043 already defers.
- **Seasonal warnings as notifications** (§17, "seasonal warnings") belong to issue 9.6, the
  notification engine.
- **Persisting `seasonal_indices`** (§20.1's table inventory) waits until an insight needs history of the
  index itself. Today the index is recomputed from the ledger on every read.

## Consequences

- The forecast card names each month the season moves, with the percentage against the last ninety
  days, the amount, and why. It adds a "Seasonal extra/saving" part to the components line, and the
  rules line cites SEAS-INDEX.
- A young install sees small adjustments. That is the shrinkage working as designed, not a bug.
- The forecast's backtest is unchanged: its ledgers carry no seasonality. A seasonal backtest needs
  a multi-year synthetic set, and that is left for when AI-SEAS's parameters are tuned.

## Alternatives considered

- **Apply `index(m)` directly, without dividing out the lookback.** This is simpler, but it
  double-counts any season inside the lookback. Rejected.
- **Blend `k × own + (1 − k) × prior`.** This keeps priors strong in year one. It contradicts the
  KB's stated formula and the budget's shipped reading, so the two engines would give the same
  October two different Diwalis. Rejected.
- **Keep a second mirror in AI-SEAS.** Two copies of nine events and two drift tests, one of which
  could be left behind. Rejected in favour of moving the one mirror.
- **Treat a zero own index as a season.** Literal, but it forecasts no spending in a category the
  user uses most months. Rejected.
