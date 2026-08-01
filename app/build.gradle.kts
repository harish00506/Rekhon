// :app — single-activity Compose host + Hilt graph. The only application module (§21.2).
// versionName is kept equal to the repo-root VERSION file (design spec §9, SemVer).
plugins {
    alias(libs.plugins.cfo.android.application)
    alias(libs.plugins.cfo.android.compose)
    alias(libs.plugins.cfo.hilt)
    // Type-safe navigation routes are @Serializable objects (issue 1.10), so a destination cannot
    // be reached with a mistyped string.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.aicfo.app"

    defaultConfig {
        applicationId = "com.aicfo.personalcfo"
        versionCode = 7
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
    implementation(project(":feature:accounts"))
    implementation(project(":feature:dashboard"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:transactions"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Issue 2.1: MainActivity collects a ViewModel to decide the start destination, so :app needs
    // the same Compose/Hilt bridges the feature convention plugin gives every :feature:* module.
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    // The DI graph wires the stores from issues 1.6 and 1.9.
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))

    // Issue 2.6: FR-ACC-005's daily net-worth snapshot runs as a WorkManager job. Both of these
    // have been in the version catalog since issue 1.1 and unused until now — this is the app's
    // first background work.
    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Issue 2.2: the app lock. :core:crypto is the PIN/lockout logic, :data:repository the audit
    // log, and biometric-ktx is BiometricPrompt (class 3, SEC-002) — which is also what pulls in
    // androidx.fragment, so MainActivity can be a FragmentActivity.
    implementation(project(":core:crypto"))
    implementation(project(":data:repository"))
    implementation(libs.androidx.biometric)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // Issue 2.6: the snapshot worker runs on Robolectric so its locked path — the one that would
    // otherwise crash the process — is checked on every `test` run, not only on a device.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.work.testing)
    // FakeClock (issue 1.3) — the app lock's timeout and lockout are clock-driven.
    testImplementation(testFixtures(project(":core:common")))
}
