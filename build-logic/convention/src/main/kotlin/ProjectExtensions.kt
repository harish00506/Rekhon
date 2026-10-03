import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.api.tasks.testing.Test

/**
 * Shared helpers for the Cfo* convention plugins.
 *
 * Why:  keep the plugin classes tiny and consistent (§21.6) and put the two
 *       cross-cutting concerns — the version catalog accessor and the repo-wide
 *       quality gate — in one place.
 * What: the `libs` accessor, [configureQuality], and the ARC-002 guard.
 * Result: every module gets ktlint/detekt/kover, and pure-Kotlin modules are
 *         protected from accidentally applying an Android plugin.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */

/**
 * The shared version catalog.
 * Why:    convention plugins run as compiled classes, so the generated `libs`
 *         script accessor is unavailable; this reads the catalog directly.
 * Input:  none (uses the project's catalog extension).
 * Output: the "libs" [VersionCatalog].
 */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * Reads a required integer version from the catalog (e.g. compileSdk).
 * Input:  [alias] — the [versions] key. Output: the pinned value as Int.
 */
internal fun Project.intVersion(alias: String): Int =
    libs.findVersion(alias).get().requiredVersion.toInt()

/**
 * Applies the repo-wide quality gate to a module.
 * Why:    §21.6 — ktlint (official) + detekt on every module; Kover for coverage
 *         (money math must reach 100% once real engines land — issue 1.2).
 * What:   applies the three plugins and points detekt at the default ruleset.
 * Result: `ktlintCheck`, `detekt`, and `koverVerify` exist on the module.
 * Input:  the receiver [Project]. Output: none (configures the project).
 */
internal fun Project.configureQuality() {
    with(pluginManager) {
        apply("org.jlleitschuh.gradle.ktlint")
        apply("io.gitlab.arturbosch.detekt")
        apply("org.jetbrains.kotlinx.kover")
    }
    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        ignoreFailures = false
        basePath = rootDir.absolutePath
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    }
}

/**
 * Adds the project's custom lint checks to a module and makes them block the build.
 * Why:    issue 1.5 / audit G-03 — MNY-001, TIM-001, ARC-006, the strings rule and the PII-logging
 *         ban were all documented as enforced while nothing in the build checked them. Applying
 *         `:lint` here rather than per-module means a new module cannot opt out by omission, which
 *         is how enforcement quietly erodes.
 * What:   depends on the `:lint` checks, turns warnings into build failures, and deliberately
 *         configures **no baseline** — task 1.1.5 §4 forbids grandfathering existing violations.
 * Result: `./gradlew lint` fails on a banned construct anywhere in the tree.
 * Input:  the receiver [Project]. Output: none (adds the dependency + lint config).
 */
internal fun Project.configureCustomLint() {
    dependencies.add("lintChecks", project(":lint"))
}

/**
 * Gives `koverVerify` actual teeth on a pure-Kotlin module.
 * Why:    CLAUDE.md §4 promises "coverage >= 85%; money math 100%", and until issue 1.2 that
 *         promise was a no-op — Kover was applied with **zero rules**, so `koverVerify` passed at
 *         any coverage including 0% (governance audit G-01). A gate that cannot fail is worse
 *         than no gate: CI goes green and reviewers believe it checked something.
 * What:   a line-coverage floor on the modules the rule actually targets (:core:model,
 *         :core:common, :domain:*) — 85% normally, 100% for the money module ([MONEY_MODULE_PATH]).
 * Result: deleting a money test, or adding an untested branch to Money, fails the build. Proved
 *         by temporarily raising the bound to an impossible 101 and watching it go red.
 * Input:  the receiver [Project]. Output: none (configures the kover extension).
 */
internal fun Project.configureCoverage() {
    val minimum = if (path == MONEY_MODULE_PATH) FULL_COVERAGE else MIN_MODULE_COVERAGE
    extensions.configure<KoverProjectExtension> {
        reports {
            verify {
                rule("$path line coverage must be >= $minimum% (CLAUDE.md §4)") {
                    minBound(minimum, CoverageUnit.LINE, AggregationType.COVERED_PERCENTAGE)
                }
            }
        }
    }
}

/**
 * Declares the repository's checked data as an input to every test task (CLAUDE.md §6, ADR-0005).
 *
 * Why:  twenty-nine drift tests read a file from the repository root at runtime — the rulebooks and
 *       knowledge bases under `ai/`, and the DPDP matrix under `docs/compliance/` — and each is the
 *       only thing stopping a typed Kotlin mirror diverging from the row it claims to come from.
 *       **Those gates can be skipped.** A file read at runtime is not a declared task input, so
 *       Gradle calls the test task UP-TO-DATE and the build stays green on exactly the edit the test
 *       was written to catch.
 *
 *       Issue 7.2 found this and fixed it — **for one file.** `ai/rules/rules-kb.json` was declared
 *       and the other nine data files were not, and the call was wired into the pure-Kotlin
 *       convention plugin only, so no Android-library module got it at all. Issue 11.5 found the
 *       remainder the same way: deleting `docs/compliance/dpdp-2023.md` left
 *       `:core:datastore:testDebugUnitTest` UP-TO-DATE and the suite passing, and `--rerun-tasks`
 *       then failed all four assertions — the gate was right and only its scheduling was wrong.
 *       Declaring the **directories** rather than a list of files is what stops this recurring a
 *       third time: a knowledge base added next year is covered without anyone remembering.
 * What: names `ai/` and `docs/compliance/` as inputs, so a change to either invalidates the task.
 * Result: an edit to any checked data file always re-runs every drift test. Modules that read none
 *         of it re-run their tests too, which costs one directory's worth of up-to-date checking
 *         and is the cheaper mistake than a silent skip.
 * Input:  the receiver — the module being configured. Output: none (configures the tasks).
 * Changelog: 2026-09-02 — Created for issue 7.2, declaring `ai/rules/rules-kb.json`.
 *            2026-10-01 — Issue 11.5: widened to the whole of `ai/` plus `docs/compliance/`, after
 *            finding that the nine other data files and every Android-library module were still
 *            exposed to the very skip 7.2 fixed.
 *            2026-10-02 — Issue 11.7: added `docs/security/`, so the crypto audit's drift test is
 *            scheduled rather than held up to date on an edit to the document it checks.
 */
internal fun Project.configureCheckedDataAsTestInput() {
    val directories = CHECKED_DATA_PATHS.map { rootProject.layout.projectDirectory.dir(it) }
    tasks.withType(Test::class.java).configureEach {
        directories.forEachIndexed { index, directory ->
            inputs
                .dir(directory)
                .withPropertyName("cfoCheckedData$index")
                .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
        }
    }
}

/**
 * Declares the module's own `src/main` tree as an input to its test tasks (CLAUDE.md §21.5).
 *
 * Why:  [configureCheckedDataAsTestInput] fixed this bug for *data* a module reads at runtime, and
 *       issue 13.1 found the same hole one layer over: a test that reads **Kotlin source**.
 *       `ProfileScopingTest` scans every `@Query` in `Daos.kt` and the `profileId` columns in
 *       `Entities.kt`, and the facts it checks live largely in **comments** — the `// DEVICE-WIDE:`
 *       marker and the reason underneath it. A comment-only edit compiles to byte-identical
 *       classes, so Gradle finds nothing changed on the task's declared inputs and replays the
 *       previous **pass**. Measured: deleting the marker left `:core:database:testDebugUnitTest`
 *       UP-TO-DATE and the build green in 1s; the same edit under `--rerun-tasks` failed two
 *       assertions. The gate was right and only its scheduling was wrong — for the fourth time in
 *       this repository (issues 7.2, 11.5, 11.7, 13.1).
 * What: names `src/main` as an input, so any edit to the module's own source — comments included —
 *       invalidates its test tasks.
 * Result: a convention test cannot be defeated by the compiler discarding the thing it checks.
 *         Declared per module rather than per file, so a later source-scanning test is covered
 *         without anyone remembering this paragraph.
 * Input:  the receiver — the module being configured. Output: none (configures the tasks).
 * Changelog: 2026-10-03 — Created for issue 13.1 (ADR-0069).
 */
internal fun Project.configureOwnSourceAsTestInput() {
    val mainSource = layout.projectDirectory.dir("src/main")
    tasks.withType(Test::class.java).configureEach {
        inputs
            .dir(mainSource)
            .optional(true)
            .withPropertyName("cfoOwnMainSource")
            .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    }
}

/**
 * The directories whose contents a drift test may read.
 *
 * `ai/` is the AI subsystem's runtime data — rulebooks, knowledge bases, the tool registry, the
 * guardrail and its eval set — every one of which has a typed mirror in Kotlin and a test pinning
 * the two together. `docs/compliance/` holds the DPDP matrix, whose claims
 * `DpdpComplianceDriftTest` checks against the code (issue 11.5).
 */
private val CHECKED_DATA_PATHS = listOf("ai", "docs/compliance", "docs/security")

/** Engine/domain floor from CLAUDE.md §4. */
private const val MIN_MODULE_COVERAGE = 85

/** Money math is the one place the rule is absolute (MNY-001). */
private const val FULL_COVERAGE = 100

/**
 * The module holding money math, which is held to 100% rather than 85%.
 *
 * Kover 0.9 has no per-rule class filter (verified against the plugin API: `KoverVerifyRule`
 * exposes only bounds and `groupBy`; `filters` live on the whole report set), so "money math
 * 100%" is expressed as "the money module is 100%". That is true today — `:core:model` contains
 * only `Money` and `MoneyFormatter` — and it is the stricter reading, so it cannot under-enforce
 * the rule. If non-money types land here (issue 1.4's `Result`/`AppError`), they inherit 100%
 * too; move money to its own module before relaxing this.
 */
private const val MONEY_MODULE_PATH = ":core:model"

/**
 * ARC-002 guard: fail the build if a pure-Kotlin module applies an Android plugin.
 * Why:    §21.2 — :core:model and :domain:* must stay JVM-only so engines stay
 *         portable and unit-testable. This makes the rule enforced, not aspirational.
 * What:   after evaluation, throws with a clear message if any com.android.* plugin
 *         is present on a module that applied cfo.kotlin.library.
 * Result: adding `com.android.library` to such a module breaks the build (proved by T2).
 * Input:  the receiver [Project]. Output: none (may throw [GradleException]).
 */
internal fun Project.enforceNoAndroidPlugins() {
    afterEvaluate {
        val applied = Arc002.FORBIDDEN_PLUGIN_IDS.filter { pluginManager.hasPlugin(it) }
        val message = Arc002.violationMessage(path, applied) ?: return@afterEvaluate
        throw GradleException(message)
    }
}
