package com.aicfo.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aicfo.core.database.entity.AccountEntity
import com.aicfo.core.database.entity.AuditLogEntity
import com.aicfo.core.database.entity.BudgetEntity
import com.aicfo.core.database.entity.CategoryEntity
import com.aicfo.core.database.entity.NetWorthSnapshotEntity
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

/**
 * An account row together with the net movement of its live transactions (issue 2.5; DB-001).
 *
 * Why:  DB-001 — "current_balance is derivable from opening balance + transactions and is verified
 *       by a nightly integrity job". This is the derivation, done in SQL rather than by loading a
 *       year of transactions into memory to add them up. Keeping the sum beside the row it belongs
 *       to means the repository does one arithmetic step, [movementMinor] plus the opening balance,
 *       and never has to match two lists up by id.
 * Result: everything one [Account] needs, in one row.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 *
 * Input:  [account] — the row; [movementMinor] — MNY-001 paise, signed, `0` for an account with no
 *         transactions. Output: an immutable value.
 */
data class AccountWithBalance(
    @Embedded val account: AccountEntity,
    @ColumnInfo(name = "movement_minor") val movementMinor: Long,
)

/** Reads and writes accounts (issue 1.6; extended by issue 2.5 for FR-ACC-001 and FR-ACC-007). */
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
     * Observes a profile's accounts with their derived balances (issue 2.5; DB-001, FR-ACC-007).
     *
     * Why:    the query the accounts screen and the net-worth engine (issue 2.6) actually want.
     *         Three decisions are load-bearing:
     *
     *         **A correlated subquery, not a `JOIN … GROUP BY`.** A join drops any account with no
     *         transactions unless it is a `LEFT JOIN` with a `GROUP BY` on every selected column —
     *         and a brand-new account, the one the user just created, has no transactions. The
     *         subquery cannot drop a row because it is evaluated per row.
     *
     *         **The sum filters `deleted_at_utc_millis IS NULL`.** A soft-deleted transaction must
     *         not count towards a balance; leaving this out is the bug that makes a deleted expense
     *         keep suppressing the total.
     *
     *         **`archived_at_utc_millis` is filtered separately from `deleted_at_utc_millis`.**
     *         FR-ACC-007 wants a closed account excluded from *active* totals while its history
     *         survives, which is a different question from whether the row was deleted.
     * Result: emits on every change to `account` **or** `transactions`, name-ordered. Each row's
     *         balance is `opening_balance_minor + movement_minor`.
     * Input:  [profileId]; [includeArchived] — `false` for active totals, `true` for the full list.
     * Output: `Flow<List<AccountWithBalance>>`.
     * Changelog: 2026-07-28 — Created for issue 2.5.
     */
    @Query(
        "SELECT a.*, COALESCE((" +
            "SELECT SUM(t.amount_minor) FROM transactions t " +
            "WHERE t.account_id = a.id AND t.deleted_at_utc_millis IS NULL" +
            "), 0) AS movement_minor " +
            "FROM account a " +
            "WHERE a.profile_id = :profileId AND a.deleted_at_utc_millis IS NULL " +
            "AND (:includeArchived OR a.archived_at_utc_millis IS NULL) " +
            "ORDER BY a.name",
    )
    fun observeWithBalances(
        profileId: String,
        includeArchived: Boolean,
    ): Flow<List<AccountWithBalance>>

    /**
     * Fetches one account with its derived balance (issue 2.5).
     * Why:    the editor loads the account it is about to change, and it must show the same balance
     *         the list showed — so it uses the same derivation rather than reading the cached
     *         column (DB-001). Archived accounts are found too: editing is how a user un-archives.
     * Result: the row, or `null` if it does not exist or was soft-deleted.
     * Input:  [id]. Output: `AccountWithBalance?`.
     */
    @Query(
        "SELECT a.*, COALESCE((" +
            "SELECT SUM(t.amount_minor) FROM transactions t " +
            "WHERE t.account_id = a.id AND t.deleted_at_utc_millis IS NULL" +
            "), 0) AS movement_minor " +
            "FROM account a WHERE a.id = :id AND a.deleted_at_utc_millis IS NULL",
    )
    suspend fun findWithBalance(id: String): AccountWithBalance?

    /**
     * The accounts and balances that count towards net worth on one day (issue 2.6; FR-ACC-005).
     *
     * Why:    **this is deliberately not [observeWithBalances] with a date bolted on** — it answers a
     *         different question, and three of its clauses say so:
     *
     *         **`booked_on_iso_date <= :asOfIsoDate`.** Net worth on a day is what the user held on
     *         that day. The current-balance query sums every live transaction whenever it happened,
     *         which is right for "what is in this account now" and wrong here the moment issue 3.4
     *         lands future-dated transactions — a rent payment scheduled for next week would
     *         otherwise be subtracted from today's net worth. It is also what makes the backfill
     *         possible: the same query with an older date reconstructs an older day exactly.
     *
     *         **`archived_at_utc_millis IS NULL`.** FR-ACC-007: a closed account is "excluded from
     *         active totals".
     *
     *         **`include_in_networth = 1`.** An account that is open and transacting but is not the
     *         user's to count (issue 2.6, §20.2).
     *
     *         `suspend`, not a `Flow`: the caller computes one snapshot per day and stores it. A
     *         live query would recompute history every time any row changed, which is the drift the
     *         stored snapshot exists to prevent.
     * Result: one row per counted account, with its balance as at [asOfIsoDate].
     * Input:  [profileId]; [asOfIsoDate] — inclusive ISO `yyyy-MM-dd` bound (TIM-002).
     * Output: `List<AccountWithBalance>`.
     * Changelog: 2026-08-01 — Created for issue 2.6.
     */
    @Query(
        "SELECT a.*, COALESCE((" +
            "SELECT SUM(t.amount_minor) FROM transactions t " +
            "WHERE t.account_id = a.id AND t.deleted_at_utc_millis IS NULL " +
            "AND t.booked_on_iso_date <= :asOfIsoDate" +
            "), 0) AS movement_minor " +
            "FROM account a " +
            "WHERE a.profile_id = :profileId AND a.deleted_at_utc_millis IS NULL " +
            "AND a.archived_at_utc_millis IS NULL AND a.include_in_networth = 1 " +
            "ORDER BY a.id",
    )
    suspend fun balancesForNetWorth(
        profileId: String,
        asOfIsoDate: String,
    ): List<AccountWithBalance>

    /**
     * The same query as a live one, for the screen that shows net worth **now** (issue 2.6).
     *
     * Why:    the stored daily snapshot is a *historical record* — it is what makes issue 6.6's trend
     *         exact. It is the wrong thing for a headline figure: a user who adds an account, or
     *         deletes one, would see the total sit unchanged until the next day's job ran and
     *         reasonably conclude the app was broken. **Found by driving the app, not by a test** —
     *         every unit test asserted the stored figure, which was correct and not what a user
     *         needs to see.
     *
     *         So the dashboard observes this and the snapshot table records history; both go through
     *         the identical filter above, so the number the screen shows and the number tonight's
     *         job stores can never disagree about which accounts count.
     * Result: emits on every change to `account` **or** `transactions`.
     * Input:  [profileId]; [asOfIsoDate] — normally today. Output: `Flow<List<AccountWithBalance>>`.
     * Changelog: 2026-08-01 — Created for issue 2.6.
     */
    @Query(
        "SELECT a.*, COALESCE((" +
            "SELECT SUM(t.amount_minor) FROM transactions t " +
            "WHERE t.account_id = a.id AND t.deleted_at_utc_millis IS NULL " +
            "AND t.booked_on_iso_date <= :asOfIsoDate" +
            "), 0) AS movement_minor " +
            "FROM account a " +
            "WHERE a.profile_id = :profileId AND a.deleted_at_utc_millis IS NULL " +
            "AND a.archived_at_utc_millis IS NULL AND a.include_in_networth = 1 " +
            "ORDER BY a.id",
    )
    fun observeBalancesForNetWorth(
        profileId: String,
        asOfIsoDate: String,
    ): Flow<List<AccountWithBalance>>

    /**
     * Archives or restores an account (issue 2.5; FR-ACC-007).
     * Why:    a single nullable column rather than an `is_archived` flag plus a date, so the two
     *         can never disagree about whether the account is closed.
     * Result: the account leaves the active list, keeping every transaction it ever had. `0` when
     *         the id names nothing live, which is how the caller tells "archived" from "no such
     *         account".
     * Input:  [id]; [archivedAtUtcMillis] — from the injected `Clock` (TIM-001), or `null` to
     *         restore; [updatedAtUtcMillis]. Output: rows affected.
     */
    @Query(
        "UPDATE account SET archived_at_utc_millis = :archivedAtUtcMillis, " +
            "updated_at_utc_millis = :updatedAtUtcMillis " +
            "WHERE id = :id AND deleted_at_utc_millis IS NULL",
    )
    suspend fun setArchivedAt(
        id: String,
        archivedAtUtcMillis: Long?,
        updatedAtUtcMillis: Long,
    ): Int

    /**
     * Marks an account deleted without removing the row.
     * Why:    DB-003 and recoverability — the row stays so history and any future sync can still
     *         see that it existed.
     *
     *         **`AND deleted_at_utc_millis IS NULL` matters** (added by issue 2.5): without it a
     *         second delete of the same id reports one row affected and the caller has no way to
     *         tell it apart from the first. That is the bug where a user taps Delete twice and the
     *         app confirms something that never happened.
     * Result: the account disappears from reads; `0` when it was already gone.
     * Input:  [id], [deletedAtUtcMillis]. Output: rows affected.
     */
    @Query(
        "UPDATE account SET deleted_at_utc_millis = :deletedAtUtcMillis " +
            "WHERE id = :id AND deleted_at_utc_millis IS NULL",
    )
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
     * Points a profile's unattached rules of one source at an account (issue 2.5; FR-ONB-001).
     *
     * Why:    issue 2.3 wrote the quick-setup rules with no account, because none existed yet. This
     *         attaches the account onboarding's new fourth step creates.
     *
     *         **Three conditions, and each excludes a rule that is not this call's to touch.**
     *         `account_id IS NULL` leaves alone anything the user already attached by hand;
     *         `source = :source` leaves alone the rules issue 3.7 will detect from the transaction
     *         stream; `deleted_at_utc_millis IS NULL` leaves alone tombstones, which must not be
     *         quietly edited back into a state they never had.
     * Result: the rules start naming an account. `0` when there were none, which is the normal
     *         outcome for a user who skipped quick setup — not an error.
     * Input:  [profileId]; [accountId]; [source] — the provenance code to match, e.g. `quick_setup`;
     *         [updatedAtUtcMillis] — from the injected `Clock` (TIM-001). Output: rows affected.
     */
    @Query(
        "UPDATE recurring_rule SET account_id = :accountId, " +
            "updated_at_utc_millis = :updatedAtUtcMillis " +
            "WHERE profile_id = :profileId AND account_id IS NULL AND source = :source " +
            "AND deleted_at_utc_millis IS NULL",
    )
    suspend fun attachAccountToUnattached(
        profileId: String,
        accountId: String,
        source: String,
        updatedAtUtcMillis: Long,
    ): Int

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
 * Erases one profile's rows outright — the demo wipe, and nothing else (issue 2.4; FR-ONB-004).
 *
 * Why:  **this is the one DAO in the app that hard-deletes, and that is deliberate.** Every other
 *       query here sets `deleted_at_utc_millis` instead, because a user's own row must stay
 *       recoverable and visible to a future sync. Demo rows have neither property: they were
 *       fabricated by the app, nobody can want them back, and issue 2.4's acceptance criterion is
 *       that exiting the demo leaves **no residue** — a tombstone is residue. Recorded in
 *       `docs/adr/0006-demo-mode-profile-isolation-and-hard-delete.md`.
 * What: one `DELETE` per profile-scoped table, plus the profile row itself.
 * Result: once every query here has run for a profile id, [countRowsFor] returns `0`.
 * Changelog: 2026-07-28 — Created for issue 2.4.
 *
 * **Every query is scoped by `profile_id` and takes it as a parameter** — there is no
 * "delete everything" here. That is what keeps the real profile safe from a mistyped call: the
 * worst a bug can do is erase the profile it was handed, and the only id ever handed to it is the
 * demo one.
 *
 * **`audit_log` is not touched.** It has no `profile_id` — the app lock gates the whole app before
 * any profile is chosen — and it is append-only by design (see [AuditLogDao]). Security events are
 * not the demo's to erase.
 */
@Dao
interface DemoDao {
    /** Result: rows removed from `budget`. Input: [profileId]. Output: the count. */
    @Query("DELETE FROM budget WHERE profile_id = :profileId")
    suspend fun deleteBudgets(profileId: String): Int

    /**
     * Result: rows removed from `net_worth_snapshot`. Input: [profileId]. Output: the count.
     *
     * Added by issue 2.6, which introduced the table. A profile-scoped table that the wipe does not
     * reach is exactly the residue ADR-0006 forbids — and [countRowsFor] below now counts it, so
     * forgetting this would have reddened the residue test rather than shipping quietly.
     */
    @Query("DELETE FROM net_worth_snapshot WHERE profile_id = :profileId")
    suspend fun deleteNetWorthSnapshots(profileId: String): Int

    /** Result: rows removed from `recurring_rule`. Input: [profileId]. Output: the count. */
    @Query("DELETE FROM recurring_rule WHERE profile_id = :profileId")
    suspend fun deleteRecurringRules(profileId: String): Int

    /** Result: rows removed from `transactions`. Input: [profileId]. Output: the count. */
    @Query("DELETE FROM transactions WHERE profile_id = :profileId")
    suspend fun deleteTransactions(profileId: String): Int

    /** Result: rows removed from `category`. Input: [profileId]. Output: the count. */
    @Query("DELETE FROM category WHERE profile_id = :profileId")
    suspend fun deleteCategories(profileId: String): Int

    /** Result: rows removed from `account`. Input: [profileId]. Output: the count. */
    @Query("DELETE FROM account WHERE profile_id = :profileId")
    suspend fun deleteAccounts(profileId: String): Int

    /**
     * Removes the profile row itself.
     * Why:    called **last**, after everything scoped to it — deleting the parent first would leave
     *         orphans behind if the caller failed mid-way, which is exactly the residue this exists
     *         to prevent.
     * Result: rows removed from `profile`. Input: [profileId]. Output: the count.
     */
    @Query("DELETE FROM profile WHERE id = :profileId")
    suspend fun deleteProfile(profileId: String): Int

    /**
     * Counts every row still carrying a profile id, across every table above.
     * Why:    "no residue" has to be *assertable*, not asserted table by table in a test that a new
     *         table would silently fall out of. This is the one query that answers it, and adding a
     *         profile-scoped table means adding a term here — which the reviewer will see.
     *         Deliberately **not** filtered by `deleted_at_utc_millis`: a soft-deleted row is
     *         precisely the residue being looked for, so it must count.
     * Result: `0` once the profile has been erased.
     * Input:  [profileId]. Output: the total row count across all seven tables.
     */
    @Query(
        "SELECT (SELECT COUNT(*) FROM profile WHERE id = :profileId) + " +
            "(SELECT COUNT(*) FROM account WHERE profile_id = :profileId) + " +
            "(SELECT COUNT(*) FROM transactions WHERE profile_id = :profileId) + " +
            "(SELECT COUNT(*) FROM category WHERE profile_id = :profileId) + " +
            "(SELECT COUNT(*) FROM budget WHERE profile_id = :profileId) + " +
            "(SELECT COUNT(*) FROM recurring_rule WHERE profile_id = :profileId) + " +
            "(SELECT COUNT(*) FROM net_worth_snapshot WHERE profile_id = :profileId)",
    )
    suspend fun countRowsFor(profileId: String): Int
}

/**
 * Reads and writes the daily net-worth snapshots (issue 2.6; FR-ACC-005).
 *
 * Why:  the whole table exists so history cannot drift, and two queries here carry that. [upsertAll]
 *       relies on a **derived** id, so the daily job is idempotent by construction rather than by
 *       remembering to check first. And [findLatestAsOfDate] is what makes the backfill possible:
 *       "which day did we last record?" is the only state the job needs, and asking SQL for it beats
 *       keeping a cursor somewhere that could disagree with the rows.
 * Result: an exact, reproducible series for issue 6.6's trend chart to read.
 * Changelog: 2026-08-01 — Created for issue 2.6.
 */
@Dao
interface NetWorthSnapshotDao {
    /**
     * Inserts snapshots, replacing any for the same day.
     * Why:    REPLACE on a derived id (`<profile>:networth:<date>`) is what makes running the job
     *         twice in a day update one row rather than leave two figures for one date — the same
     *         mechanism `BudgetDao.upsertAll` uses. A backfill writes several days at once, so this
     *         takes a list and the caller wraps it in one transaction.
     * Result: the rows are present afterwards. Input: [snapshots]. Output: none (suspends).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(snapshots: List<NetWorthSnapshotEntity>)

    /**
     * Observes a profile's most recent snapshot.
     * Why:    the dashboard shows one figure — the latest — and a `Flow` so it updates when tonight's
     *         job lands without the screen polling.
     * Result: emits the newest row, or `null` when the profile has none yet. **`null` is a real
     *         state**: a user who has just onboarded has no snapshot, and the screen must render
     *         that as "not computed yet" rather than as ₹0 (P-03).
     * Input:  [profileId]. Output: `Flow<NetWorthSnapshotEntity?>`.
     */
    @Query(
        "SELECT * FROM net_worth_snapshot WHERE profile_id = :profileId " +
            "AND deleted_at_utc_millis IS NULL ORDER BY as_of_iso_date DESC LIMIT 1",
    )
    fun observeLatest(profileId: String): Flow<NetWorthSnapshotEntity?>

    /**
     * The newest day this profile has a snapshot for.
     * Why:    the backfill's only input. Returning the **date** rather than the row keeps the job
     *         from accidentally reusing a stale figure instead of recomputing.
     * Result: an ISO `yyyy-MM-dd`, or `null` when there are none — which the job reads as "first
     *         ever run", and writes today only rather than inventing a history (P-03).
     * Input:  [profileId]. Output: `String?`.
     */
    @Query(
        "SELECT MAX(as_of_iso_date) FROM net_worth_snapshot " +
            "WHERE profile_id = :profileId AND deleted_at_utc_millis IS NULL",
    )
    suspend fun findLatestAsOfDate(profileId: String): String?

    /**
     * Fetches one day's snapshot.
     * Result: the row, or `null`. Input: [profileId], [asOfIsoDate]. Output: `NetWorthSnapshotEntity?`.
     */
    @Query(
        "SELECT * FROM net_worth_snapshot WHERE profile_id = :profileId " +
            "AND as_of_iso_date = :asOfIsoDate AND deleted_at_utc_millis IS NULL",
    )
    suspend fun findForDate(
        profileId: String,
        asOfIsoDate: String,
    ): NetWorthSnapshotEntity?
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
