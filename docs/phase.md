<!--
  Why:  A static map of how the 13 epics group into build phases and the order they unlock,
        so anyone can see the shape of the roadmap at a glance. Live status is NOT here — it
        lives in memory.md so this map stays stable.
  What: Phase breakdown — epics grouped into SRS build phases, with exit criteria + order.
  Result: A reader knows what ships in which phase and what unblocks what.
  Changelog:
    2026-07-18 — Created (condensed from design spec §13 + Dependency Order + features/README).
-->

# AI Personal CFO — Phases

> **Detail:** [design spec §13](superpowers/specs/2026-07-17-ai-personal-cfo-design.md) (per-epic
> issues, priorities, deps) and [`features/README.md`](features/README.md). **Live status is in
> [`memory.md`](memory.md), not here** — this is the static roadmap map.

The 13 epics map onto the SRS build phases (§26). Priority: MUST→High, SHOULD→Medium, MAY→Low.
The backlog is topologically ordered by each issue's `Deps`.

## Phase 0 — Foundation
*Epics 1–3 (+ security/accounts). Exit: add-txn ≤3 taps · airplane-mode pass · migration harness live.*
- **Epic 1 — Foundation & Core Platform:** one-way multi-module skeleton + Money/Clock/Result,
  lint, encrypted Room, design system, DataStore, app shell. **Unblocks everything.**
- **Epic 2 — Onboarding, Security & Accounts:** onboarding, biometric/PIN lock, accounts + net worth.
- **Epic 3 — Transactions & Capture:** ≤3-tap entry, transfers, splits, recurring, OCR, opt-in SMS. Provides the L1 data.

## Phase 1 — Core finance
*Epics 4–5. Exit: classification ≥85% on eval set · budget alerts fire · export round-trip.*
- **Epic 4 — Categorisation & Budgets:** category KB, auto-categorisation, Need/Want/Invest, budgets + alerts.
- **Epic 5 — Dashboard, Export & Widget:** home dashboard, Safe-to-Spend, privacy blur, JSON export, widget.

## Phase 2 — Wealth & backup
*Epics 6 & 8 (run in parallel once 1.6/2.5 land). Exit: net worth accurate vs fixtures · restore on fresh device.*
- **Epic 6 — Wealth: Loans & Investments:** cards, loans + amortisation, holdings + XIRR, allocation, net-worth history.
- **Epic 8 — Backup & Restore:** E2EE backup, restore on fresh device, restore drill.

## Phase 3 — AI core & goals
*Epics 9 & 7. Exit: forecast MAPE tracked · OCR total ≥95% · FHS explainability review.*
- **Epic 9 — AI Core Engines:** classification, cash-flow forecast, seasonality, health score, orchestrator, notifications, guardrail.
- **Epic 7 — Goals & Emergency Fund:** goals engine, emergency-fund engine, feasibility waterfall, order of operations. (Depends on Epic 9.)

## Phase 4 — Advisor & chat
*Epic 10. Exit: PA verdict traces stored · chat guardrail eval green · localisation QA.*
- **Epic 10 — Advisor & Chat:** Purchase Advisor, simulators, vehicle prediction, on-device chat + guardrail, market signals, localisation.

## Phase 5 — Expansion (design-for)
*Epic 13 — each item ships an ADR + flagged foundation in v1, not a full build.*
- **Epic 13 — Expansion:** household, appliances, insurance, tax v2, business, Account Aggregator, iOS via KMP.

## Cross-cutting (start early, gate every merge)
- **Epic 11 — Privacy, Security & Compliance** and **Epic 12 — Quality, Testing & Release** run
  across all phases — harden continuously; their gates block every merge.

## Build order (critical path)
`Epic 1 → Epic 2 → Epic 3 → Epic 4 → Epic 5`, then `Epic 9 → Epic 7 & Epic 10`; Epics 6 & 8 in
parallel after 1.6/2.5; Epics 11 & 12 throughout; Epic 13 last (design-for). Full graph:
[design spec — Dependency Order](superpowers/specs/2026-07-17-ai-personal-cfo-design.md).
