package com.aicfo.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aicfo.core.database.entity.AccountEntity
import com.aicfo.core.database.entity.CategoryEntity
import com.aicfo.core.database.entity.ProfileEntity
import com.aicfo.core.database.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

/*
 * The data-access objects for the base schema (issue 1.6; ARC-005, DB-003).
 *
 * Why:  DAOs are the only code allowed to speak SQL, and repositories are the only code allowed
 *       to touch DAOs (ARC-005) — so a ViewModel can never accidentally hold a Room type. Kept
 *       deliberately small: each feature issue (2.5 accounts, 3.x transactions, 4.1 categories)
 *       adds the queries it actually needs. Guessing at a query surface now would mean writing
 *       SQL nobody has a use case for and nobody tests.
 * What: insert/update plus one observation query per table, all soft-delete aware.
 * Result: enough to prove the encrypted round-trip and to build the first repositories on.
 * Changelog: 2026-07-25 — Created for issue 1.6.
 *
 * Two rules every query here follows, and every query added later must:
 * - **`deleted_at_utc_millis IS NULL`** — a soft-deleted row is invisible to normal reads. Leaving
 *   it out is the bug that makes "deleted" transactions reappear in a total.
 * - **scoped by `profile_id`** — no query may span profiles.
 *
 * Everything is `suspend` or `Flow`: no DAO call may block a caller's thread (CLAUDE.md §5).
 */

/** Reads and writes profiles — the root record other tables are scoped to. */
@Dao
interface ProfileDao {
    /**
     * Inserts a profile, replacing one with the same id.
     * Result: the row is present afterwards. Input: [profile]. Output: none (suspends).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ProfileEntity)

    /**
     * Observes the live profiles.
     * Why:    a Flow so the UI re-renders when the profile's time zone or currency changes.
     * Result: emits on every change; excludes soft-deleted rows.
     * Input:  none. Output: `Flow<List<ProfileEntity>>`.
     */
    @Query("SELECT * FROM profile WHERE deleted_at_utc_millis IS NULL ORDER BY created_at_utc_millis")
    fun observeAll(): Flow<List<ProfileEntity>>

    /**
     * Fetches one profile.
     * Result: the row, or `null` if it does not exist or was soft-deleted.
     * Input:  [id]. Output: `ProfileEntity?`.
     */
    @Query("SELECT * FROM profile WHERE id = :id AND deleted_at_utc_millis IS NULL")
    suspend fun findById(id: String): ProfileEntity?
}

/** Reads and writes accounts. */
@Dao
interface AccountDao {
    /** Inserts an account, replacing one with the same id. Input: [account]. Output: none. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: AccountEntity)

    /** Updates an existing account in place. Input: [account]. Output: rows affected. */
    @Update
    suspend fun update(account: AccountEntity): Int

    /**
     * Observes a profile's live accounts.
     * Result: emits on every change; soft-deleted accounts are excluded.
     * Input:  [profileId]. Output: `Flow<List<AccountEntity>>`.
     */
    @Query(
        "SELECT * FROM account WHERE profile_id = :profileId " +
            "AND deleted_at_utc_millis IS NULL ORDER BY name",
    )
    fun observeForProfile(profileId: String): Flow<List<AccountEntity>>

    /**
     * Marks an account deleted without removing the row.
     * Why:    DB-003 and recoverability — the row stays so history and any future sync can still
     *         see that it existed.
     * Result: the account disappears from reads. Input: [id], [deletedAtUtcMillis]. Output: rows affected.
     */
    @Query("UPDATE account SET deleted_at_utc_millis = :deletedAtUtcMillis WHERE id = :id")
    suspend fun softDelete(
        id: String,
        deletedAtUtcMillis: Long,
    ): Int
}

/** Reads and writes transactions. */
@Dao
interface TransactionDao {
    /** Inserts a transaction, replacing one with the same id. Input: [transaction]. Output: none. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(transaction: TransactionEntity)

    /**
     * Observes a profile's transactions within a date range.
     * Why:    every dashboard figure is "this month" or "last 30 days", and the range is compared
     *         on the ISO date string precisely because that is the profile-zone day (TIM-002) —
     *         comparing instants would slice the day at the wrong moment for any offset zone.
     * Result: newest first; soft-deleted rows excluded.
     * Input:  [profileId], [fromIsoDate], [toIsoDate] — inclusive ISO `yyyy-MM-dd` bounds.
     * Output: `Flow<List<TransactionEntity>>`.
     */
    @Query(
        "SELECT * FROM transactions WHERE profile_id = :profileId " +
            "AND booked_on_iso_date BETWEEN :fromIsoDate AND :toIsoDate " +
            "AND deleted_at_utc_millis IS NULL ORDER BY occurred_at_utc_millis DESC",
    )
    fun observeBookedBetween(
        profileId: String,
        fromIsoDate: String,
        toIsoDate: String,
    ): Flow<List<TransactionEntity>>

    /**
     * Fetches one transaction.
     * Result: the row, or `null`. Input: [id]. Output: `TransactionEntity?`.
     */
    @Query("SELECT * FROM transactions WHERE id = :id AND deleted_at_utc_millis IS NULL")
    suspend fun findById(id: String): TransactionEntity?

    /**
     * Marks a transaction deleted without removing the row.
     * Result: it disappears from reads and totals. Input: [id], [deletedAtUtcMillis]. Output: rows affected.
     */
    @Query("UPDATE transactions SET deleted_at_utc_millis = :deletedAtUtcMillis WHERE id = :id")
    suspend fun softDelete(
        id: String,
        deletedAtUtcMillis: Long,
    ): Int
}

/** Reads and writes categories. */
@Dao
interface CategoryDao {
    /** Inserts a category, replacing one with the same id. Input: [category]. Output: none. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(category: CategoryEntity)

    /** Inserts many at once — used to seed the system categories. Input: [categories]. Output: none. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(categories: List<CategoryEntity>)

    /**
     * Observes a profile's live categories.
     * Result: emits on every change; soft-deleted rows excluded.
     * Input:  [profileId]. Output: `Flow<List<CategoryEntity>>`.
     */
    @Query(
        "SELECT * FROM category WHERE profile_id = :profileId " +
            "AND deleted_at_utc_millis IS NULL ORDER BY name",
    )
    fun observeForProfile(profileId: String): Flow<List<CategoryEntity>>
}
