// :domain:engines:quicksetup — turns onboarding's FR-ONB-002 seeds into budget envelopes,
// recurring seeds and an emergency-fund target. Pure Kotlin (ARC-002): no Android imports, so the
// whole engine is unit-testable on the JVM and portable to KMP later (issue 13.7).
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    // api, not implementation: Money, EngineProvenance and RuleCitation all appear on
    // QuickSetupPlan's public surface, so every caller must be able to name them.
    api(project(":core:model"))
    api(project(":core:common"))
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
