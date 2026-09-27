// :feature:market — §30's Opportunity screen (issue 10.7). Feature module (ARC-001): it never
// depends on another feature, and navigation to it goes through :app's typed nav graph.
//
// The screen shows a verdict **and the evidence for it** — each signal's points and the number it
// measured, the hit rate that verdict has actually had, and the gates that stopped any suggestion.
// It renders; it never computes (P-03), and it never buys anything (P-07).
plugins {
    alias(libs.plugins.cfo.android.feature)
}

android {
    namespace = "com.aicfo.feature.market"

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

tasks.withType<Test>()
    .matching { it.name.contains("Release") }
    .configureEach {
        exclude("**/OpportunityScreenTest.class")
    }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":data:repository"))
    // The assessment's types are this screen's subject; the engine is injected by :app.
    implementation(project(":domain:engines:marketsignal"))

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
