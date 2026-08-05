// :core:datastore — Proto DataStore for settings + the per-feature consent ledger (issue 1.9).
plugins {
    alias(libs.plugins.cfo.android.library)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.aicfo.core.datastore"
}

protobuf {
    protoc { artifact = libs.protobuf.protoc.get().toString() }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins { register("java") { option("lite") } }
        }
    }
}

dependencies {
    // api, not implementation: issue 2.1 puts Money in this module's public surface
    // (QuickSetupSeeds), so consumers must be able to see the type — the same reason
    // :core:database exposes RoomDatabase with api.
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(libs.datastore)
    implementation(libs.datastore.core.okio)
    implementation(libs.protobuf.javalite)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(testFixtures(project(":core:common")))
}
