// :core:designsystem — M3 theme tokens + shared Compose components (issue 1.8).
plugins {
    alias(libs.plugins.cfo.android.library)
    alias(libs.plugins.cfo.android.compose)
    alias(libs.plugins.paparazzi)
}

android {
    namespace = "com.aicfo.core.designsystem"
}

dependencies {
    // Money + MoneyFormatter (issue 1.2): amounts are formatted in one place, never re-implemented.
    implementation(project(":core:model"))
}
