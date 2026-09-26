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
}

// `ChatRegistryDriftTest` reads the tool registry, so it is a declared test input: an edit to the
// file alone must re-run the drift gate rather than leave it UP-TO-DATE (issue 10.4's lesson).
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("ai/skills/tool-registry.json"))
        .withPropertyName("toolRegistry")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
