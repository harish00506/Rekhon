// =============================================================================
// Root build script — declares the pinned plugins (apply false) so every module
// resolves the same versions from the catalog, and applies repo-wide quality gates.
//
// Why:  Issue 1.1 / §21.6 — one place fixes the plugin versions; convention plugins
//       in build-logic then apply them to each module. Nothing is applied at the root
//       except tasks that must run across the whole build.
// What: `plugins { … apply false }` for the §21.3 stack.
// Result: Subprojects and the build-logic composite share one version matrix.
// Changelog:
//   2026-07-19 — Created for issue 1.1 (Gradle multi-module skeleton).
// =============================================================================

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover) apply false
    // Applied, not `apply false`: the scanner plugin must be on the root project so it
    // can walk every subproject's source sets and classpaths (see the `sonar` block below).
    alias(libs.plugins.sonarqube)
}

/**
 * `unitTests` — every module's unit tests, in one command (issue 2.6).
 *
 * Why:  the workflow, CLAUDE.md and every issue template told a developer to run
 *       `./gradlew testDebugUnitTest`, and that command **silently skips whole modules**. It is an
 *       Android variant task, so it does not exist on the pure-Kotlin ones — `:core:model` (Money),
 *       `:core:common` (Clock, Result) and every `:domain:engines:*` use `test` instead. Locally
 *       that leaves stale results on disk and reports a green that covered less than it looked like.
 *
 *       **And `:lint` was reachable from nothing at all.** Its fourteen tests cover the five custom
 *       detectors that make MNY-001, TIM-001, ARC-006, the PII-logging ban and the hardcoded-string
 *       ban fail the build. Neither `testDebugUnitTest` nor `koverVerify` reaches it, and CI ran
 *       only those two — so the enforcement layer's own tests had never run in CI. A detector could
 *       have broken and every gate would have stayed green while the rules quietly stopped being
 *       enforced. That is the same shape as audit G-01's vacuous coverage gate.
 *       **And the screenshot baselines were verified by nothing.** Paparazzi only compares against
 *       its recorded PNGs when `verifyPaparazziDebug` is in the task graph; a plain
 *       `testDebugUnitTest` renders and asserts nothing. That task lived only in `/pre-merge`, which
 *       is a checklist a human runs, so a UI change could — and did — ship against a stale baseline:
 *       issue 5.4 recorded the dashboard's empty state, the Settings screen added a button to it,
 *       and every gate stayed green for a commit while the recorded image no longer showed the app.
 *       Screenshot tests are the DoD's evidence for dark mode and 200% font (§4.2), so a baseline
 *       nothing compares against is the same vacuous gate in a different costume.
 * What: depends on all three unit-test task names across every subproject, so a module cannot be
 *       missed by having the "wrong" kind of build script.
 * Result: one command that genuinely means "run the unit tests".
 * Changelog: 2026-08-02 — Created for issue 2.6, after `testDebugUnitTest` reported a stale failure.
 *            2026-08-29 — Issue 6.5: added `verifyPaparazziDebug`, after a stale dashboard baseline
 *            survived a merge. Paparazzi reuses the same test task, so this enables verification
 *            rather than running the suite twice.
 *
 * Deliberately matched by **name** rather than by listing modules: a module added later is picked up
 * without anyone remembering to edit this, which is precisely the failure being fixed.
 */
tasks.register("unitTests") {
    group = "verification"
    description =
        "Runs every module's unit tests — Android variants, pure-Kotlin modules, :lint, " +
            "the Paparazzi baselines, and the Python tooling's own tests."
    dependsOn(
        subprojects.mapNotNull { module ->
            module.tasks.matching {
                // `jvmTest` is issue 13.7's KMP spike (:spike:kmp), whose tests are named by target
                // rather than `test` — so without it they would be tests nothing runs, which is the
                // defect this very comment warns about. Its **Kotlin/Native** half is deliberately
                // NOT here: `linuxX64Test` needs ~1GB of toolchain in ~/.konan, which CI does not
                // cache, so it is run on demand (ADR-0075). The JVM half — the Money-equivalence
                // check and the portability audit — gates every build.
                it.name == "testDebugUnitTest" || it.name == "test" ||
                    it.name == "verifyPaparazziDebug" || it.name == "jvmTest"
            }
        },
    )
    // Issue 11.6: `scripts/osv_scan.py` decides whether a build is blocked, so its policy has tests
    // — and they are wired in here rather than left to be remembered, because a test nothing runs is
    // this project's recurring defect. No network: these are pure functions over fixture data.
    dependsOn("scriptTests")
}

/**
 * Keeps the BouncyCastle family on one version in every module (issue 8.1; ADR-0039).
 *
 * Why:  `:core:crypto` takes `bcprov` for Argon2id, and Robolectric brings its own `bcpkix` and
 *       `bcutil` at an older release. Gradle upgrades the shared `bcprov` to ours and leaves the other
 *       two where they were, and the mixed jars break `BouncyCastleProvider`'s static setup — which
 *       Robolectric runs in every Compose test. Fifteen dashboard tests failed with
 *       `NoClassDefFoundError ... compositesignatures.KeyFactorySpi` the first time this was bumped.
 *       The three artifacts ship as one release and must resolve as one.
 * What: every `org.bouncycastle:*-jdk18on` request in every subproject configuration (bar `:lint`) resolves to the
 *       catalog's `bouncycastle` version.
 * Result: one BouncyCastle version on every classpath, test ones included.
 * Changelog: 2026-09-18 — Created for issue 8.1.
 */
val bouncyCastleVersion = libs.versions.bouncycastle.get()
subprojects {
    // `:lint` runs Android Lint's own classpath, which must stay exactly as AGP ships it.
    if (path == ":lint") return@subprojects
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.bouncycastle" && requested.name.endsWith("-jdk18on")) {
                useVersion(bouncyCastleVersion)
                because("the BouncyCastle family ships as one release (issue 8.1, ADR-0039)")
            }
        }
    }
}

/**
 * `restoreDrill` — the backup restore drill, the release-gate step (issue 8.3; §21.5, DRL-001).
 *
 * Why:  one memorable name for the step `docs/issues/00-issue-workflow.md` requires before every
 *       promotion to `stage` or `main`, and the one CI's `restore-drill` job runs. Without it the
 *       step would be a long module-task path somebody has to remember exactly.
 * What: `:data:repository:connectedDebugAndroidTest` — `BackupRestoreDrillDeviceTest` plus 8.2's
 *       `BackupRestoreDeviceTest`, the module's only instrumented tests. Needs a device or emulator.
 * Result: green only if a backup made by this build restores onto a clean encrypted database with
 *       every table's rows intact.
 * Changelog: 2026-09-19 — Created for issue 8.3.
 */
tasks.register("restoreDrill") {
    group = "verification"
    description = "Backup → destroy → restore on a device; every table must match (release gate, DRL-001)."
    dependsOn(":data:repository:connectedDebugAndroidTest")
}

/**
 * `verifyReleaseLogStripping` — proves the release APK has no chatty log surface (issue 11.6).
 *
 * Why:  §21.6 bans PII and amounts from logs, and `app/proguard-rules.pro` strips `Log.v/d/i/w`,
 *       `Log.isLoggable` and `println` from the release build so there is nothing to get wrong. That
 *       is a claim about a **binary**, and this project has twice shipped a gate that never ran:
 *       the rule file can be edited, `isMinifyEnabled` can be flipped back, and every test here
 *       would stay green while the APK shipped the thing §21.6 forbids. So the shipped DEX is read.
 * What: assembles the release APK, then parses every `classes*.dex` and fails on any reference to a
 *       stripped method. `Log.e` and `Log.wtf` are deliberately allowed — an error path that cannot
 *       speak is a release nobody can diagnose, and `CfoPiiInLogs` already blocks PII in their
 *       arguments at compile time.
 * Result: green only when the strip actually held. Verified non-vacuous by running the same checker
 *         against the **unminified debug** APK, where it finds all six methods and fails.
 * Changelog: 2026-10-02 — Created for issue 11.6.
 *
 * Standard-library Python only, and a DEX parser rather than `dexdump` — which lives under a
 * versioned `build-tools` path that differs per machine and would have to be pinned in CI. The
 * parser was cross-checked against `dexdump -d` on this app's own release APK: both agree that
 * `v`, `d`, `i`, `w` and `isLoggable` are gone and that `e` and `wtf` remain.
 */
tasks.register<Exec>("verifyReleaseLogStripping") {
    group = "verification"
    description = "Fails if the release APK still references a stripped log method (§21.6, SEC-007)."
    dependsOn(":app:assembleRelease")
    commandLine(
        "python3",
        rootProject.file("scripts/verify_release_log_stripping.py").absolutePath,
        rootProject.file("app/build/outputs/apk/release/app-release-unsigned.apk").absolutePath,
    )
}

/**
 * `writeDependencyCoordinates` — the input the vulnerability scan reads (issue 11.6; SEC-007).
 *
 * Why:  OSV is queried by coordinate, so something has to produce the list. Parsing
 *       `./gradlew :app:dependencies` output would be fragile and, worse, **wrong**: it prints the
 *       requested versions alongside the resolved ones, and a scan that checked what was asked for
 *       rather than what is on the classpath would miss a transitive upgrade. This resolves the
 *       release runtime classpath and reports what actually ships.
 * What: writes `group:artifact:version`, one per line, sorted and de-duplicated.
 * Result: `build/reports/dependencies/release-runtime.txt`, the scan's only input.
 * Changelog: 2026-10-02 — Created for issue 11.6.
 *
 * Every module's release runtime classpath, not just `:app`'s — a library pulled in only by, say,
 * `:core:crypto` ships in the APK exactly the same way.
 */
tasks.register("writeDependencyCoordinates") {
    group = "verification"
    description = "Writes every resolved release-runtime dependency coordinate, for the OSV scan."
    val output = layout.buildDirectory.file("reports/dependencies/release-runtime.txt")
    outputs.file(output)
    val coordinates = provider {
        subprojects
            .flatMap { module ->
                module.configurations
                    .filter { it.isCanBeResolved && // The configuration whose resolution is what actually ships in a release APK.
                    it.name == "releaseRuntimeClasspath" }
                    .flatMap { configuration ->
                        runCatching {
                            configuration.incoming.resolutionResult.allComponents.mapNotNull { component ->
                                (component.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)
                                    ?.let { "${it.group}:${it.module}:${it.version}" }
                            }
                        }.getOrDefault(emptyList())
                    }
            }.distinct()
            .sorted()
    }
    doLast {
        val file = output.get().asFile
        file.parentFile.mkdirs()
        val lines = coordinates.get()
        // An empty list means the resolution silently failed, which would make the scan pass by
        // having nothing to scan — the vacuous-gate shape this project keeps finding.
        require(lines.isNotEmpty()) { "resolved no dependency coordinates; the scan would check nothing" }
        file.writeText(lines.joinToString("\n", postfix = "\n"))
        logger.lifecycle("Wrote ${lines.size} dependency coordinates to $file")
    }
}

/**
 * `scanDependencies` — the OSV supply-chain scan (issue 11.6; SEC-007).
 *
 * Why:  SEC-007 asks for dependency scanning, and this app's threat model makes it matter more than
 *       usual rather than less: it is offline-first and holds a complete picture of someone's
 *       finances, so a compromised dependency is the *only* realistic route to that data leaving the
 *       device. There is no server to breach, which concentrates the risk here.
 * What: resolves every release-runtime coordinate, queries OSV, and fails on anything at or above
 *       HIGH that is not allowlisted with a reason and a review date.
 * Result: red on a new high-severity advisory in anything that ships.
 * Changelog: 2026-10-02 — Created for issue 11.6.
 *
 * **Needs the network, and exits 2 rather than 0 when it cannot reach OSV.** A scanner that passes
 * when it cannot scan is the vacuous gate this project keeps finding — it would be green on every
 * offline machine forever. So this is **not** part of `unitTests`: it runs in CI, and locally it is
 * expected to fail with "could not reach OSV". P-04 is a promise about the app, not about tooling.
 */
tasks.register<Exec>("scanDependencies") {
    group = "verification"
    description = "Scans every shipped dependency against OSV; new HIGH/CRITICAL findings fail (SEC-007)."
    dependsOn("writeDependencyCoordinates")
    commandLine(
        "python3",
        rootProject.file("scripts/osv_scan.py").absolutePath,
        layout.buildDirectory.file("reports/dependencies/release-runtime.txt").get().asFile.absolutePath,
        rootProject.file("config/osv/allowlist.json").absolutePath,
    )
}

/**
 * `scriptTests` — the unit tests for the repository's Python tooling (issue 11.6).
 *
 * Why:  `scripts/osv_scan.py` decides whether a build is blocked, and that decision has branches
 *       worth testing: the severity floor, the allowlist, and the expiry that stops an acceptance
 *       becoming permanent. Those tests are useless if nothing runs them, and this project has twice
 *       shipped a gate that never ran — so they are wired into `unitTests` rather than left to be
 *       remembered.
 * What: `python3 -m unittest discover` over `scripts/`, standard library only.
 * Result: a policy change that breaks a documented decision fails the normal local gate.
 * Changelog: 2026-10-02 — Created for issue 11.6.
 */
tasks.register<Exec>("scriptTests") {
    group = "verification"
    description = "Unit tests for scripts/ (the OSV scan policy). Standard-library unittest."
    workingDir = rootProject.projectDir
    commandLine("python3", "-m", "unittest", "discover", "-s", "scripts", "-p", "test_*.py", "-v")
}

/**
 * `aiEval` — every frozen AI-evaluation gate, named (issue 12.2; §21.5).
 *
 * Why:  §21.5's accuracy floors are the thresholds that "block merges", and until this task the only
 *       way CI ran them was inside `unitTests` — four thousand anonymous tests, so a regression read
 *       as "a test failed" rather than "categorisation accuracy dropped below 92%". Issue 10.6 made
 *       exactly this argument for `guardrailEval`; this extends it to the other four sets.
 * What: the six modules holding a frozen set — categorisation, receipts, SMS, forecast ledgers, the
 *       chat guardrail, and the verbaliser's fidelity set.
 * Result: one named CI step whose failure says which dataset regressed, and one command a developer
 *       can run without the rest of the suite.
 * Changelog: 2026-10-02 — Created for issue 12.2.
 *            2026-10-10 — ADR-0080: added `:ml:llm`'s verbalisation-fidelity set. It measures the
 *            one thing the on-device model is asked to do, in both directions: no drifted or
 *            invented figure accepted, and no faithful reply refused.
 *
 * **`unitTests` is still what blocks a merge** — these tasks are inside it. This exists so the
 * pipeline names what it is checking, and so the accuracy lines the runners print are findable in a
 * log rather than buried.
 */
tasks.register("aiEval") {
    group = "verification"
    description = "Runs every frozen AI-evaluation set and reports accuracy against its §21.5 floor."
    dependsOn(
        ":domain:engines:classification:test",
        ":domain:engines:receipt:test",
        ":domain:engines:sms:test",
        ":domain:engines:forecast:test",
        ":domain:engines:chat:test",
        // ADR-0080's verbalisation-fidelity set. An Android library, so the task is the variant one
        // — the distinction issue 2.6 exists to remember.
        ":ml:llm:testDebugUnitTest",
    )
}

/**
 * `offlineSmoke` — the end-to-end offline gate (issue 12.4; §21.5, P-04).
 *
 * Why:  P-04 says every core feature works in airplane mode, and until issue 12.4 **nothing in the
 *       repository had ever turned airplane mode on.** The claim lived in three source comments and
 *       in every tracker's verification log, where it was checked by a human toggling a setting and
 *       remembering to. One name makes it a step CI can run and a developer can reach without the
 *       rest of the instrumented suite.
 * What: `:app:connectedDebugAndroidTest` (the offline E2E and the smoke) plus
 *       `:data:repository:connectedDebugAndroidTest` (the archive round trip, the backup drill and
 *       the erase). Needs a device or emulator.
 * Result: a release path where "works offline" is verified rather than asserted.
 * Changelog: 2026-10-02 — Created for issue 12.4.
 *
 * Overlaps `restoreDrill` on `:data:repository` deliberately: that task names the backup drill as a
 * release gate in its own right (DRL-001, issue 8.3), and one task shadowing another's purpose is
 * worse than running a module's instrumented tests twice in a pipeline that already has an emulator up.
 */
tasks.register("offlineSmoke") {
    group = "verification"
    description = "End-to-end on a device, including an airplane-mode leg and the archive round trip (P-04)."
    dependsOn(
        ":app:connectedDebugAndroidTest",
        ":data:repository:connectedDebugAndroidTest",
    )
}

/**
 * `verifyReleaseMetadata` — VERSION, versionCode and CHANGELOG must agree (issue 12.5; §26, SemVer).
 *
 * Why:  all three are bumped **by hand** on every issue and nothing checked that they agree. They
 *       have already drifted twice, and both are recorded rather than tidied away: Epic 11's seven
 *       issues shipped in the `0.10.x` series when the workflow says an epic starts with a minor
 *       bump, and **ten versions shipped with a `versionCode` that did not increase** — which Google
 *       Play rejects at upload, after a release has been cut.
 * What: the version parses as SemVer, has exactly one changelog entry, that entry's issue belongs to
 *       the epic its minor names, and the `versionCode` is positive, monotonic, and unchanged for a
 *       version already in `docs/releases.md`.
 * Result: a release bump that is internally inconsistent fails the build instead of being noticed
 *       seven releases later, or by the Play console.
 * Changelog: 2026-10-03 — Created for issue 12.5.
 *
 * Reads `docs/releases.md` rather than git history, because a shallow CI clone cannot answer "what
 * was the last versionCode?".
 */
tasks.register<Exec>("verifyReleaseMetadata") {
    group = "verification"
    description = "Fails when VERSION, versionCode and CHANGELOG.md disagree (§26, SemVer)."
    commandLine(
        "python3",
        rootProject.file("scripts/verify_release_metadata.py").absolutePath,
        rootProject.projectDir.absolutePath,
    )
    // Declared so an edit to any of the four actually re-runs the check — issue 11.5's finding, which
    // is that a file read at runtime is invisible to Gradle's up-to-date checking.
    inputs.file(rootProject.file("VERSION"))
    inputs.file(rootProject.file("CHANGELOG.md"))
    inputs.file(rootProject.file("docs/releases.md"))
    inputs.file(rootProject.file("app/build.gradle.kts"))
    outputs.upToDateWhen { false }
}

/**
 * SonarQube analysis — the server-side static-analysis report (ADR-0081).
 *
 * Why:  the repo already fails a build on ktlint, detekt, Android Lint and five custom detectors,
 *       but all four are *file-local*: they see one file at a time and have no cross-module view and
 *       no history. SonarQube adds the two things they cannot give — a whole-program view (dead
 *       private members, unreachable branches, cognitive complexity measured against a profile) and
 *       a durable record, so "did this change make the code worse?" is answerable rather than
 *       remembered. It does **not** replace any existing gate; detekt and `:lint` stay the ones that
 *       fail the build, because they are the ones that run with no server present (P-04 applies to
 *       the tooling story too: a developer offline must still get the full local gate).
 * What: applies the scanner plugin at the root and points it at a local SonarQube Community Build.
 *       Host and credentials come from the environment — **never** committed (P-01: a token in the
 *       repo is a credential leak, and this one can write analyses). `sonar.gradle.skipCompile` is
 *       left at its default so the analysis sees compiled classes and resolved classpaths; a
 *       source-only scan reports phantom issues on Kotlin this size.
 * Result: `SONAR_TOKEN=… ./gradlew sonar` publishes an analysis of every module to the server; with
 *       no token set the task is skipped rather than failed, so a plain `./gradlew build` on a
 *       machine with no server is unaffected.
 * Changelog: 2026-10-15 — Created. Added alongside the first full-repo analysis.
 *
 * Exclusions are deliberately few. Generated code (Room, Hilt, KSP, protobuf, Paparazzi goldens) is
 * excluded because nobody can act on a finding in it; `spike/` is excluded because ADR-0075 says it
 * is a throwaway portability probe, not shipped code. Everything else is in scope.
 */
sonar {
    // No server, no analysis — rather than a failed build on every offline machine.
    isSkipProject = System.getenv("SONAR_TOKEN").isNullOrBlank()

    properties {
        property("sonar.projectKey", "ai-personal-cfo")
        property("sonar.projectName", "AI Personal CFO")
        property("sonar.projectVersion", rootProject.file("VERSION").readText().trim())
        property("sonar.host.url", System.getenv("SONAR_HOST_URL") ?: "http://localhost:9000")
        property("sonar.sourceEncoding", "UTF-8")

        // Coverage comes from Kover, whose XML is JaCoCo-format, so Sonar reads it directly rather
        // than needing JaCoCo added alongside. Every module's report is listed because each one
        // writes its own; `koverXmlReport` must have run, which is why `sonarAnalysis` below depends
        // on it. Without this the dashboard shows 0% and the quality gate fails on a coverage
        // condition — a red gate that says nothing about the code, which is the same vacuous-signal
        // problem in reverse.
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            subprojects
                .map { module -> module.layout.buildDirectory.file("reports/kover/report.xml").get().asFile }
                .joinToString(",") { it.absolutePath },
        )
        property(
            "sonar.exclusions",
            listOf(
                "**/build/**",
                "**/generated/**",
                "**/*_Factory.java",
                "**/*_HiltModules*.java",
                "**/Hilt_*.java",
                "**/snapshots/**",
                "spike/**",
            ).joinToString(","),
        )

        // --- Rules switched off for specific files, each with the reason -------------------
        //
        // Why this list exists at all: a rule that is wrong *here* should be turned off *here*,
        // with the argument written down, rather than marked "won't fix" one issue at a time in a
        // server database that no reviewer reads and a volume wipe would erase. Everything below is
        // a decision this repository has already made somewhere else — an ADR, a `@Suppress` with a
        // reason, or a platform guarantee — and the rule simply cannot see it. The rules stay fully
        // active everywhere else in the repo.
        //
        // Each entry is `ruleKey` + `resourceKey` (an Ant-style path).
        val ignores =
            listOf(
                // kotlin:S107 "more than 7 parameters" — three places where the parameter list *is*
                // the data and an aggregate type would exist only to shrink a count:
                //   · Hilt `@Provides` factories: the parameters are the dependency graph. A
                //     14-dependency repository is a fact about the repository, and wrapping those
                //     dependencies in a holder would hide it from Hilt and from the reader both.
                //   · The non-Hilt `RepositoryFactory` mirror, which exists so `:data:repository`
                //     can be built in a test with no Hilt graph — it must take the same arguments.
                //   · Room DAO methods, whose parameters are the row's columns.
                //   · `RankedInsightEngine.insight`, which already carries
                //     `@Suppress("LongParameterList")` with the same argument for detekt's copy of
                //     this rule — "one argument per field of the card; the alternative is a second
                //     type". Two gates, one decision.
                "kotlin:S107" to "**/app/src/main/kotlin/com/aicfo/app/di/*.kt",
                "kotlin:S107" to "**/data/repository/src/main/kotlin/com/aicfo/data/repository/RepositoryFactory.kt",
                "kotlin:S107" to "**/core/database/src/main/kotlin/com/aicfo/core/database/dao/Daos.kt",
                "kotlin:S107" to "**/domain/engines/insight/src/main/kotlin/com/aicfo/domain/engines/insight/RankedInsightEngine.kt",

                // kotlin:S1192 "duplicated literal" in the Room migration history. The 24 findings
                // are column fragments like "`profile_id` TEXT NOT NULL, " repeated across 34
                // migrations. Hoisting them into shared constants is the one change that must never
                // be made here: a migration is a frozen record of what schema version N actually
                // did, and a shared constant means a single edit silently rewrites every past
                // version's DDL. DB-003 forbids destructive migrations; this would be worse —
                // retroactive ones. The duplication is the safety property.
                "kotlin:S1192" to "**/core/database/src/main/kotlin/com/aicfo/core/database/migration/Migrations.kt",

                // kotlin:S6311 "pointless dispatcher". It is not pointless:
                // `ReceiptImageStore.read` is a suspend function that does its file I/O and its
                // AES-GCM decrypt **without dispatching internally**, so the `withContext(io)` in
                // `ReceiptRepository` is the only thing keeping a receipt decrypt off the main
                // thread. The rule assumes the main-safe-suspend convention; this codebase
                // dispatches at the repository boundary instead (§5, "dispatchers injected").
                "kotlin:S6311" to "**/data/repository/src/main/kotlin/com/aicfo/data/repository/ReceiptRepository.kt",

                // kotlin:S6619 "null check always fails" in `TemplateLlmEngine.formatted`. False
                // positive: the `when` arms yield `String?`, `Int?` and `String?`, so a null is
                // exactly what happens when a figure's kind and its populated field disagree. The
                // rule widens the inferred element type to a non-null common supertype and then
                // calls the guard dead. Removing it would let a missing figure render as the text
                // "null" in a sentence about the user's money — the P-03 failure this guard exists
                // for, and the one `FIELD_FIGURE` reports.
                "kotlin:S6619" to "**/ml/llm/src/main/kotlin/com/aicfo/ml/llm/TemplateLlmEngine.kt",

                // kotlin:S5324 "accessing external storage" in `AndroidSecureEraser`. The access is
                // a **delete**: the erase sweeps `externalCacheDir` so decoded receipt bitmaps and
                // OCR scratch files do not outlive the crypto-shred. The hotspot is about *putting*
                // sensitive data on shared storage; this is the opposite, and skipping the sweep is
                // what would leave data behind (§20.1).
                "kotlin:S5324" to "**/data/repository/src/main/kotlin/com/aicfo/data/repository/AndroidSecureEraser.kt",

                // kotlin:S6288 "key usable without user authentication" in `KeystoreMasterKey`.
                // Deliberate, and recorded in ADR-0003: the app lock gates a `SessionLock` flag and
                // the Hilt provider for `CfoDatabase` refuses to build while locked, so no code
                // path reaches the data without unlocking. Binding the key itself to user auth
                // would also break the thing that must keep working — Tink has to be able to use
                // the key it finds, and a key with an auth-validity window cannot serve the
                // background net-worth snapshot worker.
                "kotlin:S6288" to "**/core/database/src/main/kotlin/com/aicfo/core/database/crypto/KeystoreMasterKey.kt",

                // kotlin:S6293 "biometric authentication without a CryptoObject" in `AppLockGate`.
                // Same ADR-0003 decision seen from the other end: a successful class-3 prompt flips
                // the session flag, it does not unwrap the database key. A `CryptoObject` would
                // only matter if the biometric were the thing releasing the key, and ADR-0003
                // explains why it is not.
                "kotlin:S6293" to "**/app/src/main/kotlin/com/aicfo/app/lock/AppLockGate.kt",

                // kotlin:S6517 "make this interface functional" on the two `:core:network` API
                // contracts. Converting the other 47 single-method interfaces to `fun interface` was
                // right; these two were not, and the analyser said so itself. `MarketDataApi` has
                // three named implementations (the Retrofit client, the offline stand-in an app with
                // no backend uses, and the test fake); `AccountAggregatorApi` is a named contract for
                // a backend that is specified and unbuilt, whose one implementation exists to carry
                // a documented refusal. Neither has a call site that passes a lambda. Declaring them
                // functional immediately produced the *opposite* finding, `kotlin:S6516`, which asked
                // for those documented stand-in objects to become anonymous lambdas — deleting both a
                // name the factory returns and a doc comment §5 requires and a lambda cannot carry.
                // So the declarations stay plain and the rule is off for exactly these two files.
                "kotlin:S6517" to "**/core/network/src/main/kotlin/com/aicfo/core/network/MarketDataApi.kt",
                "kotlin:S6517" to "**/core/network/src/main/kotlin/com/aicfo/core/network/AccountAggregatorApi.kt",

                // xml:S5322 "make sure intents are received safely" on the widget receiver.
                // `android.appwidget.action.APPWIDGET_UPDATE` is a **protected broadcast**: the
                // platform refuses to deliver it from a non-system sender, so no third-party app can
                // reach this receiver. `exported="true"` is not a choice either — an
                // AppWidgetProvider the launcher cannot bind to is a widget that never appears. The
                // receiver reads its own preference store and no intent extras.
                "xml:S5322" to "**/widget/src/main/AndroidManifest.xml",
            )
        property("sonar.issue.ignore.multicriteria", ignores.indices.joinToString(",") { "e${it + 1}" })
        ignores.forEachIndexed { index, (rule, path) ->
            property("sonar.issue.ignore.multicriteria.e${index + 1}.ruleKey", rule)
            property("sonar.issue.ignore.multicriteria.e${index + 1}.resourceKey", path)
        }
    }
}

/**
 * Per-module scanner wiring — what each subproject hands the analysis (ADR-0081).
 *
 * Why:  the first run of this analysis reported 54 issues over 293 files and looked healthy. It was
 *       **vacuous for every Android module**: 295 of the repo's 450 main Kotlin files were never
 *       read. The scanner takes an AGP module's sources from `android.sourceSets["main"].java
 *       .srcDirs`, which is `src/main/java` — a directory this repo does not have, because the
 *       Kotlin Android plugin puts them in `src/main/kotlin`. So `sonar.sources` resolved to
 *       nothing, the module was indexed as zero lines of code, and the only reason it was caught is
 *       that Android Lint's own report named files the index did not contain
 *       ("No input file found for …TransactionsScreen.kt"). Nothing failed. The dashboard was green
 *       over `:app`, `:data:repository`, every `:feature:*`, `:core:database`, `:core:crypto`,
 *       `:ml:*`, `:widget` and `:sync:backup` — which is this project's recurring defect (G-01's
 *       vacuous coverage gate, the unreachable `:lint` tests, the stale Paparazzi baseline) wearing
 *       a new costume.
 * What: for every module the Android plugins are applied to, names the source and test directories
 *       that actually exist. Pure-Kotlin modules are left alone — the scanner reads their
 *       `SourceSet`s correctly and already indexed all 155 main and 171 test files.
 * Result: the analysis covers the whole repository, so a clean dashboard means something.
 * Changelog: 2026-10-15 — Created, after the first analysis silently skipped 26 modules.
 *
 * `plugins.withId` rather than `hasPlugin`: at the time the root script configures `subprojects`,
 * the module scripts have not run yet, so `hasPlugin` answers false for all of them — the same
 * ordering trap that would have left this fix looking applied while changing nothing.
 */
subprojects {
    sonar {
        // ADR-0075: a throwaway Kotlin/Native portability probe, not shipped code.
        if (path.startsWith(":spike")) isSkipProject = true
    }

    /**
     * Joins the directories/files in [candidates] that exist, as an absolute comma-separated list.
     *
     * Why:  the scanner fails the analysis on a `sonar.sources` entry that is not on disk, and these
     *       directories differ per module (`:widget` has no `src/test`, `:core:database` has no
     *       `res`). Filtering is what makes one shared declaration safe for all of them.
     * Input:  [candidates] — module-relative paths.
     * Output: the existing ones, absolute, comma-separated; `""` when none exist.
     */
    fun existingPaths(candidates: List<String>): String =
        candidates.map { project.file(it) }
            .filter { it.exists() }
            .joinToString(",") { it.absolutePath }

    listOf("com.android.application", "com.android.library").forEach { androidPluginId ->
        plugins.withId(androidPluginId) {
            val mainPaths = existingPaths(
                listOf("src/main/kotlin", "src/main/java", "src/main/res", "src/main/AndroidManifest.xml"),
            )
            val testPaths = existingPaths(
                listOf("src/test/kotlin", "src/test/java", "src/androidTest/kotlin", "src/androidTest/java"),
            )
            sonar {
                properties {
                    // An empty value is not "no sources", it is an error — so only set what exists.
                    if (mainPaths.isNotEmpty()) property("sonar.sources", mainPaths)
                    if (testPaths.isNotEmpty()) property("sonar.tests", testPaths)
                }
            }
        }
    }
}

/**
 * `sonarAnalysis` — the analysis with its inputs guaranteed fresh (ADR-0081).
 *
 * Why:  `./gradlew sonar` on its own publishes an analysis built from **whatever happens to be on
 *       disk**, and two of its inputs are produced by other tasks: Android Lint's
 *       `lint-results-debug.xml`, which is where every `external_android-lint:*` finding comes from,
 *       and Kover's `report.xml`, which is the only source of coverage. Run `sonar` alone and a
 *       finding fixed an hour ago is still reported from a stale lint file, while coverage shows 0%
 *       and the quality gate fails on a condition that is about the tooling rather than the code.
 *       Both are the shape of defect this repository keeps finding — a number that looks like
 *       evidence and is not — so the fix is a task that cannot be invoked with the wrong inputs
 *       rather than a line in a README asking people to remember.
 * What: every module's `lintDebug` and `koverXmlReport`, then `sonar`, ordered.
 * Result: one command whose dashboard reflects this working tree.
 * Changelog: 2026-10-15 — Created with ADR-0081.
 *
 * Matched by task **name** rather than by listing modules, for the reason `unitTests` is: a module
 * added later is picked up without anyone remembering to edit this.
 */
val sonarInputTasks =
    subprojects.map { module ->
        module.tasks.matching { it.name == "lintDebug" || it.name == "koverXmlReport" }
    }

tasks.named("sonar") { mustRunAfter(sonarInputTasks) }

tasks.register("sonarAnalysis") {
    group = "verification"
    description = "Refreshes the lint and coverage reports, then publishes a SonarQube analysis."
    dependsOn(sonarInputTasks)
    dependsOn("sonar")
}
