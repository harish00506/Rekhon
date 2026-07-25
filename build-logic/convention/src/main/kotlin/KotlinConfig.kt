import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Kotlin/Java compile configuration shared by every module.
 *
 * Why:  §21.3 pins JDK 17; centralising the compile settings means no module
 *       repeats them and every module emits identical, portable JVM 17 bytecode.
 * What: [configureKotlinAndroid] for Android modules, [configureKotlinJvm] for
 *       pure-Kotlin modules, and the shared jvmTarget wiring.
 * Result: consistent source/target level 17 across the whole build.
 * Changelog: 2026-07-19 — Created for issue 1.1. Targets bytecode 17 while
 *            compiling on the installed JDK (no separate toolchain download).
 */

/**
 * Configures compileSdk/minSdk and Java/Kotlin level 17 on an Android module.
 * Input:  [commonExtension] — the module's AGP extension (library or application).
 * Output: none (mutates the extension + registers the Kotlin jvmTarget).
 */
internal fun Project.configureKotlinAndroid(
    commonExtension: CommonExtension<*, *, *, *, *, *>,
) {
    commonExtension.apply {
        compileSdk = intVersion("compileSdk")
        defaultConfig {
            minSdk = intVersion("minSdk")
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
    configureKotlinJvmTarget()
}

/**
 * Configures Java level 17 on a pure-Kotlin/JVM module.
 * Input:  the receiver [Project]. Output: none (mutates the java extension).
 */
internal fun Project.configureKotlinJvm() {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    configureKotlinJvmTarget()
}

/**
 * Sets the Kotlin compiler's jvmTarget to 17 for all compile tasks.
 * Input:  the receiver [Project]. Output: none.
 */
private fun Project.configureKotlinJvmTarget() {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}
