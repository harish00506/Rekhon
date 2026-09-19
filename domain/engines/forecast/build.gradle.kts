// :domain:engines:forecast — AI-FCT, §9. Opening balance + scheduled items + predicted variable
// spend -> a 90-day daily liquid-balance forecast with P10/P50/P90 bands and crunch days.
//
// Pure Kotlin/JVM (ARC-002). `api`, not `implementation`, because Money and EngineProvenance are on
// this module's public surface — every caller has to be able to name what a forecast contains.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
}
