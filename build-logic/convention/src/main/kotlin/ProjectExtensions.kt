import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
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
