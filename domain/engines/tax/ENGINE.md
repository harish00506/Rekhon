# AI-TAX — both regimes, the break-even, and what this estimate leaves out

**Module:** `:domain:engines:tax` · **Version:** 1.0 · **Layer:** L3/L5 · **SRS:** §38.1, §38.2
**Issue:** 13.4 · **ADR:** [ADR-0072](../../../docs/adr/0072-the-tax-kb-had-no-slabs-and-an-estimate-must-say-what-it-left-out.md)
**Knowledge base:** `ai/knowledge/tax-kb-fy2025-26.json` v1.1 · **FY rules:** `2025-26.1`

## Contract

```
TaxInput(salary, todayIsoDate, nowUtcMillis, deductions, realisedGains, openPositions)
    -> Result<TaxEstimate, AppError>

TaxEstimate(
    old, new            RegimeComputation   taxable, BANDS, taxBeforeRebate, rebate, cess, total
    winner, margin      Regime, Money       the margin is never negative
    breakEvenDeductions Money?              how much MORE the old regime needs; null when it wins
    capitalGains        CapitalGainsSummary §38.2 by asset class
    alerts              List<TaxAlert>      TAX-001, generic — no instrument can be named
    limitations         List<TaxLimitation> what was NOT modelled
    fyRulesVersion      String              TAX-002: on every result
    provenance          EngineProvenance
)
```

Pure (P-08): `todayIsoDate` and `nowUtcMillis` are inputs. No clock, no I/O, no Android (ARC-002).

## Formula

### The slab walk is marginal

Income in a band is taxed at **that band's** rate, not the whole income at the top rate. Every band
is reported — including the ones where nothing was taxed — because "you paid nothing here" is part
of showing the work (P-02, AC2).

| | Old regime | New regime |
|---|---|---|
| Slabs | nil ≤2.5L · 5% ≤5L · 20% ≤10L · 30% above | nil ≤4L · 5% ≤8L · 10% ≤12L · 15% ≤16L · 20% ≤20L · 25% ≤24L · 30% above |
| Standard deduction | ₹50,000 | ₹75,000 |
| §87A rebate | ₹12,500, taxable ≤ ₹5L | ₹60,000, taxable ≤ ₹12L |
| Itemised deductions | 80C ₹1.5L · 80CCD(1B) ₹50k · 80D ₹25k · home-loan interest · HRA | **none** |
| 80CCD(2) employer NPS | deductible | **also deductible** |

**The rebate is a cliff, not a taper.** One rupee past the ceiling and the whole rebate is gone.
That is the law, and the engine reproduces it rather than smoothing it.

**Cess:** 4% health and education, applied **after** the rebate, in both regimes.

### Capital gains (§38.2, post-23-July-2024)

| Asset | Treatment |
|---|---|
| Equity / equity MF | STCG 20% (≤12 months); LTCG 12.5% above a **₹1.25L annual** exemption |
| Debt MF bought on/after 1 Apr 2023 | **Slab rate always** — added to salary, taxed in whatever band it lands in |
| Pre-Apr-2023 debt MF, gold | LTCG 12.5% after 24 months, no indexation; slab rate before |
| SGB held to maturity | Fully exempt |
| Property | **Not modelled** — §38.2's dual computation needs an indexation table the KB does not have, and TAX-002 sends complex cases to a professional |

"More than 12 months" is the law's wording: **twelve months is still short-term.** The annual
exemption is taken **once over the net long-term equity gain**, never per lot — per lot would
multiply a ₹1.25 lakh allowance by the number of holdings sold.

### The break-even (§38.1, "shown in rupees")

How much **more** deduction the old regime would need, on top of what is already claimed; `null`
when it already wins. Found by bisection over 40 fixed steps rather than algebra, because the
rebate and the cess make the function piecewise — algebra would need a case per slab boundary and
would break the next time a Budget adds one. Fixed steps keep it reproducible (P-08).

**Under FY2025-26's rates that threshold is high.** At ₹18L of income, 80C, 80CCD(1B) and 80D all
maxed *plus* ₹2L of home-loan interest — ₹4.25L of deductions — still loses to the new regime by
₹67,600. The old regime needs about ₹7.25L. That is the practical answer the break-even exists to
give, and it surprises people.

### TAX-001's alerts

Unused LTCG exemption near the financial year's end, harvestable losses before 31 March, and a
holding within the countdown of turning long-term. **Generic by construction:** `TaxAlert` has
nowhere to put an instrument, so "never say sell fund X" is a property of the type rather than a
rule somebody has to remember.

The financial year ends on **31 March**, not 31 December — a date in January belongs to the year
ending that March, a date in May to the next one.

## What this estimate leaves out, and says so

`TaxLimitation` travels **with** the figure, not in a comment:

- **`SURCHARGE_NOT_MODELLED`** — surcharge starts at 10% above ₹50L taxable and comes with marginal
  relief, which needs a second computation at each band boundary. Above that income the engine says
  the estimate is understated rather than quietly reporting a low number.
- **`HRA_TAKEN_AS_GIVEN`** — the exempt figure is accepted as entered; deriving it needs basic, the
  rent paid and the city.
- **Property gains** have no `AssetClass`, so they cannot be supplied at all (TAX-002).

P-03 is usually "never invent a number". This is its other half: **never hide that one is
incomplete.**

## Assumptions

- **Deductions as actually used, not as capped.** §38.1 is explicit; comparing on the caps flatters
  the old regime for everybody.
- **No FIFO lot matching.** The engine takes realised gains already matched. Matching needs the
  whole lot history, which is a repository's job.
- **One salaried individual, resident, under 60.** Senior-citizen slabs are not in the KB.

## Data it reads

`ai/knowledge/tax-kb-fy2025-26.json` v1.1. The engine is pure and cannot read it, so it reads the
typed mirror in `TaxKnowledge`; `TaxKbDriftTest` fails the build the moment they disagree — the
drift test with the shortest fuse in this project, because the Budget edit is guaranteed to come.

## Tests

| Test | What it holds |
|---|---|
| `TaxGoldenTest` | 13 households against **`tax_oracle.py`**, an independent Python re-implementation reading the same KB. Plus a coverage assertion demanding a **positive-margin** win for each regime |
| `TaxMathTest` | The band walk's identities over seeded incomes (slices sum, monotonic, effective ≤ top marginal), the boundary crossing, and the financial year's end |
| `TaxEngineTest` | What each regime allows, the caps, the rebate cliff, the break-even, the limitations, every capital-gains rule, TAX-001's alerts, and that **no alert ever names an instrument** |
| `TaxKbDriftTest` | Every slab in order, caps, rebates, cess, CG rates and harvesting thresholds against the file; plus that property is still unmodelled on both sides |

## Version log

| Version | Issue | What changed |
|---|---|---|
| 1.0 | 13.4 | Created. Both regimes, break-even, §38.2 capital gains, TAX-001 alerts, explicit limitations. |
