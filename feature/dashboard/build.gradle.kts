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
}
