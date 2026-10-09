// :domain:engines:tax — §38's Tax Engine v2: both regimes computed from the user's own deductions,
// the break-even shown in rupees, and capital gains labelled by §38.2's post-23-July-2024 rules.
// Pure Kotlin (ARC-002). Every parameter is a row in `ai/knowledge/tax-kb-fy2025-26.json` (§6,
// TAX-002), every result is stamped with the FY rules version, and the engine estimates — it never
// files (P-07). Issue 13.4.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    // api, not implementation: Money, EngineProvenance and RuleCitation are on the public surface.
    api(project(":core:model"))
    api(project(":core:common"))

    // Issue 12.1's shared golden-file + seeded-case harness. Test-only, so ARC-002 holds.
    testImplementation(testFixtures(project(":core:common")))
}
