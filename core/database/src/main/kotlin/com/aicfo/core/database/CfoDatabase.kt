package com.aicfo.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aicfo.core.database.dao.AccountDao
import com.aicfo.core.database.dao.CategoryDao
import com.aicfo.core.database.dao.ProfileDao
import com.aicfo.core.database.dao.TransactionDao
import com.aicfo.core.database.entity.AccountEntity
import com.aicfo.core.database.entity.CategoryEntity
import com.aicfo.core.database.entity.ProfileEntity
import com.aicfo.core.database.entity.TransactionEntity

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
        CategoryEntity::class,
    ],
    version = CfoDatabase.VERSION,
    exportSchema = true,
)
abstract class CfoDatabase : RoomDatabase() {
    /** Input: none. Output: the profile DAO. */
    abstract fun profileDao(): ProfileDao

    /** Input: none. Output: the account DAO. */
    abstract fun accountDao(): AccountDao

    /** Input: none. Output: the transaction DAO. */
    abstract fun transactionDao(): TransactionDao

    /** Input: none. Output: the category DAO. */
    abstract fun categoryDao(): CategoryDao

    companion object {
        /** Bump only alongside a hand-written migration and a test that proves data survives. */
        const val VERSION = 1

        /** The on-disk file name, inside app-private storage. */
        const val FILE_NAME = "cfo.db"
    }
}
