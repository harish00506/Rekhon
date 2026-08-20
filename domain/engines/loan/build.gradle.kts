// :domain:engines:loan — FR-ACC-003's loan: what one instalment costs, how much of it is interest,
// and what the whole schedule looks like (issue 6.2). Pure Kotlin (ARC-002). It is handed five
// numbers and a date and answers with an EMI and a list of rows — never touching a database and
// never reading a clock, so a twenty-year schedule is provable on the JVM against a frozen golden
// file.
//
// No `ai/rules/rules-kb.json` test input, unlike :domain:engines:card. This engine reads no rule:
// amortisation has no threshold to tune, only arithmetic to get right. There is nothing to drift.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    // api, not implementation: Money, EngineProvenance and Loan all appear on this engine's public
    // surface, so every caller must be able to name them.
    api(project(":core:model"))
    api(project(":core:common"))

    // The golden gate reads `src/test/resources/golden/loan.txt`. A pure-Kotlin module has no
    // serialisation dependency (ARC-002), so the fixture is parsed by the test itself.
    testImplementation(libs.truth)
}
