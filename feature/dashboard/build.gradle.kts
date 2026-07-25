// :feature:dashboard — Safe-to-Spend / net-worth home screen. Features never depend on
// each other (ARC-001); cross-feature nav goes through :app's typed nav graph.
plugins {
    alias(libs.plugins.cfo.android.feature)
}

android {
    namespace = "com.aicfo.feature.dashboard"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":domain:usecase"))
}
