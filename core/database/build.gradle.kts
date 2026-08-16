// :core:database — Room over SQLCipher, DAOs, migrations (issue 1.6; SRS §20, §23, DB-003, SEC-003).
//
// Why:  every rupee the user owns lives in this database, on a device that can be lost or stolen,
//       so it is encrypted at rest with the key held in the Android Keystore. Room's schema is
//       exported because DB-003 forbids destructive migrations — issue 1.7 tests every version
//       bump against these JSON fixtures, which only exist if they are generated from day one.
plugins {
    alias(libs.plugins.cfo.android.library)
    alias(libs.plugins.ksp)
    // Issue 5.4: the entities are the archive format. §5.10's export is a lossless dump of every
    // profile-scoped table, so the shape it writes IS the schema — see the note on `@Serializable`
    // in Entities.kt for why a parallel set of DTOs would be less safe rather than more.
    alias(libs.plugins.kotlin.serialization)
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

// Issue 1.7: the exported schemas are *inputs* to the unit tests, not just build output. Without
// this, editing a schema JSON leaves the test task UP-TO-DATE and the migration guard reports a
// stale pass — found the hard way while proving the guard could fail.
tasks.withType<Test>().configureEach {
    inputs
        .dir(layout.projectDirectory.dir("schemas"))
        .withPropertyName("roomExportedSchemas")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    // api, not implementation: CfoDatabase extends RoomDatabase, so the type is part of this
    // module's public surface and consumers (the DI graph) must be able to see it.
    api(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Issue 3.6: `TransactionDao.pagedFiltered` returns a PagingSource, so the type is on this
    // module's public surface and `:data:repository` must be able to name it (FR-TXN-007).
    api(libs.room.paging)

    // SQLCipher supplies the encrypted SQLite build Room opens through; androidx-sqlite is the
    // SupportSQLite API both sides speak.
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite)

    // SEC-003: Tink only — no raw javax.crypto anywhere in this module.
    implementation(libs.tink.android)

    // Issue 5.4: `api`, not `implementation` — the entities carry `@Serializable`, so
    // `:data:repository` needs the runtime on its compile classpath to build the archive envelope
    // around them. It was a `testImplementation` here until this issue (issue 1.7's schema parsing).
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
    // Issue 1.7: parses the exported schema JSON so DB-003 is checked on the JVM, not only on a
    // device. Both are JVM artifacts, which is what makes the migration guard runnable in CI.
    testImplementation(libs.room.migration)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
