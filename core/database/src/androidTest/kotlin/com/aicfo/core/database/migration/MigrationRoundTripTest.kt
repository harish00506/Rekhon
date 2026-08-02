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
 * **These have now run.** Issue 2.4 opened the emulator gate for the first time in this project and
 * every case here passed against real SQLite: `emulator -avd CfoTest`, then
 * `./gradlew :core:database:connectedDebugAndroidTest`. Before that they compiled and had never
 * executed anywhere, so DB-003 was taken on the structural guard's word alone.
 *
 * **Version 2 (issue 2.2) is the first real bump**, so there is now a migration with data to carry
 * across — see `migrate1To2_preservesTransactionsAndAddsAuditLog`. It is the template for every
 * later bump: insert real rows at the old version, migrate, assert the exact values survived.
 * Changelog: 2026-07-26 — Issue 2.2: added the 1 → 2 case.
 *            2026-07-27 — Issue 2.3: added the 2 → 3 case (`budget`, `recurring_rule`).
 *            2026-07-28 — Issue 2.4: first execution on a device; both cases passed.
 *            2026-07-28 — Issue 2.5: added the 3 → 4 case — **the first migration that alters an
 *            existing table rather than creating new ones**, so the first with a row that has to
 *            survive a change to its own definition.
 *            2026-08-02 — Issue 3.2: added the 5 → 6 case — **the first migration that rewrites the
 *            content of existing rows** rather than only adding empty columns, so the first where
 *            the SQL can be structurally valid and still leave the data saying the wrong thing.
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

    /**
     * 2 → 3 adds `budget` and `recurring_rule` for the quick-setup seeds (issue 2.3, FR-ONB-002).
     *
     * Input:  a version-2 database holding a transaction and an audit event — one row from each of
     *         the two versions that came before, so the test proves the *chain* survives rather
     *         than just the newest step.
     * Output: asserts both rows are intact afterwards and that the two new tables accept a write.
     *
     * The budget amount is checked as an exact `Long` for the same reason the transaction is: a
     * planned ₹42,500.00 is `4250000` paise, and a column that had been created as `REAL` would
     * return it as a value that no longer compares equal (MNY-001). `runMigrationsAndValidate` also
     * re-checks the hand-written DDL in `MIGRATION_2_3` against the committed `schemas/3.json`, so
     * a typo in a column name fails here rather than on a user's phone.
     */
    @Test
    fun migrate2To3_preservesEarlierRowsAndAddsBudgetAndRecurringRule() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                "INSERT INTO transactions (id, profile_id, account_id, amount_minor, " +
                    "currency_code, occurred_at_utc_millis, booked_on_iso_date, source, " +
                    "created_at_utc_millis, updated_at_utc_millis) " +
                    "VALUES ('t1','p1','a1',-12345678,'INR',1767312000000,'2026-01-02'," +
                    "'manual',1767312000000,1767312000000)",
            )
            db.execSQL(
                "INSERT INTO audit_log (event, occurred_at_utc_millis, method) " +
                    "VALUES ('APP_UNLOCK_SUCCESS', 1767312000000, 'PIN')",
            )
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 3, true, Migrations.MIGRATION_2_3)

        migrated.query("SELECT amount_minor FROM transactions WHERE id = 't1'").use { cursor ->
            assertTrue("the pre-migration transaction must still be there", cursor.moveToFirst())
            assertEquals(-12345678L, cursor.getLong(0))
        }
        migrated.query("SELECT COUNT(*) FROM audit_log").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("the audit log added at v2 must survive v3", 1, cursor.getInt(0))
        }

        migrated.execSQL(
            "INSERT INTO budget (id, profile_id, category_id, nature, period_start_iso_date, " +
                "amount_minor, rollover_enabled, source, rule_id, rule_version, " +
                "created_at_utc_millis, updated_at_utc_millis) " +
                "VALUES ('b1','p1',NULL,'need','2026-07-01',4250000,0,'quick_setup'," +
                "'RULE-50-30-20','1.0',1767312000000,1767312000000)",
        )
        migrated.query("SELECT amount_minor FROM budget WHERE id = 'b1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(4250000L, cursor.getLong(0))
        }

        migrated.execSQL(
            "INSERT INTO recurring_rule (id, profile_id, account_id, category_id, name, seed_kind, " +
                "amount_minor, cadence, next_due_iso_date, source, is_confirmed, " +
                "created_at_utc_millis, updated_at_utc_millis) " +
                "VALUES ('r1','p1',NULL,NULL,NULL,'rent_emi',-2400000,'monthly','2026-08-01'," +
                "'quick_setup',0,1767312000000,1767312000000)",
        )
        migrated.query("SELECT amount_minor FROM recurring_rule WHERE id = 'r1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("an outflow stays negative, as in transactions", -2400000L, cursor.getLong(0))
        }
        migrated.close()
    }

    /**
     * 3 → 4 adds `institution` and `archived_at_utc_millis` to `account` (issue 2.5, FR-ACC-007).
     *
     * Input:  a version-3 database holding an account with an exact opening balance, plus a
     *         transaction against it — the row whose own table is about to change, and a row that
     *         references it.
     * Output: asserts the account survives with its balance intact, that the two new columns arrive
     *         **NULL** on the existing row, and that both are writable afterwards.
     *
     * **This is the first migration in the app that alters an existing table.** 1 → 2 and 2 → 3 both
     * only created new ones, where the worst outcome is a table that does not appear. Here the worst
     * outcome is a user's account being rewritten, which is exactly what DB-003 exists to prevent —
     * so the assertion that matters is `1_85_000_00` coming back byte for byte, not the row merely
     * existing.
     *
     * The NULL assertions are not decoration either: SQLite's `ADD COLUMN` fills existing rows with
     * the column default, and a migration written with `NOT NULL DEFAULT 0` would silently mark
     * every pre-existing account as archived at the epoch.
     */
    @Test
    fun migrate3To4_preservesAccountsAndAddsInstitutionAndArchivedAt() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL(
                "INSERT INTO account (id, profile_id, name, type, opening_balance_minor, " +
                    "current_balance_minor, currency_code, created_at_utc_millis, updated_at_utc_millis) " +
                    "VALUES ('a1','p1','HDFC Savings','bank',18500000,18500000,'INR'," +
                    "1767312000000,1767312000000)",
            )
            db.execSQL(
                "INSERT INTO transactions (id, profile_id, account_id, amount_minor, " +
                    "currency_code, occurred_at_utc_millis, booked_on_iso_date, source, " +
                    "created_at_utc_millis, updated_at_utc_millis) " +
                    "VALUES ('t1','p1','a1',-12345678,'INR',1767312000000,'2026-01-02'," +
                    "'manual',1767312000000,1767312000000)",
            )
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 4, true, Migrations.MIGRATION_3_4)

        migrated.query(
            "SELECT opening_balance_minor, institution, archived_at_utc_millis " +
                "FROM account WHERE id = 'a1'",
        ).use { cursor ->
            assertTrue("the pre-migration account must still be there", cursor.moveToFirst())
            assertEquals("MNY-001: the opening balance must survive byte for byte", 18500000L, cursor.getLong(0))
            assertTrue("institution must arrive NULL on an existing row", cursor.isNull(1))
            assertTrue("an existing account must not come back archived", cursor.isNull(2))
        }
        migrated.query("SELECT amount_minor FROM transactions WHERE id = 't1'").use { cursor ->
            assertTrue("the account's transaction must survive too", cursor.moveToFirst())
            assertEquals(-12345678L, cursor.getLong(0))
        }

        // Both new columns must be usable, not merely present.
        migrated.execSQL(
            "UPDATE account SET institution = 'HDFC Bank', archived_at_utc_millis = 1767312000000 " +
                "WHERE id = 'a1'",
        )
        migrated.query("SELECT institution, archived_at_utc_millis FROM account WHERE id = 'a1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("HDFC Bank", cursor.getString(0))
            assertEquals(1767312000000L, cursor.getLong(1))
        }
        migrated.close()
    }

    /**
     * 4 → 5 adds `net_worth_snapshot` and `account.include_in_networth` (issue 2.6, FR-ACC-005).
     *
     * Input:  a version-4 database holding an account with an exact opening balance.
     * Output: asserts the account survives, that the new column arrives **set to 1** on the existing
     *         row, and that the new table accepts a write.
     *
     * **The `1` is the assertion that matters.** `ADD COLUMN … NOT NULL DEFAULT 1` fills existing
     * rows with the default, and getting it wrong the other way — `DEFAULT 0`, or a nullable column
     * read as "null means no" — would silently drop every account a user already had out of their
     * net worth, with no error anywhere. The figure would just be wrong.
     */
    @Test
    fun migrate4To5_preservesAccountsAndCountsThemInNetWorthByDefault() {
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.execSQL(
                "INSERT INTO account (id, profile_id, name, type, opening_balance_minor, " +
                    "current_balance_minor, currency_code, created_at_utc_millis, updated_at_utc_millis) " +
                    "VALUES ('a1','p1','HDFC Savings','bank',18500000,18500000,'INR'," +
                    "1767312000000,1767312000000)",
            )
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 5, true, Migrations.MIGRATION_4_5)

        migrated.query("SELECT opening_balance_minor, include_in_networth FROM account WHERE id = 'a1'")
            .use { cursor ->
                assertTrue("the pre-migration account must still be there", cursor.moveToFirst())
                assertEquals("MNY-001: the balance must survive byte for byte", 18500000L, cursor.getLong(0))
                assertEquals("an existing account must keep counting towards net worth", 1, cursor.getInt(1))
            }

        migrated.execSQL(
            "INSERT INTO net_worth_snapshot (id, profile_id, as_of_iso_date, assets_minor, " +
                "liabilities_minor, net_worth_minor, engine_id, engine_version, " +
                "computed_at_utc_millis, created_at_utc_millis, updated_at_utc_millis) " +
                "VALUES ('p1:networth:2026-08-01','p1','2026-08-01',31000000,1800000,29200000," +
                "'net-worth','1.0',1767312000000,1767312000000,1767312000000)",
        )
        migrated.query("SELECT net_worth_minor, engine_version FROM net_worth_snapshot").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(29200000L, cursor.getLong(0))
            assertEquals("AI-ARC-006: the row remembers which formula produced it", "1.0", cursor.getString(1))
        }
        migrated.close()
    }

    /**
     * Input:  a v5 database holding one ordinary expense, one salary credit, and one FR-ACC-006
     *         adjustment — the three shapes of row that exist before transfers do.
     * Output: asserts all three survive **and that the backfill classified each correctly**.
     *
     * **The backfill is what this case exists for.** SQLite can only add a `NOT NULL` column with a
     * `DEFAULT`, so without the `UPDATE` every pre-upgrade row would read as an `expense` — the
     * user's salary would be typed as spending and issue 2.7's adjustments would be indistinguishable
     * from things they bought. Nothing about that is visible in the schema shape, so
     * `MigrationSafetyTest` cannot catch it; only running the SQL can.
     */
    @Test
    fun migrate5To6_preservesTransactionsAndClassifiesThemByTypeAndSign() {
        helper.createDatabase(TEST_DB, 5).use { db ->
            db.execSQL(
                "INSERT INTO transactions (id, profile_id, account_id, amount_minor, currency_code, " +
                    "occurred_at_utc_millis, booked_on_iso_date, source, created_at_utc_millis, " +
                    "updated_at_utc_millis) VALUES " +
                    "('t1','p1','a1',-12345678,'INR',1767312000000,'2026-01-02','manual'," +
                    "1767312000000,1767312000000)," +
                    "('t2','p1','a1',9500000,'INR',1767312000000,'2026-01-01','manual'," +
                    "1767312000000,1767312000000)," +
                    "('t3','p1','a1',-250000,'INR',1767312000000,'2026-01-03','reconciliation'," +
                    "1767312000000,1767312000000)",
            )
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 6, true, Migrations.MIGRATION_5_6)

        migrated.query("SELECT id, amount_minor, type, transfer_id FROM transactions ORDER BY id")
            .use { cursor ->
                assertTrue("the pre-migration expense must still be there", cursor.moveToFirst())
                assertEquals("t1", cursor.getString(0))
                assertEquals("MNY-001: the amount must survive byte for byte", -12345678L, cursor.getLong(1))
                assertEquals("a negative row is an expense", "expense", cursor.getString(2))
                assertTrue("no pre-existing row can be part of a transfer", cursor.isNull(3))

                assertTrue(cursor.moveToNext())
                assertEquals("t2", cursor.getString(0))
                assertEquals(9500000L, cursor.getLong(1))
                assertEquals("a positive row is income, not an expense", "income", cursor.getString(2))

                assertTrue(cursor.moveToNext())
                assertEquals("t3", cursor.getString(0))
                // Source wins over the sign: FR-ACC-006 corrections are §20.2's `adjustment`, and
                // classifying them as spending would put every balance correction into spend totals.
                assertEquals("a reconciliation row is an adjustment", "adjustment", cursor.getString(2))
            }

        // The columns must be writable, not merely present: a transfer's two legs share one id.
        migrated.execSQL(
            "INSERT INTO transactions (id, profile_id, account_id, amount_minor, currency_code, " +
                "occurred_at_utc_millis, booked_on_iso_date, source, type, transfer_id, " +
                "created_at_utc_millis, updated_at_utc_millis) VALUES " +
                "('t4','p1','a1',-500000,'INR',1767312000000,'2026-01-04','manual','transfer_out'," +
                "'tfr1',1767312000000,1767312000000)," +
                "('t5','p1','a2',500000,'INR',1767312000000,'2026-01-04','manual','transfer_in'," +
                "'tfr1',1767312000000,1767312000000)",
        )
        migrated.query("SELECT SUM(amount_minor), COUNT(*) FROM transactions WHERE transfer_id = 'tfr1'")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("FR-TXN-003: a transfer's legs must balance exactly", 0L, cursor.getLong(0))
                assertEquals("both legs must be findable by the shared id", 2, cursor.getInt(1))
            }
        migrated.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
