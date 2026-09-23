# GuardrailEngine — AI-GRD (the numeric guardrail)

**SRS:** AI-ARC-004, P-03, §19.1, §19.3 · **Contract:** `ai/chat/guardrail.md` · **Pipeline layer:** L3
**Module:** `:domain:engines:guardrail` · **Version:** 1.0 · **Status:** active · **Engine id on results:** `AI-GRD`

## Why this engine exists
A language model writes a confident wrong number as readily as a right one, and a wrong rupee figure
in a financial app is not a typo — it is advice someone may act on. AI-ARC-004 makes P-03
enforceable rather than aspirational: text is checked against the values that produced it, before
anyone sees it, by deterministic matching. The model is never asked to certify itself (GRD-001).

It runs on **all** user-facing text that states a figure — today the three notification paths, and
from epic 10 the chat replies and any generated explanation.

## Contract
```
interface GuardrailEngine {
    fun verify(input: GuardrailInput): Result<GuardrailVerdict, AppError>
}
```
- **Input** — `GuardrailInput`: `candidateText` (the **composed** text, never the template),
  `evidence`, `attemptsMade`, `nowUtcMillis`, `rules`.
- **Evidence** — one list per kind of figure, in the units engines publish: `amounts` (Money, paise),
  `percents`, `percentsBps`, `counts` (**scores too** — a score is an integer), `quantities`
  (BigDecimal: months of runway and the like), `dates`, and `names` (verbatim strings the caller
  interpolated, struck out before any digit is read).
- **Output** — `GuardrailVerdict`, carrying provenance (AI-GRD 1.0, citing both rules, **no
  confidence**: a verification is not an estimate) and the verified claims:
  - `Pass` — render it;
  - `Regenerate(unverifiable, verified, attemptsLeft)` — ask for the text again, showing the spans;
  - `Refuse(unverifiable, verified)` — do not show it; a fallback may repeat `verified` and nothing
    else (GRD-005).
- `Err(Validation("guardrail.attemptsMade"))` for a negative attempt count.

## Formula / algorithm
```
strike out evidence.names (replaced by spaces, so offsets survive)
extract in order  rupees → paise → percentages → dates → any remaining number
resolve each claim against the renderings its kind allows      (RULE-GRD-TRANSFORMS)
decide   nothing unverifiable        -> PASS
         attempts left               -> REGENERATE               (RULE-GRD-LADDER)
         otherwise                   -> REFUSE
```
Allowed renderings, and nothing else:

| Evidence | May be written as |
|----------|-------------------|
| `Money` | `MoneyFormatter`'s own output · that without `.00` · rounded to 0–2 decimals · exact lakh/crore wording · `<minor> paise` |
| percent | `35%` · from bps, `35.5%` and its rounded forms |
| date | `2027-03-31` · `31 Mar 2027` · `March 2027` · `2027` |
| count / quantity | itself · a quantity rounded to 0–2 decimals |

## Assumptions & guardrails
- **An allowlist, not a tolerance.** A claim must *equal* a permitted rendering, so arithmetic the
  model performed fails by construction (GRD-003).
- **Rounding stops at the rupee.** Lakh and crore wording is offered only where it is exact:
  "₹2 lakh" for ₹1.5 lakh is a third of the figure. Truncation is never a rendering.
- **Grouping is not normalised**; whitespace and case are. "₹123,456.78" is not a figure this app
  produces.
- **`names` is a hole by construction**, and callers must pass only what they interpolated.
- **Fail-closed**: a caller that forgets to pass its values gets a refusal, not a pass.
- **Not implemented** (ADR-0048): the `convert_currency` FX transform, the refusal's wording, the
  audit-log event, and regeneration itself — nothing asks a model yet.

## Rules / knowledge consumed
| ID / file | What it provides |
|-----------|------------------|
| RULE-GRD-LADDER v1.0 (`rules-kb.json` 1.20.0) | `max_attempts` 2, `refuse_on_exhaustion` |
| RULE-GRD-TRANSFORMS v1.0 | the transform allowlist and `max_display_decimals` 2 |

Mirrored as `GuardrailRules`, guarded by `GuardrailRulebookDriftTest`, which also pins the contract
file's own ladder words.

## Evidence shown to the user (P-02)
Nothing, directly — this engine decides rather than speaks. What it protects is everything else the
user is shown: a figure on screen is one an engine produced, and a refusal is silence rather than a
warning label.

## Tests
- **Behaviour** (`GuardrailEngineTest`, 30): each transform and each refusal, arithmetic, the
  four-digit figure the old subset could not read, names with digits, the ladder's three rungs, the
  validation, provenance, and the rules seam.
- **Golden** (`golden/guardrail.txt`, 4 replies): compared line for line with an **independent**
  oracle, `guardrail_oracle.py`, which reads the rulebook and builds the renderings from Python's
  own formatting — so a mistake must be made twice, in two languages, to go unnoticed. It caught
  one: the extractor swallowing the sentence comma in "₹1,23,457, which is…".
- **Property** (`GuardrailPropertyTest`, 6 × 300): anything `MoneyFormatter` produced always
  verifies; anything no engine produced never does; figure-free text is never refused; the ladder
  depends only on failure and attempts; every claim is classified once, in reading order;
  determinism.
- **Drift** (`GuardrailRulebookDriftTest`, 6).
- **Watched red:** truncation accepted as rounding; dates no longer extracted first; a ladder that
  never refuses; lakh/crore rounded to whole lakhs; names not struck out; a mirror drifting from the
  rulebook.

## Version log
| Version | Date | Change |
|---------|------|--------|
| 1.0 | 2026-09-23 | Initial implementation of `ai/chat/guardrail.md`, superseding `core:model`'s minimal `NumericGuardrail` (issue 9.7, ADR-0048). |
