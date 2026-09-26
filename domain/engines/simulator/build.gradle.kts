// :domain:engines:simulator — AI-SIM, §36 and §40.2. What-if arithmetic: prepay a loan or invest
// the same money, and which order clears a pile of debts sooner.
//
// Pure Kotlin/JVM (ARC-002). It **simulates and never executes** (P-07): every result is a
// comparison the user reads, and nothing here writes a rupee anywhere.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    // The one engine dependency in the domain layer, and a deliberate one: `:domain:engines:loan`
    // owns EMI and amortisation, and its own doc names this simulator as a caller. Re-deriving the
    // instalment here would be a second definition of the number the accounts screen shows.
    api(project(":domain:engines:loan"))
}
