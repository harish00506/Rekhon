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
