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
