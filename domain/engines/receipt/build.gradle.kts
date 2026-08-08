// :domain:engines:receipt — SRS §18.1's receipt parser (issue 3.8; FR-OCR-003). Pure Kotlin
// (ARC-002): no Android, no ML Kit. It reads text someone else recognised and decides what it
// means, which is why the ≥ 95% accuracy gate can run on the JVM against frozen fixtures.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    // api, not implementation: RecognizedText, Money, EngineProvenance and RuleCitation all appear
    // on ReceiptInput's and ReceiptFields' public surfaces.
    api(project(":core:model"))
    api(project(":core:common"))

    // The ≥ 95% eval gate reads `src/test/resources/eval/receipts.json`. A pure-Kotlin module has
    // no serialisation dependency (ARC-002), so the fixture is parsed by the test itself — see
    // ReceiptEvalTest — and this is only here for the assertions.
    testImplementation(libs.truth)
}

// The rulebook is an input to this module's tests, because `RulebookDriftTest` reads it (ADR-0005).
// Without this Gradle does not know that, so editing `ai/rules/rules-kb.json` alone leaves the test
// UP-TO-DATE and the drift gate reports green against a rulebook it never read — verified on
// 2026-08-07 by breaking a threshold and watching `BUILD SUCCESSFUL`. The deferral ADR-0005 records
// is only acceptable while the duplicate cannot drift, and the test is that mechanism; this is what
// connects the mechanism to the build.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("ai/rules/rules-kb.json"))
        .withPropertyName("rulebook")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
