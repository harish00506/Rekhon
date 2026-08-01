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

- **Version:** `0.2.4` (see [`../VERSION`](../VERSION)) · **Phase:** 0 — Foundation (Epic 2 under way).
- **Currently working file:** none — issue 2.4 is implemented and verified on
  `feature/2-4-demo-mode-with-sample-data`, **not yet committed or merged**.
- **In progress:** nothing. Last built: **Epic 2 · issue 2.4** — demo mode
  ([2.4](issues/2.4-demo-mode-with-sample-data.md) ·
  [tracker](issues/2.4-demo-mode-with-sample-data-tracker.md)). **386 unit tests + 8 instrumented,
  all green.**
- **Count tests correctly.** Earlier figures here (2.3's "536") were inflated: summing every
  `**/build/test-results/**/*.xml` picks up `testReleaseUnitTest` output as well as
  `testDebugUnitTest`, counting each test twice and mixing in stale results from previous runs.
  Exclude `testReleaseUnitTest` when counting.
- **The emulator gate is OPEN — this changed with 2.4.** `adb` *is* installed
  (`~/AppData/Local/Android/Sdk/platform-tools`) and an AVD named `CfoTest` *does* exist; the earlier
  claim here was stale. The app has now been built, installed and driven on a device for the first
  time: onboarding → demo → banner → exit, and the same flow again in airplane mode. **Run
  `emulator -avd CfoTest` and use the gate — do not log it as blocked.** Instrumented tests: 8/8, but
  **scope the command** — `connectedDebugAndroidTest` project-wide instruments every module and burns
  ~5 min each on the many with no `androidTest` sources; only `:core:database` and
  `:feature:onboarding` have any.
- **Two long-unproven paths are now proven** (side effect of 2.4's emulator run): the **v1→v2 and
  v2→v3 Room migrations** ran against real SQLite and preserved their rows, and **SEC-002's Keystore
  PIN round trip** executed on a real TEE. Both had previously only ever been exercised by JVM
  stand-ins.
- **Next up:** **2.5** (accounts CRUD) — it inserts the last deferred onboarding step (position fixed
  by [ADR-0002](adr/0002-onboarding-step-order.md)) and is what finally attaches an account to the
  recurring rules 2.3 leaves with a null `account_id`. Then **2.6** (net worth), which is the first
  engine that will find the demo's four accounts and ~84 transactions already waiting for it.
- **Still the largest gap:** CI has never run — there is no git remote, so every green is a local
  green on one Windows machine. **Second:** nothing in the app loads `ai/` yet, so the first engine's
  thresholds are Kotlin constants guarded by a drift test rather than rulebook rows — a deliberate,
  recorded deferral of CLAUDE.md §6
  ([ADR-0005](adr/0005-quick-setup-thresholds-deferred-rulebook-loader.md)) with a named trigger.
- **Practice worth keeping:** 2.3 and 2.4 both made every new gate fail on purpose before trusting
  it (2.4: a one-digit seed change to red the golden dataset test; one hard delete swapped for a soft
  delete to red the residue test). This project has shipped a vacuous gate before — audit G-01, a
  `koverVerify` green at 0% coverage — so "the gate passed" is not evidence until the gate has been
  seen to fail. **2.4 found another one:** `OnboardingFlowInstrumentedTest` had not compiled since
  2.3, because `androidTest` is only compiled when a device is attached. Compile the androidTest
  source set on every issue, device or no device.

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
- **Epic 2 — issue 2.4 (v0.2.4, 2026-07-28):** demo mode (FR-ONB-004). A deterministic, seeded
  three-month sample dataset under an isolated `demo` profile, labelled by one banner above the nav
  graph and erased by hard delete on the way out
  ([ADR-0006](adr/0006-demo-mode-profile-isolation-and-hard-delete.md)). Needed **no schema change**.
  Also: the project's **first emulator run**, and the repair of an instrumented test that had been
  uncompilable since 2.3.
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
