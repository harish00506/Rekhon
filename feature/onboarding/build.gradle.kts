// :feature:onboarding — first-run setup + consent capture (issue 2.1). Feature module (ARC-001).
plugins {
    alias(libs.plugins.cfo.android.feature)
}

android {
    namespace = "com.aicfo.feature.onboarding"

    defaultConfig {
        // The whole flow is also driven on a real device — the DoD's "run it" gate is not something
        // a JVM test can stand in for.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        // Robolectric renders this module's own strings.xml and theme; without the real resources
        // every stringResource() would come back blank and the flow test would assert nothing.
        unitTests.isIncludeAndroidResources = true
    }
}

// The Compose UI test launches a ComponentActivity, which only exists in the merged manifest of the
// debug variant — `androidx.compose.ui:ui-test-manifest` is a `debugImplementation` dependency by
// design, because shipping a launcher activity in a release library would be wrong. So it runs on
// debug only. The release variant still runs every other test in this module, which is what would
// catch a release-only compile or ProGuard-shaped problem.
tasks.withType<Test>()
    .matching { it.name.contains("Release") }
    .configureEach { exclude("**/OnboardingFlowTest.class") }

dependencies {
    implementation(project(":core:designsystem"))
    // Money for the quick-setup amounts (MNY-001) and the stores onboarding writes to.
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:datastore"))
    // Issue 2.2: the SECURITY step sets the PIN through the same Keystore-bound verifier the lock
    // screen checks against (SEC-002).
    implementation(project(":core:crypto"))
    // Issue 2.3: the quick-setup step derives its summary from the engine and persists the result
    // through the repository. ARC-001's chain is feature -> domain -> data/core, so both are below
    // this module; `:app` already injects a repository the same way (issue 2.2).
    implementation(project(":domain:engines:quicksetup"))
    implementation(project(":data:repository"))

    // Compose UI tests run on the JVM here (issue 2.1): the flow is checked on every `test` run,
    // not only when an emulator happens to be up.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // FakeClock / TestDispatchers (issue 1.3).
    testImplementation(testFixtures(project(":core:common")))

    androidTestImplementation(libs.androidx.test.junit)
    // Without this the instrumentation reports "Starting 0 tests" — a failure shaped like a pass.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // androidTest does not inherit the module's `implementation` deps, so the real stores the
    // device test drives have to be named again here.
    androidTestImplementation(project(":core:common"))
    androidTestImplementation(project(":core:datastore"))
    androidTestImplementation(project(":core:designsystem"))
    // Issue 2.2: the device test sets a PIN through the real Keystore-backed verifier — the one
    // path no JVM test can exercise, because a TEE does not exist off-device.
    androidTestImplementation(project(":core:crypto"))
    androidTestImplementation(project(":domain:engines:quicksetup"))
    androidTestImplementation(project(":data:repository"))
    androidTestImplementation(testFixtures(project(":core:common")))
}
