# Project Structure — AI Personal CFO

> **What this is:** a detailed, file-level map of the repository — what every directory and
> key file is for, how the Gradle multi-module build fits together, and where new code goes.
> **Sources of truth remain** `docs/init/AI_Personal_CFO_SRS_v1.7.pdf` (the SRS), the design
> spec (`docs/superpowers/specs/2026-07-17-ai-personal-cfo-design.md`), and `CLAUDE.md` (binding
> rules). This document is a navigational view of them — when in doubt, those win.
>
> The module graph was stood up by **issue 1.1** (`docs/issues/1.1-gradle-multi-module-skeleton-version-catalog-ci.md`).
> Requirement IDs cited here (ARC-xxx, P-xx, MNY/TIM) are defined in `CLAUDE.md §1–§5` and the SRS.

---

## 1. The mental model — two halves of one repo

```
AI_personal_cfo/
├─ ai/  docs/  .claude/  .github/  scripts/  config/   ← "ABOUT the app": spec, plans, AI data, tooling
└─ settings.gradle.kts + build-logic/ + <modules>/     ← "THE app": the Gradle multi-module build
```

- The **left half** is the blueprint and the machinery that maintains it: the SRS, the issue
  backlog, Architecture Decision Records, the AI subsystem's runtime data files, and the
  agent/CI tooling. None of it is compiled into the app.
- The **right half** is the actual Android application, built to that blueprint. This is what
  `./gradlew` compiles.
- `CLAUDE.md` at the root is the **contract** binding the two: the rules any change must obey.

---

## 2. Full annotated tree

```
AI_personal_cfo/
│
├─ CLAUDE.md                     Binding rules for all code (loaded every session)
├─ VERSION                       SemVer single source of truth (currently 0.1.0)
├─ CHANGELOG.md                  Keep-a-Changelog history; every entry cites requirement IDs
├─ README-less root docs …
│
├─ settings.gradle.kts           THE MAP — declares the §21.2 module graph + repositories
├─ build.gradle.kts              Root build — pins plugin versions (apply false); applies nothing
├─ gradle.properties             JVM args, parallel/caching, AndroidX, Kotlin code style
├─ gradlew / gradlew.bat         Gradle wrapper launchers (pin Gradle to 8.13)
├─ local.properties              Machine-local sdk.dir — GIT-IGNORED, never committed
│
├─ gradle/
│  ├─ libs.versions.toml         VERSION CATALOG — the one place versions live (§21.3 stack)
│  └─ wrapper/
│     ├─ gradle-wrapper.jar      Committed on purpose (bootstraps the exact Gradle)
│     └─ gradle-wrapper.properties
│
├─ build-logic/                  Composite build holding the reusable "convention" plugins
│  ├─ settings.gradle.kts        Re-reads the same version catalog for the plugins
│  └─ convention/
│     ├─ build.gradle.kts        Compiles the plugins; registers the cfo.* plugin ids
│     └─ src/
│        ├─ main/kotlin/         The plugins + shared config (see §4)
│        └─ test/kotlin/         ARC-002 unit + TestKit tests
│
├─ app/                          :app — the ONE application module (Compose host + Hilt root)
│  ├─ build.gradle.kts
│  └─ src/main/{AndroidManifest.xml, kotlin/…, res/values/…}
│
├─ core/                         :core:* — portable primitives + Android infrastructure
│  ├─ model/                     :core:model      pure Kotlin/JVM  (Money, Result, provenance)
│  ├─ common/                    :core:common     pure Kotlin/JVM  (Clock, DispatcherProvider)
│  ├─ database/                  :core:database   Room + SQLCipher
│  ├─ datastore/                 :core:datastore  Proto DataStore (settings + consent ledger)
│  ├─ network/                   :core:network    Retrofit/OkHttp edge (offline-first)
│  ├─ crypto/                    :core:crypto     Tink / Keystore key management
│  └─ designsystem/              :core:designsystem  M3 tokens + Compose components
│
├─ domain/                       :domain:* — pure-Kotlin business logic (no Android)
│  ├─ engines/forecast/          :domain:engines:forecast  representative engine
│  └─ usecase/                   :domain:usecase           orchestrates engines for features
│
├─ data/
│  └─ repository/                :data:repository  the ONLY DAO/network toucher (ARC-005)
│
├─ ml/                           :ml:* — on-device ML behind interfaces
│  ├─ ocr/                       :ml:ocr   ML Kit Text Recognition v2 (receipts)
│  └─ llm/                       :ml:llm   on-device LLM behind LlmEngine (verbalises only)
│
├─ feature/                      :feature:* — Compose screens + ViewModels (never depend on each other)
│  ├─ dashboard/                 :feature:dashboard
│  ├─ onboarding/                :feature:onboarding
│  └─ transactions/              :feature:transactions
│
├─ sync/
│  └─ backup/                    :sync:backup   end-to-end-encrypted backup/restore
│
├─ widget/                       :widget        Glance home-screen widget
│
├─ config/
│  └─ detekt/detekt.yml          Detekt overlay (Compose naming exemption)
│
├─ ai/                           RUNTIME DATA the app LOADS (not app code) — see ai/README.md
│  ├─ architecture/  chat/  knowledge/  orchestrator/  rules/  skills/
│
├─ docs/                         Blueprint + process
│  ├─ init/AI_Personal_CFO_SRS_v1.7.pdf   the master spec (source of truth)
│  ├─ superpowers/specs/                  the planning-grade design spec + issues CSV
│  ├─ issues/                             85-issue backlog + per-issue trackers
│  ├─ features/                           deeper sub-task breakdowns (e.g. 1.1 skeleton)
│  ├─ adr/                                Architecture Decision Records
│  ├─ templates/                          ENGINE.md + other doc templates
│  ├─ Architecture.md  Design.md  PRD.md  Rules.md   thin cross-referenced views
│  └─ ProjectStructure.md                 ← this file
│
├─ .claude/                      Agent tooling: skills/ + commands/ (/pre-merge, new-engine, …)
├─ .github/                      workflows/ci.yml + pull_request_template.md
├─ scripts/                      gen_issue_docs.py — generates the issue backlog
│
├─ .editorconfig                 ktlint/format baseline (120 cols, official style)
├─ .gitignore                    ignores build/, .gradle/, local.properties, *.apk …
└─ .gitattributes                line-ending normalisation
```

---

## 3. Root control files (the build entry points)

Gradle reads these in a fixed order; understanding the order explains the whole system.

| File | Role | Why it exists this way |
|------|------|------------------------|
| `settings.gradle.kts` | Declares every module via `include(...)`, includes `build-logic`, and sets the repositories. **Read first.** | One authoritative list of modules; `RepositoriesMode.FAIL_ON_PROJECT_REPOS` forbids a module from declaring its own repos. |
| `build.gradle.kts` (root) | Lists the plugins with `apply false`. Applies nothing to the root itself. | Fixes one version per plugin for the whole build; convention plugins then apply them. |
| `gradle/libs.versions.toml` | The **version catalog**: `[versions]`, `[libraries]`, `[plugins]`. | The §21.3 stack is pinned in exactly one place. Modules reference `libs.…`; no hardcoded versions (changing the stack needs an ADR). |
| `gradle.properties` | Heap (`-Xmx4g`), `org.gradle.parallel/caching`, `android.useAndroidX=true`, `kotlin.code.style=official`. | Reproducible builds for every agent/human and CI. |
| `gradlew`/`gradlew.bat` + `gradle/wrapper/*` | The **wrapper** — pins Gradle to **8.13**. The `.jar` is committed. | Everyone builds with the identical Gradle; no "works on my machine". |
| `local.properties` | This machine's `sdk.dir`. | Machine-specific → **git-ignored**. AGP also falls back to `ANDROID_HOME`. |

### The pinned matrix (in `libs.versions.toml`)

| Tool | Version | Notes |
|------|---------|-------|
| Gradle | 8.13 | via the wrapper |
| Android Gradle Plugin | 8.11.1 | |
| Kotlin | 2.1.20 | Compose compiler is the Kotlin plugin |
| KSP | 2.1.20-2.0.1 | annotation processing (Hilt/Room) |
| compileSdk / targetSdk | 36 | |
| minSdk | 26 | product decision — revisitable |
| Hilt | 2.56.2 · Room 2.7.1 · Compose BOM 2025.06.01 · Retrofit 2.11 · Tink 1.17 · … | full stack in the catalog |

---

## 4. `build-logic/` — the convention plugins (deep)

`build-logic/` is a **separate Gradle build** pulled in by `settings.gradle.kts` via
`includeBuild("build-logic")`. Its sole output is a set of reusable plugins so that the 18
module build files stay ~4 lines each and shared policy is defined once (§21.6).

```
build-logic/convention/src/main/kotlin/
│
├─ ProjectExtensions.kt   Shared helpers, used by every plugin:
│                           • `Project.libs`      — reads the version catalog from a compiled plugin
│                           • `intVersion(alias)`  — pulls e.g. compileSdk as an Int
│                           • `configureQuality()` — applies ktlint + detekt + Kover
│                           • `enforceNoAndroidPlugins()` — wires the ARC-002 guard (afterEvaluate)
│
├─ KotlinConfig.kt        `configureKotlinAndroid()` / `configureKotlinJvm()`:
│                           compileSdk/minSdk + Java/Kotlin target 17 (bytecode 17, runs on any JDK ≥ 17)
│
├─ AndroidCompose.kt      `configureAndroidCompose()`: turns on buildFeatures.compose and adds the
│                           BOM-managed Compose deps (ui, material3, tooling, test)
│
├─ Arc002.kt             PURE decision logic (no Gradle types): FORBIDDEN_PLUGIN_IDS +
│                           `violationMessage()`. Kept pure so it is unit-testable (P-08).
│
├─ CfoKotlinLibraryConventionPlugin.kt       id "cfo.kotlin.library"     (pure Kotlin/JVM + ARC-002 guard)
├─ CfoAndroidLibraryConventionPlugin.kt      id "cfo.android.library"    (Android library)
├─ CfoAndroidApplicationConventionPlugin.kt  id "cfo.android.application"(the app; adds targetSdk/versionName)
├─ CfoAndroidComposeConventionPlugin.kt      id "cfo.android.compose"    (adds Compose to an Android module)
├─ CfoAndroidFeatureConventionPlugin.kt      id "cfo.android.feature"    (library + compose + hilt + nav)
└─ CfoHiltConventionPlugin.kt                id "cfo.hilt"               (KSP + Hilt)

build-logic/convention/src/test/kotlin/
├─ Arc002Test.kt         Fast unit test of the decision logic (no build launched)
└─ Arc002GuardTest.kt    Gradle TestKit test: generates a throwaway build that applies an Android
                         plugin to a Kotlin module and asserts it fails with an "ARC-002" message (AC3)
```

**Why this indirection pays off:** a feature module's entire build file is
`plugins { alias(libs.plugins.cfo.android.feature) }` + its downward `dependencies { … }`.
JVM level, ktlint, detekt, Kover, Compose wiring, and the ARC-002 guard are all inherited.

---

## 5. Module archetypes & the dependency rule

There are **five archetypes**, each backed by one convention plugin:

| Archetype | Convention plugin | Android? | Modules |
|-----------|-------------------|----------|---------|
| Pure Kotlin/JVM | `cfo.kotlin.library` | **No** | `:core:model`, `:core:common`, `:domain:engines:forecast`, `:domain:usecase` |
| Android library | `cfo.android.library` | Yes | `:core:{database,datastore,network,crypto}`, `:data:repository`, `:ml:{ocr,llm}`, `:sync:backup`, `:widget` |
| Android + Compose | `cfo.android.library` + `cfo.android.compose` | Yes | `:core:designsystem` |
| Feature | `cfo.android.feature` | Yes | `:feature:{dashboard,onboarding,transactions}` |
| Application | `cfo.android.application` + compose + hilt | Yes | `:app` |

### The one hard rule — dependencies flow one way (ARC-001)

```
:feature:*  ──►  :domain:*  ──►  :core:* / :data:*
   (UI)          (engines)         (primitives, storage)
```

- A `:feature:*` **never** depends on another `:feature:*`. Cross-feature navigation goes through
  `:app`'s typed nav graph (issue 1.10).
- `:core:model` and `:domain:*` are **pure Kotlin — no Android imports** (ARC-002). This keeps the
  financial engines portable (KMP-ready) and fast to unit-test.
- **Enforced, not aspirational:** `cfo.kotlin.library` throws a clear *ARC-002 violation* if an
  Android plugin is ever applied to one of those modules — proven by `Arc002GuardTest`.
- Repositories (`:data:*`) are the **only** classes that touch DAOs or the network (ARC-005).

### Per-module dependency edges (current)

| Module | Depends on |
|--------|-----------|
| `:core:model`, `:core:common`, `:core:crypto`, `:core:designsystem` | — (leaves) |
| `:core:database`, `:core:datastore`, `:core:network`, `:ml:ocr`, `:ml:llm` | `:core:model` |
| `:domain:engines:forecast` | `:core:model`, `:core:common` |
| `:domain:usecase` | `:core:model`, `:core:common`, `:domain:engines:forecast` |
| `:data:repository` | `:core:model`, `:core:common`, `:core:database`, `:domain:usecase` |
| `:sync:backup` | `:core:model`, `:core:crypto`, `:core:database` |
| `:widget` | `:core:model`, `:domain:usecase` |
| `:feature:dashboard`, `:feature:transactions` | `:core:model`, `:core:designsystem`, `:domain:usecase` |
| `:feature:onboarding` | `:core:designsystem` |
| `:app` | `:core:designsystem`, `:feature:{dashboard,onboarding,transactions}` + androidx |

---

## 6. Inside a module — source sets & naming

Every module has the identical internal layout, and the path/name/package line up:

```
core/model/                              ← directory
├─ build.gradle.kts                      ← applies one convention plugin + downward deps
└─ src/
   ├─ main/kotlin/com/aicfo/core/model/  ← production code   (package = com.aicfo.core.model)
   └─ test/kotlin/com/aicfo/core/model/  ← unit tests
```

- **Directory `core/model` = Gradle path `:core:model` = package `com.aicfo.core.model`.** This
  predictability is deliberate.
- Every module currently ships a `ModulePlaceholder` object + a matching `ModulePlaceholderTest`
  — compiling scaffolding so the graph is green with a live test source set until real types land.
- Two modules are already "real": `:core:designsystem` has a `CfoPlaceholder` `@Composable` (proves
  the Compose toolchain compiles); `:app` is a working single-Activity app.

### `:app` in detail

```
app/src/main/
├─ AndroidManifest.xml               declares the Hilt app + single launcher activity; allowBackup=false
├─ kotlin/com/aicfo/app/
│  ├─ CfoApplication.kt              @HiltAndroidApp — the DI root
│  └─ MainActivity.kt                @AndroidEntryPoint Compose host (setContent)
└─ res/values/{strings,themes}.xml   externalised strings + base M3 theme
```

`:app` is the only place that knows about all features at once — the assembly point.

---

## 7. How a build flows

1. `./gradlew build` → the wrapper launches **Gradle 8.13**.
2. Gradle reads `settings.gradle.kts` → `includeBuild("build-logic")` → **compiles the convention plugins first**.
3. For each module it applies the named `cfo.*` plugin, which pulls versions from `libs.versions.toml`.
4. Pure-Kotlin modules get the **ARC-002 guard** installed.
5. Tasks run: compile → `ktlintCheck` → `detekt` (config `config/detekt/detekt.yml`) → `koverVerify`
   → Android `lint` → unit tests → `assembleDebug`.
6. Useful entry points: `./gradlew projects` (list the graph), `./gradlew :app:assembleDebug`
   (build the APK), `./gradlew -p build-logic :convention:test` (run the ARC-002 tests).

---

## 8. CI — `.github/workflows/ci.yml`

Runs on every push/PR to `main`, `stage`, `dev`. Steps: validate `ai/**` JSON → set up JDK 17 +
Android SDK → **convention/ARC-002 tests** → `ktlintCheck detekt lintDebug` → `testDebugUnitTest
koverVerify` → `assembleDebug` → upload reports. (Paparazzi screenshot diff and the OSV dependency
scan are stubbed until issues 1.8 and 11.6 wire their tasks.) A red pipeline blocks the merge.

---

## 9. The non-code halves

- **`ai/`** — runtime data the app *loads* (rulebook, knowledge bases, orchestrator workflow, chat
  guardrail). Changing the AI's behaviour means editing these rows, **not** hardcoding numbers in an
  engine (`CLAUDE.md §6`). See `ai/README.md`.
- **`docs/`** — the SRS (source of truth), the design spec, the 85-issue backlog + trackers, ADRs,
  templates, and the thin cross-referenced views (`Architecture.md`, `Design.md`, `PRD.md`, `Rules.md`).
- **`.claude/`** — project skills (`new-engine`, `add-rulebook-rule`, `money-time-audit`) and
  slash-command workflows (`/new-feature`, `/pre-merge`).
- **`.github/`** — CI + PR template. **`scripts/`** — the Python issue-doc generator. **`config/`** — tool config.

---

## 10. Where new code goes (quick guide)

| You want to… | Do this |
|--------------|---------|
| Add a deterministic engine | New `:domain:engines:<name>` (pure Kotlin, `cfo.kotlin.library`); interface + result types; golden/property tests. Use the `new-engine` skill. |
| Add a screen | New `:feature:<name>` (`cfo.android.feature`); one immutable `UiState` as `StateFlow` (ARC-004); register the route in `:app`'s nav graph. |
| Add a dependency | Add it to `gradle/libs.versions.toml`, reference `libs.…` in the module. Never hardcode a version. |
| Store/read data | Add a DAO/entity in `:core:database` (Room, migration test) or a setting/consent in `:core:datastore`; expose it via `:data:repository` only. |
| Change an AI threshold or rule | Edit the relevant row in `ai/` (use `add-rulebook-rule`). Never hardcode a financial number in code (P-03, `CLAUDE.md §6`). |
| Register a brand-new module | Add `include(":group:name")` to `settings.gradle.kts`; create `build.gradle.kts` applying the right `cfo.*` plugin; deps point **downward only**. |

---

## 11. Conventions cheat-sheet

- **Money** is `Long` paise via a `Money` value class — never `Double`/`Float` (MNY-001).
- **Rates/percentages** are integer basis points (MNY-002).
- **Time**: UTC epoch millis; calendar logic uses an injected `Clock`, never `System.currentTimeMillis()`
  in domain code (TIM-001). Date-only fields are ISO `LocalDate` strings (TIM-002).
- **State** flows down as one immutable `data class`; events flow up via a sealed interface (ARC-004).
- **Errors** don't cross layers as exceptions — engines/repos return `Result<T, AppError>`.
- **Every class and function** gets a doc comment (why / what / result / changelog / inputs / outputs).
- **Every user-visible string** lives in `strings.xml`; every colour/dimension from theme tokens.
- **Offline-first** (P-04): every core feature works in airplane mode; no network on a core path (P-01).

---

_Last updated: 2026-07-19 (issue 1.1). Keep this file in sync when the module graph changes._
