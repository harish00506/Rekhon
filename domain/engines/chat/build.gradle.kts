// :domain:engines:chat — AI-CHAT, §19. The L6 layer: it parses intent, plans which registry tools
// to call, and turns their structured results into a reply — after the numeric guardrail has
// checked every figure in it (AI-ARC-004).
//
// Pure Kotlin/JVM (ARC-002). **It computes nothing** (P-03): every number in a reply arrives as a
// tool result and leaves as the same tool result. It depends on :domain:engines:guardrail because
// AI-GRD is the gate this layer exists behind, and re-implementing that check here would be a
// second definition of what "verified" means.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    api(project(":domain:engines:guardrail"))

    // Test-only: `GuardrailEvalTest` reads `ai/eval/guardrail-eval.json`, whose cases are nested
    // objects and arrays. The regex parsing the drift tests use would be the wrong tool here — and
    // this is the runtime only, parsing into `JsonElement`, so no compiler plugin is involved.
    // DECISIONS.md carries the row.
    testImplementation(libs.kotlinx.serialization.json)
}

// `ChatRegistryDriftTest` reads the tool registry and `GuardrailEvalTest` reads the frozen eval
// set, so both are declared test inputs: an edit to either file alone must re-run the gate rather
// than leave it UP-TO-DATE (issue 10.4's lesson, and the reason a drift gate can pass without
// running at all).
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("ai/skills/tool-registry.json"))
        .withPropertyName("toolRegistry")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("ai/eval/guardrail-eval.json"))
        .withPropertyName("guardrailEvalSet")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

/**
 * `./gradlew guardrailEval` — §21.5's regression gate on its own, so CI can name it and a developer
 * can run it without the rest of the suite. It is also part of `unitTests`, which is what actually
 * blocks a merge; this task exists so the step in the pipeline says what it is checking.
 */
tasks.register("guardrailEval") {
    group = "verification"
    description = "Runs the frozen chat-guardrail evaluation set (AI-ARC-004, §21.5)."
    dependsOn(tasks.named("test"))
}
