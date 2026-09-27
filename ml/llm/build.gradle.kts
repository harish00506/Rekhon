// :ml:llm — the on-device LLM behind LlmEngine (issue 10.5; §19, AI-ARC-007). It only verbalises
// figures an engine already computed (P-03), and everything it says passes the numeric guardrail
// (AI-ARC-004) before anyone sees it.
//
// An Android library rather than a pure-Kotlin one, deliberately: the verbaliser's sentences are
// string resources, so they are translatable (§21.6, and issue 10.8's Hindi pass) — and the real
// model binding, when it lands, needs a Context anyway.
plugins {
    alias(libs.plugins.cfo.android.library)
}

android {
    namespace = "com.aicfo.ml.llm"

    testOptions {
        // Robolectric renders this module's own strings.xml; without the real resources every
        // getString() would come back blank and the sentence tests would assert nothing.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    // The LlmEngine port and the draft it is handed. The model never sees anything else (P-01).
    api(project(":domain:engines:chat"))

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
}
