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

    /** Every migration, in order, for `CfoDatabaseFactory` to register. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

    /** Named so the version pair reads as a schema version rather than an unexplained literal. */
    private const val VERSION_2 = 2

    /** The version issue 2.3 introduced, and the one issue 2.5 upgrades from. */
    private const val VERSION_3 = 3

    /** Matches `CfoDatabase.VERSION` at the time this migration was written (issue 2.5). */
    private const val VERSION_4 = 4
}
