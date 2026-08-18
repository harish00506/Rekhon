// :feature:accounts — create/read/update/archive/soft-delete for every account type (issue 2.5,
// FR-ACC-001, FR-ACC-007). Feature module (ARC-001): it never depends on another feature, and
// cross-feature navigation goes through :app's typed nav graph.
plugins {
    alias(libs.plugins.cfo.android.feature)
}

android {
    namespace = "com.aicfo.feature.accounts"

    testOptions {
        // Robolectric renders this module's own strings.xml and theme; without the real resources
        // every stringResource() would come back blank and the flow test would assert nothing.
        unitTests.isIncludeAndroidResources = true
    }
}

// The Compose UI test launches a ComponentActivity, which only exists in the merged manifest of the
// debug variant — `androidx.compose.ui:ui-test-manifest` is a `debugImplementation` dependency by
// design. So it runs on debug only, exactly as :feature:onboarding does. The release variant still
// runs every other test in this module.
tasks.withType<Test>()
    .matching { it.name.contains("Release") }
    .configureEach {
        exclude("**/AccountsFlowTest.class")
        // Issue 6.1's card-terms form is the same shape and needs the same exclusion.
        exclude("**/AccountEditorCardFieldsTest.class")
    }

dependencies {
    // Account, AccountType and Money — the domain types the ViewModel exposes (ARC-005).
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    // ARC-001's chain is feature -> domain -> data/core, so the repository sits below this module.
    implementation(project(":data:repository"))

    // Compose UI tests run on the JVM here (the :feature:onboarding pattern): the flow is checked
    // on every `test` run, not only when an emulator happens to be up.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // FakeClock / FakeIdGenerator / TestDispatchers (issues 1.3, 2.5).
    testImplementation(testFixtures(project(":core:common")))
}
