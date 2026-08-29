// :ml:ocr — ML Kit Text Recognition v2 receipt scanning, on-device (issue 3.8; FR-OCR-002, P-01).
plugins {
    alias(libs.plugins.cfo.android.library)
}

android {
    namespace = "com.aicfo.ml.ocr"
}

dependencies {
    // api, not implementation: RecognizedText is on `ReceiptTextRecognizer`'s public surface, so
    // every caller — the repository and its tests — must be able to name it.
    api(project(":core:model"))

    // Result/AppError (§21.6) — nothing here throws across a boundary.
    api(project(":core:common"))

    // FR-OCR-002's "fully on-device". **The bundled artifact, deliberately**: the thin
    // `play-services-mlkit-text-recognition` variant downloads its model from Play Services on
    // first use, which would put a network fetch on a core path (P-01) and leave the scanner
    // broken in airplane mode (P-04). This one ships the model inside the APK.
    implementation(libs.mlkit.text.recognition)

    // suspendCancellableCoroutine, to bridge ML Kit's Task into a suspend function. Deliberately
    // *not* `kotlinx-coroutines-play-services` for its `await()`: a whole extra dependency for one
    // short adapter (see `MlKitReceiptTextRecognizer.awaitText`).
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.truth)
}
