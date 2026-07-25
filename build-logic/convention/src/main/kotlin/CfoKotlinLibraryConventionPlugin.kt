import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Convention plugin `cfo.kotlin.library` — a pure-Kotlin/JVM module.
 *
 * Why:  §21.2 / ARC-002 — :core:model, :core:common and :domain:* must be plain
 *       Kotlin with no Android dependency so engines stay portable (KMP-ready) and
 *       fast to unit-test. This plugin makes that the default and enforces it.
 * What: applies kotlin("jvm") + java-library, sets JVM 17, adds the quality gate and the
 *       coverage bounds, wires JUnit4, and installs the ARC-002 guard.
 * Result: a module that compiles as pure JVM, fails the build if it ever gains an Android
 *         plugin, and fails it again if coverage drops below the §4 bounds.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 *            2026-07-25 — Issue 1.2: added configureCoverage() so koverVerify has real rules
 *            (governance audit G-01 — it previously passed at 0% coverage).
 */
class CfoKotlinLibraryConventionPlugin : Plugin<Project> {
    /**
     * Input:  [target] — the module the plugin is applied to.
     * Output: none (configures the module).
     */
    override fun apply(target: Project) {
        with(target) {
            // ARC-002 fail-fast: if an Android plugin is applied *before* this one, throw a
            // clear ARC-002 error now — before the java/kotlin plugins would surface AGP's
            // generic "not compatible with the Android plugins" message.
            Arc002.FORBIDDEN_PLUGIN_IDS
                .firstOrNull { pluginManager.hasPlugin(it) }
                ?.let { throw GradleException(Arc002.violationMessage(path, listOf(it))!!) }

            with(pluginManager) {
                apply("java-library")
                apply("org.jetbrains.kotlin.jvm")
            }
            configureKotlinJvm()
            configureQuality()
            configureCoverage()
            // Defense-in-depth for the reverse ordering (Android plugin applied later).
            enforceNoAndroidPlugins()
            dependencies {
                add("testImplementation", libs.findLibrary("junit4").get())
            }
        }
    }
}
