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

    /**
     * 8 → 9: adds `tags` and `transaction_tags` (issue 3.6; FR-TXN-007, FR-TXN-008).
     *
     * Why:    FR-TXN-007 requires the list to be searchable by tag and FR-TXN-008 requires bulk
     *         retag. SRS §20.1 names these two tables, so this follows the blueprint rather than
     *         inventing a comma-joined column on `transactions` — which would have made "find every
     *         transaction tagged `travel`" a substring match that also matched `travel-insurance`.
     * What:   two `CREATE TABLE IF NOT EXISTS` and their indices. **Nothing existing is read,
     *         written or altered**, which is the same property [MIGRATION_2_3] and [MIGRATION_6_7]
     *         have and the reason this cannot lose data: an upgraded installation simply gains two
     *         empty tables. `MigrationSafetyTest` proves it by diffing 8.json against 9.json.
     * Result: an upgraded installation keeps every row and can carry tags.
     * Input:  [SupportSQLiteDatabase] — the database mid-upgrade. Output: none (executes DDL).
     *
     * **No backfill, deliberately.** Unlike [MIGRATION_7_8], there is no pre-existing value these
     * tables should hold: a user who has never tagged anything has no tags, and inventing some from
     * merchants or categories would be the app putting words in their mouth.
     *
     * The DDL matches what Room generates for `TagEntity` and `TransactionTagEntity`; the committed
     * `schemas/9.json` is the reference, and `MigrationRoundTripTest` fails on a device if the two
     * drift.
     */
    val MIGRATION_8_9 =
        object : Migration(VERSION_8, VERSION_9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createTags(db)
                createTransactionTags(db)
            }
        }

    /**
     * 9 → 10: adds `recurring_rule.dismissed_at_utc_millis` (issue 3.7; FR-TXN-006).
     *
     * Why:    FR-TXN-006 lets the user reject a proposed series, and a rejection has to be *stored*
     *         or the detector proposes the same merchant again on the next read — the app arguing
     *         with a decision the user already made. The existing columns cannot carry it:
     *         `is_confirmed = false` is what a *pending* proposal looks like, and
     *         `deleted_at_utc_millis` means the row is gone, which every read already filters out.
     * What:   one nullable `ADD COLUMN`. **Nullable on purpose** — null already means "not
     *         dismissed" for every existing row, so there is nothing to backfill and no `DEFAULT`
     *         to keep in sync with an entity annotation. The trap [MIGRATION_5_6] documents (a
     *         `NOT NULL` column whose SQL default must match `@ColumnInfo(defaultValue)` exactly)
     *         does not apply here, precisely because this column is allowed to be absent.
     * Result: an upgraded installation keeps every rule and can record a rejection.
     * Input:  [SupportSQLiteDatabase] — the database mid-upgrade. Output: none (executes DDL).
     *
     * The DDL matches what Room generates for `RecurringRuleEntity`; the committed `schemas/10.json`
     * is the reference, and `MigrationRoundTripTest` fails on a device if the two drift.
     */
    val MIGRATION_9_10 =
        object : Migration(VERSION_9, VERSION_10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `recurring_rule` ADD COLUMN `dismissed_at_utc_millis` INTEGER")
            }
        }

    /**
     * 10 → 11: adds `attachments` for encrypted receipt images (issue 3.8; FR-OCR-005).
     *
     * Why:    FR-OCR-005 requires a scanned receipt to be "stored encrypted and linked as an
     *         attachment", and §20.1 already names the table. Nothing in the existing schema can
     *         carry it: a column on `transactions` would allow exactly one file per row and would
     *         put the link on the wrong side of "delete the image, keep the transaction".
     * What:   one `CREATE TABLE IF NOT EXISTS` plus its two indices. **Purely additive** — no
     *         existing table, column or row is read or altered, which is the shape every migration
     *         in this app should have unless there is a written reason otherwise.
     * Result: an upgraded installation gains an empty `attachments` and keeps everything else.
     * Input:  [SupportSQLiteDatabase] — the database mid-upgrade. Output: none (executes DDL).
     *
     * The DDL matches what Room generates for `AttachmentEntity`; the committed `schemas/11.json`
     * is the reference, and `MigrationRoundTripTest` fails on a device if the two drift.
     */
    val MIGRATION_10_11 =
        object : Migration(VERSION_10, VERSION_11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createAttachments(db)
            }
        }

    /**
     * 11 → 12: adds `sms_draft` for opt-in bank-alert parsing (issue 3.9; §18, §23, P-01).
     *
     * Why:    a parsed alert has to survive being closed and re-opened without becoming a
     *         transaction, so it needs somewhere to live that is neither the ledger nor memory.
     *         Nothing existing can carry it: a `transactions` row would mean the app had recorded
     *         money the user has not confirmed (P-07), and re-deriving the list from the inbox on
     *         every screen open would re-propose every alert the user has already dismissed.
     * What:   one `CREATE TABLE IF NOT EXISTS` plus its two indices. **Purely additive** — no
     *         existing table, column or row is read or altered.
     * Result: an upgraded installation gains an empty `sms_draft` and keeps everything else. A user
     *         who never grants the consent will never have a row in it.
     * Input:  [SupportSQLiteDatabase] — the database mid-upgrade. Output: none (executes DDL).
     *
     * The DDL matches what Room generates for `SmsDraftEntity`; the committed `schemas/12.json` is
     * the reference, and `MigrationRoundTripTest` fails on a device if the two drift.
     */
    val MIGRATION_11_12 =
        object : Migration(VERSION_11, VERSION_12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createSmsDraft(db)
            }
        }

    /**
     * Creates `sms_draft` and its indices (issue 3.9).
     * Why:    one function per table, the convention [createBudget] set — each block stays short
     *         enough to check line by line against the entity it mirrors.
     *
     *         **There is no `body` column, and that absence is the point.** Issue 3.9 requires that
     *         no raw SMS is stored beyond what is needed, and a schema with nowhere to put one is a
     *         stronger guarantee than a rule that says not to. A reviewer can verify the claim by
     *         reading this DDL.
     *
     *         **The first index is UNIQUE on `(profile_id, sms_id)`.** It is what stops one alert
     *         becoming two drafts when a scan overlaps — a crash between reading a batch and
     *         advancing the cursor re-reads it — and it is what `SmsDraftDao.insertIfNew` relies on
     *         to keep a dismissal from being overwritten by a fresh proposal.
     * Result: the table exists. Input: [db]. Output: none (executes DDL).
     */
    private fun createSmsDraft(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sms_draft` (" +
                "`id` TEXT NOT NULL, " +
                "`profile_id` TEXT NOT NULL, " +
                "`sms_id` INTEGER NOT NULL, " +
                "`sender` TEXT NOT NULL, " +
                "`amount_minor` INTEGER NOT NULL, " +
                "`direction` TEXT NOT NULL, " +
                "`booked_on` TEXT NOT NULL, " +
                "`counterparty` TEXT, " +
                "`account_tail` TEXT, " +
                "`confidence_bps` INTEGER NOT NULL, " +
                "`engine_version` TEXT NOT NULL, " +
                "`rule_version` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`transaction_id` TEXT, " +
                "`created_at_utc_millis` INTEGER NOT NULL, " +
                "`updated_at_utc_millis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sms_draft_profile_id_sms_id` " +
                "ON `sms_draft` (`profile_id`, `sms_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_sms_draft_profile_id_status` " +
                "ON `sms_draft` (`profile_id`, `status`)",
        )
    }

    /**
     * Creates `attachments` and its indices (issue 3.8).
     * Why:    one function per table, the convention [createBudget] set — each block stays short
     *         enough to check line by line against the entity it mirrors.
     *
     *         **Two indices, and both are read.** `transaction_id` is how the detail sheet finds a
     *         row's receipt; `profile_id` is how the demo wipe reaches these rows at all (ADR-0006).
     * Result: the table exists. Input: [db]. Output: none (executes DDL).
     */
    private fun createAttachments(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `attachments` (" +
                "`id` TEXT NOT NULL, " +
                "`profile_id` TEXT NOT NULL, " +
                "`transaction_id` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`file_name` TEXT NOT NULL, " +
                "`mime_type` TEXT NOT NULL, " +
                "`byte_size` INTEGER NOT NULL, " +
                "`created_at_utc_millis` INTEGER NOT NULL, " +
                "`updated_at_utc_millis` INTEGER NOT NULL, " +
                "`deleted_at_utc_millis` INTEGER, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_attachments_transaction_id` " +
                "ON `attachments` (`transaction_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_attachments_profile_id` " +
                "ON `attachments` (`profile_id`)",
        )
    }

    /**
     * Creates `tags` and its unique index (issue 3.6).
     * Why:    one function per table, the convention [createBudget] set — each block stays short
     *         enough to check line by line against the entity it mirrors.
     *
     *         **The index is `UNIQUE` on `(profile_id, name)`.** It is the only thing stopping two
     *         `travel` rows from existing under one profile, which would split a tag's transactions
     *         across two chips the user cannot tell apart. Scoped by profile because the demo lives
     *         under a second profile id and must be free to have its own `travel`.
     * Result: the table exists. Input: [db]. Output: none (executes DDL).
     */
    private fun createTags(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tags` (" +
                "`id` TEXT NOT NULL, " +
                "`profile_id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`created_at_utc_millis` INTEGER NOT NULL, " +
                "`updated_at_utc_millis` INTEGER NOT NULL, " +
                "`deleted_at_utc_millis` INTEGER, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_profile_id_name` " +
                "ON `tags` (`profile_id`, `name`)",
        )
    }

    /**
     * Creates `transaction_tags` and its indices (issue 3.6).
     * Why:    see [createTags]. Three indices because the join is read from all three directions —
     *         by transaction (the detail sheet), by tag (the filter's `EXISTS`), and by profile
     *         (the demo wipe, ADR-0006).
     * Result: the table exists. Input: [db]. Output: none (executes DDL).
     */
    private fun createTransactionTags(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `transaction_tags` (" +
                "`id` TEXT NOT NULL, " +
                "`profile_id` TEXT NOT NULL, " +
                "`transaction_id` TEXT NOT NULL, " +
                "`tag_id` TEXT NOT NULL, " +
                "`created_at_utc_millis` INTEGER NOT NULL, " +
                "`updated_at_utc_millis` INTEGER NOT NULL, " +
                "`deleted_at_utc_millis` INTEGER, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_transaction_tags_transaction_id` " +
                "ON `transaction_tags` (`transaction_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_transaction_tags_tag_id` " +
                "ON `transaction_tags` (`tag_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_transaction_tags_profile_id` " +
                "ON `transaction_tags` (`profile_id`)",
        )
    }

    /**
     * 12 → 13: adds `transactions.nature` — the user's nature override (issue 4.3; §8.3).
     *
     * Why:    §8.3 makes nature "auto-assigned, user-correctable, learned", and only the middle word
     *         needs storage. The automatic nature is derived on read by `:domain:engines:nature`
     *         from the account, the category and the user's past corrections, so **the only thing
     *         this column ever holds is a correction** — `NULL` means "whatever the rules currently
     *         say", not "unknown".
     *
     *         That choice is what makes this migration one line. Storing the *resolved* nature
     *         instead would need a backfill of every existing row through the engine here, and then
     *         a recompute job for every future rulebook edit — the shape that already bit the
     *         net-worth series in issue 3.10 and had to be repaired in `repairStaleHistory`. A
     *         derived value with one stored exception cannot go stale.
     * What:   one `ALTER TABLE … ADD COLUMN`, nullable, following [MIGRATION_9_10].
     * Result: an upgraded installation gains an empty column and keeps every row. **No backfill,
     *         deliberately** — and unlike [MIGRATION_7_8], where the absent backfill *was* the bug,
     *         here a `NULL` is the correct and complete value for every pre-existing transaction:
     *         nobody has overridden anything yet.
     * Input:  [SupportSQLiteDatabase] — the database mid-upgrade. Output: none (executes DDL).
     *
     * The DDL matches what Room generates for `TransactionEntity.nature`; the committed
     * `schemas/13.json` is the reference, and `MigrationRoundTripTest` fails on a device if the two
     * drift.
     */
    val MIGRATION_12_13 =
        object : Migration(VERSION_12, VERSION_13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `nature` TEXT")
            }
        }

    /**
     * 13 → 14: adds `budget_alert` — the record of what the user has already been told (issue 4.5;
     * FR-BUD-004).
     *
     * Why:    FR-BUD-004 asks for alerts at 80% and 100%, and the unstated half of that requirement
     *         is *not* alerting again on every purchase afterwards. This table is that memory.
     *
     *         **The unique index is the requirement, not an optimisation.** With
     *         `UNIQUE(profile_id, budget_id, month_start_iso_date, band)` a second WARN for the same
     *         month cannot be inserted, so "told once" holds even if two workers run concurrently or
     *         one retries after a partial failure. A boolean on `budget` would have needed a
     *         read-then-write with a window between them, and would have had nowhere to put the
     *         *second* band — 80% then 100% must produce two messages, and does, because the band is
     *         part of the key.
     * What:   one `CREATE TABLE` plus the unique index and the month lookup index. No backfill, and
     *         none is possible or wanted: nobody has been notified about a month that has already
     *         passed, so an empty table is the complete and correct history (DB-003 — nothing is
     *         dropped or rewritten).
     * Result: an upgraded installation keeps every row and gains an empty table.
     * Input:  [SupportSQLiteDatabase] — the database mid-upgrade. Output: none (executes DDL).
     *
     * The DDL matches what Room generates for `BudgetAlertEntity`; the committed `schemas/14.json`
     * is the reference, and `MigrationRoundTripTest` fails on a device if the two drift.
     */
    val MIGRATION_13_14 =
        object : Migration(VERSION_13, VERSION_14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `budget_alert` (" +
                        "`id` TEXT NOT NULL, " +
                        "`profile_id` TEXT NOT NULL, " +
                        "`budget_id` TEXT NOT NULL, " +
                        "`category_id` TEXT, " +
                        "`month_start_iso_date` TEXT NOT NULL, " +
                        "`band` TEXT NOT NULL, " +
                        "`rule_id` TEXT NOT NULL, " +
                        "`rule_version` TEXT NOT NULL, " +
                        "`notified_at_utc_millis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_budget_alert_profile_id_budget_id_month_start_iso_date_band` " +
                        "ON `budget_alert` (`profile_id`, `budget_id`, `month_start_iso_date`, `band`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_budget_alert_profile_id_month_start_iso_date` " +
                        "ON `budget_alert` (`profile_id`, `month_start_iso_date`)",
                )
            }
        }

    /**
     * 14 → 15: adds `budget_review` — the record that a closed month's review has been shown
     * (issue 4.6; §5.5).
     *
     * Why:    `RULE-BUD-REVIEW.review_once_per_month` is documentation, not enforcement, exactly as
     *         `RULE-BUD-ALERT.notify_once_per_band_per_month` was — the unique index is what makes
     *         it true. **Keyed by (profile, month) alone**, not per category: this is a
     *         review-and-move-on card, not a persistent per-finding status, so one dismissal closes
     *         the whole month (ADR-0020).
     * What:   one `CREATE TABLE` plus the unique index. No backfill, and none is possible or
     *         wanted: nobody has reviewed a month that predates this column existing, so an empty
     *         table is the complete and correct history (DB-003 — nothing is deleted or destroyed).
     */
    val MIGRATION_14_15 =
        object : Migration(VERSION_14, VERSION_15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `budget_review` (" +
                        "`id` TEXT NOT NULL, " +
                        "`profile_id` TEXT NOT NULL, " +
                        "`month_start_iso_date` TEXT NOT NULL, " +
                        "`rule_id` TEXT NOT NULL, " +
                        "`rule_version` TEXT NOT NULL, " +
                        "`total_budgeted_minor` INTEGER NOT NULL, " +
                        "`total_actual_minor` INTEGER NOT NULL, " +
                        "`reviewed_at_utc_millis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_budget_review_profile_id_month_start_iso_date` " +
                        "ON `budget_review` (`profile_id`, `month_start_iso_date`)",
                )
            }
        }

    /**
     * 15 → 16: adds `credit_card` and `card_alert` — a card's terms, and the record of what the
     * user has already been told about it (issue 6.1; FR-ACC-002, §17.1).
     *
     * Why:    two tables in one bump because they arrive together and neither is useful alone: the
     *         terms are what the alerts are computed from, and the claims are what stop those alerts
     *         repeating. Splitting them across 16 and 17 would ship a version nobody can be on.
     *
     *         `credit_card` is keyed by `account_id` rather than an id of its own — a card *is* an
     *         account, and a second identity would allow two card rows for one account with nothing
     *         in the app able to choose between them.
     *
     *         `card_alert`'s cycle key is the **statement date**, not a month start, which is the
     *         one thing here that is easy to get wrong: a card billing on the 25th has a cycle
     *         straddling two calendar months, so a month-keyed claim would let one statement's
     *         reminder fire twice.
     * What:   two `CREATE TABLE`s plus their indices. No backfill, and none is possible or wanted:
     *         no card had terms before this version and nobody has been notified about one, so the
     *         empty tables are the complete and correct history (DB-003 — nothing is destroyed).
     */
    val MIGRATION_15_16 =
        object : Migration(VERSION_15, VERSION_16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createCreditCard(db)
                createCardAlert(db)
            }
        }

    /**
     * `credit_card` — a card's terms, one row per account (issue 6.1; FR-ACC-002).
     * Why:    split from [MIGRATION_15_16]'s body so each table's DDL is readable on its own; two
     *         `CREATE TABLE`s inline put the migration over the 40-line limit (§21.6) and, more to
     *         the point, made the second one easy to skim past.
     * Result: the table and its two indices exist.
     * Input:  [db]. Output: none.
     */
    private fun createCreditCard(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `credit_card` (" +
                "`account_id` TEXT NOT NULL, " +
                "`profile_id` TEXT NOT NULL, " +
                "`credit_limit_minor` INTEGER NOT NULL, " +
                "`statement_day` INTEGER NOT NULL, " +
                "`due_day` INTEGER NOT NULL, " +
                "`last_statement_minor` INTEGER, " +
                "`last_statement_iso_date` TEXT, " +
                "`minimum_due_minor` INTEGER, " +
                "`apr_bps` INTEGER, " +
                "`created_at_utc_millis` INTEGER NOT NULL, " +
                "`updated_at_utc_millis` INTEGER NOT NULL, " +
                "`deleted_at_utc_millis` INTEGER, " +
                "PRIMARY KEY(`account_id`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_credit_card_profile_id` " +
                "ON `credit_card` (`profile_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_credit_card_profile_id_deleted_at_utc_millis` " +
                "ON `credit_card` (`profile_id`, `deleted_at_utc_millis`)",
        )
    }

    /**
     * `card_alert` — the record of what the user has been told about a card (issue 6.1; §17.1).
     * Why:    the unique index is the point of the table, not decoration: it is what makes "at most
     *         one reminder per cycle per kind" true when a worker retries. The cycle key is the
     *         **statement date**, not a month, because a card billing on the 25th has a cycle
     *         straddling two calendar months and a month-keyed claim would fire twice for one
     *         statement.
     * Result: the table and its indices exist.
     * Input:  [db]. Output: none.
     */
    private fun createCardAlert(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `card_alert` (" +
                "`id` TEXT NOT NULL, " +
                "`profile_id` TEXT NOT NULL, " +
                "`account_id` TEXT NOT NULL, " +
                "`cycle_start_iso_date` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`rule_id` TEXT NOT NULL, " +
                "`rule_version` TEXT NOT NULL, " +
                "`notified_at_utc_millis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_card_alert_profile_id_account_id_cycle_start_iso_date_kind` " +
                "ON `card_alert` (`profile_id`, `account_id`, `cycle_start_iso_date`, `kind`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_card_alert_profile_id_cycle_start_iso_date` " +
                "ON `card_alert` (`profile_id`, `cycle_start_iso_date`)",
        )
    }

    /**
     * 16 → 17: adds `loan` — a loan's principal, rate, tenure and first instalment date (issue 6.2;
     * FR-ACC-003, §5.8).
     *
     * Why:    one table, not two, and that is the decision worth recording. ADR-0016 names a
     *         `loan_amortization_rows` table, and this migration deliberately does not create one:
     *         the schedule is a pure function of the five columns here, so it is derived on read and
     *         never stored (ADR-0026). A stored schedule is a cache that can disagree with the terms
     *         that produced it, and nothing in the app would notice the disagreement. Same argument
     *         `credit_card` makes for storing no "current unbilled", and ADR-0007 for storing no
     *         account balance.
     *
     *         Keyed by `account_id` for the reason `credit_card` is: a loan *is* an account here.
     * What:   one `CREATE TABLE` plus its two indices. No backfill, and none is possible: no loan
     *         had terms before this version, so the empty table is the complete and correct history
     *         (DB-003 — nothing is destroyed).
     */
    val MIGRATION_16_17 =
        object : Migration(VERSION_16, VERSION_17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `loan` (" +
                        "`account_id` TEXT NOT NULL, " +
                        "`profile_id` TEXT NOT NULL, " +
                        "`principal_minor` INTEGER NOT NULL, " +
                        "`annual_rate_bps` INTEGER NOT NULL, " +
                        "`tenure_months` INTEGER NOT NULL, " +
                        "`first_emi_iso_date` TEXT NOT NULL, " +
                        "`emi_override_minor` INTEGER, " +
                        "`created_at_utc_millis` INTEGER NOT NULL, " +
                        "`updated_at_utc_millis` INTEGER NOT NULL, " +
                        "`deleted_at_utc_millis` INTEGER, " +
                        "PRIMARY KEY(`account_id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_loan_profile_id` ON `loan` (`profile_id`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_loan_profile_id_deleted_at_utc_millis` " +
                        "ON `loan` (`profile_id`, `deleted_at_utc_millis`)",
                )
            }
        }

    /**
     * 17 -> 18: the holdings inside an investment account, and their dated cash movements
     * (issue 6.3; §11, FR-ACC-001).
     *
     * Why:   §20.1 names `holdings` and `holding_lots`, and until they existed the app could show
     *        what an investment account was worth in total but never what any part of it had
     *        earned. `ai/knowledge/classification-kb.json` records the same gap from the other
     *        side: `CLS-NAT-003` says its holdings need a holdings table (ADR-0016).
     * What:  two `CREATE TABLE`s and their six indices. No backfill, and none is possible: no
     *        account had holdings before this version, so the empty tables are the complete and
     *        correct history (DB-003 - nothing is destroyed).
     *
     * No foreign key from `investment_lot` to `investment_holding`, by the convention every other
     * table here keeps: references carry an index, not a constraint.
     */
    val MIGRATION_17_18 =
        object : Migration(VERSION_17, VERSION_18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createInvestmentHolding(db)
                createInvestmentLot(db)
            }
        }

    /**
     * `investment_holding` — an instrument's class and last observed unit price (issue 6.3; §11).
     * Why:    split from [MIGRATION_17_18]'s body for the reason [createCreditCard] was split from
     *         [MIGRATION_15_16]'s: two `CREATE TABLE`s inline put the migration over the 40-line
     *         limit (§21.6) and made the second one easy to skim past.
     *
     *         Keyed by a surrogate `id`, unlike `loan` and `credit_card`, because an account holds
     *         many of these — hence the third index, on `account_id`, which is the read path the
     *         holdings screen uses.
     * Result: the table and its three indices exist.
     * Input:  [db]. Output: none.
     */
    private fun createInvestmentHolding(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `investment_holding` (" +
                "`id` TEXT NOT NULL, " +
                "`profile_id` TEXT NOT NULL, " +
                "`account_id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`asset_class` TEXT NOT NULL, " +
                "`unit_price_minor` INTEGER, " +
                "`priced_on_iso_date` TEXT, " +
                "`created_at_utc_millis` INTEGER NOT NULL, " +
                "`updated_at_utc_millis` INTEGER NOT NULL, " +
                "`deleted_at_utc_millis` INTEGER, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_investment_holding_profile_id` " +
                "ON `investment_holding` (`profile_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_investment_holding_profile_id_deleted_at_utc_millis` " +
                "ON `investment_holding` (`profile_id`, `deleted_at_utc_millis`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_investment_holding_account_id` " +
                "ON `investment_holding` (`account_id`)",
        )
    }

    /**
     * `investment_lot` — one dated cash movement inside a holding (issue 6.3; §11).
     * Why:    XIRR is money-weighted, so every purchase, sale and payout needs its own dated row; a
     *         single "total invested" column could not express a SIP.
     *
     *         No foreign key to `investment_holding`, by the convention every other table here
     *         keeps: a reference carries an index, not a constraint.
     * Result: the table and its three indices exist.
     * Input:  [db]. Output: none.
     */
    private fun createInvestmentLot(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `investment_lot` (" +
                "`id` TEXT NOT NULL, " +
                "`profile_id` TEXT NOT NULL, " +
                "`holding_id` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`transacted_on_iso_date` TEXT NOT NULL, " +
                "`quantity_nano` INTEGER NOT NULL, " +
                "`amount_minor` INTEGER NOT NULL, " +
                "`created_at_utc_millis` INTEGER NOT NULL, " +
                "`updated_at_utc_millis` INTEGER NOT NULL, " +
                "`deleted_at_utc_millis` INTEGER, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_investment_lot_profile_id` " +
                "ON `investment_lot` (`profile_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_investment_lot_profile_id_deleted_at_utc_millis` " +
                "ON `investment_lot` (`profile_id`, `deleted_at_utc_millis`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_investment_lot_holding_id` " +
                "ON `investment_lot` (`holding_id`)",
        )
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
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
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

    /** The version issue 3.4 introduced, and the one issue 3.6 upgrades from. */
    private const val VERSION_8 = 8

    /** The version issue 3.6 introduced, and the one issue 3.7 upgrades from. */
    private const val VERSION_9 = 9

    /** The version issue 3.7 introduced, and the one issue 3.8 upgrades from. */
    private const val VERSION_10 = 10

    /** The version issue 3.8 introduced, and the one issue 3.9 upgrades from. */
    private const val VERSION_11 = 11

    /** The version issue 3.9 introduced, and the one issue 4.3 upgrades from. */
    private const val VERSION_12 = 12

    /** The version issue 4.3 introduced, and the one issue 4.5 upgrades from. */
    private const val VERSION_13 = 13

    /** The version issue 4.5 introduced, and the one issue 4.6 upgrades from. */
    private const val VERSION_14 = 14

    /** Matches `CfoDatabase.VERSION` at the time this migration was written (issue 4.6). */
    private const val VERSION_15 = 15

    /** Matches `CfoDatabase.VERSION` at the time issue 6.1 was written. */
    private const val VERSION_16 = 16

    /** The version issue 6.2 introduces — `loan` (FR-ACC-003). */
    private const val VERSION_17 = 17

    /** The version issue 6.2 introduced, and the one issue 6.3 upgrades from. */
    private const val VERSION_18 = 18
}
