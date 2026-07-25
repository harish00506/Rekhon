// :data:repository — the ONLY modules that touch DAOs or network (ARC-005). Exposes
// domain models to ViewModels; depends downward on core + domain.
plugins {
    alias(libs.plugins.cfo.android.library)
}

android {
    namespace = "com.aicfo.data.repository"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":domain:usecase"))
}
