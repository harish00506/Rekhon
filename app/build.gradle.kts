// :app — single-activity Compose host + Hilt graph. The only application module (§21.2).
// versionName is kept equal to the repo-root VERSION file (design spec §9, SemVer).
plugins {
    alias(libs.plugins.cfo.android.application)
    alias(libs.plugins.cfo.android.compose)
    alias(libs.plugins.cfo.hilt)
    // Type-safe navigation routes are @Serializable objects (issue 1.10), so a destination cannot
    // be reached with a mistyped string.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.aicfo.app"

    defaultConfig {
        applicationId = "com.aicfo.personalcfo"
        versionCode = 24
        versionName = rootProject.file("VERSION").readText().trim()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // R8 minify + resource shrink are wired in issue 11.6 (proguard-r8-release).
            isMinifyEnabled = false
        }
    }

    testOptions {
        // Issue 3.1: Robolectric renders this module's strings.xml and theme for the FAB test;
        // without the real resources the content description would come back blank.
        unitTests.isIncludeAndroidResources = true
    }
}

// The Compose UI test launches a ComponentActivity, which only exists in the merged manifest of the
// debug variant — `androidx.compose.ui:ui-test-manifest` is a `debugImplementation` dependency by
// design. The same exclusion :feature:accounts and :feature:transactions carry.
tasks.withType<Test>()
    .matching { it.name.contains("Release") }
    .configureEach { exclude("**/AddTransactionFabTest.class") }

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":feature:accounts"))
    implementation(project(":feature:budgets"))
    implementation(project(":feature:categories"))
    implementation(project(":feature:dashboard"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:transactions"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Issue 2.1: MainActivity collects a ViewModel to decide the start destination, so :app needs
    // the same Compose/Hilt bridges the feature convention plugin gives every :feature:* module.
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    // The DI graph wires the stores from issues 1.6 and 1.9.
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))

    // Issue 2.6: FR-ACC-005's daily net-worth snapshot runs as a WorkManager job. Both of these
    // have been in the version catalog since issue 1.1 and unused until now — this is the app's
    // first background work.
    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Issue 2.2: the app lock. :core:crypto is the PIN/lockout logic, :data:repository the audit
    // log, and biometric-ktx is BiometricPrompt (class 3, SEC-002) — which is also what pulls in
    // androidx.fragment, so MainActivity can be a FragmentActivity.
    implementation(project(":core:crypto"))
    implementation(project(":data:repository"))
    implementation(libs.androidx.biometric)

    // Issue 3.8: the DI graph binds the on-device recogniser (FR-OCR-002) and the receipt parser
    // (FR-OCR-003) by name, so :app compiles against both. Declared explicitly rather than relying
    // on :data:repository's `api` to leak them in — a binding whose type arrived by accident breaks
    // the day that module reorganises its dependencies.
    implementation(project(":ml:ocr"))
    implementation(project(":domain:engines:receipt"))

    // Issue 5.5: the home-screen widget. This edge is what makes the widget ship at all — its
    // `<receiver>` lives in :widget's own manifest and only merges into the APK from here. :app also
    // names `CfoWidget` directly: the widget renders from a cache it cannot fill itself (ADR-0024),
    // so `WidgetRefreshWorker` and `WidgetBlurWatcher` on this side are the only writers.
    implementation(project(":widget"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // Issue 2.6: the snapshot worker runs on Robolectric so its locked path — the one that would
    // otherwise crash the process — is checked on every `test` run, not only on a device.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.work.testing)
    // Issue 3.1: FR-TXN-002's first tap — the global FAB — lives in this module, so the half of the
    // tap budget that :feature:transactions cannot see is asserted here, on the JVM.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Issue 3.7: the E2E smoke. `:app:connectedDebugAndroidTest` is named in the Definition of Done
    // (`docs/issues/00-issue-workflow.md` phase 8) and had **no source set at all** until now, so the
    // task passed by running zero tests — the governance audit's failure mode exactly. These four
    // lines are what make it a gate. No Hilt testing artifact is needed: the smoke runs against the
    // *real* `@HiltAndroidApp` graph and the real SQLCipher database, which is the whole point.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // Issue 5.5: WidgetDeviceTest inflates the widget's RemoteViews on a device — the step between
    // a composed Glance tree and what a launcher actually draws, which has no JVM equivalent and is
    // why the widget carries no Paparazzi baselines (ADR-0024).
    androidTestImplementation(project(":widget"))
    androidTestImplementation(libs.glance.appwidget)
    // FakeClock (issue 1.3) — the app lock's timeout and lockout are clock-driven.
    testImplementation(testFixtures(project(":core:common")))
}
