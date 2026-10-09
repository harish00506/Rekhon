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

    // Issue 13.5: BusinessModeDriftTest asserts, at COMPILE time, that the receipt engine still
    // extracts a GST figure — a reflection-free check that cannot rot into reading source text.
    testImplementation(project(":domain:engines:receipt"))
}

// Issue 13.5: `BusinessModeDriftTest` reads two files from outside this module at runtime — the
// database's entity declarations and ADR-0073 itself. Neither is a declared input of this module's
// test task, so without these lines Gradle would call the task UP-TO-DATE on exactly the edits the
// test exists to catch: a `tax_relevance` column appearing, or the ADR's findings being edited away.
//
// That is the same bug issues 7.2, 11.5, 11.7 and 13.1 each found in a different guise, and 13.1's
// `configureOwnSourceAsTestInput()` does not cover it — that declares a module's OWN src/main, and
// both of these live somewhere else.
tasks.withType<Test>().configureEach {
    inputs.file(
        rootProject.file("core/database/src/main/kotlin/com/aicfo/core/database/entity/Entities.kt"),
    ).withPropertyName("databaseEntities")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(
        rootProject.file("docs/adr/0073-the-business-book-is-a-second-profile-and-two-promises-were-broken.md"),
    ).withPropertyName("businessModeAdr")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
