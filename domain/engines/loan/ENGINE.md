# LoanEngine — amortisation & EMI split

**SRS:** §5.8, §11 · **Pipeline layer:** L2 (analytics — derived facts, no judgement)
**Module:** `:domain:engines:loan` · **Requirements:** FR-ACC-003, MNY-001, MNY-002, P-02, P-08
**Version:** 1.0 · **Status:** active

## Why this engine exists

A loan is the one account where the balance tells you almost nothing. "You owe ₹28,40,000" is true
and useless; what a borrower needs to know is that this month's ₹26,034.70 is ₹21,250.00 of interest
and ₹4,784.70 of principal, and that the ratio inverts somewhere around year fourteen. Until this
engine existed the app could show a loan's *worth* and never a rupee of its *cost*.

Two things downstream are waiting on it. `docs/adr/0016-nature-classification-by-account-type.md`
records that §8.3's nature classification decides an EMI on the account type, because nothing could
split the interest (true spend) from the principal (a liability reduction) — this engine is the
half of that gap that is arithmetic. And issue 10.3's prepay-vs-invest simulator is built directly
on `emi()` and `schedule()`.

## Contract

```kotlin
interface LoanEngine {
    fun emi(input: LoanTermsInput): Result<LoanEmi, AppError>
    fun schedule(input: LoanTermsInput): Result<AmortisationSchedule, AppError>
    fun instalment(input: LoanInstalmentInput): Result<AmortisationRow, AppError>
}
```

Three operations rather than one `analyse()`: the accounts list wants one instalment and should not
build 240 rows to get it, and the prepay simulator wants the EMI and no rows at all.

- **Input** — `LoanTermsInput(loan, nowUtcMillis)`; `LoanInstalmentInput(loan, number, nowUtcMillis)`.
  `Loan` (`:core:model`) carries `principal` (**paise**, `Long`), `annualRateBps` (**integer basis
  points**, `0` allowed), `tenureMonths` (1..600), `firstEmiIsoDate` (ISO `yyyy-MM-dd`), and an
  optional `emiOverride` (paise). `nowUtcMillis` is stamped onto provenance only — **nothing here
  reads it as a date**.
- **Output** — `LoanEmi(amount, basis, derived, provenance)`;
  `AmortisationRow(number, dueIsoDate, amount, principal, interest, openingBalance, closingBalance)`;
  `AmortisationSchedule(rows, emi, totalInterest, provenance)`. All amounts are `Money` paise.
- **Failures** — `Err(AppError.Validation(field))` when the instalment cannot cover the first
  month's interest (`field` is `emiOverride` or `annualRateBps`, whichever the user can fix) or when
  an instalment number falls outside the tenure; `Err(AppError.NotFound)` for an instalment inside
  the tenure that the loan never reaches because a lender-stated EMI closed it early.

## Formula / algorithm

Let `P` = principal (paise), `r` = annual rate (bps), `n` = tenure (months),
`i = r / 120 000` (bps → fraction is ÷10 000; year → month is ÷12).

**1 · The instalment** — the standard reducing-balance closed form:

```
EMI = P · i · (1+i)^n / ((1+i)^n − 1)          r > 0
EMI = P / n                                    r = 0   (the formula's denominator is zero there)
```

`(1+i)^n` is a growth factor, not an amount, so it is computed in `BigDecimal` at a **pinned**
`MathContext(34, HALF_EVEN)` — DECIMAL128, spelled out rather than referenced. P-08 requires the
same terms to give the same paise on every device and in every future build, so that precision is
part of this engine's version contract: changing it changes every historical answer and is a
version bump, not a tidy-up. The result is rounded to whole paise HALF_EVEN **once**, at the end.

A user-entered `emiOverride` replaces the derived figure outright. Banks round the closed form their
own way, and a schedule that disagrees with the borrower's own statement by ₹2 a month is a schedule
they stop trusting. The derived figure is still carried on `LoanEmi.derived` so the editor can show
both (P-02).

**2 · The guard** — `EMI > P · r / (10 000 × 12)`, strictly. An instalment equal to the first
month's interest leaves the balance unchanged for ever, which is the same non-terminating walk as
one that is too small.

**3 · The schedule** — a reducing-balance walk over `B`, starting at `P`:

```
interest  = B · r / (10 000 × 12)     rounded HALF_EVEN to whole paise
principal = EMI − interest
B         = B − principal
```

**The closing instalment absorbs the remainder.** Its principal is whatever is still owed and its
amount is that plus its interest — which is exactly what a lender's own final instalment does. The
closing instalment is the tenure's last, *or* the first one large enough to clear the balance
(a lender EMI above the derived one shortens the loan). This is what makes the two invariants exact
**by construction** rather than by luck.

**4 · Dates** — instalment `k` falls on `firstEmiDate.plusMonths(k − 1)`, always measured from the
origin and never from the previous date: `plusMonths` clamps to a short month, so stepping
31 Jan → 28 Feb → 28 Mar would silently move the loan's EMI day to the 28th for ever.

## Assumptions & guardrails

- Money is `Long` paise end to end (MNY-001); the rate is integer basis points (MNY-002). **No
  `Double` or `Float` anywhere.** `BigDecimal` appears only inside `Emi`'s closed form and never on
  the public surface.
- Monthly rest, reducing balance — the standard Indian retail structure. **Daily-rest and
  quarterly-rest loans are not modelled**, nor are processing fees, insurance premia bundled into
  the EMI, moratorium periods, step-up/step-down schedules, or floating-rate resets. A floating-rate
  loan is modelled at its current rate, which is right until the rate moves and the user re-enters it.
- **Reads no clock** (TIM-001) and holds no state. `nowUtcMillis` is provenance only. Every date
  comes from the loan's own `firstEmiIsoDate`, which is what makes a twenty-year schedule a golden
  file rather than something only observable in February.
- No randomness, so nothing to seed (P-08).
- It produces numbers and never prose (P-03), and it recommends nothing (P-07) — a schedule is a
  fact about a contract the user has already signed.
- The tenure ceiling of 600 months is enforced by `Loan`, not here: a mistyped tenure must not be
  able to ask this engine for a hundred thousand rows.

## Rules / knowledge consumed

**None.** This engine reads no `ai/rules` row and no `ai/knowledge` file, and that is deliberate
rather than an omission: amortisation has no tunable threshold in it. There is nothing here a user
or a rulebook edit could reasonably move — the formula is the loan contract's, not the app's.

Consequently there is **no `LoanRules.kt` and no `RulebookDriftTest`** in this module (every other
engine that cites a rule has both), and `provenance.evidence` is empty. `CardStatus` refuses an
empty evidence list because a card alert is a judgement and P-02 says the user must see which
threshold fired; a schedule is arithmetic, and citing a rule it never read would be a false claim
about where the number came from.

## Evidence shown to the user (P-02)

Every result returns its own inputs beside its answer, which is what "show the work" means for a
calculation with no rule behind it:

- `LoanEmi` carries **both** the instalment in force and the derived one, plus `basis`
  (`DERIVED` / `LENDER_STATED`) — so a screen can say "your bank charges ₹1.30 more than the
  standard calculation" instead of showing one number and hiding the other.
- `AmortisationRow` carries the opening and closing balance either side of the split, so a reader
  can check the row without re-deriving it.
- `AmortisationSchedule.totalInterest` is what makes a tenure decision visible: the same loan over
  15 years instead of 20 is a number, not a feeling.

## Tests

- **Golden file** — `src/test/resources/golden/loan.txt`: five loans and their **entire** schedules,
  ~500 instalments in all (a 20-year home loan at 8.5%, a 5-year personal loan at 10.5%, an
  interest-free family loan, a car loan with the lender's own EMI, and a day-31 first instalment).
  The expected figures were produced by an **independent** 50-significant-digit decimal
  implementation, not by capturing this engine's output — a golden file regenerated from the code it
  guards asserts only that the code has not changed. The headline case is checkable against any
  public EMI calculator: ₹30,00,000 · 8.5% · 240 months → **₹26,034.70**.
- **Property tests** — 500 seeded loans (₹10,000–₹5 crore, 0–36%, 1–360 months, first instalment on
  any day including the 29th–31st), each schedule checked against five identities: every instalment
  balances (`principal + interest == amount`), the principals sum **exactly** to the loan, the
  balance only falls and reaches exactly zero, interest is never negative and never exceeds the
  instalment, and the schedule never runs past its tenure.
- **Determinism** — the same terms scheduled twice give byte-identical results.
- **The gate was proven to fail.** On 2026-08-19 the last-instalment remainder absorption was
  removed on purpose: five tests across both suites went red, and green again when it was restored.
  A gate that has never failed has not been shown to be a gate.
- **Coverage** — engine ≥ 85%, money math 100%.

## Version log

| Version | Date | Change |
|---------|------|--------|
| 1.0 | 2026-08-19 | Initial implementation for issue 6.2 from SRS §5.8/§11 (FR-ACC-003). Closed-form EMI at a pinned `MathContext(34, HALF_EVEN)`; reducing-balance walk with the remainder absorbed by the closing instalment. |
