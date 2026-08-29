// =============================================================================
// settings.gradle.kts — declares the §21.2 module graph and repositories.
//
// Why:  Issue 1.1 / ARC-001 require the one-way module graph (feature → domain →
//       data/core) to be real and buildable; this file is where every module is
//       registered and where the build-logic convention plugins are included.
// What: pluginManagement (includes ./build-logic), dependencyResolutionManagement
//       (single repo set + the version catalog), and the full include(...) list.
// Result: `./gradlew projects` lists the complete §21.2 graph.
// Changelog:
//   2026-07-19 — Created for issue 1.1 (Gradle multi-module skeleton).
// =============================================================================

@file:Suppress("UnstableApiUsage")

pluginManagement {
    // Convention plugins live in an isolated composite build so the module scripts
    // stay tiny and consistent (see build-logic/).
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Modules must not declare their own repositories; fail the build if they try.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ai-personal-cfo"

// --- §21.2 module graph -------------------------------------------------------
// :app                     single-activity Compose host, nav graph, Hilt graph
include(":app")

// :core:*                  portable primitives + Android infra
include(":core:model")          // pure Kotlin/JVM — Money, Result, provenance
include(":core:common")         // pure Kotlin/JVM — Clock, DispatcherProvider
include(":core:database")       // Room + SQLCipher, DAOs, migrations
include(":core:datastore")      // Proto DataStore — settings + consent ledger
include(":core:network")        // Retrofit/OkHttp edge (offline-first)
include(":core:crypto")         // Tink / Keystore key management
include(":core:designsystem")   // M3 theme tokens + Compose components

// :domain:*                pure-Kotlin engines + use cases (ARC-002/003)
include(":domain:engines:forecast") // representative engine stub
include(":domain:engines:quicksetup") // FR-ONB-002 seeds -> budget envelopes + emergency target
include(":domain:engines:networth")    // FR-ACC-005 assets - liabilities + the daily snapshot
include(":domain:engines:recurring")   // FR-TXN-006 proposes recurring series from the ledger
include(":domain:engines:receipt")     // FR-OCR-003 reads total/date/merchant/GST off recognised text
include(":domain:engines:sms")         // §18/§23 reads amount/direction/payee off an opt-in bank alert
include(":domain:engines:classification") // §8.1 Stage 1 — merchant → category, with confidence + evidence
include(":domain:engines:nature")      // §8.3 — what a rupee became: Need/Want/Invest/Asset/Liability
include(":domain:engines:budget")      // §5.5 — FR-BUD-002/003 suggested budgets + spent/pace/projection
include(":domain:engines:safetospend") // §5.2/§14 AI-STS — what is left to spend this month, and why
include(":domain:engines:card")        // §5.7 FR-ACC-002 — a card's billing cycle, utilisation and reminders
include(":domain:engines:loan")        // §5.8 FR-ACC-003 — a loan's EMI and its principal/interest split
include(":domain:engines:investment") // §11 AI-INV — a holding's value, gain and money-weighted return
include(":domain:usecase")

// :data:*                  the ONLY DAO/network touchers (ARC-005)
include(":data:repository")
include(":data:sms")            // the phone's inbox, behind READ_SMS + an explicit consent (3.9)

// :ml:*                    on-device ML behind interfaces
include(":ml:ocr")              // ML Kit Text Recognition v2
include(":ml:llm")              // on-device LLM behind LlmEngine

// :feature:*               Compose screens + ViewModels (never depend on each other)
include(":feature:accounts")    // FR-ACC-001/007 — CRUD for all eleven account types
include(":feature:budgets")     // FR-BUD-001/002/003 — per-category budgets and suggestions (4.4)
include(":feature:categories")  // FR-SET-001 — the category taxonomy editor (issue 4.1)
include(":feature:dashboard")
include(":feature:settings")     // FR-SET-001 — income, consents and the app lock, after onboarding
include(":feature:onboarding")
include(":feature:transactions")

// :sync:*                  E2EE backup/restore
include(":sync:backup")

// :widget                  Glance home-screen widget
include(":widget")

// :lint                    custom Android Lint detectors that make MNY-001 / TIM-001 / ARC-006 and
//                          the strings + PII-logging bans fail the build (issue 1.5). Build tooling,
//                          not a product module — it ships in no APK. Outside the §21.2 graph, so
//                          see docs/adr/0001-custom-lint-module-and-money-heuristic.md.
include(":lint")
