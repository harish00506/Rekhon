// :feature:advisor — §13's Purchase Advisor (issue 10.1). Feature module (ARC-001): it never
// depends on another feature, and navigation to it goes through :app's typed nav graph.
//
// Its own module rather than a dashboard card, because §13.2's card is a screen: a verdict, a
// gate-by-gate table, an impact strip and the alternatives, with the history of past verdicts
// beneath it. A tile could not show the working, and the working is the point (P-02).
plugins {
    alias(libs.plugins.cfo.android.feature)
}

android {
    namespace = "com.aicfo.feature.advisor"

    testOptions {
        // Robolectric renders this module's own strings.xml and theme; without the real resources
        // every stringResource() would come back blank and the render test would assert nothing.
        unitTests.isIncludeAndroidResources = true
    }
}

// The Compose UI test launches a ComponentActivity, which exists only in the merged manifest of the
// debug variant — `androidx.compose.ui:ui-test-manifest` is a `debugImplementation` by design. So it
// runs on debug only, exactly as :feature:emergencyfund and :feature:goals do.
tasks.withType<Test>()
    .matching { it.name.contains("Release") }
    .configureEach { exclude("**/AdvisorScreenTest.class") }

dependencies {
    // Money and MoneyFormatter — every amount here is rendered, never computed (MNY-001, P-03).
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    // ARC-001's chain is feature -> domain -> data/core.
    implementation(project(":data:repository"))
    // The verdict, the gates and their figures are this screen's subject; the module is on the path
    // so the state class can name their types. It never constructs the engine — :app injects it.
    implementation(project(":domain:engines:purchase"))

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
