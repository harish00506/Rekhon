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

- **Version:** `0.2.6` (see [`../VERSION`](../VERSION)) · **Phase:** 0 — Foundation (Epic 2 under way).
- **Currently working file:** none — issue 2.6 is implemented and verified on
  `feature/2-6-net-worth-assets-liabilities-daily-snapshot`, **not yet committed or merged**.
- **In progress:** nothing. Last built: **Epic 2 · issue 2.6** — net worth
  ([2.6](issues/2.6-net-worth-assets-liabilities-daily-snapshot.md) ·
  [tracker](issues/2.6-net-worth-assets-liabilities-daily-snapshot-tracker.md)). **578 unit tests +
  10 instrumented, all green.**
- **Run `./gradlew unitTests`. Never `testDebugUnitTest`.** The latter is an Android *variant* task,
  so it skips the pure-Kotlin modules (`:core:model`, `:core:common`, `:domain:engines:*`) **and
  never reached `:lint` at all** — whose fourteen tests are the only thing checking the five custom
  detectors that make MNY-001, TIM-001, ARC-006, the PII-logging ban and the hardcoded-string ban
  fail the build. **Those tests had never run in CI.** Proven by disabling `MoneyDoubleDetector`
  outright: `testDebugUnitTest` stayed green, `unitTests` failed. Issue 2.6 added the root aggregate
  and pointed CI, CLAUDE.md, the workflow and the templates at it. `koverVerify` does pull the
  pure-Kotlin modules in transitively, so CI was covering those — `:lint` was the real hole.
- **Count tests from `unitTests` only, after clearing stale results.** The count is **573** at
  v0.2.6, across 21 modules. `build-logic:convention`'s 5 are a separate composite CI runs on its
  own and are not in that figure. Earlier numbers in this file drifted because whatever happened to
  be on disk got counted.
- **The app now has background work.** `NetWorthSnapshotWorker` (daily, WorkManager) is the first.
  Two things it establishes for every worker after it: the gated `CfoDatabase` **throws** while the
  app is locked (SEC-002), so a worker must check `SessionLock` first and inject its repository
  through a `Provider`; and `CfoApplication` is a `Configuration.Provider`, which means the manifest
  must keep removing `androidx.work.WorkManagerInitializer` (lint enforces it).
- **FR-ONB-001 is finally satisfied.** Its fourth step — "add first account with opening balance" —
  landed in 2.5, three ADR-0002 updates after that record first deferred it. The ADR is now closed. Onboarding is six
  steps, and **the skip action is no longer `isLast`**: quick setup used to be last, so Skip and
  Finish were the same thing; anything inserted after `ACCOUNT` must keep using
  `OnboardingStep.isSkippable` instead.
- **`gen_issue_docs.py` used to destroy every tracker on every run**, and 2.5 found it by running
  it: fourteen completed verification logs blanked to "not started" in one command, recoverable only
  because they were committed. It now writes a tracker **only when one does not already exist**.
  If you need to reset one, delete the file first.
- **Count tests correctly.** Earlier figures here (2.3's "536") were inflated: summing every
  `**/build/test-results/**/*.xml` picks up `testReleaseUnitTest` output as well as
  `testDebugUnitTest`, counting each test twice and mixing in stale results from previous runs.
  Exclude `testReleaseUnitTest` when counting.
- **The emulator gate is OPEN — this changed with 2.4.** `adb` *is* installed
  (`~/AppData/Local/Android/Sdk/platform-tools`) and an AVD named `CfoTest` *does* exist; the earlier
  claim here was stale. The app has now been built, installed and driven on a device for the first
  time: onboarding → demo → banner → exit, and the same flow again in airplane mode. **Run
  `emulator -avd CfoTest` and use the gate — do not log it as blocked.** Instrumented tests: 9/9 as of
  2.5, but **scope the command** — `connectedDebugAndroidTest` project-wide instruments every module and burns
  ~5 min each on the many with no `androidTest` sources; only `:core:database` and
  `:feature:onboarding` have any.
- **Two long-unproven paths are now proven** (side effect of 2.4's emulator run): the **v1→v2 and
  v2→v3 Room migrations** ran against real SQLite and preserved their rows, and **SEC-002's Keystore
  PIN round trip** executed on a real TEE. Both had previously only ever been exercised by JVM
  stand-ins.
- **Next up:** **2.7** (account reconciliation, FR-ACC-006) — the last issue in Epic 2. It builds
  DB-001's integrity job, which is what finally gives `account.current_balance_minor` a reader:
  today it is a cache nothing checks ([ADR-0007](adr/0007-account-balances-derived-not-stored.md)).
  Then Epic 3 opens with transactions, and **issue 3.4 (future-dated) is already accounted for** —
  net worth's as-of query bounds by `booked_on_iso_date`, so a scheduled payment will not be
  subtracted from today's figure.
- **Still the largest gap:** CI has never run — there is no git remote, so every green is a local
  green on one Windows machine. **Second:** nothing in the app loads `ai/` yet, so the first engine's
  thresholds are Kotlin constants guarded by a drift test rather than rulebook rows — a deliberate,
  recorded deferral of CLAUDE.md §6
  ([ADR-0005](adr/0005-quick-setup-thresholds-deferred-rulebook-loader.md)) with a named trigger.
- **The emulator gate has now found something two issues running.** 2.5: a squeezed Delete button.
  2.6: the dashboard showed the *stored* daily snapshot, so deleting an account left net worth
  unchanged — every unit test agreed with the code because they all asserted the stored figure, which
  was correct. Unlike 2.5's case this one **could** be turned into a test, and was. **Drive the app;
  green tests are not the same as a working screen.**
- **A gate that could not be made to bite, recorded as such.** 2.5 found a layout defect on the
  device (a `Row` squeezed the Delete button to 10px) and could not reproduce it in Robolectric at
  any screen width — its text measurement is a stub. The regression test is kept as a smoke check
  and is **explicitly not claimed as a gate**, in the test and in the tracker. Prefer that over a
  green test nobody has seen fail.
- **Practice worth keeping:** 2.3, 2.4 and 2.5 all made every new gate fail on purpose before trusting
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
- **Epic 2 — issue 2.6 (v0.2.6, 2026-08-02):** net worth (FR-ACC-005). The project's **second
  engine** (`:domain:engines:networth`, pure Kotlin) and its **first background work** — a daily
  WorkManager snapshot that backfills missed days, at schema **v5** (`net_worth_snapshot`,
  `account.include_in_networth`). Classification is by account type, never by the sign of the
  balance. The dashboard's hardcoded ₹4,82,350.00 is gone; Safe-to-Spend is the last placeholder.
- **Epic 2 — issue 2.5 (v0.2.5, 2026-08-01):** accounts CRUD (FR-ACC-001, FR-ACC-007). All eleven
  SRS account types behind an `AccountType` enum (the old six included a `wallet` the SRS never had,
  and the demo was writing a `card` nothing would match); balances **derived** from transactions
  rather than stored ([ADR-0007](adr/0007-account-balances-derived-not-stored.md), DB-001); archive
  kept distinct from soft delete; schema **v4** and the first migration that alters an existing
  table. Also the app's **first typed route with an argument**, and **FR-ONB-001's last step**, which
  closes [ADR-0002](adr/0002-onboarding-step-order.md).
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
