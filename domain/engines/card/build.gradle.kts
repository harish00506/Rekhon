// :domain:engines:card — FR-ACC-002's credit card: where its billing cycle falls, how much of the
// limit is being used, and whether either fact is worth interrupting someone about (issue 6.1).
// Pure Kotlin (ARC-002). It is handed today's date and two amounts and answers with dates, ratios
// and alerts — never touching a database and never reading a clock, so a whole billing cycle is
// provable on the JVM against a frozen golden file.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    // api, not implementation: Money, EngineProvenance and RuleCitation all appear on this engine's
    // public surface, so every caller must be able to name them.
    api(project(":core:model"))
    api(project(":core:common"))

    // The golden gate reads `src/test/resources/golden/card.txt`. A pure-Kotlin module has no
    // serialisation dependency (ARC-002), so the fixture is parsed by the test itself.
    testImplementation(libs.truth)
}

// The rulebook is an input to this module's tests, because `RulebookDriftTest` reads it. Without
// this Gradle does not know that, so editing RULE-CC-DUE alone leaves the tests UP-TO-DATE and the
// drift gate reports green against a file it never read — the failure found in :domain:engines:sms
// on 2026-08-07, in :domain:engines:classification on 2026-08-10, and guarded for ever since.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("ai/rules/rules-kb.json"))
        .withPropertyName("rulebook")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
