// :domain:usecase — pure-Kotlin use cases orchestrating engines for features.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":domain:engines:forecast"))
}
