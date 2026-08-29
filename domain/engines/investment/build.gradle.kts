// :domain:engines:investment — §11's AI-INV: what a holding is worth today, what it cost, the
// money-weighted return (XIRR) over its dated cash flows (issue 6.3), and how the portfolio those
// holdings make up is spread across asset classes (issue 6.4, FR-INV-002). Pure Kotlin (ARC-002).
// It is handed lists of dated amounts and priced positions and answers with rates, shares and
// flags — never touching a database and never reading a clock, so a twelve-year SIP and a
// concentration breach are both provable on the JVM against a frozen golden file.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
}

dependencies {
    // api, not implementation: Money, Quantity, EngineProvenance, RuleCitation and the investment
    // models all appear on this engine's public surface, so every caller must be able to name them.
    api(project(":core:model"))
    api(project(":core:common"))

    // The golden gates read `src/test/resources/golden/investment.txt` and `.../allocation.txt`. A
    // pure-Kotlin module has no serialisation dependency (ARC-002), so the fixtures are parsed by
    // the tests themselves.
    testImplementation(libs.truth)
}

// The rulebook is an input to this module's tests, because `RulebookDriftTest` reads it. Without
// this Gradle does not know that, so editing RULE-GOLD-CAP alone leaves the tests UP-TO-DATE and
// the drift gate reports green against a file it never read — the failure found in
// :domain:engines:sms on 2026-08-07, in :domain:engines:classification on 2026-08-10, and guarded
// for ever since.
//
// Issue 6.3 shipped without this block and said so: XIRR reads no rule, because a money-weighted
// return has no threshold to tune. Issue 6.4's allocation does — RULE-GOLD-CAP, RULE-CRYPTO-CAP and
// RULE-CONC-15-70 — so the block arrives with it, exactly as that comment promised. The remaining
// two AI-INV rows, RULE-AGE-EQUITY and RULE-5-25, are still unmirrored: both need a target the app
// does not yet collect (the user's age, and the §11.2 risk profile).
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("ai/rules/rules-kb.json"))
        .withPropertyName("rulebook")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
