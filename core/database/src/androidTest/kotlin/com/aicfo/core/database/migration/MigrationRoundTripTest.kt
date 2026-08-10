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
 *            2026-08-02 — Issue 3.3: added the 6 → 7 case (`transaction_splits`). Additive again,
 *            so the risk here is the opposite one: a migration that quietly recreates the table it
 *            was meant to leave alone would validate perfectly and lose every row.
 *            2026-08-03 — Issue 3.4: added the 7 → 8 case (`transactions.posted_at_utc_millis`).
 *            **The backfill is the whole test.** `ADD COLUMN` alone validates, preserves every row
 *            and still ships a broken app — every pre-existing transaction would read as unposted.
 *            2026-08-04 — Issue 3.6: added the 8 → 9 case (`tags`, `transaction_tags`). Additive,
 *            so it asserts the *index* as well as the rows: a `UNIQUE` index a migration forgot to
 *            create is invisible until a user happens to have two of something.
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

    /**
     * Input:  a v6 database holding one ordinary transaction.
     * Output: asserts it survives untouched and that the new `transaction_splits` table accepts
     *         lines that sum exactly to their parent (FR-TXN-004).
     *
     * **The parent row is the point of the first half.** 6 → 7 adds a table and touches nothing, so
     * the risk is not corruption but a migration that accidentally recreates `transactions` — which
     * would look identical in the schema and lose every row. Asserting the pre-existing amount
     * byte-for-byte is what separates the two.
     */
    @Test
    fun migrate6To7_preservesTransactionsAndAcceptsSplitLines() {
        helper.createDatabase(TEST_DB, 6).use { db ->
            db.execSQL(
                "INSERT INTO transactions (id, profile_id, account_id, amount_minor, currency_code, " +
                    "occurred_at_utc_millis, booked_on_iso_date, source, type, created_at_utc_millis, " +
                    "updated_at_utc_millis) VALUES " +
                    "('t1','p1','a1',-100000,'INR',1767312000000,'2026-01-02','manual','expense'," +
                    "1767312000000,1767312000000)",
            )
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 7, true, Migrations.MIGRATION_6_7)

        migrated.query("SELECT amount_minor, type FROM transactions WHERE id = 't1'").use { cursor ->
            assertTrue("the pre-migration transaction must still be there", cursor.moveToFirst())
            assertEquals("MNY-001: the amount must survive byte for byte", -100000L, cursor.getLong(0))
            assertEquals("expense", cursor.getString(1))
        }

        // Every existing transaction is simply unsplit, which is the truth about it.
        migrated.query("SELECT COUNT(*) FROM transaction_splits").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("a migration cannot invent split lines", 0, cursor.getInt(0))
        }

        migrated.execSQL(
            "INSERT INTO transaction_splits (id, profile_id, transaction_id, amount_minor, " +
                "category_id, note, created_at_utc_millis, updated_at_utc_millis) VALUES " +
                "('spl1','p1','t1',-60000,'cat:groceries',NULL,1767312000000,1767312000000)," +
                "('spl2','p1','t1',-40000,NULL,NULL,1767312000000,1767312000000)",
        )
        migrated.query(
            "SELECT SUM(s.amount_minor), COUNT(*) FROM transaction_splits s WHERE s.transaction_id = 't1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            // FR-TXN-004's whole requirement, expressed in SQL: the lines sum to the parent exactly.
            assertEquals("lines must sum exactly to the parent", -100000L, cursor.getLong(0))
            assertEquals(2, cursor.getInt(1))
        }
        // A line with no category is legal - a real profile has none until issue 4.1.
        migrated.query("SELECT category_id FROM transaction_splits WHERE id = 'spl2'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
        migrated.close()
    }

    /**
     * Input:  a v7 database holding two transactions, one of them soft-deleted.
     * Output: asserts both survive, that **every** row comes out of the migration posted, and that
     *         the column then accepts `NULL` for a genuinely future-dated row (FR-TXN-010).
     *
     * **The backfill is what this test exists for.** `ALTER TABLE … ADD COLUMN` gives every existing
     * row `NULL`, which is precisely the value that means "not yet posted" — so a migration that
     * stopped there would validate, preserve every byte, and still leave the user's entire history
     * sitting in the Scheduled group with an empty recent list. The tombstone is included because a
     * `WHERE deleted_at IS NULL` accidentally added to the backfill would leave exactly one row
     * behind, and undeleting is issue 3.6's job.
     */
    @Test
    fun migrate7To8_backfillsPostedAtOnEveryExistingRow() {
        helper.createDatabase(TEST_DB, 7).use { db ->
            db.execSQL(
                "INSERT INTO transactions (id, profile_id, account_id, amount_minor, currency_code, " +
                    "occurred_at_utc_millis, booked_on_iso_date, source, type, created_at_utc_millis, " +
                    "updated_at_utc_millis, deleted_at_utc_millis) VALUES " +
                    "('t1','p1','a1',-100000,'INR',1767312000000,'2026-01-02','manual','expense'," +
                    "1767312000000,1767312000000,NULL)," +
                    "('t2','p1','a1',-250000,'INR',1767398400000,'2026-01-03','manual','expense'," +
                    "1767398400000,1767398400000,1767484800000)",
            )
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 8, true, Migrations.MIGRATION_7_8)

        migrated.query("SELECT amount_minor, posted_at_utc_millis FROM transactions WHERE id = 't1'")
            .use { cursor ->
                assertTrue("the pre-migration transaction must still be there", cursor.moveToFirst())
                assertEquals("MNY-001: the amount must survive byte for byte", -100000L, cursor.getLong(0))
                // Backfilled from `occurred_at_utc_millis`: every row written before issue 3.4 was
                // booked on the day it was created, so that instant is when it posted.
                assertEquals(
                    "a row that existed before 3.4 is posted, not scheduled",
                    1767312000000L,
                    cursor.getLong(1),
                )
            }

        migrated.query("SELECT COUNT(*) FROM transactions WHERE posted_at_utc_millis IS NULL").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("the backfill must reach tombstones too", 0, cursor.getInt(0))
        }

        // The column must be writable as NULL: that is what a future-dated row looks like.
        migrated.execSQL(
            "INSERT INTO transactions (id, profile_id, account_id, amount_minor, currency_code, " +
                "occurred_at_utc_millis, booked_on_iso_date, source, type, posted_at_utc_millis, " +
                "created_at_utc_millis, updated_at_utc_millis) VALUES " +
                "('t3','p1','a1',-2500000,'INR',1769904000000,'2026-02-01','manual','expense',NULL," +
                "1767312000000,1767312000000)",
        )
        migrated.query("SELECT posted_at_utc_millis FROM transactions WHERE id = 't3'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue("FR-TXN-010: a scheduled row carries no posted stamp", cursor.isNull(0))
        }
        migrated.close()
    }

    /**
     * 8 → 9 adds `tags` and `transaction_tags` (issue 3.6; FR-TXN-007, FR-TXN-008).
     *
     * Input:  a version-8 database holding a transaction with an exact paise amount.
     * Output: asserts the transaction survives untouched, both new tables exist and are writable,
     *         and the unique index actually rejects a duplicate tag name within a profile while
     *         allowing the same name under a different profile.
     *
     * **Additive, so the risk is the 6 → 7 one rather than the 7 → 8 one**: nothing needs
     * backfilling, but a migration that recreated `transactions` on its way to adding a table would
     * validate perfectly against `schemas/9.json` and lose every row. The amount is asserted byte
     * for byte for the reason `migrate1To2` gives (MNY-001).
     *
     * **The unique index is asserted, not assumed.** It is the only thing stopping `travel` existing
     * twice under one profile and splitting a tag's transactions across two chips — and an index a
     * migration forgot to create is invisible until a user has two of something.
     */
    @Test
    fun migrate8To9_preservesTransactionsAndAcceptsTags() {
        helper.createDatabase(TEST_DB, 8).use { db ->
            db.execSQL(
                "INSERT INTO transactions (id, profile_id, account_id, amount_minor, currency_code, " +
                    "occurred_at_utc_millis, booked_on_iso_date, source, type, posted_at_utc_millis, " +
                    "created_at_utc_millis, updated_at_utc_millis) VALUES " +
                    "('t1','p1','a1',-12345678,'INR',1767312000000,'2026-01-02','manual','expense'," +
                    "1767312000000,1767312000000,1767312000000)",
            )
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 9, true, Migrations.MIGRATION_8_9)

        migrated.query("SELECT amount_minor FROM transactions WHERE id = 't1'").use { cursor ->
            assertTrue("the pre-migration transaction must still be there", cursor.moveToFirst())
            assertEquals("MNY-001: the amount must survive byte for byte", -12345678L, cursor.getLong(0))
        }

        migrated.execSQL(
            "INSERT INTO tags (id, profile_id, name, created_at_utc_millis, updated_at_utc_millis) " +
                "VALUES ('g1','p1','travel',1767312000000,1767312000000)",
        )
        migrated.execSQL(
            "INSERT INTO transaction_tags (id, profile_id, transaction_id, tag_id, " +
                "created_at_utc_millis, updated_at_utc_millis) " +
                "VALUES ('tt1','p1','t1','g1',1767312000000,1767312000000)",
        )
        migrated.query(
            "SELECT g.name FROM transaction_tags tt JOIN tags g ON g.id = tt.tag_id " +
                "WHERE tt.transaction_id = 't1'",
        ).use { cursor ->
            assertTrue("the join must resolve a tag back to its transaction", cursor.moveToFirst())
            assertEquals("travel", cursor.getString(0))
        }

        // The same name under a second profile is a different tag — the demo profile depends on it.
        migrated.execSQL(
            "INSERT INTO tags (id, profile_id, name, created_at_utc_millis, updated_at_utc_millis) " +
                "VALUES ('g2','p2','travel',1767312000000,1767312000000)",
        )
        // The same name under the *same* profile is not.
        val duplicateRejected =
            runCatching {
                migrated.execSQL(
                    "INSERT INTO tags (id, profile_id, name, created_at_utc_millis, updated_at_utc_millis) " +
                        "VALUES ('g3','p1','travel',1767312000000,1767312000000)",
                )
            }.isFailure
        assertTrue("the unique index must reject a duplicate tag name within a profile", duplicateRejected)
        migrated.close()
    }

    /**
     * 9 → 10 adds `recurring_rule.dismissed_at_utc_millis` (issue 3.7; FR-TXN-006).
     *
     * Input:  a version-9 database holding a confirmed quick-setup rule with an exact paise amount.
     * Output: asserts the rule survives untouched with `dismissed_at_utc_millis` null — the truth
     *         for every pre-existing row, which is why the migration backfills nothing — and that
     *         the column is writable, which is what a rejection is.
     *
     * **Nullable, so the trap is the 8 → 9 one rather than the 5 → 6 one**: there is no `DEFAULT` to
     * keep in step with an entity annotation, but a migration that recreated `recurring_rule` on its
     * way to adding the column would validate perfectly against `schemas/10.json` and lose the
     * user's rules. The amount is asserted byte for byte for the reason `migrate1To2` gives
     * (MNY-001).
     *
     * **The distinction is asserted, not assumed.** A dismissed rule must leave `observeForProfile`
     * while still being *found* by `observeDecidedNames` — that difference is the whole mechanism
     * stopping a rejected proposal from coming back, and a column that quietly behaved like
     * `deleted_at_utc_millis` would break it invisibly.
     */
    @Test
    fun migrate9To10_preservesRulesAndAcceptsDismissal() {
        helper.createDatabase(TEST_DB, 9).use { db ->
            db.execSQL(
                "INSERT INTO recurring_rule (id, profile_id, name, amount_minor, cadence, " +
                    "next_due_iso_date, source, is_confirmed, created_at_utc_millis, updated_at_utc_millis) " +
                    "VALUES ('r1','p1','Landlord',-2500000,'monthly','2026-09-03','quick_setup',1," +
                    "1767312000000,1767312000000)",
            )
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 10, true, Migrations.MIGRATION_9_10)

        migrated.query(
            "SELECT amount_minor, dismissed_at_utc_millis FROM recurring_rule WHERE id = 'r1'",
        ).use { cursor ->
            assertTrue("the pre-migration rule must still be there", cursor.moveToFirst())
            assertEquals("MNY-001: the amount must survive byte for byte", -2500000L, cursor.getLong(0))
            assertTrue("no backfill: an existing rule has not been dismissed", cursor.isNull(1))
        }

        migrated.execSQL(
            "INSERT INTO recurring_rule (id, profile_id, name, amount_minor, cadence, " +
                "next_due_iso_date, source, is_confirmed, dismissed_at_utc_millis, " +
                "created_at_utc_millis, updated_at_utc_millis) " +
                "VALUES ('r2','p1','Netflix',-64900,'monthly','2026-09-04','detected',0,1767312000000," +
                "1767312000000,1767312000000)",
        )

        // Dismissed rows leave the ordinary read...
        migrated.query(
            "SELECT COUNT(*) FROM recurring_rule WHERE profile_id = 'p1' " +
                "AND deleted_at_utc_millis IS NULL AND dismissed_at_utc_millis IS NULL",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("only the confirmed rule is live", 1, cursor.getInt(0))
        }
        // ...but stay on record as a merchant the user has already answered.
        migrated.query(
            "SELECT COUNT(DISTINCT LOWER(name)) FROM recurring_rule WHERE profile_id = 'p1' AND name IS NOT NULL",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("a rejection is a decision, not an absence", 2, cursor.getInt(0))
        }
        migrated.close()
    }

    /**
     * 10 → 11 adds `attachments` (issue 3.8; FR-OCR-005).
     *
     * Input:  a version-10 database holding a transaction with an exact paise amount.
     * Output: asserts the transaction survives untouched, the new table exists and accepts a
     *         receipt row, and — the half of FR-OCR-005 that matters — tombstoning that row leaves
     *         the transaction alone.
     *
     * **Purely additive, so the failure this guards against is a migration that recreated an
     * existing table on its way to adding a new one**: that would validate perfectly against
     * `schemas/11.json` and lose the user's ledger. The amount is asserted byte for byte for the
     * reason `migrate1To2` gives (MNY-001).
     */
    @Test
    fun migrate10To11_preservesTransactionsAndAcceptsAttachments() {
        helper.createDatabase(TEST_DB, 10).use { db ->
            db.execSQL(
                "INSERT INTO transactions (id, profile_id, account_id, amount_minor, currency_code, " +
                    "occurred_at_utc_millis, booked_on_iso_date, source, type, " +
                    "created_at_utc_millis, updated_at_utc_millis) " +
                    "VALUES ('t1','p1','a1',-36580,'INR',1767312000000,'2026-08-04','ocr','expense'," +
                    "1767312000000,1767312000000)",
            )
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 11, true, Migrations.MIGRATION_10_11)

        migrated.query("SELECT amount_minor FROM transactions WHERE id = 't1'").use { cursor ->
            assertTrue("the pre-migration transaction must still be there", cursor.moveToFirst())
            assertEquals("MNY-001: the amount must survive byte for byte", -36580L, cursor.getLong(0))
        }

        migrated.execSQL(
            "INSERT INTO attachments (id, profile_id, transaction_id, kind, file_name, mime_type, " +
                "byte_size, created_at_utc_millis, updated_at_utc_millis) " +
                "VALUES ('at1','p1','t1','receipt','at1.bin','image/jpeg',48213," +
                "1767312000000,1767312000000)",
        )
        migrated.query(
            "SELECT COUNT(*) FROM attachments WHERE transaction_id = 't1' AND deleted_at_utc_millis IS NULL",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("the receipt must be linked to its transaction", 1, cursor.getInt(0))
        }

        // FR-OCR-005's second half: the image goes, the transaction stays.
        migrated.execSQL("UPDATE attachments SET deleted_at_utc_millis = 1767312000000 WHERE id = 'at1'")
        migrated.query("SELECT COUNT(*) FROM transactions WHERE id = 't1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("deleting the image must not touch the transaction", 1, cursor.getInt(0))
        }
        migrated.close()
    }

    /**
     * 12 → 13: `transactions.nature`, the user's nature override (issue 4.3; §8.3).
     *
     * Why:    additive and one column, so the structural risk is the small one — a migration that
     *         recreated `transactions` on its way to altering it would validate against
     *         `schemas/13.json` and lose the ledger. But the **semantic** claim is the one worth a
     *         test, and it is the opposite of `migrate7To8`'s: there, the absent backfill *was* the
     *         bug, because every pre-existing row read as unposted. Here `NULL` is the correct and
     *         complete value for every existing transaction — nobody has overridden anything yet,
     *         and the automatic nature is derived on read rather than stored.
     *
     *         So this asserts the column arrives **empty**, not merely present. A migration that
     *         helpfully backfilled it with a guessed nature would pass a shape check and quietly
     *         convert five engine-derived guesses into five user decisions, which is exactly the
     *         distinction §8.3.1 step 4 learns from.
     * Input:  a transaction written at version 12. Output: it survives, with a null override that
     *         then accepts a value.
     */
    @Test
    fun migrate12To13_preservesTransactionsAndLeavesTheNatureOverrideEmpty() {
        helper.createDatabase(TEST_DB, 12).use { db ->
            db.execSQL(
                "INSERT INTO transactions (id, profile_id, account_id, amount_minor, currency_code, " +
                    "occurred_at_utc_millis, booked_on_iso_date, category_id, source, type, " +
                    "created_at_utc_millis, updated_at_utc_millis) " +
                    "VALUES ('t1','p1','a1',-940000,'INR',1767312000000,'2026-08-10','c1','manual','expense'," +
                    "1767312000000,1767312000000)",
            )
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 13, true, Migrations.MIGRATION_12_13)

        migrated.query("SELECT amount_minor, nature FROM transactions WHERE id = 't1'").use { cursor ->
            assertTrue("the pre-migration transaction must still be there", cursor.moveToFirst())
            assertEquals("MNY-001: the amount must survive byte for byte", -940000L, cursor.getLong(0))
            assertTrue(
                "the override column must arrive empty — a backfilled guess would read as the user's decision",
                cursor.isNull(1),
            )
        }

        // And it is writable: an override set after the upgrade is the one thing this column stores.
        migrated.execSQL("UPDATE transactions SET nature = 'want' WHERE id = 't1'")
        migrated.query("SELECT nature FROM transactions WHERE id = 't1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("want", cursor.getString(0))
        }
        migrated.close()
    }

    /**
     * 11 → 12 adds `sms_draft` (issue 3.9; §18, §23, P-01).
     *
     * Input:  a version-11 database holding a transaction with an exact paise amount.
     * Output: asserts the transaction survives untouched, the new table exists and accepts a draft,
     *         and the two guarantees the table was shaped around actually hold in SQL: **one alert
     *         cannot become two drafts**, and **revoking the consent removes the pending inference
     *         while leaving what the user already accepted**.
     *
     * The unique index is exercised rather than assumed. It is the only thing standing between an
     * overlapping re-scan — a crash between reading a batch and advancing the cursor — and the same
     * purchase being offered to the user twice, and an index that exists in `schemas/12.json` but
     * was never inserted against is a claim rather than a guarantee.
     *
     * **Purely additive, so the failure this guards against is a migration that recreated an
     * existing table on its way to adding a new one**: that would validate perfectly against
     * `schemas/12.json` and lose the user's ledger. The amount is asserted byte for byte for the
     * reason `migrate1To2` gives (MNY-001).
     */

    @Test
    fun migrate11To12_preservesTransactionsAndEnforcesTheDraftGuarantees() {
        helper.createDatabase(TEST_DB, 11).use { db ->
            db.execSQL(
                "INSERT INTO transactions (id, profile_id, account_id, amount_minor, currency_code, " +
                    "occurred_at_utc_millis, booked_on_iso_date, source, type, " +
                    "created_at_utc_millis, updated_at_utc_millis) " +
                    "VALUES ('t1','p1','a1',-125000,'INR',1767312000000,'2026-08-07','sms','expense'," +
                    "1767312000000,1767312000000)",
            )
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 12, true, Migrations.MIGRATION_11_12)

        migrated.query("SELECT amount_minor FROM transactions WHERE id = 't1'").use { cursor ->
            assertTrue("the pre-migration transaction must still be there", cursor.moveToFirst())
            assertEquals("MNY-001: the amount must survive byte for byte", -125000L, cursor.getLong(0))
        }

        migrated.execSQL(draftInsert(id = "d1", smsId = 71L, status = "pending"))
        migrated.execSQL(draftInsert(id = "d2", smsId = 72L, status = "accepted"))

        // One alert, one draft: an overlapping re-scan must not offer the same purchase twice.
        migrated.execSQL("INSERT OR IGNORE INTO sms_draft " + draftValues(id = "d3", smsId = 71L, status = "pending"))
        migrated.query("SELECT COUNT(*) FROM sms_draft WHERE profile_id = 'p1' AND sms_id = 71").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("the unique index must collapse a re-scanned alert to one draft", 1, cursor.getInt(0))
        }

        // Revoking the consent: the pending inference goes, the accepted transaction's provenance stays.
        migrated.execSQL("DELETE FROM sms_draft WHERE profile_id = 'p1' AND status = 'pending'")
        migrated.query("SELECT COUNT(*) FROM sms_draft WHERE profile_id = 'p1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("revocation must clear pending drafts and keep accepted ones", 1, cursor.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM transactions WHERE id = 't1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("revoking the SMS consent must not touch the ledger", 1, cursor.getInt(0))
        }
        migrated.close()
    }

    /**
     * Result: a full `INSERT` for one draft. Input: [id], [smsId], [status]. Output: SQL.
     * Why:    three inserts differing in three values; spelling out sixteen columns each time would
     *         bury what the test is actually varying.
     */
    private fun draftInsert(
        id: String,
        smsId: Long,
        status: String,
    ): String = "INSERT INTO sms_draft " + draftValues(id, smsId, status)

    /** Result: the column list and values of [draftInsert], for reuse by an `INSERT OR IGNORE`. */
    private fun draftValues(
        id: String,
        smsId: Long,
        status: String,
    ): String =
        "(id, profile_id, sms_id, sender, amount_minor, direction, booked_on, confidence_bps, " +
            "engine_version, rule_version, status, created_at_utc_millis, updated_at_utc_millis) " +
            "VALUES ('$id','p1',$smsId,'VM-HDFCBK',125000,'debit','2026-08-07',9000," +
            "'1.0','1.0','$status',1767312000000,1767312000000)"

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
