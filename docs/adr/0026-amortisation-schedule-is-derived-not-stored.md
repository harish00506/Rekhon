# ADR-0026 — The amortisation schedule is derived, never stored

- **Status:** accepted
- **Date:** 2026-08-20
- **Deciders:** Harish G (solo), implementing issue 6.2
- **Refs:** [ADR-0007](0007-account-balances-derived-not-stored.md),
  [ADR-0016](0016-nature-classification-by-account-type.md),
  [ADR-0001](0001-custom-lint-module-and-money-heuristic.md), CLAUDE.md §3 (MNY-001/002, TIM-002),
  SRS §5.8 (FR-ACC-003), §11, §21.4, `domain/engines/loan/ENGINE.md`

## Context

Issue 6.2 gives a loan its terms — principal, rate, tenure, first EMI date — and the schedule those
terms imply: for each of up to 600 instalments, how much is interest and how much repays the loan.

[ADR-0016](0016-nature-classification-by-account-type.md) named a table for that schedule. Deciding
whether an EMI is spend or a liability reduction needs the split, and with no split available that
ADR fell back on the account type and recorded the gap: *"§8.3's step 1 wants the interest portion of
an EMI, and there is no amortisation table to read it from."* Reading it as a specification for
`loan_amortization_rows` is the obvious next step, and it is the wrong one.

A 20-year loan is 240 rows. Five loans is 1 200 rows that exist only to restate five columns each.
Every one of them is a copy of an answer that can be recomputed from its inputs in under a
millisecond, and every copy is a thing that can fall out of step with what produced it. The failure
is specific and quiet: a user corrects a mistyped rate, the `loan` row updates, the 240 stored rows
do not, and the app goes on showing a split for a loan the user no longer has. Nothing crashes and
nothing looks wrong.

This project has answered this question twice already, both times the same way.
[ADR-0007](0007-account-balances-derived-not-stored.md) refuses to store an account's balance
because a stored balance and its transactions can disagree, and only one of them is the truth.
`CreditCard` refuses to store unbilled spend for the same reason one issue ago.

## Decision

**Store the terms. Derive the schedule on every read. No `loan_amortization_rows` table.**

The `loan` table is five columns and a primary key of `account_id`: `principal_minor`,
`annual_rate_bps`, `tenure_months`, `first_emi_iso_date`, and a nullable `emi_override_minor`. The
schedule is a pure function of those five, computed by `:domain:engines:loan`, which reads no clock
and holds no state — so the same row gives the same 240 instalments on any device, in any build, in
any month (P-08).

Three things make that safe to rely on where a general "just recompute it" would not:

1. **The engine is exact, not approximate.** Amounts are `Long` paise throughout (MNY-001) and the
   rate is integer basis points (MNY-002). `BigDecimal` appears only inside the closed-form EMI, at a
   *pinned* `MathContext(34, HALF_EVEN)` that is part of the engine's version contract. There is no
   floating-point drift to accumulate and therefore no reason a recomputation would ever differ from
   the last one.
2. **It is bounded.** `Loan` refuses a tenure outside `1..600`, so "derive the schedule" can never
   mean a hundred thousand rows.
3. **The common read does not build a schedule at all.** The accounts list needs one instalment, so
   `LoanEngine.instalment(loan, number)` exists beside `schedule(loan)`. Which instalment is *next*
   is the one clock-dependent question on the path, and it is answered in `LoanRepository` where the
   injected `Clock` lives (TIM-001), then handed down as a number.

The 16 → 17 migration round-trip test asserts the **absence** of the table, not merely the presence
of `loan` — a decision that only says "we did not build it" is one somebody adds back next quarter
without noticing they are contradicting it.

## Consequences

- **A loan's terms are always self-consistent.** Editing a rate cannot leave a stale schedule behind,
  because there is no schedule to leave behind. The class of bug this removes is the one that shows a
  plausible wrong number rather than an obvious failure.
- **ADR-0016's gap is half closed, and the remaining half is named.** The split now *exists*:
  `AmortisationRow` carries principal and interest for any instalment, and `NatureEngine` can ask for
  it. What is still missing for §8.3 step 1 is not the rows — it is a **link from a transaction to
  the instalment it paid**. A user's EMI debit is a `transaction`, and nothing yet says which of the
  240 instalments it was. That link is a column on `transaction` (or a small join table), not a copy
  of the schedule, and it is the piece a later issue must add.
- **Issue 10.3's prepay-vs-invest simulator gets what it needs for free.** Simulating "what if I pay
  ₹2 lakh extra in month 40" means re-deriving the tail of a schedule from a modified balance. With
  stored rows that is a write-and-roll-back; with a pure function it is a second call.
- **Historical reproducibility rests on the engine's version, not on stored output.** Results carry
  `EngineProvenance(engineId, engineVersion, …)` (AI-ARC-003, AI-ARC-006). If the formula ever
  changes, the version bumps and old insights stay explainable by *which* engine produced them.
  Changing the pinned `MathContext` is such a change, and `ENGINE.md` says so.
- **The cost is CPU on every read, and it is small.** 240 iterations of two `BigDecimal` operations,
  off the main thread on `dispatchers.io`. If a user with fifty loans ever appears, the fix is a
  memoisation keyed by the terms — a cache that is *derived from* its input rather than a second
  copy of it in SQLite, which is a different thing from what this ADR refuses.
- **A schedule cannot be queried in SQL.** "Total interest paid in FY 2027-28" is a Kotlin fold over
  a derived list rather than a `SUM(...) WHERE`. Acceptable at this size; if a tax report ever needs
  it across many loans it is a reason to revisit, and revisiting means adding a *report* table, not
  making the schedule the source of truth.

## Alternatives considered

**Store `loan_amortization_rows`, as ADR-0016 named.** Rejected above: it is a cache of a pure
function, and a cache with no invalidation is a bug with a schema. It would also make the
migration story worse — the day the formula changes, every stored row is wrong and there is no
honest migration for them.

**Store only the current instalment's split on the `loan` row.** Tempting, because the accounts list
is the only screen that reads one. Rejected: it needs a clock to know when "current" moved, which
means a worker to update it, which means the app now has a background job whose entire purpose is to
keep a copy in step with something it could have computed. It also does not help the schedule screen
or the simulator, both of which want arbitrary instalments.

**Store the derived EMI but not the rows.** Rejected as unnecessary: the EMI is one `BigDecimal`
expression, and the user-entered override — the one figure the app genuinely cannot compute — *is*
stored, in `emi_override_minor`. Storing the derived one beside it would create exactly the
disagreement this ADR exists to avoid, between a stored figure and the terms that imply it.
