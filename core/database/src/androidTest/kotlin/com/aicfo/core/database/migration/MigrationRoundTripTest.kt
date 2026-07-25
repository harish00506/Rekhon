package com.aicfo.core.database.migration

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aicfo.core.database.CfoDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The row-level half of the migration harness: data must survive a version bump (DB-003, §21.5).
 *
 * Why:  [MigrationSafetyTest] proves the *shape* of the schema never loses anything, and it runs
 *       everywhere. It cannot prove that a hand-written `Migration` actually carries the rows
 *       across — that needs a real SQLite database, which needs a device. The two together are
 *       the harness: the structural guard catches the common mistake on every build, and this
 *       catches a migration whose SQL is wrong.
 * What: creates a database at version N with real rows, runs the migrations, and asserts the data
 *       is still there and readable.
 * Result: at the first version bump, a migration that drops or corrupts data fails here.
 * Changelog: 2026-07-25 — Created for issue 1.7.
 *
 * **NOT YET RUN.** No device or emulator exists on this machine (`adb devices` is empty, no AVD
 * installed), so like `EncryptedDatabaseTest` this compiles but has never executed:
 * `./gradlew :core:database:connectedDebugAndroidTest`.
 *
 * **At version 1 there is no migration to test.** The single test below is the one meaningful
 * assertion available now — that the exported fixture really matches what Room creates — and the
 * commented template is the pattern to copy at the first bump. Writing an empty "migration test"
 * that asserts nothing would be the vacuous gate this project has already been bitten by once.
 */
@RunWith(AndroidJUnit4::class)
class MigrationRoundTripTest {
    /**
     * Room's helper, pointed at the schemas the build exports.
     * The instrumentation build copies `schemas/` into assets (see `build.gradle.kts`), which is
     * how the fixture reaches the device.
     */
    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            CfoDatabase::class.java,
        )

    /**
     * Input:  a database created at the current version from the exported fixture.
     * Output: asserts Room validates it — i.e. the committed schema JSON is a truthful description
     *         of what the entities actually produce. A fixture that has drifted from the code
     *         would make every future migration test meaningless, so this is worth asserting even
     *         with nothing to migrate yet.
     */
    @Test
    fun currentSchemaMatchesItsExportedFixture() {
        helper.createDatabase(TEST_DB, CfoDatabase.VERSION).close()
        helper.runMigrationsAndValidate(TEST_DB, CfoDatabase.VERSION, true).close()
    }

    /*
     * TEMPLATE — copy this at the first version bump (v1 -> v2):
     *
     * @Test
     * fun migrate1To2_preservesTransactions() {
     *     helper.createDatabase(TEST_DB, 1).use { db ->
     *         db.execSQL(
     *             "INSERT INTO transactions (id, profile_id, account_id, amount_minor, " +
     *                 "currency_code, occurred_at_utc_millis, booked_on_iso_date, source, " +
     *                 "created_at_utc_millis, updated_at_utc_millis) " +
     *                 "VALUES ('t1','p1','a1',-12345678,'INR',1767312000000,'2026-01-02'," +
     *                 "'manual',1767312000000,1767312000000)",
     *         )
     *     }
     *
     *     val migrated = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)
     *
     *     migrated.query("SELECT amount_minor FROM transactions WHERE id = 't1'").use { cursor ->
     *         assertTrue(cursor.moveToFirst())
     *         // The exact paise value must survive — this is the assertion DB-003 exists for.
     *         assertEquals(-12345678L, cursor.getLong(0))
     *     }
     *     migrated.close()
     * }
     */

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
