// :domain:engines:networth — FR-ACC-005's assets − liabilities. Pure Kotlin (ARC-002): no Android
// imports, so the whole engine is unit-testable on the JVM and portable to KMP later (issue 13.7).
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    // api, not implementation: Money, AccountType and EngineProvenance all appear on
    // NetWorthResult's and NetWorthInput's public surfaces, so every caller must be able to name them.
    api(project(":core:model"))
    api(project(":core:common"))
}
