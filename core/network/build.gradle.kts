// :core:network — Retrofit/OkHttp edge, offline-first (P-04). Cert-pinned to our backend.
plugins {
    alias(libs.plugins.cfo.android.library)
}

android {
    namespace = "com.aicfo.core.network"
}

dependencies {
    implementation(project(":core:model"))
}
