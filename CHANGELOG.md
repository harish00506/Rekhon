# Changelog

All notable changes to AI Personal CFO are recorded here.
Format: [Keep a Changelog](https://keepachangelog.com). Versioning: [SemVer](https://semver.org).
Single source of truth for the version number is the repo-root [`VERSION`](VERSION) file; keep
`app/build.gradle.kts` `versionName` equal to it. Epics map to the SRS roadmap (§26); every
entry cites its requirement IDs (§28). See [`docs/issues/00-issue-workflow.md`](docs/issues/00-issue-workflow.md).

## [Unreleased]

### Added
- **Injected `Clock` + `DispatcherProvider`** (issue 1.3 / task 1.1.3; SRS §21.4 TIM-001/TIM-002,
  §21.2 ARC-006): `:core:common` now owns the time and concurrency seams every engine will inject.
  `Clock` answers `nowUtcMillis()` / `zone()` / `today()` in the **profile time zone** — so a spend
  at 23:30 IST belongs to that day's budget even though UTC has rolled over — with `startOfDay`,
  `endOfDay`, `isSameProfileDay` and `toProfileDate` as extensions, and `SystemClock` as the single
  sanctioned `System.currentTimeMillis()` call site in the codebase (TIM-001). The profile zone is
  read through a provider lambda on every call, which is the seam Proto DataStore settings (issue
  1.9) will plug into. `DispatcherProvider` exposes Main/IO/Default so no call site names
  `Dispatchers.IO` inline and `GlobalScope` is never needed (ARC-006). Uses `java.time` (native at
  minSdk 26, NFR-008) rather than adding `kotlinx-datetime`. `FakeClock` and `TestDispatchers` ship
  from a **`testFixtures`** artifact so later modules reuse one set of doubles. 20 tests covering the
  IST day/month rollover, UTC-midnight straddling, a DST transition, and virtual-time coroutines;
  `:core:common` measures **100%** line coverage.

### Verified
- The **85% coverage floor now bites a second module.** Issue 1.2 could only prove the gate on
  `:core:model`; `:core:common` measured 89.66% before the gaps were closed, so the floor is
  demonstrably measuring real code rather than passing vacuously. Also learned: Kover counts
  `testFixtures` classes, so published fixtures need their own tests.
- **`Money` value class** (issue 1.2 / task 1.1.2; SRS §21.4, MNY-001/MNY-002, NFR-012): the single
  monetary type, `Long` minor units (paise) end-to-end — `@JvmInline value class Money(val minor: Long)`
  in `:core:model` with overflow-checked `plus`/`minus`/`times` (`Math.*Exact`, so a wrong answer
  throws instead of wrapping), `percentOf(bps: Int)` using **HALF_EVEN** banker's rounding on integer
  basis points (MNY-002 — no `Double` rate), and `split(n)`/`allocate(weights)` using the
  largest-remainder method so parts **sum exactly** to the original, for refunds as well as payments.
  Plus `MoneyFormatter` rendering Indian 2,2,3 digit grouping (₹1,23,456.78) with the grouping written
  out rather than delegated, so output does not drift with JDK or Android locale data. 35 tests:
  the T1–T8 table, a seeded property sweep (P-08) over ~41 000 split combinations, and the Long
  extremes. No `Double`/`Float` touches a monetary value anywhere.
- **A coverage gate that actually blocks** (governance audit G-01): `configureCoverage()` in the
  `cfo.kotlin.library` convention plugin gives `koverVerify` its first real rules — line coverage
  ≥ 85% on pure-Kotlin modules and **100% on `:core:model`** (money math). Previously Kover was
  applied with zero rules and passed at any coverage, including 0%. Proved to bite twice before
  merging: an impossible 101% bound failed the build, and deleting one test dropped the measurement
  to 77.5% and failed it again.
- **Gradle multi-module skeleton** (issue 1.1; SRS §21.2/§21.3, ARC-001/ARC-002): the module graph
  made real and building green — `:app`; `:core:{model,common,database,datastore,network,crypto,
  designsystem}`; `:domain:engines:forecast` + `:domain:usecase`; `:data:repository`;
  `:ml:{ocr,llm}`; `:feature:{dashboard,onboarding,transactions}`; `:sync:backup`; `:widget`.
  Dependencies are one-way `feature → domain → data/core` (ARC-001); `:core:model`/`:domain:*` are
  pure Kotlin/JVM with a **Gradle-enforced ARC-002 guard** that fails the build (with a clear
  message) if an Android plugin is applied — proved by a Gradle TestKit test. A single version
  catalog (`gradle/libs.versions.toml`) pins the §21.3 stack (AGP 8.11 / Kotlin 2.1 / Gradle 8.13,
  compileSdk 36); `build-logic/` convention plugins (`cfo.kotlin.library`,
  `cfo.android.{library,application,compose,feature}`, `cfo.hilt`) keep module scripts tiny with
  shared JVM-17 + ktlint/detekt/Kover config. CI (`.github/workflows/ci.yml`) now runs the real
  tasks (convention/ARC-002 tests · ktlint/detekt/lint · unit + coverage · assemble) on
  `dev`/`stage`/`main`.
- **Reference-style backlog** (`docs/superpowers/specs/`): a planning-grade design spec
  (`2026-07-17-ai-personal-cfo-design.md`, 14 §-sections distilling the SRS) and its
  machine-readable index (`2026-07-17-issues.csv`) — **13 epics, 85 issues** mapped to the SRS
  roadmap (§26) and traceability (§28).
- **Full issue backlog** (`docs/issues/`): one rich `<id>-<slug>.md` + `<id>-<slug>-tracker.md`
  per issue (170 files), each with acceptance criteria, a label-driven Skill Rules table, guiding
  principles, three-tier workflow rules, a Definition-of-Done gate, and a Verification-Log tracker.
- **Issue-docs generator** (`scripts/gen_issue_docs.py`): single source of truth holding all 85
  issue records; emits the CSV + every issue/tracker file, idempotently.
- **Reference-format templates**: `_ISSUE_TEMPLATE.md` / `_TRACKER_TEMPLATE.md` rewritten to match
  the generated files.
- **Android use-case dev skills** (19, global `~/.claude/skills/`): `compose-ui`,
  `room-and-migrations`, `hilt-di`, `gradle-modules`, `ml-kit-ocr`, `on-device-llm`,
  `workmanager-jobs`, `datastore-consent`, `keystore-crypto`, `biometric-auth`,
  `retrofit-networking`, `glance-widget`, `paparazzi-screenshot-testing`, `proguard-r8-release`,
  `kotlin-multiplatform`, `compose-navigation`, `compose-performance`, `edge-to-edge`,
  `kotlin-coroutines-flow` — each a project-tailored playbook for the pinned §21.3 stack, citing the
  binding rules (ARC/AI-ARC/MNY/TIM/SEC, P-01…P-08). The last six are grounded in the official
  Google Android (R8 audit, Navigation 3, edge-to-edge, Compose performance / Baseline Profiles) and
  JetBrains/Kotlin (KMP, coroutines/Flow) agent-skill guidance rather than copied from third-party
  registries (skills.sh's mobile catalogue is React-Native/Firebase-centric and its cloud-auth skills
  conflict with P-01/P-04); reconciled line-by-line against those official SKILL.md sources, which
  also surfaced the coroutines/Flow-discipline and Compose-recomposition gaps the last three close.
  Wired into the generator's `LABEL_SKILLS` so each surfaces on the relevant issues — including
  crypto/backup, auth, market-data, integration, widget, designsystem, testing, release, `kmp`,
  `dashboard`/`core`, and the previously-unmapped `app`/`di`/`lint`/`accounts`/`transactions`/
  `notifications` labels. The 7 universal skills (`test-driven-development`, `security-review`,
  `ci-cd-and-automation`, …) were already installed globally — left untouched. `check_issue_docs.py`
  asserts every referenced skill path resolves.
- **`/run` and `/verify` commands** (`.claude/commands/`): the "real gate" (§9) — build + install +
  launch on an emulator, then drive the changed flow (incl. an airplane-mode leg) and confirm it works.
- **Top-level project docs** (`docs/`): `PRD.md`, `Architecture.md`, `Rules.md`, `phase.md`,
  `Design.md`, `memory.md` — thin, cross-referenced views of the SRS / design spec / `CLAUDE.md`
  for fast onboarding (the SRS and `CLAUDE.md` remain the sources of truth). `Design.md` proposes
  the initial Material 3 tokens (seed `#00696E`, Roboto, M3 type scale) pending issue 1.8;
  `memory.md` is the living progress tracker.

### Changed
- **Branch model → GitFlow-lite.** `CLAUDE.md` §7, `docs/issues/00-issue-workflow.md` (steps 8/10),
  and the design spec §9 now specify `feature/* → dev → stage → main` (was trunk-based), with
  `main` (releases) and `stage` (live testing) as **protected**, PR-only, CI-gated branches and
  `dev` as the integration branch.
- **`docs/features/`** repositioned as the deeper **sub-task** layer the issues link down into
  (kept; the 13-epic CSV is now the canonical epic/issue index). `00-issue-workflow.md` and
  `docs/features/README.md` point at the new spec + CSV.
- **Documentation no longer asserts gates that are not wired** (governance audit G-02/G-03/G-04;
  §21.6). `CLAUDE.md` now marks the `GlobalScope` (ARC-006), `System.currentTimeMillis()` (TIM-001)
  and PII/amount-logging bans as **review-blocking today, lint-enforced with task 1.1.5** instead of
  claiming an existing lint rule; detekt now sets `complexity.LongMethod.threshold: 40` so the
  documented 40-line limit is real (detekt's default 60 left it unenforced).

### Removed
- Superseded feature-level `docs/issues/1.1-project-skeleton.md` + tracker (replaced by issues
  1.1–1.5, which link down to the existing `docs/features/1.1-project-skeleton/tasks/` files).
- Dead `verifyPaparazzi*` invocations from `/pre-merge` and `.claude/settings.json` (audit G-02) —
  the task does not exist until Paparazzi lands with issue 1.8, so the DoD command was unrunnable.

## [0.1.0] — Epic 0: Foundations & AI blueprint  (2026-07-17)

### Added
- **AI subsystem files** the app loads at runtime (`ai/`): layered-pipeline architecture,
  Insight Orchestrator workflow + engine registry, RULE-KB rulebook + Financial Order of
  Operations, chat tool registry, LLM system prompt + numeric guardrail, and the
  classification / market-signal / tax / seasonality / vehicle-maintenance knowledge bases.
  (SRS §7, §8, §19, §29, §30, §36, §38.)
- **Agent development config** for writing & maintaining the code: `CLAUDE.md` (binding rules),
  project skills (`new-engine`, `add-rulebook-rule`, `money-time-audit`), slash-command
  workflows (`/new-feature`, `/pre-merge`), CI pipeline, PR template, `ENGINE.md` + ADR
  templates, `.editorconfig`. (SRS §4.2, §21.)
- **Issue workflow** (`docs/issues/`): master workflow + issue/tracker templates for driving
  backlog issues from the SRS.
- `VERSION` and this changelog.

### Notes
- No application (Kotlin/Gradle) code yet — this release is the spec-faithful scaffolding and
  the AI/agent configuration that the build will be written against.
