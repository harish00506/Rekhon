// :domain:engines:goals — AI-GOAL, §15. Target amount + date -> required monthly, ETA and horizon.
//
// Pure Kotlin/JVM (ARC-002): the whole calculation is provable on the JVM, with no Android and no
// serialisation dependency. `api`, not `implementation`, because Money and EngineProvenance are on
// this module's public surface — every caller has to be able to name what a projection contains.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
}
