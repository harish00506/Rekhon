import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Convention plugin `cfo.android.library` — an Android library module.
 *
 * Why:  the Android-touching layers (:core:database, :core:crypto, :data:*, :ml:*,
 *       :sync:*, :widget) all need the same compileSdk/minSdk, JVM 17 and quality
 *       gate; this removes that boilerplate from each module script (§21.6).
 * What: applies com.android.library + kotlin("android"), configures SDK levels and
 *       JVM 17, adds the quality gate and JUnit4.
 * Result: a consistent Android library that builds green.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
class CfoAndroidLibraryConventionPlugin : Plugin<Project> {
    /**
     * Input:  [target] — the module the plugin is applied to.
     * Output: none (configures the module).
     */
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.library")
                apply("org.jetbrains.kotlin.android")
            }
            extensions.configure<LibraryExtension> {
                configureKotlinAndroid(this)
            }
            configureQuality()
            configureCustomLint()
            dependencies {
                add("testImplementation", libs.findLibrary("junit4").get())
            }
        }
    }
}
