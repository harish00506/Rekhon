<!--
  Why:  CLAUDE.md §10 — one session file per working session, holding the full reasoning the root
        records only point at.
  What: issue 13.4 — the slab tables the knowledge base never had, why an estimate carries its own
        limitations, the test whose premise was wrong, and a coverage gate that passed on a tie.
  Result: a reader can see where the slab numbers came from and how they were checked, and why
          "never hide that a number is incomplete" is the other half of P-03.
  Changelog: 2026-10-09 — Created.
-->

# 2026-10-09 — The tax engine (issue 13.4)

Branch `feature/13-4-tax-engine-v2-ai-tax` off `dev` (`db02535`). Version 0.13.2 → **0.13.3**
(versionCode 67). Tax KB **1.0 → 1.1**, FY rules unchanged at 2025-26.1.

---

## 1 · The knowledge base could not be computed from

`ai/knowledge/tax-kb-fy2025-26.json` has existed since the skeleton as the versioned parameter file
§38.1 requires. Reading it before writing any code:

- **Neither regime had a slab table.** It had the 80C cap, the standard deductions, the 87A ceiling
  and "the 30% slab starts above ₹24L" — no rates, no boundaries.
- **§38.1 does not state them either.** Its parameter table lists the same anchors.
- **The 4% cess was absent.** Leaving it out understates every figure by 4% — consistently enough to
  look right.
- Several values were **prose**: `"stcg": "20% (holding <= 12 months)"`. An engine cannot consume a
  sentence, which is the same problem issue 10.4 fixed in the vehicle KB.

So the first task was not code. It was putting the slabs in the file (§6: financial numbers are
data) — and, because the blueprint does not state them, *justifying* them rather than transcribing.

### How the slabs were corroborated

| §38.1 says | The table gives | |
|---|---|---|
| "30% slab starts above ₹24L" | marginal rate just above ₹24L = 30% | ✓ |
| "87A rebate → income ≤ ₹12L effectively tax-free" | tax at ₹12L taxable = ₹20,000 + ₹40,000 = **₹60,000**, exactly the rebate | ✓ |
| standard deduction ₹75,000 | ₹12.75L gross − ₹75,000 = ₹12L taxable → zero | ✓ |

The second is the strong one: the rebate amount and the slab table are independent facts in the
file, and they agree to the rupee. The old regime checks the same way — ₹5L taxable gives exactly
₹12,500, which is its own rebate.

`fy_rules_version` stays **2025-26.1**. The law did not move; only the file's completeness did.
Bumping it would assert a Budget change that did not happen and send a future reader looking for it.

---

## 2 · An estimate must say what it left out

`TaxLimitation` travels **on the result**, beside the figure.

Surcharge forced it. It starts at 10% above ₹50L of taxable income and comes with **marginal
relief**, which needs a second computation at each band boundary. Modelling it properly is careful
work; modelling it badly is worse than not modelling it. So the engine does not — and above that
income it *says* the estimate is understated.

**P-03 is usually read as "never invent a number". This is its other half: never hide that one is
incomplete.** A figure labelled "tax" is read as authoritative, and an authoritative figure that
silently omits a 10% surcharge is not an estimate, it is a wrong answer.

Property gains go further: there is **no `AssetClass` for property at all**, so they cannot be
supplied. §38.2 describes a dual computation needing an indexation table the KB does not carry, and
TAX-002 sends complex cases to a professional. Making it impossible to express is stronger than
refusing it at runtime.

---

## 3 · Two guarantees are structural, not remembered

**TAX-001's alerts never name an instrument.** `TaxAlert` has a kind, an amount, a day count and a
citation — and nowhere to put a fund name. The engine receives labels on `OpenPosition` and has no
field to copy one into. A test drives a year-end scenario with real fund names attached and asserts
none of them appears anywhere.

**The engine never files.** Nothing in `TaxEstimate` represents an action (P-07).

Both are properties of the types rather than rules somebody has to keep following.

---

## 4 · Three things went wrong, and each was informative

### 4.1 A test I wrote failed, and the engine was right

`there is no break-even when the old regime already wins` asserted that at ₹18L of income with 80C,
80CCD(1B) and 80D maxed *plus* ₹2L of home-loan interest, the old regime wins. **It does not.** The
new regime still wins by ₹67,600; the old regime needs about **₹7.25L** of deductions.

That is the most useful thing this engine has to say, and it arrived by being contradicted. §38.1
asks for the break-even "shown in rupees" precisely because the intuition — "I have lots of
deductions, so the old regime must be better" — is usually wrong under FY2025-26's rates.

### 4.2 A real bug in my own break-even

The search probed by **replacing** the household's deductions rather than adding to them. So "does
the old regime already win?" was answered for a household with none of them, and a household that
was already winning still got a break-even figure. It now probes on top of what is claimed, so
`null` means "you already win" — which is what the field has to mean to be read correctly.

### 4.3 A coverage gate that passed on a tie

The golden fixture asserted "a household where each regime wins". It passed — on `zero_income`,
where both regimes compute zero and the tie-break happens to pick the old one. The fixture contained
**no case where the old regime is genuinely better**, and the assertion was satisfied by a
degenerate tie.

It now requires a win with a **positive margin**, and a thirteenth household was added that reaches
one. The near-miss is the lesson: under these rates a real old-regime win is hard to construct,
which is exactly why a test has to pin one rather than assume it.

**A coverage assertion satisfied by a degenerate case is not coverage.**

---

## 5 · A registry ghost, closed

Issue 13.2 measured `ai/orchestrator/engine-registry.yaml` and found two drifts: 10 engine modules
with no entry, and one entry naming `:domain:engines:growth`, **a module that has never existed**.
That entry was AI-TAX's.

Building the engine is what let it be corrected rather than guessed at. The ghost is gone; the other
half of the drift — the 10 missing entries — is still open, still its own issue, and still recorded
in ADR-0070.

---

## 6 · Flow changed this session

**None.** AI-TAX is pure, has no entity and no repository, and `TaxMode.IS_ENABLED` is false. Three
Epic 13 engines now exist that nothing reaches, which is what "design-for" means here. `FLOW.md`
records the absence so it reads as deliberate rather than missed.

---

## 7 · Code changed this session

| Path | What it does now |
|------|------------------|
| `ai/knowledge/tax-kb-fy2025-26.json` | **1.1**: both regimes' slabs, the 4% cess, 87A amounts, the old-regime standard deduction and 80D cap; typed capital-gains and harvesting rules with the prose kept as `says` |
| `domain/engines/tax/` | **New module.** `Tax.kt` (contract + `TaxMode`), `TaxKnowledge.kt` (the mirror), `TaxMath.kt` (the band walk, the FY calendar, the bisection), `SlabTaxEngine.kt` |
| `…/tax/src/test/resources/golden/tax_oracle.py` | **New.** The independent Python oracle, 13 households |
| `…/tax/src/test/…/Tax{Golden,Math,Engine,KbDrift}Test.kt` | **New.** 46 tests |
| `domain/engines/tax/ENGINE.md` | **New.** Contract, both slab tables, what it leaves out and why |
| `ai/orchestrator/engine-registry.yaml` | AI-TAX corrected from the ghost `:domain:engines:growth` |
| `docs/adr/0072-*.md` | The ADR |

---

## 8 · Quiz

1. **Where did the slab numbers come from, given the SRS does not state them?** They were sourced
   and then checked against §38.1's own anchors — tax at ₹12L taxable is ₹60,000, exactly the rebate
   that makes ₹12L tax-free.
2. **Why is surcharge not modelled, and why does that not make the engine dishonest?** Marginal
   relief needs a second computation per band, and a bad approximation is worse than a stated gap —
   so the estimate carries `SURCHARGE_NOT_MODELLED` above ₹50L instead of quietly under-reporting.
3. **Why can an alert never name a fund?** `TaxAlert` has nowhere to put one. TAX-001's rule is a
   property of the type, not a convention.
4. **How much deduction does the old regime actually need at ₹18L?** About ₹7.25L. ₹4.25L — all
   three sections maxed plus ₹2L of home-loan interest — still loses by ₹67,600.
5. **Why was "each regime wins somewhere" not real coverage?** It was satisfied by the zero-income
   tie, where both compute zero. It now demands a positive margin.
