// :ml:ocr — ML Kit Text Recognition v2 receipt scanning, on-device (P-01) (issue 10.x).
plugins {
    alias(libs.plugins.cfo.android.library)
}

android {
    namespace = "com.aicfo.ml.ocr"
}

dependencies {
    implementation(project(":core:model"))
}
