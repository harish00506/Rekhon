<!--
  Why:  AI Personal CFO keeps its AI "brain" as data + specs, not hardcoded logic
        (SRS §7, §29). This README is the map to those files so any engine author or
        reviewer can find the single source of truth for a behaviour.
  What: Index + consumption contract for every file the AI subsystem loads at runtime.
  Result: A reader knows which file feeds which engine layer and how to change it safely.
  Changelog:
    2026-07-17 — Created from SRS v1.7 (initial materialisation of the AI file set).
-->

# AI Personal CFO — AI Subsystem Files

This folder holds **every file the AI actually uses**: the orchestration **workflow**,
the **rules** knowledge base, the chat **skills** (tool registry), and the supporting
knowledge and prompt files. The Android app (Kotlin engines, §21.2) *loads* these files;
it does not embed their content as code.

## The one principle that shapes every file here

> **P-03 — Numbers come from math, words come from AI.** Deterministic engines
> (rules + statistics + on-device ML) produce every amount, score, and forecast.
> The LLM only *verbalises* results it is handed, and its output is re-checked against
> those results before display (AI-ARC-004).

If a change to any file here could let the LLM invent a number, it is wrong by construction.

## The layered pipeline (SRS §7)

```
L6  CONVERSATION (LLM)   chat/system-prompt.md · chat/guardrail.md · skills/tool-registry.json
L5  DECISION ENGINE      rules/financial-order-of-operations.json  (+ engines)
L4  PREDICTION MODELS    knowledge/calendar-seasonality.json  (+ engines)
L3  RULES ENGINE         rules/rules-kb.json
L2  ANALYTICS / FEATURES knowledge/classification-kb.json
L1  DATA (encrypted DB)  — the app's Room/SQLCipher database (not in this folder)
```

External-data engines (market, tax) sit alongside L3–L5 and read
`knowledge/market-signals.json` and `knowledge/tax-kb-fy2025-26.json`.

## File index

| Category | File | Feeds | SRS |
|----------|------|-------|-----|
| **Workflow** | `orchestrator/insight-orchestrator.yaml` | trigger → pipeline → persist → notify | §7.2 |
| **Workflow** | `orchestrator/engine-registry.yaml` | every engine's id/version/contract | §7, §21.2 |
| **Rules** | `rules/rules-kb.json` | L3 Rules Engine (`RuleEngine.evaluate`) | §29 |
| **Rules** | `rules/financial-order-of-operations.json` | AI-FOO "next best rupee" waterfall | §36 |
| **Rules** | `rules/rulebook.md` | human doc + governance for the rulebook | §29 |
| **Skill** | `skills/tool-registry.json` | the ONLY way chat touches data | §19.2 |
| **Other** | `chat/system-prompt.md` | the chat LLM's system prompt + behaviour | §19.1, §19.3 |
| **Other** | `chat/guardrail.md` | AI-ARC-004 numeric-verification contract | §7.1 |
| **Other** | `architecture/ai-architecture.md` | binding architecture + product principles | §1.3, §7.1 |
| **Other** | `knowledge/classification-kb.json` | AI-CLS category + nature classification | §8 |
| **Other** | `knowledge/market-signals.json` | AI-MKT signal library + backtest policy | §30 |
| **Other** | `knowledge/tax-kb-fy2025-26.json` | AI-TAX FY2025-26 parameters | §38 |
| **Other** | `knowledge/calendar-seasonality.json` | AI-FCT seasonality priors | §9.3 |
| **Other** | `knowledge/vehicle-maintenance-kb.json` | AI-VEH service intervals + cost ranges | §12 |

## Rules for editing these files (binding)

1. **Everything is versioned.** Each file carries a `version` and a `source_section`.
   Every stored engine result records the file/engine version that produced it
   (AI-ARC-006), so past insights stay reproducible after you change a threshold.
2. **Thresholds are defaults, not truths.** They are the floor; user overrides and
   learned behaviour refine them at runtime (§29). Never encode a user's number here.
3. **Cite by ID.** Engines cite `rule_id` + `version` in evidence ("flagged by
   RULE-EMI-40"). Renaming an ID breaks traceability — deprecate, don't rename.
4. **India-native (P-06).** INR minor units (paise), lakh/crore formatting, Indian
   instruments (FD/RD/PPF/EPF/NPS/ELSS), Indian tax and calendar.
5. **Governance.** Changing a threshold must record who/when/why in the app's
   `audit_log` at runtime; this repo's git history is the design-time record.

## How the app loads them

These ship as bundled assets and seed the corresponding Room tables on first run
(`rules_knowledge_base`, `classification_rules`, `signal_configs`, tax KB cache, …).
Backend-served updates (`/v1/knowledge/*`, §22.2) can refresh the market/tax/calendar
knowledge later; the app must build and pass all tests with the backend absent (P-04).
