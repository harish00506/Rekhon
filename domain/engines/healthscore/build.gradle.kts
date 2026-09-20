// :domain:engines:healthscore — AI-FHS, §14. Six signals from the engines and ledger beneath it ->
// five weighted pillars -> one 0–1000 score, its band, every pillar's contribution, and the signal
// with the most points left to gain.
//
// Pure Kotlin/JVM (ARC-002): integer points and exact ratios, no Android, no serialisation. `api`,
// not `implementation`, because Money and EngineProvenance are on this module's public surface.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
}

// `HealthRulebookDriftTest` reads the rulebook; the kotlin-library convention already declares it a
// test input for every module (`configureRulebookAsTestInput`), so nothing is added here.
