// :domain:usecase — pure-Kotlin use cases orchestrating engines for features.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    // api, not implementation: Money, Result and AppError all appear on the household
    // aggregation's public surface, so every caller must be able to name them (issue 13.1).
    api(project(":core:model"))
    api(project(":core:common"))
    implementation(project(":domain:engines:forecast"))

    // Issue 13.1: the seeded-case harness from 12.1, for the two aggregation properties — the
    // total equals its parts, and member order cannot change it. Test-only, so ARC-002 holds.
    testImplementation(testFixtures(project(":core:common")))
}
