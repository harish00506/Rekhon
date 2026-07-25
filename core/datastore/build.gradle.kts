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
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(libs.datastore)
    implementation(libs.datastore.core.okio)
    implementation(libs.protobuf.javalite)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(testFixtures(project(":core:common")))
}
