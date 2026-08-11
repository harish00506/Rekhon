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

    // The golden gate reads `src/test/resources/golden/budget.txt`. A pure-Kotlin module has no
    // serialisation dependency (ARC-002), so the fixture is parsed by the test itself.
    testImplementation(libs.truth)
}

// Both knowledge files are inputs to this module's tests, because `RulebookDriftTest` and
// `SeasonalityKbDriftTest` read them. Without this Gradle does not know that, so editing a
// threshold alone leaves the tests UP-TO-DATE and the drift gates report green against files they
// never read — the exact failure found and fixed in `:domain:engines:sms` on 2026-08-07 and in
// `:domain:engines:classification` on 2026-08-10.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("ai/rules/rules-kb.json"))
        .withPropertyName("rulebook")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("ai/knowledge/calendar-seasonality.json"))
        .withPropertyName("calendarSeasonality")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
