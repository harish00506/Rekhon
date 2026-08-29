// :domain:engines:nature — §8.3's "what did this money become?" (issue 4.3; AI-CLS-N). Pure Kotlin
// (ARC-002): it is handed one transaction's shape — the account it moved in, the account it moved
// to, its category's nature and what the user has decided about this merchant before — and answers
// with a nature and the rule that decided it. No database, no clock, so the whole decision order is
// provable on the JVM against a frozen golden file.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    // api, not implementation: AccountType, CategoryNature, Money, TransactionType, EngineProvenance
    // and RuleCitation all appear on NatureInput's and NatureVerdict's public surfaces, so every
    // caller must be able to name them.
    api(project(":core:model"))
    api(project(":core:common"))

    // The golden gate reads `src/test/resources/golden/nature.txt`. A pure-Kotlin module has no
    // serialisation dependency (ARC-002), so the fixture is parsed by the test itself.
    testImplementation(libs.truth)
}

// The knowledge base is an input to this module's tests, because `NatureKbDriftTest` reads it
// (ADR-0016). Without this Gradle does not know that, so editing
// `ai/knowledge/classification-kb.json` alone leaves the test UP-TO-DATE and the drift gate reports
// green against a file it never read — verified and fixed the same way in `:domain:engines:sms` on
// 2026-08-07 and in `:domain:engines:classification` on 2026-08-10.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("ai/knowledge/classification-kb.json"))
        .withPropertyName("classificationKnowledgeBase")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
