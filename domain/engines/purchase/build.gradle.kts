// :domain:engines:purchase — AI-PA, §13. Item, price and payment method against the signals the
// engines below already published -> a verdict and the gate-by-gate trace behind it.
//
// Pure Kotlin/JVM (ARC-002): seven gates of arithmetic on values the caller supplies, so every
// verdict is reproducible without a device, a database or a clock.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
}
