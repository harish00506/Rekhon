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
    implementation(project(":domain:usecase"))

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
