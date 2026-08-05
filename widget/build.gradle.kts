// :widget — Glance home-screen widget rendering CACHED engine output, offline (P-04).
// Glance/Compose wiring lands with the widget feature issue; skeleton is a plain library.
plugins {
    alias(libs.plugins.cfo.android.library)
}

android {
    namespace = "com.aicfo.widget"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":domain:usecase"))
}
