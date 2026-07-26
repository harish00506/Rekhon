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

    /** Every migration, in order, for `CfoDatabaseFactory` to register. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
