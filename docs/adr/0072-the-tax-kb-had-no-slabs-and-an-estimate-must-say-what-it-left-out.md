<!--
  Why:  CLAUDE.md §5 — any decision or deviation from the SRS needs an ADR, and 13.4's AC3 asks for
        one explicitly.
  What: issue 13.4 — the slab tables the knowledge base did not have, why the limitations travel on
        the result, what the break-even actually turns out to be under FY2025-26, and a registry
        ghost this work closed.
  Result: a reader can see where the slab numbers came from and how they were corroborated, and why
          an estimate that omits surcharge has to say so out loud.
  Changelog: 2026-10-09 — Created.
-->

# ADR-0072 — The tax KB had no slabs, and an estimate must say what it left out

**Status:** Accepted · **Date:** 2026-10-09 · **Issue:** 13.4 · **SRS:** §38.1, §38.2, TAX-001, TAX-002 · **Rules:** P-02, P-03, P-07, P-08, MNY-001, MNY-002, TIM-002

## Context

§38 supersedes §33.1 and specifies a real tax engine: both regimes computed from the user's actual
deductions, a break-even "shown in rupees", capital gains under the post-23-July-2024 rules, and
TAX-001's harvesting alerts. `ai/knowledge/tax-kb-fy2025-26.json` has existed since issue 1.x as the
versioned parameter file §38.1 requires.

Two things turned up before any code was written.

**The knowledge base could not be computed from.** Neither regime had a slab table. The file had the
80C cap, the standard deductions, the rebate ceiling and the fact that "the 30% slab starts above
₹24L" — but no rates and no boundaries. **And §38.1 does not state them either**: its parameter
table lists the same anchors. A tax engine cannot exist without slabs, so the first task was to put
them in the file (§6 — financial numbers are data).

Several other values were **prose**: `"stcg": "20% (holding <= 12 months)"`,
`"80CCD(2)_employer_nps": "Deductible in BOTH regimes, up to 14% of salary"`. An engine cannot
consume a sentence — the same problem issue 10.4 fixed in the vehicle KB.

**The registry pointed AI-TAX at a module that does not exist**, `:domain:engines:growth`. That is
one of the two drifts issue 13.2 measured and recorded in ADR-0070.

## Decision

### 1 · The slab tables, and how they were corroborated

Both regimes' slabs are now rows in the knowledge base, rates in basis points (MNY-002), with
`upto_inr: null` marking the open-ended top band.

The new-regime table is not asserted on authority — it is **checked against §38.1's own anchors**:

| §38.1 says | The table gives | |
|---|---|---|
| "30% slab starts above ₹24L" | marginal rate just above ₹24L = 30% | ✓ |
| "87A rebate → income ≤ ₹12L effectively tax-free" | tax at ₹12L taxable = ₹20,000 + ₹40,000 = **₹60,000**, which is exactly the rebate | ✓ |
| standard deduction ₹75,000 | ₹12.75L gross − ₹75,000 = ₹12L taxable → zero tax | ✓ |

The second one is the strong check: the rebate amount and the slab table are independent facts, and
they agree to the rupee. The old regime's table is corroborated the same way — ₹5L taxable gives
exactly ₹12,500, which is its rebate.

The **4% health and education cess** was also missing. Leaving it out would have understated every
figure by 4%, consistently enough to look right.

`fy_rules_version` stays **2025-26.1**. The law did not move; only this file's completeness did.
Bumping it would imply a Budget change and would make a future reader look for one.

### 2 · An estimate must say what it left out

`TaxLimitation` travels on the result, beside the figure, rather than in a comment or a doc.

Surcharge is the case that forced it. It starts at 10% above ₹50L of taxable income and comes with
**marginal relief**, which needs a second computation at each band boundary. Modelling it properly
is a day's careful work; modelling it badly is worse than not modelling it. So the engine does not —
and above that income it **says** the estimate is understated.

P-03 is usually read as "never invent a number". This is its other half: **never hide that a number
is incomplete.** A tax figure is read as authoritative, and an authoritative figure that silently
omits a 10% surcharge is not an estimate, it is a wrong answer.

The same applies to property gains. §38.2 describes a dual computation needing an indexation table
the KB does not carry, and TAX-002 says the engine "links complex cases (property, foreign/RSU
taxation) to a professional". So there is **no `AssetClass` for property** — it cannot be supplied
at all, which is a stronger guarantee than refusing it at runtime.

### 3 · Two guarantees are structural, not remembered

**TAX-001's alerts never name an instrument.** `TaxAlert` has a kind, an amount, a day count and a
citation — and nowhere to put a fund name. The engine is handed labels on `OpenPosition` and simply
has no field to copy one into. A test drives a year-end scenario with real fund names attached and
asserts none of them appears.

**The engine never files.** There is nothing in `TaxEstimate` that represents an action (P-07).

### 4 · The break-even is relative to what is already claimed — and it is high

§38.1 asks for the break-even "shown in rupees". The first implementation probed by *replacing* the
household's deductions, which meant "does the old regime already win?" was answered for a household
with none of them, and a household that was already winning still got a figure. It now probes on top
of what is claimed, so `null` means "you already win".

Found by **bisection over 40 fixed steps**, not algebra: the rebate cliff and the cess make the
function piecewise, so algebra would need a case per slab boundary and would break the next time a
Budget adds one. Fixed steps keep the answer reproducible (P-08) — the same choice AI-SIM made.

**What it reveals is the most useful thing this engine has to say.** At ₹18L of income, 80C,
80CCD(1B) and 80D all maxed *plus* ₹2L of home-loan interest — ₹4.25L of deductions — still loses to
the new regime by ₹67,600. The old regime needs about **₹7.25L**. A test was written asserting the
old regime won at ₹4.25L; it failed, and the engine was right.

### 5 · The flag

`TaxMode.IS_ENABLED` is `false`. Beyond the usual Epic 13 reason, a number labelled "tax" is read as
authoritative, and this one omits surcharge. The label and the limitations have to be designed into
a screen before the figure appears on one, or the omissions become invisible — which is exactly what
§2 exists to prevent.

Before it goes on: a repository (including the FIFO lot matching the engine deliberately does not
do), a screen that renders `limitations` as prominently as the figure, and a decision about whether
AI-FHS or the insight feed consumes the regime comparison.

## Consequences

- The KB moves 1.0 → **1.1**: slabs, cess, rebates, the old-regime standard deduction, typed capital
  gains and typed harvesting rules. 83 insertions, edited **as text** — the lesson issue 13.3 learned
  the hard way, since a serialiser round-trip reformats every untouched row.
- `TaxKbDriftTest` is the drift test with the shortest fuse in this repository: §38.1 says the
  parameters change **every Budget**, so the edit is guaranteed to come, and it will come from
  somebody editing the JSON.
- **ADR-0070's ghost registry entry is closed.** `:domain:engines:growth` is gone; the registry now
  names a module that exists. The other half of that drift — 10 engine modules with no entry at all —
  is still open and still its own issue.
- No FIFO lot matching. The engine takes realised gains already matched; matching needs the whole lot
  history and belongs in a repository.

## A gate that passed vacuously, and was tightened

The golden fixture's coverage assertion required "a household where each regime wins". It passed —
on `zero_income`, where both regimes compute zero and the tie-break happens to pick the old one. So
the fixture contained **no case where the old regime is genuinely better**, and the assertion was
satisfied by a degenerate tie.

It now requires a win with a **positive margin**, and a thirteenth household was added that reaches
one. The near-miss is instructive: under FY2025-26's rates a real old-regime win is hard to construct,
which is precisely why a test must pin one rather than assume it.

## Alternatives considered

- **Model surcharge approximately, without marginal relief.** Rejected, §2: an approximation that is
  wrong by thousands of rupees at exactly the incomes where it applies is worse than a stated gap.
- **Bump `fy_rules_version` for the slab addition.** Rejected, §1: it would assert a change in the
  law that did not happen.
- **Take the LTCG exemption per lot.** Rejected: it would multiply a ₹1.25 lakh annual allowance by
  the number of holdings sold. Pinned by a test with three lots.
- **Keep the capital-gains rules as prose and parse them.** Rejected: the same reason issue 10.4
  typed the vehicle KB. The sentence is kept beside the fields as `says`, so the transcription can be
  checked by eye.
