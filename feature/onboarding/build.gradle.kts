// :feature:onboarding — first-run setup + consent capture. Feature module (ARC-001).
plugins {
    alias(libs.plugins.cfo.android.feature)
}

android {
    namespace = "com.aicfo.feature.onboarding"
}

dependencies {
    implementation(project(":core:designsystem"))
}
