// :core:network — the HTTP edge, and the only module in this repo that can open a socket
// (issue 6.5; §16, §22). Cert-pinned to our own backend proxy: market prices come through it and
// are never scraped on-device (EXT-001).
//
// Offline-first is not a caveat here, it is the default. The client ships UNCONFIGURED — with no
// base URL there is no backend, so no client is ever built and every call returns an error without
// touching the network. Nothing in the app blocks on this module (P-04).
plugins {
    alias(libs.plugins.cfo.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.aicfo.core.network"
}

dependencies {
    // api, not implementation: Money appears on this module's public surface, and so do Result and
    // AppError — every caller has to be able to name what a quote is and how a fetch failed.
    api(project(":core:model"))
    api(project(":core:common"))

    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    // Coroutines are not supplied by cfo.android.library, and the API surface is suspend.
    implementation(libs.kotlinx.coroutines.core)

    // `okhttp-logging` is pinned in the catalog and deliberately NOT used. An interceptor logging a
    // request that carries the user's instrument list is a §21.6 PII-in-logs violation waiting for
    // someone to enable it in a debug build, and it buys nothing on a path that makes one call a day.

    testImplementation(libs.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)
}

// The wire contract is a file both sides of the network read, not a shape each side describes
// separately. `:backend` sets the identical property, so the two suites assert against the same
// bytes and a rename on either side turns one of them red.
tasks.withType<Test>().configureEach {
    systemProperty("cfo.contracts.dir", rootProject.file("contracts").absolutePath)
}
