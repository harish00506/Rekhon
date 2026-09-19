// :domain:engines:stream — AI-CLS Stage 2, §8.2. Each expense stream (a Stage-1 category) over the
// last six closed months -> FIXED / SEMI_FIXED / VARIABLE, and the month's fixed load.
//
// Pure Kotlin/JVM (ARC-002): the whole score — cv, cadence, day-lock — is integer arithmetic on the
// JVM, with no Android and no serialisation dependency. `api`, not `implementation`, because Money
// and EngineProvenance are on this module's public surface.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
}

// `StreamKbDriftTest` reads the classification knowledge base, so it is a declared test input: an
// edit to the file alone must re-run the drift gate rather than leave it UP-TO-DATE (the fix
// :domain:engines:classification carries for the same reason).
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("ai/knowledge/classification-kb.json"))
        .withPropertyName("classificationKnowledgeBase")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
