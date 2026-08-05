// :domain:engines:quicksetup — turns onboarding's FR-ONB-002 seeds into budget envelopes,
// recurring seeds and an emergency-fund target. Pure Kotlin (ARC-002): no Android imports, so the
// whole engine is unit-testable on the JVM and portable to KMP later (issue 13.7).
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    // api, not implementation: Money, EngineProvenance and RuleCitation all appear on
    // QuickSetupPlan's public surface, so every caller must be able to name them.
    api(project(":core:model"))
    api(project(":core:common"))
}
