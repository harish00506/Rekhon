// :core:database — Room + SQLCipher, DAOs, migrations (issue 1.6/1.7 fill this in).
plugins {
    alias(libs.plugins.cfo.android.library)
}

android {
    namespace = "com.aicfo.core.database"
}

dependencies {
    implementation(project(":core:model"))
}
