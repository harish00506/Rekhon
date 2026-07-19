// :feature:transactions — add/list transactions (≤ 3 taps). Feature module (ARC-001).
plugins {
    alias(libs.plugins.cfo.android.feature)
}

android {
    namespace = "com.aicfo.feature.transactions"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":domain:usecase"))
}
