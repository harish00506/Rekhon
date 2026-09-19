<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: issue 9.3 — AI-SEAS, §9.3's seasonal index, and §9.2's seasonal term in the forecast.
  Result: a reader can see where an index comes from, why the forecast divides the lookback's season
          out, what the oracle caught before any engine code existed, and what is deferred.
  Changelog: 2026-09-19 — Created.
-->

# 2026-09-19 — Seasonality (issue 9.3, ADR-0044)

**Branch:** `feature/9-3-seasonality-ai-seas` off `dev` (`7336403`) · **VERSION** 0.9.2 → **0.9.3** ·
**versionCode** 38 → 39 · **Schema** 22, unchanged · **calendar-seasonality.json** 1.0 → **1.1** ·
`AI-SEAS` 1.0 (new) · `AI-FCT` 1.0 → **1.1** · rules-kb unchanged (1.16.0)

---

## 1 · Decisions this session

The full argument for each is in ADR-0044.

- **The user's own months come first, the calendar second, and one shrinkage covers both.** The
  own index (§9.3's median ratio) is used only when that calendar month has occurred, the
  category's typical month is above zero, and its median for that month is above zero. Otherwise
  the strongest KB prior applies. Both are shrunk by `k = months/24`, which is the KB's stated
  formula and the budget's shipped reading, so the two engines agree about any given Diwali.
- **A zero own index falls back to the calendar.** A category the user spends in two months out of
  three but never in October would otherwise forecast no spending at all in October.
- **The forecast gets one factor per month, with the lookback's season divided out.** The factor
  is `Σ w_c × index_c(m) / L_c`. It is applied to each day's rounded prediction as §9.2's separate
  `seasonalAdjustment` term, and AI-FCT becomes 1.1. A factor below ×1 is a named saving.
- **An effect under 1% of a month's everyday spend is noise** (`min_effect_bps`). A month that
  moves less is ×1, and a category whose effect is smaller is not named. The first version only
  withheld the *name*. The device showed why that was not enough: the demo's three months of
  history gave four lines of "0.0% more — ₹0.39".
- **The numbers are data.** SEAS-INDEX is a `method` block in the calendar KB (1.1): 24, 36 and 100.
- **AI-SEAS owns the only calendar mirror.** `SeasonalEvent`, `SeasonalityPriors` and their two
  tests moved from the budget engine with `git mv`. Budget depends on AI-SEAS and its figures are
  unchanged. Budget's drift test now pins its denominator to AI-SEAS's.
- **The dashboard has its own event names.** Nine strings are duplicated from `feature/budgets`,
  because feature modules may not depend on each other (ARC-001). Moving them into
  `:core:designsystem` would put domain words into a design-token module.
- **The issue's §17 and §38 citations** are notifications (9.6) and tax. Neither defines the index.
  They are recorded in the ADR and not built here.
- **Deferred with triggers:**
  - exact festival dates (§22.2, network plus consent);
  - category synonyms ("Dining Out" versus "Dining");
  - the budget reading AI-SEAS's own-history index (needs a `budget-planner` bump);
  - seasonal notifications (9.6);
  - persisted `seasonal_indices` (§20.1).

## 2 · What the oracle caught

**The independent oracle found two design faults before any engine code existed.** In the first
golden scenario:
- the gappy Dining category gave an October own index of **0**, dragging January's factor to ×0.78;
- ten June "summer" days in the lookback labelled every later month "summer easing" over a 0.2%
  effect.

Both became rules (the zero fallback and the minimum effect) in the KB, the ADR and the oracle
first, then in the engine. With those rules the scenario reads sensibly: October is +23% (the
user's own October shopping plus the Dining prior), and the months after the monsoon are slightly
under ×1.

Hand arithmetic also caught one of my own test set-ups before its first run. I had dropped October
from Shopping alone, which makes October a *zero* month for Shopping (an own raw of 0), not an
unobserved one.

The engine passed the golden file on its first run. So before trusting it, I checked each gate can
fail:
- removing the zero-month fallback failed the golden and behaviour tests;
- removing the lookback division failed the golden, behaviour and property tests;
- editing `min_effect_bps` in the KB alone failed the drift test;
- dropping the seasonality result in the repository failed the new repository test.

All four were reverted.

**The device found the third fault.** The demo profile has about three months of history, so
`k = 3/24`, and every factor sat within a few bps of ×1. The card showed a "Seasonal saving ₹3.85"
and four month lines reading "0.0% more everyday spending … (₹0.39 extra) — the season". The
naming threshold had hidden the *names* of small effects but not the effects themselves.
`min_effect_bps` now also sets a month's factor to exactly ×1 when it moves less than 1%. That
went into the KB, the oracle (the golden file regenerated: three months became ×1) and a failing
test first, then the engine.

**The device also showed a limitation, recorded but not fixed.** After the deadband, a backdated
October 2025 Shopping row still moved nothing. The demo's Shopping is all on the credit card, and
card purchases are not everyday spend on the liquid balance (ADR-0043), so Shopping's weight was
zero. Then a bank-paid Shopping row in August still stayed under 1%, because the unconfirmed rent
(₹28,000 × 3) was diluting the pool. After I confirmed the Landlord repeat, the card showed
*"October 2026: 1.7% more everyday spending than the last 90 days (₹62.04 extra) — as in your past
years"*. That is modest because `k = 3/24`. It is masked under the blur, and identical in airplane
mode after a cold start. Attributing card bills to the categories they paid for is deferred in
ADR-0044.

## 3 · Flow changed this session

`FLOW.md` §2.08 now reads:

```
ForecastRepository.observeForecast()
└─ combine(… , observeNatureCandidates(today−90 … today+90), observeMonthlyCategorySpend(36 closed months))
    → forecastOf()
       ├─ SeasonalityEngine.index()   history → index per category/month; everyday rows → factor per month
       └─ ForecastEngine.forecast(+ seasonality)   seasonal(d) = predicted(d) × (factor − 1)
  ⇣ ForecastSection → ComponentsLine, SeasonalLines
```

## 4 · Code changed this session

| Path | What it does now |
|------|------------------|
| `domain/engines/seasonality/**` (new) | `SeasonalityEngine` + types, `SeasonalityRules` (SEAS-INDEX), `MedianSeasonalityEngine` (`IndexTable`, `FactorMath`), `ENGINE.md`; tests: behaviour (20), property (8 × 200), golden (60 indices + 12 factors) with `seasonality_oracle.py`, drift (8), priors (12) |
| `…/seasonality/SeasonalityPriors.kt`, its two tests | **Moved** from `:domain:engines:budget` (package only; `KB_VERSION` 1.1) |
| `ai/knowledge/calendar-seasonality.json` | `method` block SEAS-INDEX; 1.1 |
| `domain/engines/budget/**` | Depends on AI-SEAS; `MONTHS_IN_YEAR` local; drift test pins the shared denominator; build inputs; ENGINE.md |
| `domain/engines/forecast/**` | 1.1: `ForecastInput.seasonality`, `ForecastDay.seasonal`, `CashFlowForecast.seasonalAdjustment/seasonalMonths`, `SeasonalMonth`; `ForecastSeasonalityTest` (9); property identities include the term; ENGINE.md |
| `data/repository/.../ForecastRepository.kt`, `RepositoryFactory.kt` (+ test, 10) | Reads 36 closed months per category; runs AI-SEAS on the forecast's own everyday rows; passes its result in |
| `app/.../di/RepositoryModule.kt` | Provides AI-SEAS; passes it to the forecast |
| `feature/dashboard/.../SeasonalLines.kt` (new), `ForecastSection.kt`, `strings.xml` | Components line with seasonal extra/saving; a line per month with percentage, amount and reasons |
| `feature/dashboard/src/test/**` | Seasonal fixture (default on); `ForecastSectionTest` (+3) |
| `feature/budgets/.../BudgetLabels.kt` | Comment names the mirror's new home |
| `settings.gradle.kts` | Includes `:domain:engines:seasonality` |
| `ai/orchestrator/engine-registry.yaml`, `ai/README.md` | AI-SEAS contract; AI-FCT 1.1 `depends_on: [AI-SEAS]` |
| `docs/adr/0044-…`, `DECISIONS.md`, `FLOW.md` | The decision, its row, §2.08 |

## 5 · Quiz

**Outcome: not yet taken.** The answers in italics are the author's. Record the developer's own pass
here, fails included.

1. Why does the forecast divide by the lookback's average index instead of applying October's
   index directly? *(The daily base was measured on the lookback, so its season is already inside
   the base. Applying October raw would count it twice.)*
2. A category is spent in 22 of 24 months, but never in October. What is its October index, and
   why? *(It is the calendar prior, shrunk, because an own ratio of 0 is a gap, not a season.)*
3. At six months of history, what does Diwali's 1.38 become, and where does that number live?
   *(1.095, from `1 + 0.38 × 6/24` with the 24 in SEAS-INDEX.)*
4. Why can a month show "less everyday spending" when no festival ends in it? *(The lookback held
   a season that no longer applies, such as the monsoon.)*
5. Why wasn't the calendar mirror copied into the new module? *(Two copies of nine events and two
   drift tests, one of which could be left behind.)*
