// :domain:engines:marketsignal — AI-MKT, §30. Cached daily closes + the signal library -> an
// OpportunityScore, its band, the measured hit rate of that band on this instrument's own history,
// and a staged tranche suggestion.
//
// Pure Kotlin/JVM (ARC-002). It reads a **cached** history and never a network (P-04), it computes
// every figure deterministically from that history (P-03/P-08), and it suggests tranches without
// ever moving a rupee (P-07).
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
}

// `MarketKbDriftTest` reads the signal library, so it is a declared test input: an edit to the file
// alone must re-run the drift gate rather than leave it UP-TO-DATE (issue 10.4's lesson).
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("ai/knowledge/market-signals.json"))
        .withPropertyName("marketSignals")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
