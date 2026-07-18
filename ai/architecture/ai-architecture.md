<!--
  Why:  The AI is a layered pipeline, not one model (SRS §7). Without a single written
        contract for the layers and their binding rules, engines drift and the "no
        black-box numbers" guarantee (P-02/P-03) erodes.
  What: The canonical description of layers L1–L6, the binding architecture rules
        (AI-ARC-001..007), and the product principles (P-01..P-08) that override features.
  Result: A reviewer can reject any AI change that violates a layer boundary or principle.
  Changelog:
    2026-07-17 — Created from SRS v1.7 §1.3, §1.4, §6, §7.
-->

# AI Architecture (binding)

The AI system is a **layered pipeline**. Each layer has a single responsibility, a typed
contract, and is testable in isolation. Determinism increases toward the bottom; language
fluency increases toward the top.

```
┌─────────────────────────────────────────────────────────────┐
│ L6  CONVERSATION LAYER (LLM)                                  │
│     Chat assistant, explanation generation, NL → intent       │
│     Never computes numbers. Consumes structured results.      │
├─────────────────────────────────────────────────────────────┤
│ L5  DECISION ENGINE                                           │
│     Purchase Advisor, recommendations, action ranking (AI-FOO)│
│     Combines L3/L4 outputs with user policy & priorities      │
├─────────────────────────────────────────────────────────────┤
│ L4  PREDICTION MODELS                                         │
│     Cash-flow forecast, seasonality, maintenance prediction,  │
│     spend projection, income volatility                       │
├─────────────────────────────────────────────────────────────┤
│ L3  RULES ENGINE                                              │
│     Threshold alerts, budget rules, health-score rubric,      │
│     eligibility checks, guardrails on all AI output           │
├─────────────────────────────────────────────────────────────┤
│ L2  ANALYTICS / FEATURE LAYER                                 │
│     Aggregations, category stats, recurring detection,        │
│     fixed-vs-variable classification, feature vectors         │
├─────────────────────────────────────────────────────────────┤
│ L1  DATA LAYER                                                │
│     Encrypted Room DB — single source of truth                │
└─────────────────────────────────────────────────────────────┘
```

## Binding architecture rules (§7.1)

| ID | Rule |
|----|------|
| **AI-ARC-001** | Layer N may depend only on layers below it. No layer reads the UI; the UI reads only published results (`Insight`, `Forecast`, `Verdict`). |
| **AI-ARC-002** | Every engine is a pure Kotlin module: input data classes in → result data class out. No Android imports; clock, locale, and randomness are injected. |
| **AI-ARC-003** | Every engine result carries **provenance**: `engineId`, `engineVersion`, `inputWindow`, `computedAt`, `confidence`, and the `evidence` list shown to users. |
| **AI-ARC-004** | LLM output MUST pass the Rules-Engine **guardrail** before display: numeric claims are verified against engine results; unverifiable numbers are stripped and the response regenerated or refused. See `../chat/guardrail.md`. |
| **AI-ARC-005** | All engines run on background dispatchers via a single **InsightOrchestrator**; results are persisted so the UI never awaits computation. See `../orchestrator/insight-orchestrator.yaml`. |
| **AI-ARC-006** | Engine versions are recorded with every stored result so historical insights stay reproducible after algorithm upgrades. |
| **AI-ARC-007** | LLM inference is **on-device by default** (compact instruction model, Gemma-class). Cloud LLM is optional, off by default, and receives only the minimal structured context (§19.4) — never raw transaction dumps. |

## Product principles — these override feature requests (§1.3)

Any feature that violates a principle must be redesigned or rejected in review.

| ID | Principle | Practical rule |
|----|-----------|----------------|
| **P-01** | Privacy first | No financial data leaves the device without explicit, revocable, per-feature consent. Default = fully offline. |
| **P-02** | AI must show its work | Every recommendation displays inputs, the rule/model that fired, and a plain-language explanation. No black-box verdicts. |
| **P-03** | Numbers from math, words from AI | LLMs never compute amounts, scores, or forecasts. They only verbalise deterministic-engine results. |
| **P-04** | Offline-first | Every core feature works in airplane mode. Network features degrade to cached data with staleness labels. |
| **P-05** | Zero data entry where possible | OCR, opt-in SMS parsing, recurring detection, smart defaults minimise typing. |
| **P-06** | India-native | INR and lakh/crore formatting, UPI-centric flows, RBI rates, Indian gold/fuel prices, Indian tax and instruments (FD, RD, PPF, EPF, NPS, ELSS). |
| **P-07** | Advice, never orders | The app recommends and simulates; the user decides. No auto-executed financial actions in v1. |
| **P-08** | Deterministic and testable | Every engine is unit-testable with fixed inputs → fixed outputs. Randomness only via injected, seedable sources. |

## PERSONAL_MODE (§30)

This build is a single-user personal application. `PERSONAL_MODE=true` enables direct
verdict language ("Strong buy day") and removes advisory disclaimers. The transparency
rules (P-02, AI-ARC-004) still apply in full: every verdict must be reproducible from
data, and every prediction is shown with its measured historical hit rate.

## What the product is / is not (§1.4)

**Is:** a personal/household finance brain — tracking, budgeting, forecasting, advising,
on local encrypted storage. **Is not:** a broker/bank/wallet, a credential-based account
aggregator, a SEBI-registered advisor, or a cloud service that stores plaintext data.
