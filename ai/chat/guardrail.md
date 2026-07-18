<!--
  Why:  AI-ARC-004 makes the difference between a trustworthy CFO and a plausible liar:
        an LLM will happily emit a confident wrong number. The guardrail is the gate that
        makes P-03 ("numbers from math") enforceable rather than aspirational.
  What: The contract for the numeric-verification guardrail that every LLM reply (chat
        messages AND generated insight/explanation text) passes through before display.
  Result: A reply either contains only figures traceable to engine/tool results, or it is
        regenerated / refused. No fabricated number ever reaches the user.
  Changelog:
    2026-07-17 — Created from SRS v1.7 §7.1 (AI-ARC-004), §19.1, §19.3 (CHT-001).
-->

# Numeric Guardrail (AI-ARC-004)

> **AI-ARC-004** — LLM output MUST pass through the Rules-Engine guardrail before display:
> numeric claims are verified against engine results; unverifiable numbers are stripped
> and the response regenerated or refused.

This runs on **all** L6 output: chat replies **and** any LLM-generated explanation or
insight body. It is a deterministic L3 check — it never uses the LLM to judge the LLM.

## Inputs

| Input | Source |
|-------|--------|
| `candidate_text` | the LLM's proposed reply |
| `tool_results` | the structured results returned by tools this turn (`../skills/tool-registry.json`) |
| `engine_results` | any `Insight` / `Forecast` / `Verdict` objects referenced this turn |
| `locale` | for parsing Indian digit grouping (₹1,23,456.78) and lakh/crore words |

## Algorithm

```
verify(candidate_text, tool_results, engine_results, locale) -> GuardrailResult
  1. EXTRACT every numeric claim from candidate_text:
       - currency amounts (₹, minor-unit or formatted), percentages, counts,
         scores (0..1000), dates/ETAs, month/year references.
  2. For each claim, RESOLVE it against the allowed value set:
       allowed = flatten(tool_results) ∪ flatten(engine_results)
       - exact match on minor units, OR
       - match within the rendering tolerance of a formatter transform
         (e.g. 1234567 paise -> "₹12,345.67"), OR
       - a value derivable ONLY by an approved display transform
         (rounding for display, unit conversion via convert_currency result).
  3. CLASSIFY each claim: VERIFIED | UNVERIFIABLE.
  4. DECIDE:
       - all VERIFIED                      -> PASS (render, attach evidence chips)
       - any UNVERIFIABLE, attempts < N    -> REGENERATE
             (return the offending spans to the LLM: "these numbers are not backed by a
              tool result — call the right tool or remove them", attempts += 1)
       - any UNVERIFIABLE, attempts >= N   -> REFUSE
             (replace with a safe fallback: "I couldn't verify those figures. Here's what
              the data does show: ..." using only VERIFIED values, or ask to run a tool)
  5. LOG the decision (no amounts in release logs; audit_log for the event only).
```

`N` = max regeneration attempts (default **2**).

## Rules

| ID | Rule |
|----|------|
| **GRD-001** | The LLM is never trusted to self-certify. Verification is deterministic string/number matching against `tool_results` ∪ `engine_results`. |
| **GRD-002** | Approved display transforms are an explicit allowlist: minor→major formatting, Indian digit grouping, lakh/crore words, and `convert_currency` FX. Anything else counts as fabrication. |
| **GRD-003** | The LLM may **not** perform arithmetic. "₹500 × 12 = ₹6,000" is UNVERIFIABLE unless ₹6,000 appears in a tool result. Composition of numbers is the engines' job (P-03). |
| **GRD-004** | Dates/ETAs ("you can buy the bike in March") must come from an engine result (e.g. `get_goals.eta_date`, `purchase_check.alternatives`). |
| **GRD-005** | On REFUSE, the fallback contains only VERIFIED values; it never silently drops the user's question — it says what it could not verify and offers the tool that would answer it. |
| **GRD-006** | Verdict language in PERSONAL_MODE ("strong buy day") is allowed, but the numbers and the backtest hit rate behind it must still verify (transparency survives PERSONAL_MODE). |

## Why regenerate before refuse

Most failures are the LLM stating a real number it simply forgot to fetch. Returning the
offending spans and asking it to call the right tool fixes those cheaply. Refusal is the
backstop for the case where no tool can support the claim — exactly the case where the
user must NOT be given a confident-sounding fabrication.
