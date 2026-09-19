// :domain:engines:budget — §5.5's "what should this category cost, and are we on track?" (issue 4.4;
// FR-BUD-001/002/003). Pure Kotlin (ARC-002): it is handed a category's spend history and the
// month's elapsed days, and answers with a suggested amount and a status — never touching a
// database and never reading a clock, so both are provable on the JVM against a frozen golden file.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    // api, not implementation: Money, EngineProvenance and RuleCitation all appear on
    // BudgetSuggestion's and BudgetStatus's public surfaces, so every caller must be able to name
    // them.
    api(project(":core:model"))
    api(project(":core:common"))
    // The calendar-KB mirror (SeasonalEvent, SeasonalityPriors) lives in AI-SEAS since issue 9.3, so
    // the budget suggestion and the forecast read one copy of the priors, not two.
    api(project(":domain:engines:seasonality"))

    // The golden gate reads `src/test/resources/golden/budget.txt`. A pure-Kotlin module has no
    // serialisation dependency (ARC-002), so the fixture is parsed by the test itself.
    testImplementation(libs.truth)
}

// The rulebook is an input to this module's tests, because `RulebookDriftTest` reads it. Without
// this Gradle does not know that, so editing a threshold alone leaves the tests UP-TO-DATE and the
// drift gate reports green against a file it never read — the exact failure found and fixed in
// `:domain:engines:sms` on 2026-08-07 and in `:domain:engines:classification` on 2026-08-10. The
// calendar file stopped being one in issue 9.3: its drift test moved to :domain:engines:seasonality.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("ai/rules/rules-kb.json"))
        .withPropertyName("rulebook")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
