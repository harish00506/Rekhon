// :feature:dashboard — Safe-to-Spend / net-worth home screen. Features never depend on
// each other (ARC-001); cross-feature nav goes through :app's typed nav graph.
plugins {
    alias(libs.plugins.cfo.android.feature)
    // Issue 5.1: not part of the feature convention plugin (only :core:designsystem applied it
    // before this) — the acceptance criteria ask for light/dark/200% screenshot tests, which need it.
    alias(libs.plugins.paparazzi)
}

android {
    namespace = "com.aicfo.feature.dashboard"

    testOptions {
        // Issue 5.3: Robolectric renders this module's own strings.xml and theme for the privacy-blur
        // Compose test. Without the real resources every stringResource() comes back blank — and a
        // blank label would make "no amount is on screen" pass against a screen with no text at all.
        unitTests.isIncludeAndroidResources = true
    }
}

// The Compose UI test launches a ComponentActivity, which only exists in the merged manifest of the
// debug variant — `androidx.compose.ui:ui-test-manifest` is a `debugImplementation` dependency by
// design, because shipping a launcher activity in a release library would be wrong. So it runs on
// debug only, exactly as `:feature:onboarding` does for its flow test. The release variant still
// runs every other test here, which is what would catch a release-only compile problem.
tasks.withType<Test>()
    .matching { it.name.contains("Release") }
    .configureEach {
        exclude("**/DashboardPrivacyBlurTest.class")
        // Issue 6.6: same reason — it launches a ComponentActivity to tap the range chips.
        exclude("**/NetWorthHistoryScreenTest.class")
    }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":domain:usecase"))
    // Issue 2.3: the spending split is the budget quick setup persisted, read through the
    // repository. ARC-001's chain is feature -> domain -> data/core, so both sit below this module.
    implementation(project(":domain:engines:quicksetup"))
    // Issue 5.2: the card renders SafeToSpend's breakdown line by line, so this module names the
    // component enum to map each term to its strings.xml label. The type arrives through
    // :data:repository's `api` dependency; this declaration is what lets the screen name it directly
    // rather than relying on a transitive it does not own.
    implementation(project(":domain:engines:safetospend"))
    implementation(project(":data:repository"))

    // FakeClock: every screen that shows a date needs a fixed one in tests (issue 1.3).
    testImplementation(testFixtures(project(":core:common")))

    // Issue 5.3: the acceptance criteria ask for a Compose UI test asserting amounts are hidden.
    // Robolectric renders on the JVM, so it runs on every `./gradlew unitTests` rather than only
    // when a device happens to be attached — the same argument `:feature:onboarding` records for
    // its own flow test, and this repo has never had CI with an emulator.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
