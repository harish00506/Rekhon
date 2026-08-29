// :feature:settings — FR-SET-001's settings screen. Feature module (ARC-001): it never depends on
// another feature, and cross-feature navigation goes through :app's typed nav graph.
//
// Why it exists: monthly income, the per-feature consents and the app lock were all writable only by
// :feature:onboarding, which pops itself `inclusive = true` and is never reachable again. That froze
// three user-controllable settings at whatever was chosen in the first minute — and made the
// SMS_PARSING consent unrevocable from the UI, which P-01 forbids outright. This module is the way
// back to all three.
plugins {
    alias(libs.plugins.cfo.android.feature)
}

android {
    namespace = "com.aicfo.feature.settings"

    testOptions {
        // Robolectric renders this module's own strings.xml and theme; without the real resources
        // every stringResource() would come back blank and the flow test would assert nothing.
        unitTests.isIncludeAndroidResources = true
    }
}

// The Compose UI test launches a ComponentActivity, which only exists in the merged manifest of the
// debug variant, exactly as :feature:budgets records. The release variant still runs every other test.
tasks.withType<Test>()
    .matching { it.name.contains("Release") }
    .configureEach { exclude("**/SettingsScreenTest.class") }

dependencies {
    // Money and MoneyFormatter — the amounts the user types, parsed and rendered (MNY-001).
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    // The three stores this screen exists to write: settings seeds, the consent ledger, the lock.
    implementation(project(":core:datastore"))
    // PinVerifier — enabling the lock writes a PIN before it flips the flag (SEC-002).
    implementation(project(":core:crypto"))
    // Re-deriving the envelope plan when the income changes is the engine's job, never this screen's.
    implementation(project(":domain:engines:quicksetup"))
    // ARC-001's chain is feature -> domain -> data/core, so the repository sits below this module.
    implementation(project(":data:repository"))

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(testFixtures(project(":core:common")))
}
