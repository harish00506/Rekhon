// :core:crypto — Tink / Android Keystore key management. No hand-rolled crypto (SEC-003).
plugins {
    alias(libs.plugins.cfo.android.library)
}

android {
    namespace = "com.aicfo.core.crypto"

    testOptions {
        // Robolectric needs the merged Android resources to boot its runtime. Added for issue 3.8:
        // ReceiptImagePrivacy decodes a real Bitmap, so its round trip needs an Android runtime.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // api, not implementation: StoredImage and AppError appear on ReceiptImageStore's public
    // surface (issue 3.8), so :data:repository must be able to name them.
    api(project(":core:common"))

    // SEC-003 is absolute: Tink primitives only. There is no javax.crypto in this module, and the
    // two Keystore touches (KeystoreMacFactory, ReceiptImageStoreFactory) go through Tink's own
    // Android integration.
    api(libs.tink.android)
    // Issue 8.1: Argon2id, and nothing else from it. Tink has no password KDF, and SEC-005 names
    // Argon2id; hand-rolling it is exactly what SEC-003 forbids. `implementation`, not `api` — no
    // BouncyCastle type appears on BackupCipher's surface, so no other module can reach for it
    // (ADR-0039).
    implementation(libs.bouncycastle.bcprov)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
