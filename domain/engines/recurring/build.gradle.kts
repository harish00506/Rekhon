// :domain:engines:recurring — FR-TXN-006's "≥ 2 similar transactions" detector. Pure Kotlin
// (ARC-002): no Android imports, so the whole engine is unit-testable on the JVM and portable to
// KMP later (issue 13.7). `java.time` is JVM stdlib, not Android — the calendar arithmetic stays.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    // api, not implementation: Money, EngineProvenance and RuleCitation all appear on
    // RecurringSeries' and RecurringInput's public surfaces, so every caller must be able to name them.
    api(project(":core:model"))
    api(project(":core:common"))
}
