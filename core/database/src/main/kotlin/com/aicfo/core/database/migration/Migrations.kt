package com.aicfo.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Every hand-written schema migration (DB-003, §20).
 *
 * Why:  this app has no server copy. A migration that drops a column takes a user's financial
 *       history with it and nothing can bring it back, which is why DB-003 forbids
 *       `fallbackToDestructiveMigration` outright and why every bump is written by hand and
 *       reviewed. Keeping them in one list means "which migrations exist?" has one answer, and
 *       registering a new one is a single edit rather than a search through the codebase.
 * What: the ordered migrations, and [ALL] for `CfoDatabaseFactory` to register.
 * Result: opening an older database upgrades it; a missing migration fails loudly at open rather
 *       than silently recreating an empty file.
 * Changelog: 2026-07-26 — Created for issue 2.2 with the first bump, 1 → 2 (`audit_log`).
 *
 * **The checklist for adding one** (also in `MigrationSafetyTest`, which fails if it is skipped):
 * bump `CfoDatabase.VERSION`, write the `Migration` here, add it to [ALL], build the module so KSP
 * exports `schemas/<version>.json`, **commit that file**, and add a round-trip case to
 * `MigrationRoundTripTest`.
 */
internal object Migrations {
    /**
     * 1 → 2: adds `audit_log` for security events (issue 2.2; §21.6, SEC-002).
     *
     * Why:    §21.6 sends security events to a table rather than to `Log.*`, and issue 2.2 is the
     *         first code that produces any — unlock successes, failures and lockouts.
     * What:   creates one new table and its index. **Purely additive**: no existing table, column
     *         or row is touched, so there is nothing here that can lose data. That is the shape
     *         every migration in this app should have unless there is a written reason otherwise.
     * Result: an existing installation gains an empty `audit_log` and keeps everything else.
     * Input:  [SupportSQLiteDatabase] — the database mid-upgrade. Output: none (executes DDL).
     *
     * The DDL is written to match exactly what Room generates for `AuditLogEntity`; if it drifts,
     * `MigrationRoundTripTest`'s schema validation fails on a device, and the committed
     * `schemas/2.json` is the reference.
     */
    val MIGRATION_1_2 =
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `audit_log` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`event` TEXT NOT NULL, " +
                        "`occurred_at_utc_millis` INTEGER NOT NULL, " +
                        "`method` TEXT)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_audit_log_occurred_at_utc_millis` " +
                        "ON `audit_log` (`occurred_at_utc_millis`)",
                )
            }
        }

    /**
     * 2 → 3: adds `budget` and `recurring_rule` for the quick-setup seeds (issue 2.3; FR-ONB-002).
     *
     * Why:    FR-ONB-002's figures have to land somewhere, and until now there was nowhere — the
     *         schema had no notion of a planned amount or a repeating one. Both tables arrive
     *         together because quick setup writes to both in a single transaction; splitting them
     *         across two versions would leave an installation that can hold half a plan.
     * What:   creates two new tables and their indices. **Purely additive**, exactly like
     *         `MIGRATION_1_2`: no existing table, column or row is read or touched, so there is
     *         nothing here that can lose data (DB-003).
     * Result: an existing installation gains two empty tables and keeps everything else.
     * Input:  [SupportSQLiteDatabase] — the database mid-upgrade. Output: none (executes DDL).
     *
     * The DDL matches what Room generates for `BudgetEntity` and `RecurringRuleEntity`; the
     * committed `schemas/3.json` is the reference, and `MigrationRoundTripTest` fails on a device
     * if the two drift.
     */
    val MIGRATION_2_3 =
        object : Migration(VERSION_2, VERSION_3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createBudget(db)
                createRecurringRule(db)
            }
        }

    /**
     * Creates `budget` and its indices (issue 2.3).
     * Why:    split from [MIGRATION_2_3] so each table's DDL is short enough to read against the
     *         entity beside it — a migration nobody can check line by line is a migration nobody
     *         checks.
     * Result: the table exists. Input: [db]. Output: none (executes DDL).
     */
    private fun createBudget(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `budget` (" +
                "`id` TEXT NOT NULL, " +
                "`profile_id` TEXT NOT NULL, " +
                "`category_id` TEXT, " +
                "`nature` TEXT, " +
                "`period_start_iso_date` TEXT NOT NULL, " +
                "`amount_minor` INTEGER NOT NULL, " +
                "`rollover_enabled` INTEGER NOT NULL, " +
                "`source` TEXT NOT NULL, " +
                "`rule_id` TEXT, " +
                "`rule_version` TEXT, " +
                "`created_at_utc_millis` INTEGER NOT NULL, " +
                "`updated_at_utc_millis` INTEGER NOT NULL, " +
                "`deleted_at_utc_millis` INTEGER, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_budget_profile_id_period_start_iso_date` " +
                "ON `budget` (`profile_id`, `period_start_iso_date`)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_budget_category_id` ON `budget` (`category_id`)")
    }

    /**
     * Creates `recurring_rule` and its indices (issue 2.3).
     * Why:    see [createBudget] — one function per table keeps each block checkable against the
     *         entity it mirrors.
     * Result: the table exists. Input: [db]. Output: none (executes DDL).
     */
    private fun createRecurringRule(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recurring_rule` (" +
                "`id` TEXT NOT NULL, " +
                "`profile_id` TEXT NOT NULL, " +
                "`account_id` TEXT, " +
                "`category_id` TEXT, " +
                "`name` TEXT, " +
                "`seed_kind` TEXT, " +
                "`amount_minor` INTEGER NOT NULL, " +
                "`cadence` TEXT NOT NULL, " +
                "`next_due_iso_date` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, " +
                "`is_confirmed` INTEGER NOT NULL, " +
                "`created_at_utc_millis` INTEGER NOT NULL, " +
                "`updated_at_utc_millis` INTEGER NOT NULL, " +
                "`deleted_at_utc_millis` INTEGER, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_recurring_rule_profile_id_next_due_iso_date` " +
                "ON `recurring_rule` (`profile_id`, `next_due_iso_date`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_recurring_rule_account_id` " +
                "ON `recurring_rule` (`account_id`)",
        )
    }

    /**
     * 3 → 4: adds `institution` and `archived_at_utc_millis` to `account` (issue 2.5; FR-ACC-007).
     *
     * Why:    issue 2.5 is the first code that lets a user *create* an account, and two things the
     *         SRS's own DDL (§20.2) has always had were missing from issue 1.6's narrower table.
     *         `institution` is the bank or issuer. `archived_at_utc_millis` is FR-ACC-007: a closed
     *         account "MUST retain history and be excluded from active totals", which a soft delete
     *         cannot express — deleting a closed card would take its past transactions out of every
     *         month it appeared in.
     * What:   two `ALTER TABLE … ADD COLUMN` statements. **Purely additive**, like both migrations
     *         above: no existing column is read, rewritten or dropped, and both new columns are
     *         nullable, so every existing row is already valid the moment they exist (DB-003).
     * Result: an existing installation gains two null columns and keeps every account it had.
     * Input:  [SupportSQLiteDatabase] — the database mid-upgrade. Output: none (executes DDL).
     *
     * **No `IF NOT EXISTS` here, unlike the `CREATE` statements above** — SQLite's `ADD COLUMN` has
     * no such clause. That is the right behaviour anyway: a migration that finds the column already
     * present has been run twice, and failing loudly is better than continuing over a database in
     * an unknown state.
     *
     * The types match what Room generates for `AccountEntity`; the committed `schemas/4.json` is
     * the reference, and `MigrationRoundTripTest` fails on a device if the two drift.
     */
    val MIGRATION_3_4 =
        object : Migration(VERSION_3, VERSION_4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `account` ADD COLUMN `institution` TEXT")
                db.execSQL("ALTER TABLE `account` ADD COLUMN `archived_at_utc_millis` INTEGER")
            }
        }

    /**
     * 4 → 5: adds `net_worth_snapshot` and `account.include_in_networth` (issue 2.6; FR-ACC-005).
     *
     * Why:    FR-ACC-005 requires net worth "snapshotted daily", and there was nowhere to put a
     *         snapshot. The column arrives in the same migration because net worth is the only
     *         thing that reads it — splitting them would mean two migrations for one feature.
     * What:   one `CREATE TABLE` plus its index, and one `ALTER TABLE … ADD COLUMN`. **Purely
     *         additive**, like every migration above it: no existing column is read, rewritten or
     *         dropped (DB-003).
     * Result: an existing installation gains an empty table and one column, and keeps everything.
     * Input:  [SupportSQLiteDatabase] — the database mid-upgrade. Output: none (executes DDL).
     *
     * **`NOT NULL DEFAULT 1` on the new column, and the default is the load-bearing part.** Every
     * account that already exists keeps counting towards net worth, which is what a user who has
     * never been asked the question expects. A nullable column would have meant every read carrying
     * a "null means yes" rule, and the first reader to forget it would silently drop every
     * pre-upgrade account out of the total.
     *
     * The DDL matches what Room generates for `NetWorthSnapshotEntity` and `AccountEntity`; the
     * committed `schemas/5.json` is the reference, and `MigrationRoundTripTest` fails on a device if
     * the two drift.
     */
    val MIGRATION_4_5 =
        object : Migration(VERSION_4, VERSION_5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `net_worth_snapshot` (" +
                        "`id` TEXT NOT NULL, " +
                        "`profile_id` TEXT NOT NULL, " +
                        "`as_of_iso_date` TEXT NOT NULL, " +
                        "`assets_minor` INTEGER NOT NULL, " +
                        "`liabilities_minor` INTEGER NOT NULL, " +
                        "`net_worth_minor` INTEGER NOT NULL, " +
                        "`engine_id` TEXT NOT NULL, " +
                        "`engine_version` TEXT NOT NULL, " +
                        "`computed_at_utc_millis` INTEGER NOT NULL, " +
                        "`created_at_utc_millis` INTEGER NOT NULL, " +
                        "`updated_at_utc_millis` INTEGER NOT NULL, " +
                        "`deleted_at_utc_millis` INTEGER, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_net_worth_snapshot_profile_id_as_of_iso_date` " +
                        "ON `net_worth_snapshot` (`profile_id`, `as_of_iso_date`)",
                )
                db.execSQL(
                    "ALTER TABLE `account` ADD COLUMN `include_in_networth` INTEGER NOT NULL DEFAULT 1",
                )
            }
        }

    /**
     * 5 → 6: adds `transactions.type` and `transactions.transfer_id` (issue 3.2; FR-TXN-003, §20.2).
     *
     * Why:    FR-TXN-003 requires a transfer to be "a single logical record affecting two accounts
     *         atomically", which needs somewhere to record that two rows are one thing —
     *         `transfer_id`. `type` arrives with it because a transfer leg must be excluded from
     *         income and expense totals, and the amount's sign alone cannot tell a transfer_out from
     *         an ordinary expense. Both columns are §20.2's own.
     * What:   two `ALTER TABLE … ADD COLUMN`, one `UPDATE` that classifies the rows that already
     *         exist, and one index. **Additive**: no column is dropped or narrowed and no row is
     *         deleted (DB-003). The `UPDATE` rewrites only the column being introduced.
     * Result: an existing installation keeps every transaction, each correctly typed, and gains the
     *         ability to hold transfers.
     * Input:  [SupportSQLiteDatabase] — the database mid-upgrade. Output: none (executes DDL).
     *
     * **The `UPDATE` is the load-bearing part, not the `ADD COLUMN`s.** SQLite can only add a
     * `NOT NULL` column with a `DEFAULT`, so every pre-existing row would otherwise read as an
     * `expense` — including the user's salary credits and every FR-ACC-006 adjustment issue 2.7
     * wrote. The `CASE` restores what the row already implies: `source = 'reconciliation'` is
     * §20.2's `adjustment`, and for everything else the sign is the direction, exactly as it has
     * been since issue 1.6. Nothing is guessed — `transfer_id` stays null because no transfer could
     * have been created before this migration existed.
     *
     * `'expense'` as the SQL default rather than `''`: if a future row somehow escapes the `UPDATE`,
     * a valid value that reads oddly is recoverable, while an empty string parses to `null` in
     * `TransactionType.fromStored` and would make the row invisible to every list.
     *
     * The types match what Room generates for `TransactionEntity`; the committed `schemas/6.json` is
     * the reference, and `MigrationRoundTripTest` fails on a device if the two drift.
     */
    val MIGRATION_5_6 =
        object : Migration(VERSION_5, VERSION_6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `type` TEXT NOT NULL DEFAULT 'expense'")
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `transfer_id` TEXT")
                db.execSQL(
                    "UPDATE `transactions` SET `type` = CASE " +
                        "WHEN `source` = 'reconciliation' THEN 'adjustment' " +
                        "WHEN `amount_minor` >= 0 THEN 'income' " +
                        "ELSE 'expense' END",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transactions_transfer_id` " +
                        "ON `transactions` (`transfer_id`)",
                )
            }
        }

    /**
     * 6 → 7: adds `transaction_splits` (issue 3.3; FR-TXN-004, §20.1).
     *
     * Why:    FR-TXN-004 requires "N lines with independent categories" summing exactly to the
     *         parent, and one `category_id` on `transactions` cannot express that. §20.1 names this
     *         table in the inventory; §20.2 never gives it a DDL, so the columns are this issue's to
     *         choose (recorded in `docs/adr/0009-splits-as-a-child-table.md`).
     * What:   one `CREATE TABLE` and its three indices. **Purely additive** — no existing table,
     *         column or row is read or rewritten, so unlike 5 → 6 there is nothing to backfill and
     *         nothing that can lose data (DB-003).
     * Result: an existing installation gains an empty table and keeps everything it had; every
     *         transaction that already exists is simply unsplit, which is the truth about it.
     * Input:  [SupportSQLiteDatabase] — the database mid-upgrade. Output: none (executes DDL).
     *
     * **`profile_id` on a child row, duplicating its parent's.** Not redundant: `DemoDao`'s wipe and
     * its residue count are single-table, profile-scoped queries (ADR-0006), and a table they could
     * not reach directly would leave rows behind when a demo is exited. `MigrationSafetyTest`
     * enforces the same rule on every table but `audit_log`.
     *
     * **No foreign key on `transaction_id`.** No other table in this schema declares one either —
     * §20.3 wants FKs eventually, but adding one here alone would make this the only table where a
     * delete order is enforced by SQLite rather than by the repository, and DB-002 means parents are
     * soft-deleted anyway, so the constraint would never fire on the case that matters.
     *
     * The DDL matches what Room generates for `TransactionSplitEntity`; the committed
     * `schemas/7.json` is the reference, and `MigrationRoundTripTest` fails on a device if they drift.
     */
    val MIGRATION_6_7 =
        object : Migration(VERSION_6, VERSION_7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `transaction_splits` (" +
                        "`id` TEXT NOT NULL, " +
                        "`profile_id` TEXT NOT NULL, " +
                        "`transaction_id` TEXT NOT NULL, " +
                        "`amount_minor` INTEGER NOT NULL, " +
                        "`category_id` TEXT, " +
                        "`note` TEXT, " +
                        "`created_at_utc_millis` INTEGER NOT NULL, " +
                        "`updated_at_utc_millis` INTEGER NOT NULL, " +
                        "`deleted_at_utc_millis` INTEGER, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transaction_splits_transaction_id` " +
                        "ON `transaction_splits` (`transaction_id`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transaction_splits_profile_id` " +
                        "ON `transaction_splits` (`profile_id`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transaction_splits_category_id` " +
                        "ON `transaction_splits` (`category_id`)",
                )
            }
        }

    /**
     * 7 → 8: adds `transactions.posted_at_utc_millis` (issue 3.4; FR-TXN-010).
     *
     * Why:    FR-TXN-010 requires future-dated transactions to be "excluded from actuals". The
     *         exclusion itself is already structural — every balance query bounds on
     *         `booked_on_iso_date <= :asOfIsoDate` — so this column is not what keeps a scheduled
     *         row out of the figures. It records **that the rollover was observed**: it is
     *         `ScheduledTransactionWorker`'s idempotence key, and the durable marker issue 3.7 and
     *         any later "posted today" surface read. See `docs/adr/0010-future-dated-posting.md`.
     * What:   one `ADD COLUMN`, then a backfill.
     * Result: an upgraded installation keeps every row, and every row it already had is posted.
     * Input:  [SupportSQLiteDatabase] — the database mid-upgrade. Output: none (executes DDL).
     *
     * **The backfill is the whole risk in this migration, and it is not optional.** The column is
     * nullable, so `ADD COLUMN` gives every existing row `NULL` — which is exactly the value that
     * means "not yet posted". Without the `UPDATE`, every transaction written before this issue
     * would read as scheduled: `ScheduledTransactionWorker` would stamp them all on its first run,
     * and until it did, the recent list would be entirely empty and the Scheduled group would hold
     * the user's whole history. `occurred_at_utc_millis` is the honest value to backfill with — it
     * is when the money actually moved, and every pre-3.4 row was booked on the day it was created.
     *
     * **No `DEFAULT`, and no `@ColumnInfo(defaultValue = …)` on the entity.** The trap ADR-0008
     * documents — SQLite refusing a `NOT NULL` `ADD COLUMN` without one, and Room then failing
     * schema validation at open time — applies only to `NOT NULL` columns. This one is nullable by
     * design, because "not posted" needs a representable value.
     *
     * The type matches what Room generates for `TransactionEntity`; the committed `schemas/8.json`
     * is the reference, and `MigrationRoundTripTest` fails on a device if the two drift.
     */
    val MIGRATION_7_8 =
        object : Migration(VERSION_7, VERSION_8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `posted_at_utc_millis` INTEGER")
                db.execSQL(
                    "UPDATE `transactions` SET `posted_at_utc_millis` = `occurred_at_utc_millis` " +
                        "WHERE `posted_at_utc_millis` IS NULL",
                )
            }
        }

    /** Every migration, in order, for `CfoDatabaseFactory` to register. */
    val ALL: Array<Migration> =
        arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
        )

    /** Named so the version pair reads as a schema version rather than an unexplained literal. */
    private const val VERSION_2 = 2

    /** The version issue 2.3 introduced, and the one issue 2.5 upgrades from. */
    private const val VERSION_3 = 3

    /** The version issue 2.5 introduced, and the one issue 2.6 upgrades from. */
    private const val VERSION_4 = 4

    /** The version issue 2.6 introduced, and the one issue 3.2 upgrades from. */
    private const val VERSION_5 = 5

    /** The version issue 3.2 introduced, and the one issue 3.3 upgrades from. */
    private const val VERSION_6 = 6

    /** The version issue 3.3 introduced, and the one issue 3.4 upgrades from. */
    private const val VERSION_7 = 7

    /** Matches `CfoDatabase.VERSION` at the time this migration was written (issue 3.4). */
    private const val VERSION_8 = 8
}
