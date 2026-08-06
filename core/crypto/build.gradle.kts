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

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
