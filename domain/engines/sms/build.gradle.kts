// :domain:engines:sms — the opt-in bank-alert parser (issue 3.9; SRS §18, §23, P-01). Pure Kotlin
// (ARC-002): no Android, no ContentResolver, no permission. It reads a message someone else fetched
// and decides whether it describes money moving, which is why the whole false-positive gate can be
// proved on the JVM against frozen fixtures — on a device it would need a Play-restricted permission
// and a populated inbox to test at all.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    // api, not implementation: SmsMessage, Money, EngineProvenance and RuleCitation all appear on
    // SmsInput's and SmsDraftFields' public surfaces, so every caller must be able to name them.
    api(project(":core:model"))
    api(project(":core:common"))

    // The eval gate reads `src/test/resources/eval/sms.txt`. A pure-Kotlin module has no
    // serialisation dependency (ARC-002), so the fixture is parsed by the test itself — see
    // SmsEvalTest — and this is only here for the assertions.
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
