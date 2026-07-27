package com.aicfo.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aicfo.core.database.entity.AccountEntity
import com.aicfo.core.database.entity.AuditLogEntity
import com.aicfo.core.database.entity.BudgetEntity
import com.aicfo.core.database.entity.CategoryEntity
import com.aicfo.core.database.entity.ProfileEntity
import com.aicfo.core.database.entity.RecurringRuleEntity
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

/** Reads and writes budgets (issue 2.3; FR-BUD-001, FR-ONB-002). */
@Dao
interface BudgetDao {
    /**
     * Inserts many budgets at once, replacing any with the same id.
     * Why:    quick setup writes three envelopes that only make sense together, and REPLACE on a
     *         **derived** id is what makes re-running onboarding update the same three rows rather
     *         than accumulate a new set each time (P-08 determinism, applied to storage).
     * Result: the rows are present afterwards. Input: [budgets]. Output: none (suspends).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(budgets: List<BudgetEntity>)

    /**
     * Observes a profile's live budgets for one month.
     * Why:    scoped to a period because every budget question is "this month" — loading every
     *         month a user has ever had to filter in memory would grow without bound.
     * Result: emits on every change; soft-deleted rows excluded.
     * Input:  [profileId], [periodStartIsoDate] — the month's first day. Output: `Flow<List<BudgetEntity>>`.
     */
    @Query(
        "SELECT * FROM budget WHERE profile_id = :profileId " +
            "AND period_start_iso_date = :periodStartIsoDate " +
            "AND deleted_at_utc_millis IS NULL ORDER BY nature, category_id",
    )
    fun observeForPeriod(
        profileId: String,
        periodStartIsoDate: String,
    ): Flow<List<BudgetEntity>>

    /**
     * Observes a profile's most recent budget period.
     * Why:    the dashboard has to render before anything tells it which month to ask for, and
     *         "the newest period that exists" is the honest answer for a screen whose only budgets
     *         so far came from onboarding. Issue 5.1 replaces this with the current month once the
     *         dashboard reads the profile clock.
     * Result: emits the rows of the latest period, or an empty list when there are none.
     * Input:  [profileId]. Output: `Flow<List<BudgetEntity>>`.
     */
    @Query(
        "SELECT * FROM budget WHERE profile_id = :profileId AND deleted_at_utc_millis IS NULL " +
            "AND period_start_iso_date = (" +
            "SELECT MAX(period_start_iso_date) FROM budget " +
            "WHERE profile_id = :profileId AND deleted_at_utc_millis IS NULL) " +
            "ORDER BY nature, category_id",
    )
    fun observeLatestPeriod(profileId: String): Flow<List<BudgetEntity>>

    /**
     * Marks a budget deleted without removing the row.
     * Result: it disappears from reads. Input: [id], [deletedAtUtcMillis]. Output: rows affected.
     */
    @Query("UPDATE budget SET deleted_at_utc_millis = :deletedAtUtcMillis WHERE id = :id")
    suspend fun softDelete(
        id: String,
        deletedAtUtcMillis: Long,
    ): Int
}

/** Reads and writes recurring rules (issue 2.3; FR-ONB-002, FR-TXN-006). */
@Dao
interface RecurringRuleDao {
    /**
     * Inserts many rules at once, replacing any with the same id.
     * Result: the rows are present afterwards. Input: [rules]. Output: none (suspends).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rules: List<RecurringRuleEntity>)

    /**
     * Observes a profile's live recurring rules, soonest due first.
     * Result: emits on every change; soft-deleted rows excluded.
     * Input:  [profileId]. Output: `Flow<List<RecurringRuleEntity>>`.
     */
    @Query(
        "SELECT * FROM recurring_rule WHERE profile_id = :profileId " +
            "AND deleted_at_utc_millis IS NULL ORDER BY next_due_iso_date, id",
    )
    fun observeForProfile(profileId: String): Flow<List<RecurringRuleEntity>>

    /**
     * Marks a recurring rule deleted without removing the row.
     * Result: it stops being proposed. Input: [id], [deletedAtUtcMillis]. Output: rows affected.
     */
    @Query("UPDATE recurring_rule SET deleted_at_utc_millis = :deletedAtUtcMillis WHERE id = :id")
    suspend fun softDelete(
        id: String,
        deletedAtUtcMillis: Long,
    ): Int
}

/**
 * Appends and reads security events (issue 2.2; §21.6, ARC-005).
 *
 * Append-only by design: there is no update and no delete. A security log a caller can rewrite is
 * not evidence of anything, and rows leave only when the whole database does (erase-all, SEC-006).
 */
@Dao
interface AuditLogDao {
    /**
     * Appends one event.
     * Why:    `@Insert` with no conflict strategy — every event is a new row. `REPLACE` would let a
     *         supplied id overwrite an earlier event, which is the one thing an append-only log
     *         must not permit.
     * Result: the row is present afterwards. Input: [event]. Output: none (suspends).
     */
    @Insert
    suspend fun append(event: AuditLogEntity)

    /**
     * Observes the most recent events, newest first.
     * Why:    a Flow so a future security screen (issue 11.3) re-renders as events arrive. Bounded
     *         by [limit] because this table only grows, and no screen wants every row.
     * Result: emits on every append.
     * Input:  [limit] — how many rows at most. Output: `Flow<List<AuditLogEntity>>`.
     */
    @Query("SELECT * FROM audit_log ORDER BY occurred_at_utc_millis DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AuditLogEntity>>

    /**
     * Counts events of one kind since an instant.
     * Why:    the question a security review actually asks — "how many failed unlocks this week?" —
     *         answered in SQL rather than by pulling every row into memory.
     * Result: the count, 0 when there are none.
     * Input:  [event] — an `AuditEvent` constant name; [sinceUtcMillis] — inclusive lower bound.
     * Output: `Int`.
     */
    @Query(
        "SELECT COUNT(*) FROM audit_log WHERE event = :event " +
            "AND occurred_at_utc_millis >= :sinceUtcMillis",
    )
    suspend fun countSince(
        event: String,
        sinceUtcMillis: Long,
    ): Int
}
