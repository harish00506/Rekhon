// :domain:engines:guardrail — AI-GRD, AI-ARC-004. Candidate user-facing text + the figures the
// engines actually produced -> pass, send it back to be written again, or refuse.
//
// Pure Kotlin/JVM (ARC-002): the gate is string and number matching, never the model judging itself
// (GRD-001), so it is testable without a device and without an LLM.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
}
