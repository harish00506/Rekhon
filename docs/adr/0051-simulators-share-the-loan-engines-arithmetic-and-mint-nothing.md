# ADR-0051 — The simulators reuse the loan engine's arithmetic, mint no rule, and treat a rounding residue as rounding

- **Status:** accepted
- **Date:** 2026-09-26
- **Deciders:** Harish G (solo)
- **SRS refs:** §36 (stages 6–7), §40.2 (CRD-005), §34.3 (FLT-004), RULE-PREPAY-VS-INVEST,
  RULE-PAYOFF-ORDER, AI-ARC-001/003, P-02, P-03, P-07, P-08, MNY-001/002, CLAUDE.md §6; issue 10.3.
  Builds on ADR-0026 (a schedule is derived, never cached) and ADR-0046 (an L5 engine imports no
  other engine — and why this is the exception)

## Context

§36 ends its waterfall with two simulators: stage 6's grey-zone debt (10–12% APR, where payoff
generally beats investing) and stage 7's low-rate debt (a home loan prepay-vs-invest, at the loan's
*current effective* rate per FLT-004). §40.2's CRD-005 extends RULE-PAYOFF-ORDER: avalanche by
default, snowball on request, "both simulations show total-interest and debt-free-date deltas".

Two rules for this already existed in the rulebook's first version — `RULE-PREPAY-VS-INVEST` and
`RULE-PAYOFF-ORDER` — and they described exactly this behaviour, with placeholder consumers
(`loan_simulator`, `debt_simulator`) for engines nobody had built.

## Decision

**1. One module, `:domain:engines:simulator` (AI-SIM 1.0, L5), with two engines.**
`PrepayVsInvestSimulator` and `DebtPayoffSimulator` are separate interfaces over shared arithmetic,
the way `:domain:engines:purchase` holds AI-PA and AI-PA-INT.

**2. It depends on `:domain:engines:loan`, and that is the point.**
ADR-0046's rule is that an L5 engine does not import another engine, because it would start
re-deriving what that engine owns. Here the opposite risk dominates: EMI and amortisation *are* what
the loan engine owns, and a second implementation would be a second definition of the number the
accounts screen already shows. The loan engine's own documentation anticipated this caller by name
("two callers want it without a schedule — the accounts editor, and issue 10.3's prepay-vs-invest
simulator"). The shared piece is `Money.percentOf(bps, overPeriods = 12)` in `:core:model`, which
both use for a month's interest.

**A test enforces it**: one debt paid at exactly its EMI must produce the same months and the same
total interest as `LoanEngine.schedule`. That test failed on the first run — see decision 4.

**3. No rule was minted.** The two rows already said what these simulators do, so they are cited,
not restated. Their `consumed_by` now names **AI-SIM** in place of the placeholders. **Neither row's
version moved**: a row's version tracks what it says, and nothing it says changed — ADR-0017's
trigger 3 is a params or threshold change, which this is not. The rulebook file's own version moved
(1.22.0 → 1.23.0), as it does for any edit.

**4. A rounding residue is rounding, not a month's debt.**
A fixed payment rarely divides a balance exactly, so after the final instalment a few paise can
remain. A lender folds that into the last payment; the first draft of the simulator ran an **extra
month to collect ₹3**, and reported a 37-month loan the accounts screen called 36. The rule is now
explicit and shared by both simulators: a residue under **1% of the payment** is rounding and clears
with it; anything larger is a real balance and gets its own month.

**5. Both sides of the prepay question are judged over the same horizon** — the months the loan
would otherwise have run. Comparing a saving that ends in nine years with a return that compounds
for twenty is the commonest way this comparison lies.

**6. The breakeven is found by bisection with a fixed number of steps.**
The after-tax gain is a rounded monthly compounding, so its inverse has no clean closed form. Forty
steps, fixed, so the answer is reproducible to the basis point (P-08). The tests check it by asking
the simulator either side of its own answer, which is the only honest way to verify a breakeven.

**7. `order` is the order debts are *cleared*, not the order they are attacked.**
Under avalanche, a small debt can clear from its own minimum while a dearer one is being targeted —
which is what the three-debt fixture shows. The first draft of that test asserted the targeting
order and was wrong; the type's documentation now says which it is.

**8. The repository simulates the household's own debts, and leaves out what it cannot know.**
A card with no APR recorded, no statement balance or no minimum is **excluded** rather than given a
guessed rate (P-03). Loans take their rate from the loan itself rather than reverse-engineering it
from the EMI (FLT-004).

**9. Nothing is written, anywhere.** `SimulatorRepository` has no write methods at all, and the
screen says in words that nothing was paid — because a screen listing debts with buttons on it could
otherwise be read as one that moves money (P-07).

## Deferred, and why

- **§36's stage-6 framing** ("grey zone 10–12%") as a labelled band in the output. The arithmetic
  covers any rate; naming bands is AI-FOO's job, and it already has the stage.
- **CRD-001's minimum-due trap simulator** and **CRD-002's grace-period optimiser**. Both are §40.2
  simulators in their own right, with their own inputs; this issue is the two the acceptance
  criteria name.
- **Floating-rate scenario bands** (FLT-003's ±50 bps). The prepay simulator takes one rate; rate
  scenarios need the repo feed that §16.1 describes and the forecast integration FLT-003 asks for.
- **Prepaying by shortening the tenure versus reducing the EMI.** This models the first, which is
  what saves interest; the second is a bank-side option worth its own comparison later.
- **A saved simulation.** The Purchase Advisor keeps its cards because a verdict is advice about a
  decision; a what-if is a question, and storing every one would bury the ones that mattered.

## Consequences

- Two questions people get wrong in both directions now have exact answers, computed on the
  household's own debts and shown with both sides and the point where the answer flips.
- The simulators and the accounts screen cannot drift about a rupee: they share the rounding helper,
  and a test pins the totals against the amortisation schedule.
- The golden file is checked against an independent Python oracle over 180-month loops; the property
  tests hold the claim §40.2 rests on — avalanche never costs more than snowball — over 300 random
  debt piles.
