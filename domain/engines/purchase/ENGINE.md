# PurchaseAdvisorEngine — AI-PA (the Purchase Advisor)

**SRS:** §13 (FR-AI-003, §19.2 `purchase_check`) · **Pipeline layer:** L5 · **Module:** `:domain:engines:purchase`
**Version:** 1.0 · **Status:** active · **Engine id on results:** `AI-PA`

## Why this engine exists
"Can I afford this?" is the question the rest of the app only implies. Anyone can answer yes or no;
what makes the answer worth trusting is the working — which check objected, on which figures, citing
which rule. §13 sets seven gates and takes the worst answer among them, so one sentence
("₹80,000 would take you below your emergency fund") always traces back to arithmetic.

It advises and keeps its reasoning. It moves no money and executes nothing (P-07).

## Contract
```
interface PurchaseAdvisorEngine {
    fun advise(input: PurchaseInput): Result<PurchaseVerdictCard, AppError>
}
```
- **Input** — `PurchaseInput`: the `PurchaseRequest` (item, price in paise, method, urgency, the
  instalment for an EMI, the category when known), a `PurchaseSignals` of figures the engines below
  published, `today`, `nowUtcMillis`, `rules`.
- **Output** — `PurchaseVerdictCard`: the verdict, the seven `GateResult`s in §13.1's order, the
  `ImpactStrip`, the `Alternatives`, `hardFail`, and provenance (AI-PA 1.0, citing every rule any
  gate applied; **no confidence** — a gate is arithmetic on published figures, not an estimate).
- `Err(Validation("purchase.item" | "purchase.price" | "purchase.monthlyEmi"))` for an impossible
  request.

**No other engine module is imported** (ADR-0046's rule): an L5 engine that could reach AI-FCT or
AI-EMF would start re-deriving what they own.

## Formula / algorithm
| # | Gate | Passes / warns / fails when |
|---|------|------------------------------|
| 1 | Affordability | cash: fails if the balance cannot cover it, **warns** if what is left falls under the emergency floor (§13.1's "verdict ≤ STRETCH"); EMI: fails if the instalment exceeds the monthly surplus, warns if it exceeds Safe-to-Spend |
| 2 | Cash flow | fails if the forecast's lowest day goes negative, warns if it falls under the buffer **or** days are already under it (reported separately) |
| 3 | Obligations | (obligations + new EMI) ÷ income: ≥ 50% fails, ≥ 40% warns (RULE-EMI-40) |
| 4 | Goal impact | delay = price × 30 ÷ monthly goal contributions; more than a month warns |
| 5 | Budget fit | warns if the price exceeds what the category has left; silent when no budget is set |
| 6 | Opportunity cost | never fails — shows FV = price × (1 + r)^t for 5 and 10 years (RULE-PA-OPPCOST) |
| 7 | Timing | warns when a cheaper month is known, naming it and the saving |

```
verdict   any FAIL -> NOT_NOW · any WARN -> STRETCH · else COMFORTABLE
urgency   URGENT lifts one step, unless a gate failed outright        (RULE-PA-GATES)
```

## Assumptions & guardrails
- **A warning is not a refusal.** Crossing the emergency floor is the user's call to make (P-07);
  only a purchase the money cannot cover fails outright.
- **Attribution is honest**: gate 2 separates the crunch days this purchase adds from the ones
  already there.
- **`comfortablePrice` is `null` when no price would help** — days already under the buffer, a
  cheaper month ahead, or obligations already past the line. Property tests caught both the missing
  case and a negative cap flattened to ₹0.
- **Money is paise and ratios are basis points** end to end; the runway is carried in tenths of a
  month so no float goes near an amount (MNY-001/002).
- **Deterministic and pure**: no clock, no randomness, no I/O (P-08), so a card reproduces in
  December what it decided in September.
- **Not implemented** (ADR-0049): the timing gate's seasonal signal, §13.2's one-tap actions, EMI vs
  cash with total interest (10.3), and §13.3's buy list and interview (10.2).

## Rules / knowledge consumed
| ID / file | What it provides |
|-----------|------------------|
| RULE-PA-GATES v1.0 (`rules-kb.json` 1.21.0) | the seven gates, the worst-of verdict, `days_per_month` 30, what urgency may do |
| RULE-PA-OPPCOST v1.0 | 11% a year over five and ten years |
| RULE-EMI-40 v1.0 | the lender's 40% and 50% lines |
| RULE-COOL-OFF v1.0 | a purchase over 1% of annual income deserves a night |
| RULE-FCT-CRUNCH v1.0 · RULE-STS v1.0 | what counts as a day under the buffer; this month's safe-to-spend |

Mirrored as `PurchaseRules` and guarded by `PurchaseRulebookDriftTest`, which also checks the
percent-to-basis-point conversions of the two reused rows.

## Evidence shown to the user (P-02)
The advisor screen renders the verdict, then every gate with its outcome and figures, the impact
strip (money and runway before and after, and the goal delay), the alternatives, the cooling-off
note, and "From AI-PA v1.0 · <rules>". The card is kept, so the same screen can show it again later.

## Tests
- **Behaviour** (`PurchaseAdvisorEngineTest`, 37): each gate's pass, warn and fail; the figures each
  reports; the verdict; what urgency may and may not soften; the impact strip; the alternatives,
  including the no-comfortable-price case; the refusals; provenance; the rules seam.
- **Golden** (`golden/purchase.txt`, 5 households): compared line for line with an **independent**
  oracle, `purchase_oracle.py`, which reads the rulebook and re-applies §13.1 in its own arithmetic.
- **Property** (`PurchasePropertyTest`, 6 × 300): a dearer purchase never gets a kinder verdict; the
  price the card calls comfortable is comfortable; urgency never makes a verdict worse; a hard fail
  is never called comfortable; every card answers all seven gates, each citing a rule; determinism.
- **Drift** (`PurchaseRulebookDriftTest`, 7).
- **Watched red:** the emergency floor treated as a failure; urgency overriding a hard fail; a goal
  delay measured without its month; the obligation thresholds swapped; simple interest in place of
  compound.
- **Downstream:** `PurchaseAdvisorRepositoryTest` proves a kept card reads back identical, gates and
  citations included; `AdvisorScreenTest` proves the working reaches the screen.

## Version log
| Version | Date | Change |
|---------|------|--------|
| 1.0 | 2026-09-25 | Initial implementation from SRS §13 (issue 10.1, ADR-0049). |
