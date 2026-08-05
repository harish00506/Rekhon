package com.aicfo.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aicfo.core.database.dao.AccountDao
import com.aicfo.core.database.dao.AuditLogDao
import com.aicfo.core.database.dao.BudgetDao
import com.aicfo.core.database.dao.CategoryDao
import com.aicfo.core.database.dao.DemoDao
import com.aicfo.core.database.dao.NetWorthSnapshotDao
import com.aicfo.core.database.dao.ProfileDao
import com.aicfo.core.database.dao.RecurringRuleDao
import com.aicfo.core.database.dao.TagDao
import com.aicfo.core.database.dao.TransactionDao
import com.aicfo.core.database.dao.TransactionSplitDao
import com.aicfo.core.database.entity.AccountEntity
import com.aicfo.core.database.entity.AuditLogEntity
import com.aicfo.core.database.entity.BudgetEntity
import com.aicfo.core.database.entity.CategoryEntity
import com.aicfo.core.database.entity.NetWorthSnapshotEntity
import com.aicfo.core.database.entity.ProfileEntity
import com.aicfo.core.database.entity.RecurringRuleEntity
import com.aicfo.core.database.entity.TagEntity
import com.aicfo.core.database.entity.TransactionEntity
import com.aicfo.core.database.entity.TransactionSplitEntity
import com.aicfo.core.database.entity.TransactionTagEntity

/**
 * The encrypted on-device database — every rupee the user owns lives here (SRS §20, DB-003).
 *
 * Why:  P-01 and P-04 together decide the shape of this app's storage: everything works offline
 *       and nothing leaves the device, so the local database is not a cache, it is the system of
 *       record. That makes two things non-negotiable — it is encrypted at rest (SEC-003, see
 *       [com.aicfo.core.database.crypto.SqlCipherPassphraseManager]), and it is never migrated
 *       destructively (DB-003), because there is no server copy to restore from. A
 *       `fallbackToDestructiveMigration` here would mean a user's entire financial history is one
 *       schema mistake away from deletion.
 * What: the Room definition and its DAOs, with `exportSchema = true` so every version has a JSON
 *       fixture in `schemas/` for issue 1.7's migration tests to diff against.
 * Result: the durable, encrypted store the repositories (ARC-005) build on.
 * Changelog: 2026-07-25 — Created at version 1 for issue 1.6 (profile, account, transaction, category).
 *
 * **Adding a table or column means a new version and a hand-written `Migration`** — plus a test
 * proving the old data survives. Never a destructive fallback; never editing an exported schema
 * file by hand.
 */
@Database(
    entities = [
        ProfileEntity::class,
        AccountEntity::class,
        TransactionEntity::class,
        TransactionSplitEntity::class,
        CategoryEntity::class,
        AuditLogEntity::class,
        BudgetEntity::class,
        RecurringRuleEntity::class,
        NetWorthSnapshotEntity::class,
        TagEntity::class,
        TransactionTagEntity::class,
    ],
    version = CfoDatabase.VERSION,
    exportSchema = true,
)
@Suppress("TooManyFunctions") // One accessor per table: the count is the schema's, not a design choice.
abstract class CfoDatabase : RoomDatabase() {
    /** Input: none. Output: the profile DAO. */
    abstract fun profileDao(): ProfileDao

    /** Input: none. Output: the account DAO. */
    abstract fun accountDao(): AccountDao

    /** Input: none. Output: the transaction DAO. */
    abstract fun transactionDao(): TransactionDao

    /** Input: none. Output: the split-line DAO (issue 3.3). */
    abstract fun transactionSplitDao(): TransactionSplitDao

    /** Input: none. Output: the category DAO. */
    abstract fun categoryDao(): CategoryDao

    /** Input: none. Output: the audit-log DAO (issue 2.2, §21.6). */
    abstract fun auditLogDao(): AuditLogDao

    /** Input: none. Output: the budget DAO (issue 2.3, FR-BUD-001). */
    abstract fun budgetDao(): BudgetDao

    /** Input: none. Output: the recurring-rule DAO (issue 2.3, FR-TXN-006). */
    abstract fun recurringRuleDao(): RecurringRuleDao

    /** Input: none. Output: the net-worth snapshot DAO (issue 2.6, FR-ACC-005). */
    abstract fun netWorthSnapshotDao(): NetWorthSnapshotDao

    /** Input: none. Output: the tag DAO (issue 3.6, FR-TXN-007/FR-TXN-008). */
    abstract fun tagDao(): TagDao

    /**
     * Input:  none. Output: the demo wipe DAO (issue 2.4, FR-ONB-004).
     *
     * Adding it needed **no schema change** — a DAO is a set of queries over tables that already
     * exist, and Room's exported schema describes tables, not queries. It did not move [VERSION].
     */
    abstract fun demoDao(): DemoDao

    companion object {
        /**
         * Bump only alongside a hand-written migration and a test that proves data survives.
         *
         * History: 1 — issue 1.6 (profile, account, transactions, category) · 2 — issue 2.2
         * (`audit_log`) · 3 — issue 2.3 (`budget`, `recurring_rule`) · 4 — issue 2.5
         * (`account.institution`, `account.archived_at_utc_millis`; FR-ACC-007) · 5 — issue 2.6
         * (`net_worth_snapshot`, `account.include_in_networth`; FR-ACC-005) · 6 — issue 3.2
         * (`transactions.type`, `transactions.transfer_id`; FR-TXN-003) · 7 — issue 3.3
         * (`transaction_splits`; FR-TXN-004) · 8 — issue 3.4
         * (`transactions.posted_at_utc_millis`; FR-TXN-010) · 9 — issue 3.6 (`tags`,
         * `transaction_tags`; FR-TXN-007, FR-TXN-008) · 10 — issue 3.7
         * (`recurring_rule.dismissed_at_utc_millis`; FR-TXN-006).
         */
        const val VERSION = 10

        /** The on-disk file name, inside app-private storage. */
        const val FILE_NAME = "cfo.db"
    }
}
