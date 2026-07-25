<!--
  Why:  Epic 1 built the foundations every later epic sits on, and most of what matters about it
        is not visible in the code — which gates are real, which are theatre, and which mistakes
        were already made and paid for. This document is what a new contributor (or a future
        session) needs to continue without re-deriving any of it.
  What: what shipped, what is proven, what is not, the traps found the hard way, and what to do next.
  Result: a reader can build, run, test and extend the project, and knows exactly how far to trust
        each green check.
  Changelog:
    2026-07-25 — Written on completion of Epic 1 (issues 1.1–1.10), at dev commit 3a0316e.
-->

# Epic 1 — Foundation & Core Platform: handoff

**Status:** complete (issues 1.1 – 1.10) · **Branch:** `dev` · **VERSION:** `0.1.0`
**Written:** 2026-07-25 · **Last verified commit:** `3a0316e`

---

## 1. Status at a glance

| | |
|---|---|
| Modules | **19** (`:app`, 7 × `:core:*`, 2 × `:domain:*`, `:data:repository`, 2 × `:ml:*`, 3 × `:feature:*`, `:sync:backup`, `:widget`, `:lint`) |
| Unit tests | **156** across 9 modules (excluding placeholder smoke tests) |
| Instrumentation tests | **5**, all passing on an emulator |
| Screenshot baselines | 3 (light, dark, 200% font) |
| Custom lint rules | 5, all severity **error**, no baseline |
| ADRs | 1 (`docs/adr/0001-…`) |
| **Never done** | **CI has never run** (no git remote). See §7. |

**Nothing in the app is a user-facing feature yet.** Epic 1 is foundations: the two screens that
exist render hardcoded placeholder figures. First real feature work is Epic 2.

---

## 2. Run it

### Emulator

An AVD named `CfoTest` (Pixel-5-ish, API 34, `google_apis` x86_64) already exists at
`~/.android/avd/CfoTest.avd`. It was created by hand because `avdmanager` is not installed —
`cmdline-tools` is absent from the SDK.

```bash
# Hardware GPU is unstable on this machine; software rendering is slower but survives.
%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe -avd CfoTest -gpu swiftshader_indirect

./gradlew installDebug
adb shell am start -n com.aicfo.personalcfo/com.aicfo.app.MainActivity
```

**The emulator dies if it is launched as a child of a shell that then exits.** Launch it detached
(`Start-Process` on Windows) or from Android Studio. Two runs were lost to this before it was
understood.

### Everything else

```bash
./gradlew build                       # all modules, debug + release
./gradlew test                        # JVM unit tests
./gradlew ktlintCheck detekt lint     # style + the 5 custom rules
./gradlew koverVerify                 # coverage floors
./gradlew verifyPaparazziDebug        # screenshot diff (JVM, no device)
./gradlew :core:database:connectedDebugAndroidTest   # needs a device
```

---

## 3. What each issue delivered

| Issue | Delivered | Lives in |
|---|---|---|
| **1.1** | Gradle multi-module skeleton, version catalog, `build-logic` convention plugins, the **ARC-002 guard** that fails the build if a pure-Kotlin module applies an Android plugin | `build-logic/`, `settings.gradle.kts` |
| **1.2** | `Money` — `Long` paise, `Math.*Exact` arithmetic, HALF_EVEN `percentOf(bps)`, largest-remainder `split`/`allocate` that sum exactly; `MoneyFormatter` with Indian 2,2,3 grouping | `:core:model` |
| **1.3** | `Clock` (profile time zone) + `SystemClock`, `DispatcherProvider`, and `FakeClock`/`TestDispatchers` published from **testFixtures** | `:core:common` |
| **1.4** | `Result<T, AppError>` sealed type with nine combinators, `AppError` hierarchy, and `runCatchingToResult` — the single sanctioned catch site | `:core:common` |
| **1.5** | Five custom lint detectors + `CfoIssueRegistry`, wired to every module | `:lint` |
| **1.6** | Encrypted Room over SQLCipher, Keystore-wrapped passphrase, 4-table base schema, exported schema fixture | `:core:database` |
| **1.7** | Migration harness — DB-003 checked on the **JVM** by diffing exported schemas, plus a device-side round-trip test | `:core:database`, `core/database/MIGRATIONS.md` |
| **1.8** | M3 theme + tokens, 5 components, 2 chart primitives, Paparazzi screenshots, WCAG contrast tests | `:core:designsystem` |
| **1.9** | Proto DataStore settings + the per-feature consent ledger (default off, revocable, timestamped) | `:core:datastore` |
| **1.10** | App shell, typed nav graph, Hilt DI graph, the ARC-004 reference screen, and the `Clock` ← settings wiring | `:app`, `:feature:dashboard` |

---

## 4. Architecture you need to know

**Dependency direction is one-way and enforced:** `feature → domain → data/core`.

- **`:core:model` and `:domain:*` are pure Kotlin/JVM.** Applying an Android plugin to one fails
  the build (`Arc002.kt`, TestKit-tested). This is what keeps engines portable and fast to test.
- **Feature modules never import each other.** Routes are `@Serializable` objects in `:app`
  (`CfoRoute`), which is the only module that knows more than one feature exists. Features expose
  plain composables and receive navigation as lambdas.
- **Repositories are the only DAO touchers** (ARC-005). None exist yet — Epic 2 adds the first.
- **One `UiState` per screen as `StateFlow`, events up via a sealed interface** (ARC-004).
  `DashboardUiState` / `DashboardEvent` / `DashboardViewModel` is the reference; copy its shape.

### The types everything else uses

| Type | Rule it encodes | Never do |
|---|---|---|
| `Money` (`Long` paise) | MNY-001 | Use `Double`/`Float` for an amount — lint fails the build |
| `Clock` (injected) | TIM-001 | Call `System.currentTimeMillis()` in `:domain`/`:core:model` — lint fails the build |
| `Result<T, AppError>` | §21.6 | Throw across a layer boundary; **or ignore a returned `Result`** (see §6) |
| `ConsentFeature` | P-01 | Assume absence means consent; read it once instead of collecting the Flow |

---

## 5. The enforcement layer — and how far to trust it

Every gate below was **proved to fail** before being trusted. That is a project habit, not a
formality: the governance audit found a coverage gate that passed at 0% coverage, and this codebase
has since produced three more gates that looked green while checking nothing.

| Gate | Catches | Proved by |
|---|---|---|
| ARC-002 guard | Android plugin on a pure-Kotlin module | TestKit test |
| `koverVerify` | coverage < 85% (100% on `:core:model`) | impossible bound → red; deleting a test → 77.5% |
| `CfoMoneyAsFloatingPoint` | `Double` with a monetary name | seeded a real violation in `:core:model` |
| `CfoWallClockInDomain` | wall clock in domain | seeded in `:domain:engines:forecast` |
| `CfoGlobalScope` | `GlobalScope` | seeded in `:core:common` |
| `CfoHardcodedUiString` | literal in a `:feature:*`/designsystem `Text(...)` | seeded in `:feature:dashboard` |
| `CfoPiiInLogs` | amounts/PII in logs | seeded in `:feature:dashboard` |
| `MigrationSafetyTest` | dropped/retyped column between schema versions | dropped a real column, let KSP export v2 → red |
| Paparazzi | visual regression | corrupted a baseline → red |
| `ColorContrastTest` | WCAG AA failure | includes a pair that must fail |

**Detekt config deviations** (`config/detekt/detekt.yml`), each deliberate: `LongMethod` at 40,
`ReturnCount.excludeGuardClauses`, `MagicNumber` excluded for `**/theme/**` (a token file is the one
place a literal is correct).

---

## 6. Traps found the hard way

Read this section before writing code. Each cost real time and would cost it again.

1. **An ignored `Result` is an invisible failure.** Six `:core:datastore` tests reported green while
   every write was erroring, because the tests never asserted the returned `Result`. If a function
   returns `Result`, **assert it** — see `WriteAssertions.assertWritten`.
2. **A hand-edited exported schema cannot fake a migration.** KSP regenerates
   `core/database/schemas/*.json` on build. To test a destructive change, change the entity.
3. **Gradle `UP-TO-DATE` can hide a stale pass.** The migration guard reported green after a schema
   edit because `schemas/` was not a declared test input. It is now — check inputs when a test
   depends on a non-source file.
4. **Windows cannot rename onto an existing file.** This broke DataStore's default storage (fixed by
   `OkioStorage`) and would have broken the passphrase store (handled there explicitly). Android is
   unaffected — but your local tests will lie to you.
5. **Dagger does not see Kotlin default arguments.** A defaulted lambda in an `@Inject` constructor
   becomes a missing `Function0<…>` binding.
6. **There was a real DI cycle:** `Clock → ProfileZoneProvider → SettingsStore → CfoDataStores →
   Clock`. Cut with `Provider<SettingsStore>`, because the zone provider does not need the store
   until `start()`.
7. **A Kotlin `"literal"` is a string *template* in UAST**, not a `ULiteralExpression`. Lint
   detectors must use `ConstantEvaluator`.
8. **`Log.d`'s qualifier moves** between the call receiver and the parent expression depending on
   whether `android.util.Log` resolves. A detector matching one shape passed its fixture and missed
   the real module.
9. **Lint fixture files outside `src/` are not analysed at all**, so path-only module scoping passed
   every test while matching nothing. Detectors now check path **or** package.
10. **`androidx.test.ext:junit` does not bring `AndroidJUnitRunner`.** Without
    `androidx.test:runner` the suite reports "Starting 0 tests" — a failure that looks like a pass.
11. **Paparazzi 1.3.5 cannot run on Gradle 8.13** (it hooks a moved Gradle internal). Pinned to
    `2.0.0-alpha02`.
12. **Windows build flakes are real:** locked `classes.jar`, and a stale-state lint
    `FileNotFoundException` in `:app`. Both clear on a clean re-run — do not chase them as code bugs.

---

## 7. Verified vs unverified — the honest ledger

**Verified on a device (2026-07-25):** the database file on disk is genuinely ciphertext, amounts
round-trip exactly, the Keystore passphrase is reused across opens, soft-deleted rows are hidden but
retained, and the committed schema matches what Room builds. Also verified by hand: the app launches,
navigates, themes dark, and survives a 200% font setting without wrapping an amount.

**Never verified:**

- **CI has never run.** The repository has no git remote, so `.github/workflows/ci.yml` — including
  the Paparazzi step re-enabled in 1.8 — has never executed. Every "green" in this project is a
  local green on one Windows machine. **This is the single largest untested surface.**
- **No release build has been exercised beyond `assembleRelease`.** R8 is off until issue 11.6.
- **Database re-key (`PRAGMA rekey`) is not implemented.** `rotateWithPrevious()` supplies both keys
  and is tested; the re-key itself belongs with issue 11.1.
- **No automated accessibility scan** on a real screen (audit G-24, partly closed).

---

## 8. Open debt

**From the governance audit** (`docs/report/2026-07-25-governance-standards-audit.md`) — closed:
G-01, G-02, G-03, G-04, G-20, G-23; partly: G-12, G-24. **Still open, highest value first:**

| Item | Why it matters |
|---|---|
| **Add a git remote and run CI** | Everything above is unverified off this machine |
| G-05 / G-06 | No dependency-update automation; actions pinned by mutable tag |
| G-09 / G-10 | No local hooks; Conventional Commits mandated but never validated |
| G-11 | `FR-*`/`SEC-*` IDs exist only inside a binary PDF — cited IDs cannot be validated |
| G-21 | No root `README`, `LICENSE` or `CONTRIBUTING` |
| G-17 | `gen_issue_docs.py` corrupts `"ADRs"` → `"AD₹"` in 7 files |
| G-18 / G-19 / G-22 | Stale docs: `memory.md`, the 6-epic table, meaningless issue `Status` fields |

**Code-level:**

- Ten modules still hold `ModulePlaceholder.kt`: `core/crypto`, `core/network`, `data/repository`,
  `domain/engines/forecast`, `domain/usecase`, `feature/onboarding`, `ml/llm`, `ml/ocr`,
  `sync/backup`, `widget`. Delete each when its issue lands.
- Dashboard figures are hardcoded; issues 5.1/5.2 replace them without changing `DashboardUiState`.
- `₹` is a Kotlin constant in `MoneyFormatter`, not a string resource — a pure-Kotlin module cannot
  use `strings.xml`. Move it to the UI layer if a second currency ever appears.
- `ProfileZoneProvider` closes the 1.3 seam but nothing yet **writes** the zone setting; onboarding
  (2.1) should.

---

## 9. Starting Epic 2

Epic 2 is onboarding, accounts and net worth. The order that minimises rework:

1. **Add a git remote and let CI run** before writing new code. If CI is red, everything after this
   is built on an unverified base.
2. **Issue 2.1 (onboarding)** — the first screen that *writes*: profile, time zone (which activates
   `ProfileZoneProvider`), and currency. Copy the `DashboardViewModel` triad exactly.
3. **Issue 2.5 (accounts CRUD)** — the first `:data:repository` code, and therefore the first place
   ARC-005 is real. It will need `AccountEntity` columns beyond the base four; that is a **new
   schema version** — follow `core/database/MIGRATIONS.md` exactly, and expect
   `MigrationSafetyTest` to fail until the fixture is committed.
4. **Issue 2.6 (net worth)** — the first real engine, and the first `:domain:*` code the 85%
   coverage floor will actually measure.

**Before every merge:** run `/pre-merge`. It is honest about what it cannot check.

---

## 10. Where decisions are recorded

- **Binding rules:** `CLAUDE.md` (now accurate about which bans are lint-enforced).
- **Per-issue trackers:** `docs/issues/1.*-tracker.md` — each has a Verification Log with one row per
  command actually run, including the failures. Those logs are the real history; the code shows what
  was built, the trackers show what was proved.
- **ADR-0001:** why `:lint` sits outside the §21.2 module graph, and the money/PII heuristic lists.
- **`core/database/MIGRATIONS.md`:** the per-version migration procedure.
- **`docs/Design.md`:** the token rationale (the code is now the source of truth).
