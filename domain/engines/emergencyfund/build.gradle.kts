// :domain:engines:emergencyfund — AI-EMF, §10.1. Essentials + income volatility -> target, runway
// and the personal multiplier M.
//
// Pure Kotlin/JVM (ARC-002): the whole calculation is provable on the JVM, with no Android and no
// serialisation dependency. `api`, not `implementation`, because Money and EngineProvenance are on
// this module's public surface — every caller has to be able to name what an assessment contains.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
}
