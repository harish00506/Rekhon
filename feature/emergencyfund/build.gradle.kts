// :feature:emergencyfund — §10.1's emergency-fund screen (issue 7.2). Feature module (ARC-001): it
// never depends on another feature, and cross-feature navigation goes through :app's typed nav graph.
//
// Its own module rather than a card inside :feature:dashboard, for the reason :feature:goals is one:
// §10.1 requires "every number in the explanation links to its evidence", and a drill-down showing
// which categories counted as essential and which accounts counted as liquid is a screen, not a
// tile. The dashboard keeps the tile that leads here.
plugins {
    alias(libs.plugins.cfo.android.feature)
}

android {
    namespace = "com.aicfo.feature.emergencyfund"

    testOptions {
        // Robolectric renders this module's own strings.xml and theme; without the real resources
        // every stringResource() would come back blank and the flow test would assert nothing.
        unitTests.isIncludeAndroidResources = true
    }
}

// The Compose UI test launches a ComponentActivity, which only exists in the merged manifest of the
// debug variant — `androidx.compose.ui:ui-test-manifest` is a `debugImplementation` dependency by
// design. So it runs on debug only, exactly as :feature:goals does.
tasks.withType<Test>()
    .matching { it.name.contains("Release") }
    .configureEach { exclude("**/EmergencyFundFlowTest.class") }

dependencies {
    // Money and MoneyFormatter — every amount on this screen is rendered, never computed (MNY-001).
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    // ARC-001's chain is feature -> domain -> data/core, so the repository sits below this module.
    implementation(project(":data:repository"))
    // EmergencyFundPlan, EmergencyStatus and EssentialsBasis are this screen's subject: every figure
    // it draws is one the engine computed (P-03). The module is on the path so the state class can
    // name their types — it never constructs the engine, which :app injects into the repository.
    implementation(project(":domain:engines:emergencyfund"))

    // Compose UI tests run on the JVM here (the :feature:goals pattern): the flow is checked on
    // every `test` run, not only when an emulator happens to be up.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // Turbine and kotlinx-coroutines-test arrive from the feature convention plugin.
}
