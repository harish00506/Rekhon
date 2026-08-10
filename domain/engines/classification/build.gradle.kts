// :domain:engines:classification — Stage-1 auto-categorisation (issue 4.2; SRS §8.1, AI-CLS). Pure
// Kotlin (ARC-002): it is handed a merchant string, the profile's live categories and whatever the
// user has filed under that merchant before, and it answers with a category or with nothing. No
// database, no clock, no Android — which is what lets the whole precedence chain and the ≥ 92 %
// accuracy gate be proved on the JVM against a frozen set.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    // api, not implementation: Category, EngineProvenance and RuleCitation all appear on
    // ClassificationInput's and CategorySuggestion's public surfaces, so every caller must be able
    // to name them.
    api(project(":core:model"))
    api(project(":core:common"))

    // The eval gate reads `src/test/resources/eval/categorisation.txt`. A pure-Kotlin module has no
    // serialisation dependency (ARC-002), so the fixture is parsed by the test itself — see
    // ClassificationEvalTest — and this is only here for the assertions.
    testImplementation(libs.truth)
}

// The knowledge base is an input to this module's tests, because `ClassificationKbDriftTest` reads
// it (ADR-0014, ADR-0015). Without this Gradle does not know that, so editing
// `ai/knowledge/classification-kb.json` alone leaves the test UP-TO-DATE and the drift gate reports
// green against a file it never read — the exact failure `:domain:engines:sms` verified and fixed
// the same way on 2026-08-07.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("ai/knowledge/classification-kb.json"))
        .withPropertyName("classificationKnowledgeBase")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
