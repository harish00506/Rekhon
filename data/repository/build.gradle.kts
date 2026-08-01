// :data:repository — the ONLY modules that touch DAOs or network (ARC-005). Exposes
// domain models to ViewModels; depends downward on core + domain.
plugins {
    alias(libs.plugins.cfo.android.library)
}

android {
    namespace = "com.aicfo.data.repository"

    testOptions {
        // Robolectric needs the merged Android resources to boot its runtime.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // api, not implementation: AuditEvent/AuditMethod appear on AuditLogRepository's public
    // surface, so callers (the DI graph, the lock ViewModel) must be able to name them.
    api(project(":core:model"))
    api(project(":core:common"))
    implementation(project(":core:database"))
    // Issue 2.4: demo mode's on/off flag is a setting, and settings live in Proto DataStore
    // (§21.3, SharedPreferences banned). data → core is the allowed direction (ARC-001).
    implementation(project(":core:datastore"))
    implementation(project(":domain:usecase"))

    // api: QuickSetupPlan and BudgetEnvelope appear on QuickSetupRepository's public surface, so
    // the ViewModels that call it must be able to name them (issue 2.3).
    api(project(":domain:engines:quicksetup"))

    // withTransaction — the atomicity guarantee applySeeds is built on (issue 2.3).
    implementation(libs.room.ktx)

    // Issue 2.2: the repository's SQL and mapping are tested against a real SQLite engine on the
    // JVM. Unencrypted and in-memory on purpose — SQLCipher needs a device, and what is under test
    // here is the query, not the encryption (see the test's class doc).
    testImplementation(libs.robolectric)
    // Brings androidx.test:core transitively, which is where ApplicationProvider lives.
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // FakeClock / TestDispatchers (issue 1.3).
    testImplementation(testFixtures(project(":core:common")))
}
