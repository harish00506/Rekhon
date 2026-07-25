# Changelog

All notable changes to AI Personal CFO are recorded here.
Format: [Keep a Changelog](https://keepachangelog.com). Versioning: [SemVer](https://semver.org).
Single source of truth for the version number is the repo-root [`VERSION`](VERSION) file; keep
`app/build.gradle.kts` `versionName` equal to it. Epics map to the SRS roadmap (§26); every
entry cites its requirement IDs (§28). See [`docs/issues/00-issue-workflow.md`](docs/issues/00-issue-workflow.md).

## [Unreleased]

### Added
- **Settings and the per-feature consent ledger** (issue 1.9; SRS §21.3, P-01, TIM-001).
  `:core:datastore` now holds a real **Proto DataStore** — protobuf schema, generated types, no
  SharedPreferences anywhere. `ConsentStore` gates the four opt-in features (SMS parsing, market
  data, cloud LLM, cloud backup): each consent is **default off**, revocable, and carries the
  grant/revoke timestamps P-01 needs to answer "since when?" — a bare boolean cannot. Consents are
  keyed by a stable feature id, so adding one later is not a schema migration. `SettingsStore`
  carries the profile time zone (the seam `SystemClock` was built for in issue 1.3), currency,
  privacy blur and theme. Reads are `Flow` — a consent read once at startup could not be revoked —
  and everything runs on injected dispatchers, returning `Result<_, AppError>` rather than throwing.
  A corrupt file is `Err(Storage)`, never a silent reset to defaults, because resetting a consent
  ledger discards decisions the user made without telling them. 13 JVM tests.

### Fixed
- **Six tests were passing over writes that were failing.** The stores return
  `Result<Unit, AppError>` instead of throwing (§21.6), so a test that ignores the return value
  cannot fail — and the first version of these tests ignored all of them. Every write is now
  asserted with `assertWritten()`, which surfaced the real problem: DataStore's default storage
  cannot replace an existing file on Windows (`Unable to rename …tmp`), so every second write
  errored. Storage switched to `OkioStorage`, whose `atomicMove` works on every host, keeping test
  and production on the same code path. Android was never affected; the silent-green tests were the
  actual defect.

### Changed
- Version catalog gains `protobuf`/`protobuf-javalite`/`protoc`, the `com.google.protobuf` Gradle
  plugin, and `datastore-core-okio`.
- **Design system: M3 theme, tokens, components and chart primitives** (issue 1.8; SRS §24, §21.6,
  ACC-*). `:core:designsystem` now holds the colour/type/dimension tokens from `docs/Design.md`
  (seed `#00696E`, plus the `positive`/`negative`/`warning` roles Material has no slot for),
  `CfoTheme`, five components (`CfoCard`, `CfoButton`, `CfoSecondaryButton`, `CfoListRow`,
  `CfoAmountText`) and two chart primitives (`CfoProportionBar`, `CfoSparkline`). Accessibility is
  built in rather than reviewed in: 48dp is a token every clickable applies, charts **require** a
  `contentDescription`, and `CfoAmountText` always renders the sign so debit/credit never depends on
  colour alone (P-02). Copy stays out of the module — text and descriptions are parameters, because
  the wording belongs in the calling feature's `strings.xml`.
- **Screenshot tests that run without a device** (closes governance audit **G-02**). Paparazzi
  renders light, dark and 200%-font baselines on the JVM — the first visual coverage this project
  has had, and with no emulator the only way anyone sees what the UI looks like. The CI step,
  `/pre-merge` step 3 and the `settings.json` allowlist entries removed in issue 1.5 are restored.
  Proved by overwriting a baseline and watching `verifyPaparazziDebug` go red.
- **WCAG AA contrast asserted in a unit test** (partly closes **G-24**). Every token pair in both
  themes is computed against the 4.5:1 threshold, including amount colours on their own surfaces —
  turning "accessibility scan passes" from a claim in the DoD into arithmetic that runs on every
  build. The suite includes a deliberately failing pair so the formula itself is proved able to fail.

### Fixed
- **Amounts wrapped mid-number at 200% font** — `-₹2,450.00` broke with the final `0` on the next
  line, which reads as a different number. Caught by the new 200%-font screenshot on its first run;
  `CfoAmountText` is now `maxLines = 1, softWrap = false`, and `CfoListRow` lets the label wrap
  instead of the figure.

### Changed
- Paparazzi is pinned to **2.0.0-alpha02**: the 1.3.5 stable hooks a Gradle internal that moved and
  cannot run on Gradle 8.13. It is test-only tooling that ships in no APK; revisit when 2.0 is stable.
- `CfoHardcodedUiString` (issue 1.5) now covers `:core:designsystem` as well as `:feature:*`,
  closing the follow-up recorded in ADR-0001.
- `config/detekt/detekt.yml`: `MagicNumber` is excluded for `**/theme/**` and test sources. A design
  token file is the one place a literal is correct — that is what §21.6 means by "every colour from
  theme tokens" — and flagging it there would only teach contributors to suppress the rule.
- **Migration test harness — DB-003 enforced on every build, with no device** (issue 1.7; §21.5).
  Room's `MigrationTestHelper` needs hardware this project does not have, which would have left
  "destructive migrations are forbidden" enforced by nobody. But the exported schema JSON is data,
  and `androidx.room:room-migration` parses it in plain Kotlin — so `MigrationSafetyTest` now checks
  the structural half of DB-003 in ordinary unit tests: no table or column may be removed, no column
  may change SQL affinity, and no nullable column may become `NOT NULL` (which passes on an empty
  database and fails on real data). Additive changes stay allowed. It also asserts the schema-level
  money/time invariants where the data actually lives — `*_minor` and `*_utc_millis` are INTEGER,
  `*_iso_date` is TEXT, every table has soft delete and profile scoping — and that fixtures are
  contiguous from v1 and include the declared version, so a bump cannot skip its schema silently.
  The row-level half (`MigrationRoundTripTest`, device-only) and the per-version procedure are in
  `core/database/MIGRATIONS.md`.

  Proved by dropping a real column from `AccountEntity` and letting KSP export a genuine v2: the
  guard failed with *"migrating 1 -> 2 would destroy data: account: column 'current_balance_minor'
  was removed"*. Two things surfaced while doing that — a hand-edited schema JSON cannot fake a
  destructive change (KSP regenerates it), and the test task stayed `UP-TO-DATE` when only a schema
  file changed, so `schemas/` is now a declared test input; without that the guard could have
  reported a stale pass.
- **Encrypted persistence core** (issue 1.6; SRS §20/§23, SEC-003, DB-003, P-01/P-04): `:core:database`
  now holds `CfoDatabase` (Room v1) over **SQLCipher**, with the passphrase wrapped by a
  Keystore-backed Tink AEAD — a random passphrase is generated once and only its ciphertext touches
  disk, so the key that unwraps it never leaves the TEE. Base schema is the four tables the issue
  names — `profile`, `account`, `transactions`, `category` — each carrying the invariants that apply
  to every table: amounts as `Long` paise (MNY-001), instants as UTC epoch millis with user-picked
  dates as ISO strings (TIM-001/002), soft delete via `deleted_at_utc_millis`, and per-profile
  scoping on every row and every query. Schema exported to `core/database/schemas/` so issue 1.7's
  migration tests have a fixture. No `fallbackToDestructiveMigration` (DB-003) — with no server
  copy, a missing migration must fail loudly rather than drop tables. 12 unit tests cover the key
  path, including that an unwrap failure surfaces as an error rather than silently minting a new
  passphrase, which would present an unopenable database as an empty one.

### Known gaps
- **The encrypted round-trip is unproven.** SQLCipher and the Keystore exist only on a device, and
  this machine has none (`adb devices` empty, no AVD installed). `EncryptedDatabaseTest` — the
  ciphertext-on-disk check, the read-back and the reopen-with-the-same-key check — is written and
  compiles but **has never executed**. One `connectedDebugAndroidTest` run settles it.
- Database re-key (`PRAGMA rekey`) is intentionally not implemented: `rotateWithPrevious()` supplies
  both keys and is tested, but shipping an untested path that rewrites the whole encrypted file
  would be worse than the gap. It belongs with issue 11.1.
- **Custom lint: five rules that now fail the build** (issue 1.5 / task 1.1.5; SRS §21.3/§21.4/§21.6,
  MNY-001, TIM-001, ARC-006, P-01 — closes governance audit G-03). A new `:lint` module ships
  `CfoMoneyAsFloatingPoint` (a floating-point declaration with a monetary name), `CfoWallClockInDomain`
  (`System.currentTimeMillis()`/`now()` inside `:domain:*` or `:core:model`, with `:core:common`'s
  `SystemClock` exempt as the one sanctioned wall-clock read), `CfoGlobalScope`, `CfoHardcodedUiString`
  (a literal in a `:feature:*` `Text(...)`, `@Preview` exempt) and `CfoPiiInLogs` (a log line naming
  money or personal data). All at severity **error**, wired to every module — Android *and*
  pure-Kotlin, via the standalone `com.android.lint` plugin, because `Money` lives in a `java-library`
  module that lint would otherwise never visit — with **no baseline**, so nothing is grandfathered.
  Each rule was proved by seeding a real violation in a real module and watching the build go red,
  not by the fixture suite alone. 14 fixture tests cover every rule in both directions.
- **ADR-0001** — the repository's first architecture decision record: why `:lint` sits outside the
  §21.2 module graph, and the exact money/PII identifier lists with the false-positive stance behind
  them (partly closes audit G-12).

### Changed
- **`CLAUDE.md` now says "lint-enforced" because it is.** The `GlobalScope`, wall-clock,
  PII-logging and hardcoded-string entries named the rules as review-blocking with enforcement
  "landing in 1.1.5"; each now names the detector that blocks it.
- `config/detekt/detekt.yml`: `style.ReturnCount.excludeGuardClauses: true` — guard clauses are
  idiomatic Kotlin and the lint detectors are built from them; the rule's real target, tangled
  mid-function returns, still counts.

### Fixed
- Four `ExperimentalCoroutinesApi` opt-in warnings in `DispatcherProviderTest` (from issue 1.3),
  which earlier runs had missed because the compile task was up-to-date.
- **`Result<T, AppError>` error model** (issue 1.4 / task 1.1.4; SRS §21.6): the typed return every
  engine and repository will use, so no exception crosses a layer boundary and absence is modelled
  rather than nulled. `sealed interface Result` with `Ok`/`Err` and short-circuiting `map`,
  `flatMap`, `mapError`, `fold`, `getOrElse`, `getOrNull`, `errorOrNull`, `onOk`, `onErr` — a `when`
  over it is exhaustive with no `else`. `AppError` is a sealed hierarchy (`Validation`, `NotFound`,
  `Storage`, `Network(retryable)`, `Crypto`, `Unexpected`) carrying a stable `code` for the UI to
  map to `strings.xml` plus a non-localised fallback message. `runCatchingToResult { }` is the
  single sanctioned catch site: it converts I/O and crypto failures to `Err`, and deliberately
  rethrows `CancellationException` (swallowing it breaks structured concurrency, ARC-006),
  `IllegalState`/`IllegalArgumentException` (a failed `require`/`check` is a bug and §21.6 reserves
  crashes for those), and JVM `Error`s. **No PII by construction (P-01):** the only path from a
  `Throwable` to an `AppError` keeps the exception's class name and discards its message, which
  routinely carries paths, tokens or row data. 29 new tests; `:core:common` holds at 100% coverage.
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
