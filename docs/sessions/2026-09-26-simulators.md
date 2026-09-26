<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: issue 10.3 — AI-SIM, §36's prepay-vs-invest and §40.2's payoff simulators.
  Result: a reader can see why this engine is allowed to import another one, why a few paise are
          rounding rather than a month's debt, and why nothing here writes anything.
  Changelog: 2026-09-26 — Created.
-->

# 2026-09-26 — What if I paid it off? (issue 10.3, ADR-0051)

**Branch:** `feature/10-3-simulators-prepay-vs-invest-payoff` off `dev` (`b14057f`)
**Versions:**
- **VERSION** 0.10.1 → **0.10.2**
- **versionCode** 45 → 46
- **Schema** 26 → **26 (unchanged — a simulation stores nothing)**
- **rules-kb.json** 1.22.0 → **1.23.0** (two rows' `consumed_by`; neither row's own version moved)
- `AI-SIM` 1.0 (new)

---

## 1 · Decisions this session

The full argument for each is in ADR-0051.

- **One module, two engines.** `PrepayVsInvestSimulator` and `DebtPayoffSimulator` are separate
  interfaces over shared arithmetic, the way `:domain:engines:purchase` holds AI-PA and AI-PA-INT.
- **It imports `:domain:engines:loan`, and that is the point.** ADR-0046 bars an L5 engine from
  importing another because it would start re-deriving what that engine owns. Here the opposite risk
  dominates: EMI and amortisation *are* the loan engine's, and a second implementation would be a
  second definition of the figure the accounts screen shows. The loan engine's own docs named this
  caller in advance. **A test enforces it** — one debt paid at exactly its EMI must produce the same
  months and the same interest as `LoanEngine.schedule`.
- **No rule was minted.** RULE-PREPAY-VS-INVEST and RULE-PAYOFF-ORDER already said this, so they are
  cited, not restated; their `consumed_by` now names AI-SIM in place of the placeholders
  `loan_simulator` / `debt_simulator`. **Neither row's version moved** — a row's version tracks what
  it says, and nothing it says changed (ADR-0017's trigger 3 is a threshold change, which this is
  not). The file's own version moved, as it does for any edit.
- **A rounding residue is rounding, not a month's debt.** The cross-check above **failed on its first
  run**: the simulator reported 37 months where the schedule said 36, because it ran an extra month
  to collect ₹3. A lender folds that into the final instalment. `SimulatorMath.settle` now says so
  once, for both simulators: a residue under **1% of the payment** clears with it.
- **Both sides of the prepay question are judged over the same horizon** — the months the loan would
  otherwise have run. Comparing a saving that ends in nine years against a return compounding for
  twenty is the commonest way this comparison lies.
- **The breakeven is bisected in 40 fixed steps.** The after-tax gain is a rounded monthly
  compounding, so its inverse has no closed form; a fixed step count keeps it reproducible (P-08).
  The tests verify it by asking the simulator either side of its own answer.
- **`order` is the order debts are *cleared*, not attacked.** Under avalanche a small debt can clear
  from its own minimum while a dearer one is targeted. **The first draft of that test asserted the
  targeting order and was wrong**; the type's documentation now says which it is.
- **The repository leaves out what it cannot know.** A card with no APR recorded, no statement
  balance or no minimum due is excluded rather than given a guessed rate (P-03); a loan's rate comes
  from the loan itself rather than reverse-engineered from its EMI (FLT-004).
- **Nothing is written, anywhere.** `SimulatorRepository` has no write methods at all, and the screen
  says in words that nothing was paid — a screen listing debts with buttons on it could otherwise be
  read as one that moves money (P-07).
- **Deferred:** §36's grey-zone band as a label, CRD-001's minimum-due trap and CRD-002's grace
  optimiser, FLT-003's ±50 bps rate bands, prepaying by reducing the EMI instead of the tenure, and
  saving a simulation.

**What the oracle and the property tests caught that the unit tests did not:** a deliberate
"simple interest" mutation of `monthlyInterest` **survived** the first suite — every assertion was
about relative outcomes, and simple interest keeps the ordering. `SimulatorMathTest` now holds a
compounding invariant, and the mutation dies.

**What the device run showed:** on a real profile with a ₹20,00,000 loan at 9%, prepaying ₹2,00,000
saves ₹4,32,934.85 and 28 months, investing the same at 12% less 30% tax is ahead by ₹1,72,000.89,
and the answer flips at about 10% — with ₹15,000 spare a month the payoff plan drops 168 months to
75. Both cards name `AI-SIM v1.0` and their rule. Checked in dark mode and in airplane mode.

## 2 · Flow changed this session

```
DashboardScreen → "What if?" → SimulatorsScreen
├─ SimulatePrepay → SimulatorRepository.prepayVsInvest()
│     outstanding = the next instalment's opening balance
│     rate        = the loan's own (FLT-004)
│     → PrepayVsInvestSimulator: baseline vs prepaid amortisation, invest over the same horizon,
│       verdict, and the breakeven return                       (RULE-PREPAY-VS-INVEST)
└─ SimulatePayoff → SimulatorRepository.payoff()
      debts = loans + cards that have an APR, a statement and a minimum
      → DebtPayoffSimulator: avalanche and snowball over the same money, freed minimums rolling
        into the next debt                                      (RULE-PAYOFF-ORDER)
```

`FLOW.md` §2.15 holds the full chain. Nothing is written on either path.

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `ai/rules/rules-kb.json`, `rulebook.md`, fourteen `*Rules.kt` mirrors | 1.23.0: both simulator rows now name AI-SIM as their consumer; no threshold changed |
| `domain/engines/simulator/` (new) | AI-SIM 1.0: both engines, `SimulatorMath`, the typed mirror, `ENGINE.md`, and 35 tests with a golden file and its Python oracle |
| `data/repository/SimulatorRepository.kt` (new), `RepositoryFactory.kt` | the household's own debts, and the two simulations — **no write methods** |
| `feature/advisor/Simulators*.kt` (new), `SimulatorResults.kt`, strings | the "What if?" screen: two cards, both answers, the evidence line, and the note that nothing moved |
| `app/.../di/RepositoryModule.kt`, `CfoRoute.kt`, `CfoNavHost.kt` | AI-SIM provided, and the route to the screen |
| `feature/dashboard/DashboardScreen.kt`, `DashboardDestinations.kt`, strings | the "What if?" action |
| `docs/adr/0051-…`, `DECISIONS.md`, `FLOW.md` §2.15, `ai/orchestrator/engine-registry.yaml`, `CHANGELOG.md`, `docs/memory.md` | the records |
| `docs/AI_Personal_CFO_screens.pdf`, `docs/screens/` | **new** — a 51-page walk through every screen, captured on the emulator in airplane mode, each page naming the engine and rules behind its figures, plus the script that composes it and how to recapture |
