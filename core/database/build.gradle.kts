// :core:database — Room over SQLCipher, DAOs, migrations (issue 1.6; SRS §20, §23, DB-003, SEC-003).
//
// Why:  every rupee the user owns lives in this database, on a device that can be lost or stolen,
//       so it is encrypted at rest with the key held in the Android Keystore. Room's schema is
//       exported because DB-003 forbids destructive migrations — issue 1.7 tests every version
//       bump against these JSON fixtures, which only exist if they are generated from day one.
plugins {
    alias(libs.plugins.cfo.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.aicfo.core.database"

    defaultConfig {
        // The encrypted round-trip can only be proved on a device; issue 1.7's migration tests
        // run here too.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        // Makes the exported schemas visible to instrumentation tests (Room's MigrationTestHelper).
        getByName("androidTest").assets.srcDir(layout.projectDirectory.dir("schemas"))
    }
}

ksp {
    // DB-003: the schema JSON is the fixture every future migration is tested against. Without it
    // a version bump has nothing to diff, and "no destructive migrations" is unverifiable.
    arg("room.schemaLocation", layout.projectDirectory.dir("schemas").asFile.absolutePath)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // SQLCipher supplies the encrypted SQLite build Room opens through; androidx-sqlite is the
    // SupportSQLite API both sides speak.
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite)

    // SEC-003: Tink only — no raw javax.crypto anywhere in this module.
    implementation(libs.tink.android)

    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
