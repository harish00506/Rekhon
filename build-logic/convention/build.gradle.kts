// =============================================================================
// Convention plugins module. Compiles the Cfo* plugins and registers their ids.
// Why:  §21.6 — one shared build config; ARC-002 enforced here (issue 1.1).
// What: kotlin-dsl module; compiles against the Gradle-plugin artifacts (compileOnly)
//       so the convention plugins can apply/configure AGP, Kotlin, KSP, Hilt, quality.
// Changelog: 2026-07-19 — Created for issue 1.1.
// =============================================================================

plugins {
    `kotlin-dsl`
}

group = "com.aicfo.buildlogic"

java {
    // Emit JVM 17 bytecode; compiles on the installed JDK without a separate toolchain.
    // Must stay <= the CI JDK (17) so these convention plugins load on the CI daemon.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    // Keep Kotlin's target aligned with Java above (silences the mixed-target warning).
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.compiler.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.hilt.gradlePlugin)
    compileOnly(libs.ktlint.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
    compileOnly(libs.kover.gradlePlugin)

    // ARC-002 guard functional test (Gradle TestKit).
    testImplementation(libs.junit4)
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        register("kotlinLibrary") {
            id = "cfo.kotlin.library"
            implementationClass = "CfoKotlinLibraryConventionPlugin"
        }
        register("androidLibrary") {
            id = "cfo.android.library"
            implementationClass = "CfoAndroidLibraryConventionPlugin"
        }
        register("androidApplication") {
            id = "cfo.android.application"
            implementationClass = "CfoAndroidApplicationConventionPlugin"
        }
        register("androidCompose") {
            id = "cfo.android.compose"
            implementationClass = "CfoAndroidComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "cfo.android.feature"
            implementationClass = "CfoAndroidFeatureConventionPlugin"
        }
        register("hilt") {
            id = "cfo.hilt"
            implementationClass = "CfoHiltConventionPlugin"
        }
    }
}
