# ADR-0043 — The forecast schedules confirmed rules and FIXED streams, forecasts one consolidated balance, and defers on-device accuracy tracking

- **Status:** accepted
- **Date:** 2026-09-19
- **Deciders:** Harish G (solo)
- **SRS refs:** §9.1 (AI-FCT-001..004), §9.2, §9.3, §17/§18, §21.5, P-01, P-02, P-03, P-08; issue 9.2.
  Builds on ADR-0007 (derived balances), ADR-0010 (future-dated rows), ADR-0017 (typed mirrors),
  ADR-0034 (liquid accounts), ADR-0042 (streams)

## Context

§9.2 gives the method: opening balance plus scheduled items, less predicted variable spend, with
bands from seeded resampling. §9.1 asks for more than one issue can honestly ship:

- per-account and consolidated forecasts (AI-FCT-001);
- a 12-month Pro horizon;
- crunch-day alerts (AI-FCT-002, via §18);
- inspectable components (AI-FCT-003);
- stored snapshots with MAPE surfaced to engine and user (AI-FCT-004).

The criteria for issue 9.2 are narrower: a deterministic projection from recurring (3.7) and
classification (9.1), with confidence and evidence; monotonic identities; and backtests against a
frozen threshold.

Four questions had no answer in the SRS. What exactly is "scheduled"? What is "variable spend" on a
liquid balance, when card purchases don't touch the bank until the bill? Where do the numbers live?
How is accuracy measured without user data (P-01)?

## Decision

**1. Scheduled = confirmed recurring rules + FIXED streams + future-dated rows.**
- **Confirmed rules only.** Quick-setup rules are unconfirmed seeds, and §9.2 says "confirmed
  recurring credits".
- **FIXED streams:** a 9.1 stream scored, pinned or prior-estimated FIXED (but not a known obligation
  or a `recurring:` merchant stream, which are already rules) is projected monthly on its
  `modalDayOfMonth` at its typical amount. This is how classification feeds the forecast: rent is
  scheduled even before the detector has proposed it.
- **Future-dated rows** on liquid accounts (ADR-0010).

**2. Everyday spend is liquid outflow with the scheduled rows removed.**
- Outflows on bank and cash accounts only.
- Payments to a confirmed recurring merchant, and in a projected FIXED category, are taken out.
  Otherwise they would be counted twice: once on their day, and once smeared across every day.
- Transfers between two liquid accounts are excluded, because withdrawing cash is not spending it.
- A card purchase doesn't count, but paying the card bill from the bank does. The liquid balance
  only moves when money leaves it.

**3. One liquid definition.** `LIQUID_ACCOUNT_TYPES` (bank, cash) is shared with the emergency fund
(ADR-0034). The forecast keeps an overdrawn account's negative balance, where the runway drops it:
an overdraft is exactly what a crunch forecast must see.

**4. The method is §9.2's own words, with the gaps closed simply.**
- The day-of-week adjustment is a ratio of medians, as §9.2 says. The "day-of-month curve" is read
  as its own three named zones (1–5, 6–24, 25–31), each a ratio of means.
- A zero denominator or an empty class is a multiplier of 1.
- Days before the ledger starts are unknown, not zero.
- Bands use nearest-rank percentiles. The seed is the day's epoch day, so a forecast is stable
  through the day.
- A crunch day is measured on P50.

**5. The numbers are rulebook rows.** RULE-FCT-METHOD holds horizon, lookback, trim, simulations,
bands and pay-cycle edges. RULE-FCT-CRUNCH holds the ₹5,000 buffer in paise. These take rules-kb to
1.16.0, which the six existing mirrors restate.

**6. Accuracy is gated offline on a frozen synthetic set.**
- 20 ledgers from a seeded generator committed beside the test. They include drift and regime
  changes the method doesn't model.
- Two thresholds were fixed before the first run: median 90-day spend error ≤ 15%, and mean P10–P90
  coverage ≥ 70%.
- At 1.0 the results are **12.6%** and **70.3%**. The coverage margin is thin and is recorded as
  such, not widened.

## Deferred (each with what it needs)

- **Per-account forecasts and the 12-month Pro horizon (AI-FCT-001):** a per-account split of
  scheduled items and spend, plus a Pro entitlement.
- **On-device accuracy tracking (AI-FCT-004):** a `forecast_snapshot` table, a migration, the
  archive and drill fixture edits every new table needs (8.3), and a MAPE surface.
- **The crunch alert (AI-FCT-002, §18):** the notification engine, issue 9.6. The day is computed and
  shown today.
- **A user-editable buffer:** a settings field. The rulebook default applies to everyone.
- **Seasonality (§9.3):** issue 9.3. The term is zero until then.
- **Irregular income (Persona 3)** and a salary-detection confirmation prompt. Income counts only
  confirmed rules, and the card says so.
- **Loan EMIs from the loan table:** they are scheduled only once confirmed as a rule.
- **A future-dated payment that is also a projected rule occurrence** would count twice. This is rare
  (the detector doesn't create future rows) and not guarded.
- **The goals surplus (ADR-0035) still uses the observed P50 surplus, not this forecast.** Switching
  `SurplusRepository` to the forecast is its own change, with ADR-0035's substitution to undo.

## Consequences

- The dashboard gains "The next 90 days": the lowest point with its range, crunch days, the three
  components, the next scheduled items with their source, the history note and the rules. All five
  Paparazzi baselines were re-recorded.
- `StreamMetrics` gains `modalDayOfMonth` (additive; AI-CLS.stream stays 1.0).
- `TransactionDao.observeFirstBookedIsoDate` is added. It is a read only, with no schema change.

## Alternatives considered

- **Schedule all FIXED streams, obligations included.** Rejected: it counts a confirmed rent twice.
- **Deduct card purchases from the liquid balance.** Rejected: the money leaves the bank on the bill
  date, which is already a transfer out.
- **Measure crunch on P10.** Rejected: one day in ten would read as a crisis.
- **Build AI-FCT-004's snapshot table now.** It adds a migration, archive, drill and UI to an issue
  whose criteria are met by the offline backtest.
