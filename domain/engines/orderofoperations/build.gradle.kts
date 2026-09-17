// :domain:engines:orderofoperations — §36's "what should my next rupee do?" (issue 7.5; AI-FOO).
// Pure Kotlin (ARC-002): it is handed the month's surplus, the emergency-fund position, the user's
// debts and what their goals need, and answers with the eight-stage waterfall — never touching a
// database and never reading a clock, so every answer is provable on the JVM against a frozen
// golden file.
//
// Named for what it computes, not after the registry's original `:domain:engines:orchestrator`:
// "orchestrator" is already AI-ORCH, the §7.2 insight pipeline in
// `ai/orchestrator/insight-orchestrator.yaml`, and a module of that name holding only AI-FOO would
// be the first thing a reader got wrong (ADR-0037).
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    // api, not implementation: Money, EngineProvenance and RuleCitation all appear on
    // OrderOfOperations' public surface, so every caller must be able to name them.
    api(project(":core:model"))
    api(project(":core:common"))

    // SurplusBasis, and only that. AI-FOO is L5 and consumes the surplus AI-GOAL.waterfall already
    // resolved (ADR-0035), so it names the same enum rather than a second copy that could drift from
    // it. Layer N depending on a lower layer is the direction CLAUDE.md §2 allows (ADR-0037).
    api(project(":domain:engines:goals"))
}

// `OrderOfOperationsRulesDriftTest` reads the FOO rule set, which is not the rulebook the
// convention plugin already declares (`configureRulebookAsTestInput`). Without this Gradle does not
// know the test reads it, so editing a stage threshold alone would leave the test UP-TO-DATE and
// the drift gate green against a file it never opened — the failure found in
// `:domain:engines:sms` on 2026-08-07 and `:domain:engines:goals` on 2026-09-02.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("ai/rules/financial-order-of-operations.json"))
        .withPropertyName("orderOfOperations")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
