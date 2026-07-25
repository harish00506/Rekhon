// :domain:engines:forecast — representative pure-Kotlin engine (ARC-002/003).
// Depends downward only, on :core:* primitives.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
}
