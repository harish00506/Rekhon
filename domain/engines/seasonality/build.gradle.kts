// :domain:engines:seasonality — AI-SEAS, §9.3. Per-category monthly spend history + the Indian
// calendar KB -> a seasonal index per category and month, and the factor it puts on the forecast's
// everyday spend for each month ahead.
//
// Pure Kotlin/JVM (ARC-002). It owns the one typed mirror of `ai/knowledge/calendar-seasonality.json`
// (issue 9.3 moved it here from :domain:engines:budget, which now reads it through this module).
// `api`, not `implementation`, because EngineProvenance and RuleCitation are on its public surface.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
}

// `SeasonalityKbDriftTest` reads the calendar knowledge base, so it is a declared test input: an
// edit to the file alone must re-run the drift gate rather than leave it UP-TO-DATE.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("ai/knowledge/calendar-seasonality.json"))
        .withPropertyName("calendarSeasonality")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
