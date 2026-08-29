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

    // Issue 6.6: the trend's golden runner asserts one record at a time and has to name which one
    // failed. `assertWithMessage("%s - change", label)` does that; a bare assertEquals reports two
    // numbers and leaves you grepping the fixture for which of ten records they came from.
    // Already a project dependency (DECISIONS.md, issue 1.1) and used by twelve other modules.
    testImplementation(libs.truth)
}
