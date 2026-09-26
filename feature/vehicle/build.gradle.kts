// :feature:vehicle — §12's maintenance screen (issue 10.4). Feature module (ARC-001): it never
// depends on another feature, and navigation to it goes through :app's typed nav graph.
//
// Its own module rather than a dashboard card, because a vehicle is a small ledger of its own: the
// odometer readings and the service bills are what the prediction is made from, and the screen has
// to both collect them and show the working over them (P-02).
plugins {
    alias(libs.plugins.cfo.android.feature)
}

android {
    namespace = "com.aicfo.feature.vehicle"

    testOptions {
        // Robolectric renders this module's own strings.xml and theme; without the real resources
        // every stringResource() would come back blank and the render test would assert nothing.
        unitTests.isIncludeAndroidResources = true
    }
}

// The Compose UI test launches a ComponentActivity, which exists only in the merged manifest of the
// debug variant — `androidx.compose.ui:ui-test-manifest` is a `debugImplementation` by design.
tasks.withType<Test>()
    .matching { it.name.contains("Release") }
    .configureEach {
        exclude("**/VehiclesScreenTest.class")
    }

dependencies {
    // Money and MoneyFormatter — every amount here is rendered, never computed (MNY-001, P-03).
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    // ARC-001's chain is feature -> domain -> data/core.
    implementation(project(":data:repository"))
    // The prediction, its basis and its alerts are this screen's subject; the engine itself is
    // injected by :app.
    implementation(project(":domain:engines:vehicle"))

    // FakeClock — the screen defaults its date fields to the profile's today, and a test that used
    // the real clock would be a test that changes its answer at midnight.
    testImplementation(testFixtures(project(":core:common")))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
