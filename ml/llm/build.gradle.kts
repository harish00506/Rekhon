// :ml:llm — the on-device LLM behind LlmEngine (issue 10.5; §19, AI-ARC-007). It only verbalises
// figures an engine already computed (P-03), and everything it says passes the numeric guardrail
// (AI-ARC-004) before anyone sees it.
//
// An Android library rather than a pure-Kotlin one, deliberately: the verbaliser's sentences are
// string resources, so they are translatable (§21.6, and issue 10.8's Hindi pass) — and the model
// binding needs a Context for the weights, the assets and those resources.
//
// Since ADR-0079 the module holds BOTH models: the deterministic template verbaliser every phone
// gets, and the neural one a phone gets when the user has installed the weights. Which of the two
// answers a given turn is SelectingLlmEngine's decision, not a build-time choice.
plugins {
    alias(libs.plugins.cfo.android.library)
}

android {
    namespace = "com.aicfo.ml.llm"

    defaultConfig {
        // ADR-0080: `OnDeviceVerbaliserAccuracyTest` runs the real weights on a real device. It is
        // the only way to measure what the installed model actually says, and it skips itself when
        // no weights are installed — which is every CI runner.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

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

    // ADR-0079: the on-device model's runtime. Reads the weights from a local file and makes no
    // network call of any kind, which is what lets the neural path keep P-01 and P-04 intact. The
    // weights themselves are NOT here and never will be: 521 MB cannot ship in an APK, so the user
    // installs them (scripts/fetch_llm_model.sh) and LlmModelStore verifies what they installed.
    implementation(libs.mediapipe.tasks.genai)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)

    // ADR-0080: VerbaliserEvalTest reads the frozen fidelity set. Already this project's JSON
    // library and already used this way by :domain:engines:chat's guardrail eval (ADR-0054) —
    // runtime only, no compiler plugin, test scope in one more module. The alternative is the
    // regex file-reading the drift tests use, which these nested cases would not survive.
    testImplementation(libs.kotlinx.serialization.json)

    // The device-side accuracy run (ADR-0080). Instrumentation only: nothing here ships.
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// `VerbaliserEvalTest` reads the frozen fidelity set from the repository, so it is a declared test
// input: an edit to the set alone must re-run the gate rather than leave it UP-TO-DATE. The module
// already gets `ai/` through the convention plugin's checked-data inputs; this names the one file
// so a reader of this script can see what the gate reads.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("ai/eval/verbaliser-eval.json"))
        .withPropertyName("verbaliserEvalSet")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

/**
 * `./gradlew verbaliserEval` — §21.5's fidelity gate on its own, so CI can name it and a developer
 * can run it without the rest of the suite (the same argument `guardrailEval` makes).
 */
tasks.register("verbaliserEval") {
    group = "verification"
    description =
        "Runs the frozen verbaliser fidelity set — does the model say exactly the figures it was " +
        "given, in one sentence, the way this app writes them (ADR-0080)."
    dependsOn(tasks.named("testDebugUnitTest"))
}
