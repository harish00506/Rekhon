import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Enables Jetpack Compose on an Android module and wires the BOM-managed deps.
 *
 * Why:  §21.3 pins Compose + Material 3; the Compose compiler is the Kotlin
 *       plugin (Kotlin 2.x), so enabling Compose is buildFeatures + one plugin +
 *       the BOM-aligned dependency set — done once here for every UI module.
 * What: turns on buildFeatures.compose and adds ui/material3/tooling deps from the BOM.
 * Result: :core:designsystem, :feature:* and :app render Compose with one plugin apply.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 *
 * Input:  [commonExtension] — the module's AGP extension. Output: none.
 */
internal fun Project.configureAndroidCompose(
    commonExtension: CommonExtension<*, *, *, *, *, *>,
) {
    commonExtension.buildFeatures.compose = true

    dependencies {
        val bom = platform(libs.findLibrary("androidx-compose-bom").get())
        add("implementation", bom)
        add("androidTestImplementation", bom)
        add("implementation", libs.findLibrary("androidx-compose-ui").get())
        add("implementation", libs.findLibrary("androidx-compose-ui-graphics").get())
        add("implementation", libs.findLibrary("androidx-compose-ui-tooling-preview").get())
        add("implementation", libs.findLibrary("androidx-compose-material3").get())
        add("implementation", libs.findLibrary("androidx-activity-compose").get())
        add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
        add("debugImplementation", libs.findLibrary("androidx-compose-ui-test-manifest").get())
        add("androidTestImplementation", libs.findLibrary("androidx-compose-ui-test-junit4").get())
    }
}
