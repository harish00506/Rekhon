// :sync:backup — end-to-end-encrypted backup/restore (Argon2id → AES-256-GCM via Tink).
// The platform never sees plaintext or the key (issue 11.x).
plugins {
    alias(libs.plugins.cfo.android.library)
}

android {
    namespace = "com.aicfo.sync.backup"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:crypto"))
    implementation(project(":core:database"))
}
