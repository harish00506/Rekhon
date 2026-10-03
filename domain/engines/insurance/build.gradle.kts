// :domain:engines:insurance — §39.1's Protection Suite: the term-cover gap, the health floor, and
// the policies that are investments wearing an insurance label. Pure Kotlin (ARC-002). Every
// threshold is a rulebook row (§6, RULE-TERM-10X, RULE-HEALTH-COVER, RULE-TERM-VS-ENDOW), and every
// figure is arithmetic the user can check — the engine advises and never acts (P-07). Issue 13.3.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    // api, not implementation: Money, EngineProvenance and RuleCitation are all on the public
    // surface, so every caller must be able to name them.
    api(project(":core:model"))
    api(project(":core:common"))

    // Issue 12.1's shared golden-file + seeded-case harness. Test-only, so ARC-002 holds.
    testImplementation(testFixtures(project(":core:common")))
}
