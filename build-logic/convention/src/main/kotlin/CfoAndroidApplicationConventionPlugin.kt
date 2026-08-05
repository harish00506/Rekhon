import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Convention plugin `cfo.android.application` — the single :app module.
 *
 * Why:  §21.2 — :app is the only application module (single-activity Compose host);
 *       it needs targetSdk + versionName wiring that libraries do not.
 * What: applies com.android.application + kotlin("android"), SDK levels, targetSdk,
 *       JVM 17, quality gate and JUnit4.
 * Result: an installable debug app that ties the module graph together.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
class CfoAndroidApplicationConventionPlugin : Plugin<Project> {
    /**
     * Input:  [target] — the :app module. Output: none (configures the module).
     */
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.application")
                apply("org.jetbrains.kotlin.android")
            }
            extensions.configure<ApplicationExtension> {
                configureKotlinAndroid(this)
                defaultConfig.targetSdk = intVersion("targetSdk")
            }
            configureQuality()
            configureCustomLint()
            dependencies {
                add("testImplementation", libs.findLibrary("junit4").get())
            }
        }
    }
}
