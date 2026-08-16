// :domain:engines:safetospend — §5.2/§14's "what is left to spend this month, and why" (issue 5.2;
// AI-STS). Pure Kotlin (ARC-002): it is handed six already-resolved amounts and a window, and
// answers with one figure plus the line-by-line breakdown that figure came from — never touching a
// database and never reading a clock, so the whole subtraction is provable on the JVM against a
// frozen golden file.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    // api, not implementation: Money, EngineProvenance and RuleCitation all appear on SafeToSpend's
    // public surface, so every caller must be able to name them.
    api(project(":core:model"))
    api(project(":core:common"))

    // The golden gate reads `src/test/resources/golden/safe-to-spend.txt`. A pure-Kotlin module has
    // no serialisation dependency (ARC-002), so the fixture is parsed by the test itself.
    testImplementation(libs.truth)
}

// The rulebook is an input to this module's tests, because `RulebookDriftTest` reads it. Without
// this Gradle does not know that, so editing RULE-STS alone leaves the tests UP-TO-DATE and the
// drift gate reports green against a file it never read — the failure found in :domain:engines:sms
// on 2026-08-07, in :domain:engines:classification on 2026-08-10, and guarded for in :budget since.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("ai/rules/rules-kb.json"))
        .withPropertyName("rulebook")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
