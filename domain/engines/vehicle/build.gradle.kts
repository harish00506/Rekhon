// :domain:engines:vehicle — AI-VEH, §12. Odometer readings and a service history become a date, a
// cost range and a handful of alerts, from the bundled vehicle-maintenance knowledge base.
//
// Pure Kotlin/JVM (ARC-002): no Android, no clock, no I/O. The knowledge base is mirrored as typed
// values (ADR-0017's pattern) and a drift test holds the mirror to the file.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
}

// `VehicleKbDriftTest` reads the vehicle knowledge base, so it is a declared test input: an edit to
// the file alone must re-run the drift gate rather than leave it UP-TO-DATE. Without this line the
// gate is vacuous — which is exactly how it was first written, and how two deliberate drifts passed.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("ai/knowledge/vehicle-maintenance-kb.json"))
        .withPropertyName("vehicleMaintenanceKb")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
