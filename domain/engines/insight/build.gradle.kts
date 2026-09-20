// :domain:engines:insight — AI-ORCH, §7.2. The already-computed results of the engines below it ->
// a deduplicated, deterministically ranked insight feed, every card carrying the provenance of the
// engine whose figure it reports.
//
// Pure Kotlin/JVM (ARC-002). It depends on **no other engine module**: the repository hands it small
// signal types declared here, so the L5 orchestrator cannot drift into re-deriving what L3/L4 own.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
}
