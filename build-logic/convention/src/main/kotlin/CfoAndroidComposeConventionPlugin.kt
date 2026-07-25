import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Convention plugin `cfo.android.compose` — adds Jetpack Compose to an Android module.
 *
 * Why:  §21.3 pins Compose + Material 3; UI modules apply this on top of an Android
 *       library/application to get the compiler plugin and the BOM-aligned deps.
 * What: applies the Kotlin Compose compiler plugin and delegates to
 *       [configureAndroidCompose], resolving whichever concrete AGP extension is present.
 * Result: the module can declare @Composable UI and screenshot/UI tests.
 * Changelog: 2026-07-19 — Created for issue 1.1. Resolve the concrete extension
 *            (Application/Library) rather than the CommonExtension interface, which
 *            AGP does not register.
 */
class CfoAndroidComposeConventionPlugin : Plugin<Project> {
    /**
     * Input:  [target] — an already-Android module. Output: none.
     */
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            val extension =
                extensions.findByType(ApplicationExtension::class.java)
                    ?: extensions.findByType(LibraryExtension::class.java)
                    ?: throw GradleException(
                        "cfo.android.compose must be applied after an Android " +
                            "application or library plugin on '$path'.",
                    )
            configureAndroidCompose(extension)
        }
    }
}
