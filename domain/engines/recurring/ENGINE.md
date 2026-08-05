# RecurringEngine — FR-TXN-006

**SRS:** §5.3, §5.4, §17  ·  **Pipeline layer:** L3 (rules)  ·  **Module:** `:domain:engines:recurring`
**Version:** 1.0  ·  **Status:** active

## Why this engine exists

A user paying ₹25,000 rent on the 3rd of every month re-enters it every month, and the app — which
has every one of those rows — used to say nothing. FR-TXN-006 makes noticing that a MUST:

> *"Recurring detection MUST propose recurring series when ≥ 2 similar transactions match on
> merchant/amount/cadence; user confirms to create a Recurring Rule."*

P-03 puts the pattern-finding in deterministic math rather than in a model. Downstream, the
confirmed rules are what the cash-flow forecast (issue 9.2) projects from and what a subscription
audit would list; upstream, nothing depends on this engine, so it can be switched off without
breaking the ledger.

**It proposes only (P-07).** Nothing here writes a row, creates a rule or moves a rupee. The user
confirms or rejects, and `RecurringRepository` writes only what they said yes to.

## Contract

```
interface RecurringEngine {
    fun detect(input: RecurringInput): Result<List<RecurringSeries>, AppError>
}
```

- **Input** — `RecurringInput`:
  - `candidates: List<RecurringCandidate>` — `transactionId` (String), `merchant` (String, as
    typed), `amount` (`Money`, **signed paise**, MNY-001), `bookedOn` (ISO `yyyy-MM-dd`, TIM-002).
    The caller has already excluded soft-deleted rows, transfers and scheduled (not-yet-posted)
    rows; *which* rows count is a storage question, not this engine's.
  - `rules: RecurringRules` — the rulebook thresholds, **injected** (ADR-0005).
  - `nowUtcMillis: Long` — passed in, never read (TIM-001).
- **Output** — `List<RecurringSeries>`, ordered by normalised merchant: `merchant` (the most recent
  spelling), `cadence` (`WEEKLY | MONTHLY | YEARLY`), `medianAmount` (`Money`, signed paise),
  `nextDueIsoDate` (ISO), `occurrences` (transaction ids, oldest first), and `provenance`
  (`engineId = recurring-detector`, `engineVersion`, `computedAtUtcMillis`, `evidence =
  [RULE-RECUR-DETECT@1.0]`, `inputWindow = first..last`, `confidenceBps`).

`Ok(emptyList())` is the normal answer for a new profile, not an error. The only `Err` is
`AppError.Unexpected` from a `bookedOn` that is not ISO — a malformed row must not crash a screen.

## Formula / algorithm

1. Drop candidates with a blank merchant — "₹250 on the 3rd" with no payee is not something a user
   can recognise, so it is not something to propose.
2. Group by `merchant.trim().lowercase()`; iterate the groups in sorted key order (P-08: the same
   rows must propose the same series in the same order).
3. Sort each group by `(bookedOn, transactionId)`. Reject if `size < min_occurrences`.
4. Gaps = whole days between consecutive dates. **Classify on the lower median gap**, then **verify
   every gap** against `|gap − periodDays| ≤ cadence_tolerance_days[cadence]`. Both steps are
   needed: the median alone accepts 1 Mar / 31 Mar / 30 Apr / 30 Jun as "monthly".
5. `medianAmount` = the lower median of the signed minor values. Reject unless every amount
   satisfies `|amount − median| × 100 ≤ |median| × amount_tolerance_pct` — cross-multiplied, so
   there is no division and no floating point on a money path (MNY-001).
6. `nextDueIsoDate` = last occurrence advanced by `plusWeeks(1)` / `plusMonths(1)` / `plusYears(1)`.
   Calendar arithmetic, **not** `plusDays(periodDays)`: 31 Jan + one month is 28 Feb, not 2 March.
7. `confidenceBps` = `10000 − worstDeviation × 10000 / (tolerance + 1)`, clamped to `0..10000`
   (MNY-002). A perfectly regular series scores 10 000; one at the monthly tolerance edge scores
   2 000.

`Cadence.periodDays` (7 / 30 / 365) is a **match target** for step 4 only. Step 6 is the only place
that projects a date, and it uses `java.time`, which understands short months and leap years.

## Assumptions & guardrails

- Money is `Long` paise in `Money`; the tolerance comparison is integer cross-multiplication through
  `Math.multiplyExact`, so an amount too large to compare fails loudly rather than wrapping into a
  false match. There is not a `Double` in the module.
- Rates are integer basis points (MNY-002); dates are ISO strings (TIM-002); the clock is passed in
  (TIM-001) — `CfoWallClockInDomain` fails the build on a wall-clock read here.
- **Cold start:** a profile with fewer than `min_occurrences` rows per merchant gets an empty list,
  and the UI renders nothing rather than an empty-state apology. A *yearly* series needs two years
  of history before it can fire, so on a real profile it proposes nothing for a long time — that is
  honest, not a bug.
- **Matching is on the normalised merchant string.** There is no merchant knowledge base and no
  alias table; `NETFLIX.COM*4471` and `Netflix` are two merchants to this engine until issue 4.1
  lands `merchant_id`.
- It produces numbers and enums, never prose (P-03) and never orders (P-07).

## Rules / knowledge consumed

| ID / file | What it provides |
|-----------|------------------|
| `RULE-RECUR-DETECT` (`ai/rules/rules-kb.json`) | `min_occurrences: 2`, `amount_tolerance_pct: 5`, `cadence_tolerance_days: {weekly: 2, monthly: 4, yearly: 10}` |

Mirrored as `RecurringRules` per **ADR-0005** because nothing in the app loads `ai/` yet.
`RulebookDriftTest` fails the build if the two disagree — verified to bite by tampering with the
rulebook and watching it go red (2026-08-05).

## Evidence shown to the user (P-02)

The proposal card shows the merchant, the median amount, the cadence, and **the dates of the
occurrences that matched** — the very rows the proposal came from, not a count. `occurrences` holds
transaction ids precisely so the card can point at them. The cited rule is `RULE-RECUR-DETECT@1.0`.

## Tests

- Golden case: a frozen ten-row ledger — rent, a subscription that drifts a day, a weekly commute
  top-up, and two one-offs — proposing exactly three series.
- Refusals (the acceptance criterion's "no false positives on one-offs"): a single transaction; two
  identical amounts to *different* merchants; two charges 40 days apart; blank merchants; one
  irregular gap in an otherwise clean series.
- Boundaries, from both sides: the monthly gap tolerance at 34 and 35 days; the amount tolerance at
  50.00 and 50.01; a zero median requiring exactness; an injected `minOccurrences = 4`.
- Property tests over 300 seeded ledgers: occurrences are real, distinct and never double-counted;
  the next due date is always after the last occurrence; the median is always an amount actually
  paid; input order never changes the output.
- Determinism: shuffled and reversed input give an identical result, provenance included.
- Coverage: **100% line** (gate: engine ≥ 85%).

## Version log

| Version | Date | Change |
|---------|------|--------|
| 1.0 | 2026-08-05 | Initial implementation from SRS §5.3 (FR-TXN-006), issue 3.7. |
