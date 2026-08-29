// :data:sms — the only code in this app that can read the phone's inbox (issue 3.9; §18, §23, P-01).
//
// Its own module rather than a file in `:data:repository`, for the reason `:ml:ocr` is its own
// module: this is the device edge. It holds the READ_SMS declaration, the ContentResolver query and
// nothing else — no parsing, no persistence, no decisions. That boundary is what lets the whole
// judgement about what a message means live in `:domain:engines:sms`, be pure Kotlin, and be gated
// in CI against frozen fixtures, while the part that genuinely needs a device stays this thin.
//
// It is also where a reviewer looks to answer "what can this app do with my messages?" — one
// interface, one query, one column list. See docs/adr/0013.
plugins {
    alias(libs.plugins.cfo.android.library)
}

android {
    namespace = "com.aicfo.data.sms"
}

dependencies {
    // api, not implementation: SmsMessage is on SmsInboxReader's public surface, so every caller —
    // the repository and its tests — must be able to name it.
    api(project(":core:model"))

    // Result/AppError (§21.6) — nothing here throws across a boundary.
    api(project(":core:common"))

    // withContext, so the cursor walk lands on the injected IO dispatcher rather than whichever
    // thread the caller happened to be on (ARC-006).
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.truth)
    // The reader is tested against a real ContentResolver backed by a fake provider — see
    // ContentResolverSmsInboxReaderTest. Robolectric, because a ContentResolver needs a Context.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core:common")))
}
