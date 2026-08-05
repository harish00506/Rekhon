// :feature:transactions — add a transaction in ≤ 3 taps, and the recent list that shows it landed
// (issue 3.1, FR-TXN-002). Feature module (ARC-001): it never depends on another feature, and
// cross-feature navigation goes through :app's typed nav graph.
plugins {
    alias(libs.plugins.cfo.android.feature)
}

android {
    namespace = "com.aicfo.feature.transactions"

    testOptions {
        // Robolectric renders this module's own strings.xml and theme; without the real resources
        // every stringResource() would come back blank and the tap-count test would assert nothing.
        unitTests.isIncludeAndroidResources = true
    }
}

// The Compose UI test launches a ComponentActivity, which only exists in the merged manifest of the
// debug variant — `androidx.compose.ui:ui-test-manifest` is a `debugImplementation` dependency by
// design. So it runs on debug only, exactly as :feature:accounts does. The release variant still
// runs every other test in this module.
tasks.withType<Test>()
    .matching { it.name.contains("Release") }
    .configureEach { exclude("**/*FlowTest.class") }

dependencies {
    // Transaction, Category and Money — the domain types the ViewModels expose (ARC-005).
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    // ARC-001's chain is feature -> domain -> data/core, so the repositories sit below this module.
    implementation(project(":data:repository"))

    // Issue 3.6: collectAsLazyPagingItems, the Compose half of FR-TXN-007's paged list.
    implementation(libs.androidx.paging.compose)
    // `asSnapshot` — how a ViewModel test asserts against a paged stream, which is otherwise a
    // sequence of load events rather than a list.
    testImplementation(libs.androidx.paging.testing)

    // Compose UI tests run on the JVM here (the :feature:accounts pattern): FR-TXN-002's tap budget
    // is checked on every `test` run, not only when an emulator happens to be up.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // FakeClock / FakeIdGenerator / TestDispatchers (issues 1.3, 2.5).
    testImplementation(testFixtures(project(":core:common")))
}
