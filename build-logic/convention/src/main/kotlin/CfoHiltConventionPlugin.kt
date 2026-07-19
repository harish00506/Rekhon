import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Convention plugin `cfo.hilt` — Hilt DI with KSP code generation.
 *
 * Why:  §21.3 / ARC-003 — dependencies are constructor-injected via Hilt (no service
 *       locators). Any module that owns injectable types applies this.
 * What: applies KSP + the Hilt Gradle plugin and wires hilt-android + hilt-compiler.
 * Result: @Inject/@HiltViewModel work in the module.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
class CfoHiltConventionPlugin : Plugin<Project> {
    /**
     * Input:  [target] — the module gaining Hilt. Output: none.
     */
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.google.devtools.ksp")
                apply("com.google.dagger.hilt.android")
            }
            dependencies {
                add("implementation", libs.findLibrary("hilt-android").get())
                add("ksp", libs.findLibrary("hilt-compiler").get())
            }
        }
    }
}
