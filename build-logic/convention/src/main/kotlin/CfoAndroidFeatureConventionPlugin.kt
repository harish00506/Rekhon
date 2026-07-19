import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Convention plugin `cfo.android.feature` — a :feature:* UI module.
 *
 * Why:  §21.2 / ARC-004 — every feature is Compose + a ViewModel exposing one
 *       immutable UiState; they all need the same library + Compose + Hilt + nav +
 *       lifecycle set. Bundling it keeps feature scripts to a single plugin line and
 *       stops feature-to-feature coupling from creeping in.
 * What: applies cfo.android.library, cfo.android.compose and cfo.hilt, then adds the
 *       shared feature dependencies (navigation, lifecycle, coroutines, test doubles).
 * Result: a feature module ready for a StateFlow-driven screen + ViewModel tests.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
class CfoAndroidFeatureConventionPlugin : Plugin<Project> {
    /**
     * Input:  [target] — the feature module. Output: none.
     */
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("cfo.android.library")
                apply("cfo.android.compose")
                apply("cfo.hilt")
            }
            dependencies {
                add("implementation", libs.findLibrary("androidx-navigation-compose").get())
                add("implementation", libs.findLibrary("hilt-navigation-compose").get())
                add("implementation", libs.findLibrary("androidx-lifecycle-runtime-ktx").get())
                add("implementation", libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
                add("implementation", libs.findLibrary("kotlinx-coroutines-android").get())
                add("testImplementation", libs.findLibrary("turbine").get())
                add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
            }
        }
    }
}
