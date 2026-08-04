package com.aicfo.core.database.dao

import androidx.paging.PagingSource
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Junction
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
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
     *
     *         **`booked_on_iso_date <= :asOfIsoDate` (issue 3.4).** FR-TXN-010 requires a
     *         future-dated transaction to be "excluded from actuals", and a balance is the actual
     *         this matters most for. Until issue 3.4 no row could be booked past today, so the sum
     *         was already correct without the clause and [balancesForNetWorth]'s doc comment
     *         predicted exactly this: "the current-balance query sums every live transaction
     *         whenever it happened … wrong here the moment issue 3.4 lands future-dated
     *         transactions". **It was wrong the moment it landed** — a rent payment scheduled for
     *         next week was subtracted from the balance on the accounts screen while net worth,
     *         which had the clause, showed a different figure for the same money. Found by a test
     *         asserting the balance the screen renders, not by reading the query.
     * Result: emits on every change to `account` **or** `transactions`, name-ordered. Each row's
     *         balance is `opening_balance_minor + movement_minor` as at [asOfIsoDate].
     * Input:  [profileId]; [includeArchived] — `false` for active totals, `true` for the full list;
     *         [asOfIsoDate] — inclusive ISO `yyyy-MM-dd` bound (TIM-002), normally today.
     * Output: `Flow<List<AccountWithBalance>>`.
     * Changelog: 2026-07-28 — Created for issue 2.5.
     *            2026-08-03 — Issue 3.4: bounded by [asOfIsoDate] (FR-TXN-010).
     */
    @Query(
        "SELECT a.*, COALESCE((" +
            "SELECT SUM(t.amount_minor) FROM transactions t " +
            "WHERE t.account_id = a.id AND t.deleted_at_utc_millis IS NULL " +
            "AND t.booked_on_iso_date <= :asOfIsoDate" +
            "), 0) AS movement_minor " +
            "FROM account a " +
            "WHERE a.profile_id = :profileId AND a.deleted_at_utc_millis IS NULL " +
            "AND (:includeArchived OR a.archived_at_utc_millis IS NULL) " +
            "ORDER BY a.name",
    )
    fun observeWithBalances(
        profileId: String,
        includeArchived: Boolean,
        asOfIsoDate: String,
    ): Flow<List<AccountWithBalance>>

    /**
     * Fetches one account with its derived balance (issue 2.5).
     * Why:    the editor loads the account it is about to change, and it must show the same balance
     *         the list showed — so it uses the same derivation rather than reading the cached
     *         column (DB-001). Archived accounts are found too: editing is how a user un-archives.
     *         **Bounded by [asOfIsoDate] since issue 3.4**, for the reason [observeWithBalances]
     *         gives: the editor must show the figure the list shows, and reconciliation (FR-ACC-006)
     *         computes its adjustment from this — an adjustment sized against a balance that
     *         included next week's rent would post a correction for money the user still has.
     * Result: the row, or `null` if it does not exist or was soft-deleted.
     * Input:  [id]; [asOfIsoDate] — inclusive ISO `yyyy-MM-dd` bound (TIM-002), normally today.
     * Output: `AccountWithBalance?`.
     * Changelog: 2026-08-03 — Issue 3.4: bounded by [asOfIsoDate] (FR-TXN-010).
     */
    @Query(
        "SELECT a.*, COALESCE((" +
            "SELECT SUM(t.amount_minor) FROM transactions t " +
            "WHERE t.account_id = a.id AND t.deleted_at_utc_millis IS NULL " +
            "AND t.booked_on_iso_date <= :asOfIsoDate" +
            "), 0) AS movement_minor " +
            "FROM account a WHERE a.id = :id AND a.deleted_at_utc_millis IS NULL",
    )
    suspend fun findWithBalance(
        id: String,
        asOfIsoDate: String,
    ): AccountWithBalance?

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

    /**
     * Repairs every cached balance that disagrees with the derivation (issue 2.7; DB-001).
     *
     * Why:    `current_balance_minor` has been a cache nothing reads and nothing maintains since
     *         issue 1.6 — seeded at create and stale from the first transaction onwards
     *         ([ADR-0007](../../../../../../../../docs/adr/0007-account-balances-derived-not-stored.md)).
     *         DB-001 says the balance is derivable and "never mutated ad hoc"; this is the nightly
     *         job that sentence implies.
     *
     *         **One `UPDATE`, not a read-then-write loop in Kotlin.** A loop would be N+1 queries
     *         and, worse, would compute each figure at a slightly different moment — the window
     *         where a transaction landing mid-pass leaves one account repaired to a balance that was
     *         already out of date. SQLite evaluates the correlated subquery per row inside a single
     *         statement, so the whole profile is repaired against one consistent view.
     *
     *         **The trailing `<>` clause is what makes the return value mean something.** Without it
     *         every row matches and "rows affected" is just the account count; with it, the figure
     *         is exactly how many caches were wrong — the observation the job exists to make — and
     *         nothing is written that was already correct.
     *
     *         **The subquery is character-for-character the one [observeWithBalances] uses**, down
     *         to the `deleted_at_utc_millis IS NULL` filter. That is deliberate and it is the whole
     *         safety property: if this derivation and the read derivation ever disagree, the job
     *         "repairs" the cache to a figure no screen would ever show, which is strictly worse
     *         than leaving it stale. Any change to one must be made to the other.
     * Result: `current_balance_minor` equals the derived balance for every live account in the
     *         profile; returns **how many were out of step**, zero on a healthy profile.
     *         Soft-deleted accounts are skipped — a tombstone's cache is nobody's to maintain.
     * Input:  [profileId]; [updatedAtUtcMillis] — from the injected `Clock` (TIM-001).
     * Output: rows repaired.
     * Changelog: 2026-08-02 — Created for issue 2.7.
     */
    @Query(
        "UPDATE account SET current_balance_minor = opening_balance_minor + COALESCE((" +
            "SELECT SUM(t.amount_minor) FROM transactions t " +
            "WHERE t.account_id = account.id AND t.deleted_at_utc_millis IS NULL" +
            "), 0), updated_at_utc_millis = :updatedAtUtcMillis " +
            "WHERE profile_id = :profileId AND deleted_at_utc_millis IS NULL " +
            "AND current_balance_minor <> opening_balance_minor + COALESCE((" +
            "SELECT SUM(t.amount_minor) FROM transactions t " +
            "WHERE t.account_id = account.id AND t.deleted_at_utc_millis IS NULL" +
            "), 0)",
    )
    suspend fun refreshCachedBalances(
        profileId: String,
        updatedAtUtcMillis: Long,
    ): Int
}

/** Reads and writes transactions. */
@Dao
// One DAO per table is Room's model, and this is the highest-traffic table in the app: capture
// (3.1-3.4), the filtered ledger (3.6) and the bulk edits all read and write `transactions`.
// Splitting by query kind would put two DAOs on one table, which is worse than the count.
@Suppress("TooManyFunctions")
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
     * Observes a profile's transactions in a date range **with their split lines** (issue 3.3).
     *
     * Why:    the same window and ordering as [observeBookedBetween], but each row arrives with its
     *         lines attached. `@Transaction` is what makes that consistent: Room runs the parent
     *         query and the relation query inside one database transaction, so the list can never
     *         show a parent whose lines were written a moment later.
     * Result: emits when **either** table changes; newest first; soft-deleted parents excluded.
     *         Soft-deleted *lines* are not — `@Relation` cannot carry a `WHERE`, so the repository's
     *         mapper drops them.
     * Input:  [profileId], [fromIsoDate], [toIsoDate] — inclusive ISO `yyyy-MM-dd` bounds.
     * Output: `Flow<List<TransactionWithSplits>>`.
     */
    @Transaction
    @Query(
        "SELECT * FROM transactions WHERE profile_id = :profileId " +
            "AND booked_on_iso_date BETWEEN :fromIsoDate AND :toIsoDate " +
            "AND deleted_at_utc_millis IS NULL ORDER BY occurred_at_utc_millis DESC",
    )
    fun observeBookedBetweenWithSplits(
        profileId: String,
        fromIsoDate: String,
        toIsoDate: String,
    ): Flow<List<TransactionWithSplits>>

    /**
     * Fetches one transaction.
     * Result: the row, or `null`. Input: [id]. Output: `TransactionEntity?`.
     */
    @Query("SELECT * FROM transactions WHERE id = :id AND deleted_at_utc_millis IS NULL")
    suspend fun findById(id: String): TransactionEntity?

    /**
     * Marks a transaction deleted without removing the row.
     *
     * **`AND deleted_at_utc_millis IS NULL` is the guard that makes the returned count mean
     * something** (issue 3.2). Without it a second delete of the same id matches the row again and
     * returns 1, so the caller reports success for something that did not happen — the class of bug
     * where a user taps Delete twice and is lied to the second time. `AccountDao.softDelete` has had
     * this clause since issue 2.5; this one was written without it in issue 1.6 and nothing called
     * it until now.
     * Result: it disappears from reads and totals. `0` when the id names nothing live.
     * Input:  [id], [deletedAtUtcMillis]. Output: rows affected.
     */
    @Query(
        "UPDATE transactions SET deleted_at_utc_millis = :deletedAtUtcMillis " +
            "WHERE id = :id AND deleted_at_utc_millis IS NULL",
    )
    suspend fun softDelete(
        id: String,
        deletedAtUtcMillis: Long,
    ): Int

    /**
     * Marks every live leg of a transfer deleted (issue 3.2; FR-TXN-003, DB-004).
     *
     * Why:    FR-TXN-003 is explicit that "deleting one side deletes both". Doing it in **one
     *         statement** rather than reading the legs and deleting each is what makes that
     *         atomic — there is no window in which one leg is gone and the other is not, and a
     *         transfer that somehow had three legs would still be fully removed.
     * Result: both legs disappear from reads and balances; the rows survive as tombstones (DB-002).
     *         Returns how many legs were actually touched, so a caller can tell a real delete from
     *         a repeat.
     * Input:  [transferId], [deletedAtUtcMillis]. Output: rows affected.
     */
    @Query(
        "UPDATE transactions SET deleted_at_utc_millis = :deletedAtUtcMillis " +
            "WHERE transfer_id = :transferId AND deleted_at_utc_millis IS NULL",
    )
    suspend fun softDeleteTransfer(
        transferId: String,
        deletedAtUtcMillis: Long,
    ): Int

    /**
     * Stamps every scheduled row whose booked day has arrived (issue 3.4; FR-TXN-010).
     *
     * Why:    `ScheduledTransactionWorker`'s entire job, as **one statement**. Reading the due rows
     *         and updating each would open a window in which a row is due, unstamped, and being
     *         read; a single `UPDATE` has no such window and needs no transaction around it.
     *
     *         **`posted_at_utc_millis IS NULL` is what makes the job idempotent**, which the
     *         requirement asks for directly. A second run on the same day matches nothing and
     *         returns `0`, so a worker retried by WorkManager, run twice after a reboot, or racing
     *         a manual enqueue cannot stamp a row twice or move a stamp already written.
     *
     *         **`<= :todayIsoDate`, not `= :todayIsoDate`.** A device switched off for a week must
     *         catch up on every day it missed, not only the day it woke on. This is the same
     *         backfilling shape `NetWorthRepository.snapshotUpToToday` uses, and for the same
     *         reason: deferring is only safe when the deferred run does all the work.
     *
     *         **This does not move money.** Balances bound on `booked_on_iso_date`, so a row starts
     *         counting the moment its date arrives whether or not this ever runs — see
     *         `TransactionEntity.postedAtUtcMillis` and `docs/adr/0010-future-dated-posting.md`.
     * Result: the number of rows stamped — `0` when there was nothing due, which is a success and
     *         not a failure.
     * Input:  [profileId]; [todayIsoDate] — today in the profile zone (TIM-002), from the injected
     *         `Clock`; [nowUtcMillis] — the instant to stamp (TIM-001).
     * Output: rows affected.
     * Changelog: 2026-08-03 — Created for issue 3.4 (FR-TXN-010).
     */
    @Query(
        "UPDATE transactions SET posted_at_utc_millis = :nowUtcMillis " +
            "WHERE profile_id = :profileId AND booked_on_iso_date <= :todayIsoDate " +
            "AND posted_at_utc_millis IS NULL AND deleted_at_utc_millis IS NULL",
    )
    suspend fun postDue(
        profileId: String,
        todayIsoDate: String,
        nowUtcMillis: Long,
    ): Int

    /**
     * The whole ledger, filtered and paged (issue 3.6; FR-TXN-007).
     *
     * Why:    FR-TXN-007 asks one question with seven facets — "search (payee, note, amount, tag),
     *         filters (date range, account, category, type, amount range) … and infinite scroll via
     *         paging" — and this is one statement answering all of it. **The nullable-parameter
     *         shape (`:p IS NULL OR column = :p`) is the point**: the alternative is a query per
     *         combination, which is 2^7 of them, or string-concatenated SQL, which Room cannot
     *         verify at compile time and which is where an injection bug would live.
     *
     *         **A `PagingSource`, not a windowed `Flow`.** [observeBookedBetweenWithSplits] reads a
     *         fixed 30 days because loading a user's whole history into a list was never acceptable;
     *         paging is how the list stops being a window without becoming unbounded. Room keeps
     *         this source invalidated, so a write still refreshes the screen.
     *
     *         **Transfer legs are collapsed here rather than in Kotlin, and that is the subtle
     *         part.** The list must show a transfer once (FR-TXN-003), and the old
     *         `List<Transaction>.toRows()` did it by pairing legs within a loaded day — which breaks
     *         the moment paging puts the two legs in different pages. So: with no account filter,
     *         only the outgoing leg survives (`type <> 'transfer_in'`) and [counterpartAccountId]
     *         names where the money went. **With an account filter the clause stands down**, because
     *         a user who filters to the destination account must still see the money arrive — there
     *         the account clause itself admits exactly one leg.
     * Result: a page of rows, newest instant first, each with its split lines and tags attached.
     *         Soft-deleted parents excluded.
     * Input:  [profileId]; [queryPattern] — a pre-escaped `%term%`, or `null`; [queryAmountMinor] —
     *         the search text parsed as an amount, or `null` when it is not one; [accountId];
     *         [categoryId]; [type] and [source] — stored values, not enum names; [tagId];
     *         [minAmountMinor]/[maxAmountMinor] — bounds on the **absolute** amount (MNY-001), so
     *         "between ₹100 and ₹500" matches an expense; [fromIsoDate]/[toIsoDate] — inclusive
     *         ISO `yyyy-MM-dd` bounds (TIM-002). Every one is `null` for "do not filter on this".
     * Output: `PagingSource<Int, TransactionListRow>`.
     * Changelog: 2026-08-04 — Created for issue 3.6 (FR-TXN-007).
     */
    @Transaction
    @Query(
        "SELECT *, (SELECT s.account_id FROM transactions s WHERE s.transfer_id = transactions.transfer_id " +
            "AND s.id <> transactions.id AND s.deleted_at_utc_millis IS NULL LIMIT 1) " +
            "AS counterpart_account_id " +
            "FROM transactions WHERE profile_id = :profileId AND deleted_at_utc_millis IS NULL " +
            "AND (:accountId IS NULL OR account_id = :accountId) " +
            "AND (:categoryId IS NULL OR category_id = :categoryId) " +
            "AND (:type IS NULL OR type = :type) " +
            "AND (:source IS NULL OR source = :source) " +
            "AND (:fromIsoDate IS NULL OR booked_on_iso_date >= :fromIsoDate) " +
            "AND (:toIsoDate IS NULL OR booked_on_iso_date <= :toIsoDate) " +
            "AND (:minAmountMinor IS NULL OR ABS(amount_minor) >= :minAmountMinor) " +
            "AND (:maxAmountMinor IS NULL OR ABS(amount_minor) <= :maxAmountMinor) " +
            "AND (:tagId IS NULL OR EXISTS (SELECT 1 FROM transaction_tags tt " +
            "WHERE tt.transaction_id = transactions.id AND tt.tag_id = :tagId)) " +
            "AND (:queryPattern IS NULL OR merchant LIKE :queryPattern ESCAPE '\\' " +
            "OR note LIKE :queryPattern ESCAPE '\\' " +
            "OR (:queryAmountMinor IS NOT NULL AND ABS(amount_minor) = :queryAmountMinor) " +
            "OR EXISTS (SELECT 1 FROM transaction_tags tt JOIN tags g ON g.id = tt.tag_id " +
            "WHERE tt.transaction_id = transactions.id AND g.deleted_at_utc_millis IS NULL " +
            "AND g.name LIKE :queryPattern ESCAPE '\\')) " +
            "AND (transfer_id IS NULL OR :accountId IS NOT NULL OR type <> 'transfer_in') " +
            "ORDER BY occurred_at_utc_millis DESC, id DESC",
    )
    @Suppress("LongParameterList") // One parameter per FR-TXN-007 facet; a wrapper cannot cross into SQL.
    fun pagedFiltered(
        profileId: String,
        queryPattern: String?,
        queryAmountMinor: Long?,
        accountId: String?,
        categoryId: String?,
        type: String?,
        source: String?,
        tagId: String?,
        minAmountMinor: Long?,
        maxAmountMinor: Long?,
        fromIsoDate: String?,
        toIsoDate: String?,
    ): PagingSource<Int, TransactionListRow>

    /**
     * Each day's net total over the same filtered set (issue 3.6; FR-TXN-007's "daily totals").
     *
     * Why:    **this cannot be a fold over the loaded rows, and that is the whole reason it exists.**
     *         A page boundary can fall in the middle of a day, so a header that summed what was in
     *         memory would understate its own day until the user scrolled — a wrong number on screen
     *         is worse than a missing one. The database has every row; it does the sum.
     *
     *         **Transfer legs are excluded rather than both included.** A transfer's legs are `-X`
     *         and `+X`, so keeping both would contribute zero — which is exactly what
     *         `TransactionDay.total` has always produced for a collapsed pair — and excluding them
     *         reaches the same figure in one clause that cannot be broken by the account filter
     *         admitting only one leg. A transfer is not spending; it should move no day total.
     * Result: one row per day that has at least one non-transfer transaction, newest first. A day
     *         whose only activity was a transfer is absent, and reads as a zero total.
     * Input:  the same facets as [pagedFiltered], with the same `null`-means-unfiltered contract.
     * Output: `Flow<List<DayTotalRow>>`.
     * Changelog: 2026-08-04 — Created for issue 3.6 (FR-TXN-007).
     */
    @Query(
        "SELECT booked_on_iso_date AS iso_date, SUM(amount_minor) AS total_minor " +
            "FROM transactions WHERE profile_id = :profileId AND deleted_at_utc_millis IS NULL " +
            "AND transfer_id IS NULL " +
            "AND (:accountId IS NULL OR account_id = :accountId) " +
            "AND (:categoryId IS NULL OR category_id = :categoryId) " +
            "AND (:type IS NULL OR type = :type) " +
            "AND (:source IS NULL OR source = :source) " +
            "AND (:fromIsoDate IS NULL OR booked_on_iso_date >= :fromIsoDate) " +
            "AND (:toIsoDate IS NULL OR booked_on_iso_date <= :toIsoDate) " +
            "AND (:minAmountMinor IS NULL OR ABS(amount_minor) >= :minAmountMinor) " +
            "AND (:maxAmountMinor IS NULL OR ABS(amount_minor) <= :maxAmountMinor) " +
            "AND (:tagId IS NULL OR EXISTS (SELECT 1 FROM transaction_tags tt " +
            "WHERE tt.transaction_id = transactions.id AND tt.tag_id = :tagId)) " +
            "AND (:queryPattern IS NULL OR merchant LIKE :queryPattern ESCAPE '\\' " +
            "OR note LIKE :queryPattern ESCAPE '\\' " +
            "OR (:queryAmountMinor IS NOT NULL AND ABS(amount_minor) = :queryAmountMinor) " +
            "OR EXISTS (SELECT 1 FROM transaction_tags tt JOIN tags g ON g.id = tt.tag_id " +
            "WHERE tt.transaction_id = transactions.id AND g.deleted_at_utc_millis IS NULL " +
            "AND g.name LIKE :queryPattern ESCAPE '\\')) " +
            "GROUP BY booked_on_iso_date ORDER BY booked_on_iso_date DESC",
    )
    @Suppress("LongParameterList") // Mirrors pagedFiltered's facets exactly; they must not drift.
    fun observeDayTotals(
        profileId: String,
        queryPattern: String?,
        queryAmountMinor: Long?,
        accountId: String?,
        categoryId: String?,
        type: String?,
        source: String?,
        tagId: String?,
        minAmountMinor: Long?,
        maxAmountMinor: Long?,
        fromIsoDate: String?,
        toIsoDate: String?,
    ): Flow<List<DayTotalRow>>

    /**
     * The distinct sources present in a profile's whole ledger (issue 3.6; FR-TXN-009).
     *
     * Why:    the source chips. Issue 3.5 derived them from the loaded rows, which was correct while
     *         the list *was* every row it had; with paging, "loaded" is the first page, so chips
     *         built from it would appear and vanish as the user scrolled. Unfiltered on purpose —
     *         chips derived from the filtered set would delete every alternative the moment one was
     *         chosen, stranding the user with no way back.
     * Result: emits on every change; soft-deleted rows excluded. Empty for an empty profile.
     * Input:  [profileId]. Output: `Flow<List<String>>` of stored source values.
     * Changelog: 2026-08-04 — Created for issue 3.6.
     */
    @Query(
        "SELECT DISTINCT source FROM transactions WHERE profile_id = :profileId " +
            "AND deleted_at_utc_millis IS NULL",
    )
    fun observeDistinctSources(profileId: String): Flow<List<String>>

    /**
     * Sets the category on many transactions at once (issue 3.6; FR-TXN-008).
     *
     * Why:    FR-TXN-008's "multi-select recategorise", as one statement — a loop of single updates
     *         would be the same writes with a window in which half the selection is recategorised.
     *
     *         **Two rows are refused in SQL rather than filtered by the caller**, because both are
     *         invariants of other requirements and a caller that had to remember them is a caller
     *         that can forget:
     *         - `transfer_id IS NULL` — a transfer is not spending, so a leg has no category
     *           (FR-TXN-003). A categorised leg would count the user's own savings against a budget.
     *         - not a split parent — the lines carry the categories (FR-TXN-004), and a category on
     *           the parent as well is a second, contradictory answer to "what was this?".
     * Result: rows actually changed, which may be fewer than were asked for. The caller reports the
     *         count rather than assuming the selection size.
     * Input:  [ids]; [categoryId] — `null` clears the category, which is a legitimate bulk edit;
     *         [updatedAtUtcMillis] — from the injected `Clock` (TIM-001).
     * Output: rows affected.
     * Changelog: 2026-08-04 — Created for issue 3.6 (FR-TXN-008).
     */
    @Query(
        "UPDATE transactions SET category_id = :categoryId, updated_at_utc_millis = :updatedAtUtcMillis " +
            "WHERE id IN (:ids) AND deleted_at_utc_millis IS NULL AND transfer_id IS NULL " +
            "AND id NOT IN (SELECT transaction_id FROM transaction_splits " +
            "WHERE deleted_at_utc_millis IS NULL)",
    )
    suspend fun recategoriseAll(
        ids: List<String>,
        categoryId: String?,
        updatedAtUtcMillis: Long,
    ): Int

    /**
     * Marks many transactions deleted at once (issue 3.6; FR-TXN-008, DB-002).
     *
     * Why:    the bulk half of [softDelete], with the same `deleted_at_utc_millis IS NULL` guard —
     *         it is what makes the returned count mean "rows that actually went", so a repeat of the
     *         same delete reports zero rather than lying about work it did not do.
     * Result: they disappear from every read and every balance; the rows survive as tombstones, so
     *         [restoreAll] can bring them back.
     * Input:  [ids]; [deletedAtUtcMillis]. Output: rows affected.
     * Changelog: 2026-08-04 — Created for issue 3.6 (FR-TXN-008).
     */
    @Query(
        "UPDATE transactions SET deleted_at_utc_millis = :deletedAtUtcMillis " +
            "WHERE id IN (:ids) AND deleted_at_utc_millis IS NULL",
    )
    suspend fun softDeleteAll(
        ids: List<String>,
        deletedAtUtcMillis: Long,
    ): Int

    /**
     * Brings soft-deleted transactions back (issue 3.6; FR-TXN-008's undo).
     *
     * Why:    "delete (with undo snackbar)" is only honest if the delete is genuinely reversible.
     *         DB-002's tombstones already keep the rows; this is the statement that makes them
     *         visible again, and it is the reason the delete is soft rather than a `DELETE`.
     *
     *         **Every balance recovers with no further write**, because a balance is a `SUM` over
     *         live transactions (DB-001, ADR-0007) — the same property that made the delete move it.
     * Result: the rows return to every read. Splits and tags come back with their parent untouched:
     *         the lines are restored by the caller in the same database transaction, and tag links
     *         were never removed.
     * Input:  [ids]; [updatedAtUtcMillis] — from the injected `Clock` (TIM-001).
     * Output: rows affected.
     * Changelog: 2026-08-04 — Created for issue 3.6 (FR-TXN-008).
     */
    @Query(
        "UPDATE transactions SET deleted_at_utc_millis = NULL, updated_at_utc_millis = :updatedAtUtcMillis " +
            "WHERE id IN (:ids) AND deleted_at_utc_millis IS NOT NULL",
    )
    suspend fun restoreAll(
        ids: List<String>,
        updatedAtUtcMillis: Long,
    ): Int

    /**
     * Finds the ids of every live leg of the transfers the given rows belong to (issue 3.6).
     *
     * Why:    FR-TXN-003's "deleting one side deletes both" has to survive being applied to a
     *         *selection*. The repository asks for the full set before it deletes, so the undo
     *         batch it hands back names both legs — undoing a bulk delete that silently pulled in a
     *         sibling must bring the sibling back too, or the money the transfer moved reappears in
     *         one account only.
     * Result: every live transaction sharing a `transfer_id` with one of [ids], **including** the
     *         rows named in [ids] themselves. Empty when none of them are transfers.
     * Input:  [ids]. Output: `List<String>`.
     * Changelog: 2026-08-04 — Created for issue 3.6 (FR-TXN-008).
     */
    @Query(
        "SELECT id FROM transactions WHERE deleted_at_utc_millis IS NULL AND transfer_id IN " +
            "(SELECT transfer_id FROM transactions WHERE id IN (:ids) AND transfer_id IS NOT NULL)",
    )
    suspend fun findTransferSiblingIds(ids: List<String>): List<String>
}

/**
 * One day's net total, as SQLite computes it (issue 3.6; FR-TXN-007).
 *
 * Why:  the projection [TransactionDao.observeDayTotals] returns. A pair rather than a `Map` because
 *       Room maps a query to rows, and building the map once in the repository keeps the ordering
 *       decision — newest day first — in the SQL where it can be read.
 * Result: the figure a day header shows.
 * Changelog: 2026-08-04 — Created for issue 3.6.
 *
 * Input:  [isoDate] — the profile-zone day (TIM-002); [totalMinor] — MNY-001 paise, signed, wrapped
 *         in `Money` by the repository so nothing above the data layer does arithmetic on a raw
 *         `Long`. Output: an immutable value.
 */
data class DayTotalRow(
    @ColumnInfo(name = "iso_date") val isoDate: String,
    @ColumnInfo(name = "total_minor") val totalMinor: Long,
)

/**
 * One row of the filtered ledger — a transaction with everything the list needs (issue 3.6).
 *
 * Why:  [TransactionWithSplits] carries the lines but not the tags, and neither carries the fact a
 *       transfer row most needs: **which account is on the other side**. The list renders "HDFC
 *       Savings → Cash Wallet" from one leg, and with paging it can no longer find the sibling by
 *       scanning the loaded rows (they may be pages apart). So the query projects it.
 * Result: a page row that renders without a second lookup.
 * Changelog: 2026-08-04 — Created for issue 3.6 (FR-TXN-007, FR-TXN-003).
 *
 * **[splits] and [tags] both include tombstones**, for the reason [TransactionWithSplits] states:
 * `@Relation` cannot carry a `WHERE`. Both are filtered once, in the repository's mapper.
 *
 * Input:  [transaction] — the row; [counterpartAccountId] — the other leg's account for a transfer,
 *         `null` on every other row and on a transfer whose sibling is gone; [splits]; [tags].
 * Output: an immutable value.
 */
data class TransactionListRow(
    @Embedded val transaction: TransactionEntity,
    @ColumnInfo(name = "counterpart_account_id") val counterpartAccountId: String?,
    @Relation(parentColumn = "id", entityColumn = "transaction_id")
    val splits: List<TransactionSplitEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy =
            Junction(
                value = TransactionTagEntity::class,
                parentColumn = "transaction_id",
                entityColumn = "tag_id",
            ),
    )
    val tags: List<TagEntity>,
)

/**
 * A transaction with its split lines, as one row (issue 3.3; FR-TXN-004).
 *
 * Why:  the recent list needs every transaction's lines, and the alternatives are both worse than
 *       this. A query per transaction is N+1. **Combining two `Flow`s in the repository is what this
 *       replaced**: `combine` calls `yield()` internally, which `UnconfinedTestDispatcher` refuses,
 *       so every repository test that read the list died on the dispatcher rather than on anything
 *       about the data. Room's `@Relation` does the join itself and observes both tables, so one
 *       flow emits when either changes.
 * Result: a parent and its lines, arriving together and invalidating together.
 * Changelog: 2026-08-02 — Created for issue 3.3.
 *
 * **[splits] includes tombstones.** `@Relation` cannot carry a `WHERE`, so soft-deleted lines are
 * filtered once in the repository's mapper rather than pretended away here.
 */
data class TransactionWithSplits(
    @Embedded val transaction: TransactionEntity,
    @Relation(parentColumn = "id", entityColumn = "transaction_id")
    val splits: List<TransactionSplitEntity>,
)

/** Reads and writes the lines of split transactions (issue 3.3; FR-TXN-004). */
@Dao
interface TransactionSplitDao {
    /**
     * Inserts many lines at once, replacing any with the same id.
     * Why:    a split is written as one parent plus all of its lines inside a single database
     *         transaction (DB-004); inserting them one at a time would be the same number of
     *         statements with more chances for a caller to stop half-way.
     * Result: the lines exist. Input: [splits]. Output: none.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(splits: List<TransactionSplitEntity>)

    /**
     * Fetches one transaction's live lines.
     * Result: the lines, oldest first; empty when the transaction is not split.
     * Input:  [transactionId]. Output: `List<TransactionSplitEntity>`.
     */
    @Query(
        "SELECT * FROM transaction_splits WHERE transaction_id = :transactionId " +
            "AND deleted_at_utc_millis IS NULL ORDER BY created_at_utc_millis",
    )
    suspend fun findForTransaction(transactionId: String): List<TransactionSplitEntity>

    /**
     * Marks every live line of a transaction deleted (issue 3.3; DB-002).
     *
     * Why:    deleting a split parent must take its lines with it, in the same database transaction.
     *         A line whose parent is gone attributes an amount that no longer exists — and the
     *         windowed read above would still have to filter it out for ever.
     * Result: the lines disappear from every read; the rows survive as tombstones.
     * Input:  [transactionId], [deletedAtUtcMillis]. Output: rows affected.
     */
    @Query(
        "UPDATE transaction_splits SET deleted_at_utc_millis = :deletedAtUtcMillis " +
            "WHERE transaction_id = :transactionId AND deleted_at_utc_millis IS NULL",
    )
    suspend fun softDeleteForTransaction(
        transactionId: String,
        deletedAtUtcMillis: Long,
    ): Int

    /**
     * Brings a transaction's soft-deleted lines back (issue 3.6; FR-TXN-008's undo).
     *
     * Why:    the mirror of [softDeleteForTransaction]. A restored split parent whose lines stayed
     *         deleted would be an amount attributed to nothing — the transaction would reappear in
     *         every balance while the categories it was divided across stayed gone, and no screen
     *         would show why. Run in the same database transaction as the parent's restore.
     * Result: the lines return to every read. `0` when the transaction was never split, which is
     *         the normal case and not a failure.
     * Input:  [transactionId], [updatedAtUtcMillis]. Output: rows affected.
     * Changelog: 2026-08-04 — Created for issue 3.6 (FR-TXN-008).
     */
    @Query(
        "UPDATE transaction_splits SET deleted_at_utc_millis = NULL, " +
            "updated_at_utc_millis = :updatedAtUtcMillis " +
            "WHERE transaction_id = :transactionId AND deleted_at_utc_millis IS NOT NULL",
    )
    suspend fun restoreForTransaction(
        transactionId: String,
        updatedAtUtcMillis: Long,
    ): Int
}

/**
 * Reads and writes tags and their attachments to transactions (issue 3.6; FR-TXN-007, FR-TXN-008).
 *
 * **Detaching a tag is a hard `DELETE`, and that is a deliberate exception to DB-002.** A row in
 * `transaction_tags` is a pure link — it holds no amount, no date and nothing the user typed, so
 * there is nothing in it to recover. Soft-deleting one would be worse than useless here: Room's
 * `@Relation` with a `Junction` cannot carry a `WHERE`, so a tombstoned link would keep returning
 * its tag on every read and the untag would appear not to have happened. The `deleted_at_utc_millis`
 * column still exists because `MigrationSafetyTest` requires it on every table; nothing sets it.
 *
 * Deleting the *transaction* leaves its links alone, which is what makes undo restore the tags with
 * the row (FR-TXN-008) — the parent's tombstone already hides them from every read.
 */
@Dao
interface TagDao {
    /**
     * Inserts tags, replacing any with the same id.
     * Why:    a bulk retag creates every missing tag in one statement inside the same database
     *         transaction as the links (DB-004), so a half-applied retag cannot leave a link
     *         pointing at a tag that was never written.
     * Result: the tags exist. Input: [tags]. Output: none.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tags: List<TagEntity>)

    /**
     * Observes a profile's live tags, name-ordered (FR-TXN-007).
     * Why:    the filter sheet's tag chips. Read from the database rather than derived from the
     *         loaded rows: with paging, "loaded" is only the first page, so chips built from it
     *         would appear and disappear as the user scrolled.
     * Result: emits on every change; soft-deleted rows excluded. Empty is the normal state.
     * Input:  [profileId]. Output: `Flow<List<TagEntity>>`.
     */
    @Query(
        "SELECT * FROM tags WHERE profile_id = :profileId " +
            "AND deleted_at_utc_millis IS NULL ORDER BY name",
    )
    fun observeForProfile(profileId: String): Flow<List<TagEntity>>

    /**
     * Finds a profile's existing tags by name, case-insensitively (FR-TXN-008).
     * Why:    a bulk retag must reuse the tag the user already has rather than minting a second
     *         one — `Travel` typed today and `travel` typed last week are one tag, and two rows
     *         would split its transactions across two chips. The unique index enforces the same
     *         rule; this is what lets the repository satisfy it without relying on a conflict.
     * Result: the matching live tags; empty when none of the names exist yet.
     * Input:  [profileId]; [loweredNames] — already lower-cased by the caller, because SQLite's
     *         `LOWER` is ASCII-only and the caller's `lowercase()` is not.
     * Output: `List<TagEntity>`.
     */
    @Query(
        "SELECT * FROM tags WHERE profile_id = :profileId " +
            "AND LOWER(name) IN (:loweredNames) AND deleted_at_utc_millis IS NULL",
    )
    suspend fun findByNames(
        profileId: String,
        loweredNames: List<String>,
    ): List<TagEntity>

    /**
     * Attaches tags to transactions, replacing any link with the same id.
     * Result: the links exist. Input: [links]. Output: none.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun attachAll(links: List<TransactionTagEntity>)

    /**
     * Removes every tag from the named transactions (FR-TXN-008).
     * Why:    a retag is "these transactions now carry exactly these tags", so it clears first and
     *         attaches second, both inside one database transaction. Expressing it as a set rather
     *         than as add/remove deltas is what makes the operation idempotent — applying the same
     *         retag twice leaves the same links.
     *
     *         A hard `DELETE` — see the interface comment.
     * Result: the links are gone. Input: [transactionIds]. Output: rows affected.
     */
    @Query("DELETE FROM transaction_tags WHERE transaction_id IN (:transactionIds)")
    suspend fun detachAllFor(transactionIds: List<String>): Int
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
// One delete per profile-scoped table, which is exactly what ADR-0006 requires: a table this DAO
// cannot reach is residue the demo wipe leaves behind. The count tracks the schema.
@Suppress("TooManyFunctions")
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

    /**
     * Result: rows removed from `transaction_splits`. Input: [profileId]. Output: the count.
     *
     * Added by issue 3.3, which introduced the table. Called **before** [deleteTransactions]: the
     * lines are children, and clearing the parents first would leave them orphaned if the caller
     * failed in between — the same ordering argument [deleteProfile] makes for going last.
     */
    @Query("DELETE FROM transaction_splits WHERE profile_id = :profileId")
    suspend fun deleteTransactionSplits(profileId: String): Int

    /**
     * Result: rows removed from `transaction_tags`. Input: [profileId]. Output: the count.
     *
     * Added by issue 3.6, which introduced the table. Called **before** [deleteTransactions] and
     * [deleteTags], for the ordering reason [deleteTransactionSplits] gives: a link outlives neither
     * of the rows it joins.
     */
    @Query("DELETE FROM transaction_tags WHERE profile_id = :profileId")
    suspend fun deleteTransactionTags(profileId: String): Int

    /** Result: rows removed from `tags`. Input: [profileId]. Output: the count. */
    @Query("DELETE FROM tags WHERE profile_id = :profileId")
    suspend fun deleteTags(profileId: String): Int

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
     * Input:  [profileId]. Output: the total row count across all ten tables.
     */
    @Query(
        "SELECT (SELECT COUNT(*) FROM profile WHERE id = :profileId) + " +
            "(SELECT COUNT(*) FROM account WHERE profile_id = :profileId) + " +
            "(SELECT COUNT(*) FROM transactions WHERE profile_id = :profileId) + " +
            "(SELECT COUNT(*) FROM transaction_splits WHERE profile_id = :profileId) + " +
            "(SELECT COUNT(*) FROM transaction_tags WHERE profile_id = :profileId) + " +
            "(SELECT COUNT(*) FROM tags WHERE profile_id = :profileId) + " +
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
