<!--
  Why:  A single, cheap-to-update place that says where the project actually is — what's done,
        what's in flight, what's next — so any session (human or agent) can resume without
        re-deriving state from git and 85 issue files.
  What: Living progress tracker for the codebase.
  Result: A reader knows the current version, completed work, the file in progress, and next up.
  Changelog:
    2026-07-18 — Created. Baseline: Epic 0 (blueprint) done; no Kotlin code yet.
-->

# AI Personal CFO — Project Memory

> **Update this on every shipped issue and whenever you switch what you're working on.** Keep it
> short. This is the *project progress* log — distinct from the agent's own memory dir at
> `~/.claude/.../memory/`. Detail lives in [`../CHANGELOG.md`](../CHANGELOG.md) and the per-issue
> trackers in [`issues/`](issues/); the static roadmap is [`phase.md`](phase.md).

## Current state

- **Version:** `0.2.3` (see [`../VERSION`](../VERSION)) · **Phase:** 0 — Foundation (Epic 2 under way).
- **Currently working file:** none — issue 2.3 is merged into `dev`; nothing in flight.
- **In progress:** nothing. Last shipped: **Epic 2 · issue 2.3** — quick-setup seeds
  ([2.3](issues/2.3-quick-setup-seeds-income-rent-savings.md) ·
  [tracker](issues/2.3-quick-setup-seeds-income-rent-savings-tracker.md)). 536 tests green. The
  **emulator gate is still blocked, not skipped** — `adb` is not installed and no AVD exists, so the
  app has never been launched and neither the v1→v2 nor the v2→v3 migration has run against real
  SQLite.
- **Next up:** **2.5** (accounts CRUD) — it inserts the last deferred onboarding step (position fixed
  by [ADR-0002](adr/0002-onboarding-step-order.md)) and is what finally attaches an account to the
  recurring rules 2.3 leaves with a null `account_id`. Then **2.4** (demo mode) and **2.6** (net
  worth). Note 2.3 already wrote the first `profile` row and took schema v3 (`budget`,
  `recurring_rule`), both of which had been pencilled in for later issues —
  [ADR-0004](adr/0004-quick-setup-persists-budgets-and-recurring-rules.md) says what 4.4 / 3.7 / 2.5
  are expected to add to those tables rather than rewrite.
- **Still the largest gap:** CI has never run — there is no git remote, so every green is a local
  green on one Windows machine. **Second:** the security-critical Keystore and BiometricPrompt paths
  (2.2) still cannot be tested without a device. **Third, new with 2.3:** nothing in the app loads
  `ai/` yet, so the first engine's thresholds are Kotlin constants guarded by a drift test rather
  than rulebook rows — a deliberate, recorded deferral of CLAUDE.md §6
  ([ADR-0005](adr/0005-quick-setup-thresholds-deferred-rulebook-loader.md)) with a named trigger.
- **Practice worth keeping:** 2.3 made both of its new gates fail on purpose before trusting them
  (a one-point threshold change to red the drift test; temporary uncovered code to red `koverVerify`
  on the new module). This project has shipped a vacuous gate before — audit G-01, a `koverVerify`
  that was green at 0% coverage — so "the gate passed" is not evidence until the gate has been seen
  to fail.

## Completed

- **Epic 0 — Foundations & AI blueprint (v0.1.0):**
  - AI subsystem files the app loads at runtime ([`../ai/`](../ai/)) — layered pipeline,
    orchestrator, rulebook + order-of-operations, chat tool registry, LLM prompt + guardrail,
    knowledge bases.
  - Agent/dev config: [`../CLAUDE.md`](../CLAUDE.md), project skills, slash commands, CI, PR
    template, ENGINE/ADR templates.
  - Planning layer: [design spec + CSV](superpowers/specs/2026-07-17-ai-personal-cfo-design.md)
    (13 epics, 85 issues) and the full [`issues/`](issues/) backlog + trackers.
  - `/run` and `/verify` commands; `VERSION` + `CHANGELOG.md`.
- **Project docs (this set, 2026-07-18):** `PRD.md`, `Architecture.md`, `Rules.md`, `phase.md`,
  `Design.md`, `memory.md`.
- **Epic 1 — Foundation & Core Platform (v0.1.0, 2026-07-25):** issues 1.1–1.10 — the multi-module
  skeleton and its ARC-002 guard, `Money`/`Clock`/`Result`, five custom lint rules, encrypted Room
  over SQLCipher plus the migration harness, the M3 design system, Proto DataStore settings and the
  consent ledger, and the app shell with a typed nav graph. Full account of what is and is not
  proven: [`handoff_epic_completed/epic-1-foundation-handoff.md`](handoff_epic_completed/epic-1-foundation-handoff.md).
- **Epic 2 — issue 2.1 (v0.2.1, 2026-07-25):** the 4-step first-run onboarding. First screen that
  writes; closes the "nothing sets the profile time zone" seam from Epic 1 and gives the consent
  ledger its first caller.
- **Epic 2 — issue 2.3 (v0.2.3, 2026-07-27):** the quick-setup seeds (FR-ONB-002). The project's
  **first engine** (`:domain:engines:quicksetup`, pure Kotlin), its **first `EngineProvenance`**
  (AI-ARC-003), its **first `profile` row**, and schema **v3** (`budget`, `recurring_rule`). The
  dashboard's hardcoded spending split is gone, replaced by the user's real budget. Two recorded
  deviations: [ADR-0004](adr/0004-quick-setup-persists-budgets-and-recurring-rules.md) (schemas
  defined ahead of the issues that own them) and
  [ADR-0005](adr/0005-quick-setup-thresholds-deferred-rulebook-loader.md) (§6 rulebook loader
  deferred, guarded by a drift test).
- **Epic 2 — issue 2.2 (v0.2.2, 2026-07-26):** the biometric/PIN app lock (SEC-002). First security
  perimeter in the app: a session gate the database provider asserts on, a Keystore-bound PIN, the
  escalating lockout, and `audit_log` as schema **v2** — the project's first real migration and its
  first `:data:repository` class. SEC-001's user-auth key clause is deliberately still open
  ([ADR-0003](adr/0003-app-lock-gate-and-deferred-user-auth-key.md)).

## How to update

When you finish an issue: bump [`../VERSION`](../VERSION) + [`../CHANGELOG.md`](../CHANGELOG.md),
update the issue's tracker, then edit the three lines under **Current state** above and add a
bullet under **Completed**. That's it.
