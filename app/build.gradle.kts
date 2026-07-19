// :app — single-activity Compose host + Hilt graph. The only application module (§21.2).
// versionName is kept equal to the repo-root VERSION file (design spec §9, SemVer).
plugins {
    alias(libs.plugins.cfo.android.application)
    alias(libs.plugins.cfo.android.compose)
    alias(libs.plugins.cfo.hilt)
}

android {
    namespace = "com.aicfo.app"

    defaultConfig {
        applicationId = "com.aicfo.personalcfo"
        versionCode = 1
        versionName = rootProject.file("VERSION").readText().trim()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // R8 minify + resource shrink are wired in issue 11.6 (proguard-r8-release).
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":feature:dashboard"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:transactions"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
}
