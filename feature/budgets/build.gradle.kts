// :feature:budgets — FR-BUD-001/002/003's budget screen (issue 4.4). Feature module (ARC-001): it
// never depends on another feature, and cross-feature navigation goes through :app's typed nav graph.
//
// It is its own module rather than a screen inside :feature:dashboard because the dashboard shows the
// *outcome* — the 50/30/20 rings, read from quick setup's nature envelopes — while this shows the
// *plan*, per category. They read different tables through different repositories, and folding one
// into the other would make the dashboard depend on the budget engine to render a chart that does not
// use it.
plugins {
    alias(libs.plugins.cfo.android.feature)
}

android {
    namespace = "com.aicfo.feature.budgets"

    testOptions {
        // Robolectric renders this module's own strings.xml and theme; without the real resources
        // every stringResource() would come back blank and the flow test would assert nothing.
        unitTests.isIncludeAndroidResources = true
    }
}

// The Compose UI test launches a ComponentActivity, which only exists in the merged manifest of the
// debug variant — `androidx.compose.ui:ui-test-manifest` is a `debugImplementation` dependency by
// design. So it runs on debug only, exactly as :feature:categories does. The release variant still
// runs every other test in this module.
tasks.withType<Test>()
    .matching { it.name.contains("Release") }
    .configureEach { exclude("**/BudgetsFlowTest.class") }

dependencies {
    // Money and MoneyFormatter — the amount the user types, parsed and rendered (MNY-001).
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    // ARC-001's chain is feature -> domain -> data/core, so the repository sits below this module.
    implementation(project(":data:repository"))
    // BudgetStatus and BudgetSuggestion are this screen's subject: every figure it draws is one the
    // engine computed (P-03). The module is on the path here so the state class can name their types
    // — it never constructs the engine, which :app injects into the repository.
    implementation(project(":domain:engines:budget"))

    // Compose UI tests run on the JVM here (the :feature:categories pattern): the flow is checked on
    // every `test` run, not only when an emulator happens to be up.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // Turbine and kotlinx-coroutines-test arrive from the feature convention plugin.
}
