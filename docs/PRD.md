<!--
  Why:  A one-screen product view for anyone (human or agent) opening the repo — what
        AI Personal CFO is, who it's for, and what it ships. The full detail already lives
        in the SRS + design spec; this indexes them so there's one copy, not two that drift.
  What: Product Requirements — what to build, targeted users, features.
  Result: A reader knows the product shape and where to go for the binding detail.
  Changelog:
    2026-07-18 — Created (thin, cross-referenced view of SRS §1/§3 + design spec §1/§3/§13/§14).
-->

# AI Personal CFO — PRD

> **Source of truth:** [`init/AI_Personal_CFO_SRS_v1.7.pdf`](init/AI_Personal_CFO_SRS_v1.7.pdf)
> (every requirement has an ID — cite it). **Binding rules:** [`../CLAUDE.md`](../CLAUDE.md).
> **Engineer-facing distillation:** [design spec §1/§3/§13/§14](superpowers/specs/2026-07-17-ai-personal-cfo-design.md).
> When this doc and any of those disagree, **they win — fix this doc.**

## What to build

An **Android-first, AI-driven personal-finance app for India** that behaves like a full-time
CFO: it **classifies, forecasts, advises, and — always — explains**, **on-device** and
**privacy-first**. A single user tracks accounts, transactions, budgets, loans, investments,
goals, and an emergency fund; a layered AI engine stack turns that ledger into categorisation,
a cash-flow forecast, a financial-health score, a Safe-to-Spend figure, a purchase advisor,
and an on-device chat assistant. **Everything works offline** — the only network use is a
thin, optional, consent-gated backend proxy for market data (NAVs, rates, fuel prices), never
on a core path. Greenfield: the spec exists, the Kotlin/Gradle code is being written to it.

## Targeted users

- **Primary user** — one person managing their own money on their own device. No server
  account, no login; identity = the device + biometric/PIN lock.
- **Household / business** — *design-for, not built in v1.* The data model is profile-scoped so
  these can be added later without a rewrite (Epic 13). See design spec §3.
- **No server-side roles** — the optional backend is a stateless market-data proxy holding no
  user financial data.

## Non-negotiables

Numbers from math, words from AI (P-03); privacy-first, per-feature consent (P-01);
offline-first (P-04); advice never orders (P-07); deterministic & testable (P-08); show the
work (P-02). Full text and enforcement: [`../CLAUDE.md` §1](../CLAUDE.md) and [`Rules.md`](Rules.md).

## Features (13 epics)

Full backlog with dependencies, priorities, and per-issue specs: [design spec §13](superpowers/specs/2026-07-17-ai-personal-cfo-design.md)
and [`issues/`](issues/) (85 issues).

| Epic | Capability |
|------|-----------|
| 1 — Foundation & Core Platform | Multi-module skeleton, Money/Clock/Result, encrypted Room, design system, DI |
| 2 — Onboarding, Security & Accounts | 4-step onboarding, biometric/PIN lock, accounts + net worth |
| 3 — Transactions & Capture | ≤3-tap entry, transfers, splits, recurring, OCR, opt-in SMS |
| 4 — Categorisation & Budgets | Category KB, auto-categorisation, Need/Want/Invest, budgets + alerts |
| 5 — Dashboard, Export & Widget | Home dashboard, Safe-to-Spend, privacy blur, JSON export, widget |
| 6 — Wealth: Loans & Investments | Cards, loans + amortisation, holdings + XIRR, allocation, net-worth history |
| 7 — Goals & Emergency Fund | Goals engine, emergency-fund engine, feasibility waterfall, order of ops |
| 8 — Backup & Restore | E2EE backup, restore on fresh device, restore drill |
| 9 — AI Core Engines | Classification, forecast, seasonality, health score, orchestrator, guardrail |
| 10 — Advisor & Chat | Purchase Advisor, simulators, vehicle prediction, on-device chat, market signals |
| 11 — Privacy, Security & Compliance | Key mgmt, capture guard, consents dashboard, crypto-shred erase, DPDP |
| 12 — Quality, Testing & Release | Golden/property harness, AI-eval datasets, screenshot tests, E2E, CI gates |
| 13 — Expansion (design-for) | Household, appliances, insurance, tax v2, business, Account Aggregator, iOS/KMP |

## Out of scope (v1)

Auto-executed money movement (no payments/trades/auto-transfers — P-07); cloud sync /
multi-device server holding financial data; on-device scraping of market data; and everything
deferred to Epic 13. Detail: [design spec §14](superpowers/specs/2026-07-17-ai-personal-cfo-design.md).
