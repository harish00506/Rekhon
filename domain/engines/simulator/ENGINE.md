# Simulators — AI-SIM (prepay vs invest, and which debt first)

**SRS:** §36 stages 6–7, §40.2 CRD-005, FLT-004 · **Pipeline layer:** L5 · **Module:** `:domain:engines:simulator`
**Version:** 1.0 · **Status:** active · **Engine id on results:** `AI-SIM`

## Why this engine exists
Two questions people reliably get wrong in both directions: whether to put spare money against a
loan or invest it, and which debt to clear first. Both have exact answers and both are usually
decided on feeling. AI-SIM computes them side by side — and shows the rate, or the difference, at
which the answer changes — then stops. **It simulates and never executes** (P-07).

## Contract
```
interface PrepayVsInvestSimulator { fun simulate(input: PrepayInput): Result<PrepayComparison, AppError> }
interface DebtPayoffSimulator     { fun simulate(input: PayoffInput):  Result<PayoffComparison, AppError> }
```
- **Prepay input** — outstanding, the loan's **current effective** rate (FLT-004), remaining months,
  EMI, the lump sum, the user's expected return and the tax on it.
- **Prepay output** — the loan untouched, the loan after the prepayment (interest saved, months
  saved), what the same money would earn after tax over the same horizon, which is ahead and by how
  much, and the breakeven return.
- **Payoff input** — the debts (name, balance, APR, minimum) and what is spare each month.
- **Payoff output** — avalanche and snowball, each with the order debts are **cleared**, the months
  to debt-free and the total interest, plus the two deltas CRD-005 asks for.
- `Err(Validation(...))` for a negative figure, an EMI that cannot amortise, an empty debt list, or
  a minimum that cannot cover its own interest.

## Formula / algorithm
```
month's interest   balance × bps ÷ (10000 × 12), HALF_EVEN to the paise
                   — Money.percentOf, the same helper :domain:engines:loan uses
residue            after a payment, anything under 1% of that payment is rounding and clears
prepay             amortise(outstanding) vs amortise(outstanding − lump) at the same EMI
invest             lump compounded monthly over the baseline's months, less tax on the gain
breakeven          bisection, 40 fixed steps, over 0–100% a year                       (P-08)
payoff             each month: interest on all, minimums on all, everything spare on one target;
                   a cleared debt's minimum rolls into the next
avalanche          target = highest APR      ·      snowball = smallest balance
```

## Assumptions & guardrails
- **Both sides share one horizon** — the months the loan would otherwise have run.
- **The loan's rate is the loan's** (FLT-004); it is never reverse-engineered from the EMI.
- **`order` is when each debt is cleared**, not when it is attacked: a small debt can finish from its
  own minimum while a dearer one is targeted.
- **A card with no APR, statement or minimum is left out** of a payoff plan rather than given a
  guessed rate (P-03).
- **Nothing is written, anywhere.** The repository has no write methods and the screen says so.
- **Not implemented** (ADR-0051): §36's grey-zone labelling, CRD-001's minimum-due trap and
  CRD-002's grace-period optimiser, FLT-003's rate bands, tenure-versus-EMI prepayment, and saving a
  simulation.

## Rules / knowledge consumed
| ID / file | What it provides |
|-----------|------------------|
| RULE-PREPAY-VS-INVEST v1.0 (`rules-kb.json` 1.23.0) | compare the loan rate with an after-tax expected return; show the breakeven |
| RULE-PAYOFF-ORDER v1.0 | offer avalanche and snowball, with the interest delta computed |

**No row was minted** (ADR-0051): both shipped with the rulebook's first version and already
described these simulators. They now name AI-SIM as their consumer, and neither version moved.
`InterviewRules`' sibling `SimulatorRules` mirrors their switches, guarded by
`SimulatorRulebookDriftTest`.

## Evidence shown to the user (P-02)
Each answer shows both sides, the gap between them, and what would change it — the breakeven return
for a prepayment, the interest and months saved for a payoff — followed by "From AI-SIM v1.0 ·
<rule>". The screen ends with a line saying that nothing has been paid or moved.

## Tests
- **Behaviour** (`PrepayVsInvestSimulatorTest` 11, `DebtPayoffSimulatorTest` 11): each figure, the
  verdicts, the breakeven checked from either side, the rollover, the refusals, and provenance.
- **Cross-engine** — one debt paid at its EMI must equal `LoanEngine.schedule`'s months and interest.
  This is the anti-drift test, and it failed first time: see the residue rule.
- **Foundations** (`SimulatorMathTest`, 6): compounding, the shared rounding, the residue rule, and
  the loan that never clears. Written after a mutation — interest earned on the original sum instead
  of the running one — survived every other test in the module.
- **Golden** (`golden/simulator.txt`, 6 scenarios) against `simulator_oracle.py`, an independent
  Python implementation, matching to the paise over 180-month loops.
- **Property** (`SimulatorPropertyTest`, 6 × 300): prepaying never costs more; a larger prepayment
  never saves less; the verdict always matches its own figures; **avalanche never costs more than
  snowball**; both strategies clear every debt exactly once; determinism.
- **Drift** (`SimulatorRulebookDriftTest`, 5).
- **Watched red:** tax ignored on the investment side; avalanche ranked like snowball; the spare
  money never applied; simple interest in place of compound.
- **Downstream:** `SimulatorRepositoryTest` proves the figures come from the household's own loans
  and cards, and that a card with no APR is left out; `SimulatorsScreenTest` proves both sides and
  the gap reach the screen.

## Version log
| Version | Date | Change |
|---------|------|--------|
| 1.0 | 2026-09-26 | Initial implementation from SRS §36 and §40.2 (issue 10.3, ADR-0051). |
