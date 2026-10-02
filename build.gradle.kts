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
                it.name == "testDebugUnitTest" || it.name == "test" || it.name == "verifyPaparazziDebug"
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
 * What: the five modules holding a frozen set — categorisation, receipts, SMS, forecast ledgers and
 *       the chat guardrail.
 * Result: one named CI step whose failure says which dataset regressed, and one command a developer
 *       can run without the rest of the suite.
 * Changelog: 2026-10-02 — Created for issue 12.2.
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
    )
}
