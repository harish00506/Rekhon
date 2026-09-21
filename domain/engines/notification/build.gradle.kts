// :domain:engines:notification — AI-NTF, §17.2. Candidate notifications + what has already been
// sent -> deliver now, wait for the quiet hours to end, or fold into the weekly digest.
//
// Pure Kotlin/JVM (ARC-002): the policy is arithmetic on a clock reading the caller supplies, so
// every cap and every boundary is testable without a device, a channel or a permission.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
}
