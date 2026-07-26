// :core:crypto — Tink / Android Keystore key management. No hand-rolled crypto (SEC-003).
plugins {
    alias(libs.plugins.cfo.android.library)
}

android {
    namespace = "com.aicfo.core.crypto"
}

dependencies {
    // Result/AppError (§21.6) — nothing here throws across a boundary.
    implementation(project(":core:common"))

    // SEC-003 is absolute: Tink primitives only. There is no javax.crypto in this module, and the
    // one Keystore touch (KeystoreMacFactory) goes through Tink's own Android integration.
    implementation(libs.tink.android)
}
