// :core:datastore — Proto DataStore for settings + the per-feature consent ledger (issue 1.9).
plugins {
    alias(libs.plugins.cfo.android.library)
}

android {
    namespace = "com.aicfo.core.datastore"
}

dependencies {
    implementation(project(":core:model"))
}
