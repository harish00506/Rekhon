package com.aicfo.core.database.migration

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aicfo.core.database.CfoDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * **Version 2 (issue 2.2) is the first real bump**, so there is now a migration with data to carry
 * across — see `migrate1To2_preservesTransactionsAndAddsAuditLog`. It is the template for every
 * later bump: insert real rows at the old version, migrate, assert the exact values survived.
 * Changelog: 2026-07-26 — Issue 2.2: added the 1 → 2 case.
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

    /**
     * The first real bump: 1 → 2 adds `audit_log` (issue 2.2).
     *
     * Input:  a version-1 database holding a transaction with an exact paise amount.
     * Output: asserts the amount survives the migration byte for byte, and that the new table
     *         exists and is writable afterwards.
     *
     * The amount is the assertion DB-003 exists for: `-12345678` paise is ₹1,23,456.78, and a
     * migration that round-tripped it through a floating-point column would come back as
     * `-12345677` or `-12345679`. Checking the row still exists would not catch that; checking the
     * exact `Long` does (MNY-001).
     *
     * `runMigrationsAndValidate` also re-checks the schema against `schemas/2.json`, so a migration
     * whose hand-written DDL has drifted from what Room generates fails here rather than on a
     * user's phone.
     */
    @Test
    fun migrate1To2_preservesTransactionsAndAddsAuditLog() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO transactions (id, profile_id, account_id, amount_minor, " +
                    "currency_code, occurred_at_utc_millis, booked_on_iso_date, source, " +
                    "created_at_utc_millis, updated_at_utc_millis) " +
                    "VALUES ('t1','p1','a1',-12345678,'INR',1767312000000,'2026-01-02'," +
                    "'manual',1767312000000,1767312000000)",
            )
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 2, true, Migrations.MIGRATION_1_2)

        migrated.query("SELECT amount_minor FROM transactions WHERE id = 't1'").use { cursor ->
            assertTrue("the pre-migration transaction must still be there", cursor.moveToFirst())
            assertEquals(-12345678L, cursor.getLong(0))
        }
        // The new table must be usable, not merely present: a CREATE TABLE with a column Room did
        // not expect would pass validation above and still reject every insert.
        migrated.execSQL(
            "INSERT INTO audit_log (event, occurred_at_utc_millis, method) " +
                "VALUES ('APP_UNLOCK_SUCCESS', 1767312000000, 'PIN')",
        )
        migrated.query("SELECT COUNT(*) FROM audit_log").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        migrated.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
