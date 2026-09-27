# ADR-0055 — AI-MKT scores what it can measure, says what it cannot, and builds its own history

- **Status:** accepted
- **Date:** 2026-09-27
- **Deciders:** Harish G (solo)
- **SRS refs:** §30 (§30.2 the signal library, §30.3 honesty and backtesting, §30.4 tranches), §16
  (EXT-002 prices), §6 (data, not code), AI-ARC-003/006, P-01, P-02, P-03, P-04, P-07, P-08,
  MNY-001/002, TIM-001/002, ADR-0017 (mirrors and drift tests); issue 10.7. Uses RULE-IDLE-CASH and
  RULE-RUNWAY-M, both of which already named `AI-MKT.capacity_gate` as a consumer.

## Context

§30 asks for an objective, backtestable buy-day verdict, with the signal library and its weights as
**data** so a user can personalise them on evidence. Two things had to be decided that the SRS does
not: **where the price history comes from**, and **what the app says about the signals it cannot
measure at all**.

The second question is the sharp one. The library's two heaviest signals — valuation (25 points) and
implied volatility (15) — cannot be derived from a price series, and this app has no feed for
either. That is 40 of the library's 100 points.

## Decision

**1. The history is the app's own cached closes, in a new table.**
Issue 6.5 gives the app a quote per instrument per refresh, not a history feed. So every refresh now
also appends one row to `market_close` (schema 29), unique on `(profile, price_key, day)`, and the
series accumulates locally. This is not a workaround for a missing endpoint: it is what makes the
engine work in airplane mode (P-04) and means **nothing extra leaves the device** to build it
(P-01). The visible cost is honest — a fresh install has no history, and the engine says so.

**2. A signal that cannot be evaluated is reported, never scored zero.**
`possibleScore` sits beside `score` for exactly this: 40 out of a possible 60 is a different claim
from 40 out of 100, and a zero for valuation would read as "valuation says this is expensive". The
screen shows each unmeasured signal as *"not measured, so it counts for nothing either way"*.

**3. The library's prose became numbers, in the file.**
"Bottom quartile scores, bottom decile maxes" cannot be read by an engine. Each signal now carries
an explicit `score_points` ladder (KB 1.0 → 1.1), beside three new blocks the engine needs before it
may say anything: `history` (how much is enough), `hit_rate` (what "measured" means) and `staleness`
(when to label and when to refuse). No max points, tier or lookback changed.

**4. The percentile is a mid-rank.**
Everything below, plus half of everything equal. The obvious definition — the fraction strictly
below — puts a perfectly flat series at the 0th percentile, which reads as "the cheapest day of the
year" when it is the most ordinary one. **The first draft did exactly that and scored a flat market
five points**, which is how the convention got chosen deliberately instead of by accident.

**5. The hit rate is walk-forward, or it is not shown.**
Each past day is scored from the closes available on that day only; scoring it with the whole series
would measure a machine that can see the future and would flatter every verdict. Below twenty
comparable past days **no rate is shown at all**: a rate from four samples persuades without
informing, which is the opposite of §30.3. The context percentiles are necessarily held at today's
values, because the app has no history for them — a limitation stated here rather than hidden.

**6. No price at all is a history problem, not a staleness one.**
An instrument the user holds and the app has never priced answers `NOT_ENOUGH_HISTORY`. `TOO_STALE`
would imply there is a price and it is old, which is a different thing to tell someone. **The first
draft crashed on this case** — every price signal reads the last close, and there isn't one. It was
found by a repository test on the most ordinary situation there is.

**7. A month-old close gets no verdict at all.**
Labelling it "stale" and answering anyway would be a hedge rather than an answer.

**8. The tranche ladder is gated on other engines' verdicts, and every gate is reported.**
`RULE-IDLE-CASH`, `RULE-RUNWAY-M` and AI-FCT's crunch count. AI-MKT re-derives none of them: a
suggestion that ignored the crunch day two weeks out would be advice to create the emergency the
rest of the app exists to prevent. A suggestion of nothing names what stopped it.

**9. The screen never buys anything, and says so.**
§30's is the screen most likely to be read as an instruction. The verdict sits above the score it
came from, each signal shows its measured number, the hit rate is quoted or its absence is, and the
last line is a standing promise (P-07).

## Deferred, and why

- **Valuation and VIX feeds.** Both are single numbers the proxy could serve, and both would need a
  source, a cache and a history of their own before the hit rate could vary them walk-forward. Until
  then the engine is told nothing rather than told a guess (P-03).
- **The three context-only signals** (`SIG-SUPPORT`, `SIG-YIELD-GAP`, `SIG-OI-PCR`). §30.2 keeps
  them out of the score deliberately; showing them as colour needs data the app does not have. A
  drift test asserts they are still unscored.
- **Per-signal backtests in the rule editor.** §30's "the rule editor shows each signal's own
  backtest so weights can be personalised on evidence" needs a rule editor, which does not exist.
  The engine already computes the band's rate; per-signal rates are the same walk-forward loop run
  seven more times.
- **`market_status` and `opportunity_check` as chat tools.** They are registered (issue 10.5) and
  still unserved. Wiring them means deciding how a verdict reads in a sentence, which is a chat
  question rather than a market one.
- **Backfilling history from a provider.** It would make the screen useful on day one instead of in
  a year — and it is a network path, a data-licensing question, and a source of numbers the user's
  own device did not observe. Worth doing; worth doing deliberately.

## Consequences

- The app can say whether today is unusual for the instruments this household actually holds, from
  prices it already had, offline, with the evidence beside the verdict.
- It will say "not enough history yet" for months on a fresh install. That is the honest state, and
  the screen says it plainly rather than scoring three weeks.
- Changing a weight, a tier, a band or a minimum is an edit to `market-signals.json` and its mirror,
  held together by a drift test — which is §30's own requirement that the library be editable data.
