// :feature:chat — §19's assistant (issue 10.5). Feature module (ARC-001): it never depends on
// another feature, and navigation to it goes through :app's typed nav graph.
//
// The screen renders a conversation and nothing else: every figure in it was computed by an engine,
// verbalised by a model behind LlmEngine, and checked by AI-GRD before it got here (P-03,
// AI-ARC-004). The screen cannot add a number of its own, and does not try.
plugins {
    alias(libs.plugins.cfo.android.feature)
}

android {
    namespace = "com.aicfo.feature.chat"

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

tasks.withType<Test>()
    .matching { it.name.contains("Release") }
    .configureEach {
        exclude("**/ChatScreenTest.class")
    }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":data:repository"))
    // The reply's types are this screen's subject; the engines are injected by :app.
    implementation(project(":domain:engines:chat"))

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
