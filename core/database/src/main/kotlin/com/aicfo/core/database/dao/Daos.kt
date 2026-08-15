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
import com.aicfo.core.database.entity.AttachmentEntity
import com.aicfo.core.database.entity.AuditLogEntity
import com.aicfo.core.database.entity.BudgetAlertEntity
import com.aicfo.core.database.entity.BudgetEntity
import com.aicfo.core.database.entity.BudgetReviewEntity
import com.aicfo.core.database.entity.CategoryEntity
import com.aicfo.core.database.entity.NetWorthSnapshotEntity
import com.aicfo.core.database.entity.ProfileEntity
import com.aicfo.core.database.entity.RecurringRuleEntity
import com.aicfo.core.database.entity.SmsDraftEntity
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
     * Observes the rows the recurring detector may look at (issue 3.7; FR-TXN-006).
     *
     * Why:    the detector is pure Kotlin and cannot decide *which* rows count, so every exclusion
     *         lives here — and each one is a requirement, not a preference:
     *         - `deleted_at_utc_millis IS NULL` — a tombstoned row is not evidence of anything.
     *         - `merchant IS NOT NULL AND TRIM(merchant) <> ''` — FR-TXN-006 matches on merchant,
     *           and "₹250 on the 3rd" with no payee is not a series a user could recognise. Filtered
     *           in SQL rather than in Kotlin so the rows never leave the database in the first place.
     *         - `transfer_id IS NULL` — money moving between the user's own accounts is not a bill
     *           (FR-TXN-003); a standing transfer to savings would otherwise be proposed as one.
     *         - `booked_on_iso_date <= :toIsoDate` — FR-TXN-010's scheduled rows are money that has
     *           not moved yet. Detecting a pattern in payments the user has merely *planned* would
     *           propose a rule from the app's own predictions.
     *         - `>= :fromIsoDate` — the window, so the query cost stays bounded on a long ledger.
     * Result: emits on every change to the ledger, oldest first. Empty for a new profile, which is
     *         a real answer rather than a missing one.
     * Input:  [profileId]; [fromIsoDate] and [toIsoDate] — ISO `yyyy-MM-dd` bounds, inclusive,
     *         derived by the caller from the injected `Clock` (TIM-001/TIM-002).
     * Output: `Flow<List<RecurringCandidateRow>>`.
     */
    @Query(
        "SELECT id, merchant, amount_minor, booked_on_iso_date FROM transactions " +
            "WHERE profile_id = :profileId AND deleted_at_utc_millis IS NULL " +
            "AND merchant IS NOT NULL AND TRIM(merchant) <> '' AND transfer_id IS NULL " +
            "AND booked_on_iso_date >= :fromIsoDate AND booked_on_iso_date <= :toIsoDate " +
            "ORDER BY booked_on_iso_date, id",
    )
    fun observeRecurringCandidates(
        profileId: String,
        fromIsoDate: String,
        toIsoDate: String,
    ): Flow<List<RecurringCandidateRow>>

    /**
     * Finds transactions that may already be this receipt (issue 3.8; FR-OCR-006).
     *
     * Why:    FR-OCR-006 is a MUST — *"if an SMS/manual transaction with same amount ±1% and date
     *         ±1 day exists, MUST offer merge instead of creating a duplicate"* — and every clause of
     *         it is in this `WHERE`:
     *         - `source IN ('manual', 'sms')` is the requirement's own wording, not a simplification.
     *           An existing **ocr** row is a different receipt someone scanned, not a duplicate of
     *           this one; offering to merge two scans would quietly delete a real purchase.
     *         - `ABS(amount_minor) BETWEEN :minMinor AND :maxMinor` — **the ±1% band is computed by
     *           the caller, in integer paise** (MNY-001). Doing it here would mean
     *           `amount_minor * 0.99` in SQL, which is floating-point arithmetic on money.
     *           Compared on the magnitude, because a receipt's total is unsigned and the ledger's
     *           spending is negative.
     *         - `booked_on_iso_date BETWEEN :fromIso AND :toIso` — TIM-002 makes this a string
     *           comparison that is also a date comparison, because ISO `yyyy-MM-dd` sorts
     *           lexicographically.
     *         - `transfer_id IS NULL` — money moved between your own accounts is not a purchase, so
     *           it can never be the thing a receipt duplicates (FR-TXN-003).
     * Result: the rows to offer as a merge, newest first. Empty is the common answer and means "save
     *         a new transaction".
     * Input:  [profileId]; [minMinor] and [maxMinor] — the magnitude band in paise, inclusive;
     *         [fromIsoDate] and [toIsoDate] — the date window, inclusive.
     * Output: `List<TransactionEntity>`.
     */
    @Query(
        "SELECT * FROM transactions WHERE profile_id = :profileId AND deleted_at_utc_millis IS NULL " +
            "AND source IN ('manual', 'sms') AND transfer_id IS NULL " +
            "AND ABS(amount_minor) BETWEEN :minMinor AND :maxMinor " +
            "AND booked_on_iso_date BETWEEN :fromIsoDate AND :toIsoDate " +
            "ORDER BY booked_on_iso_date DESC, id",
    )
    suspend fun findDuplicateCandidates(
        profileId: String,
        minMinor: Long,
        maxMinor: Long,
        fromIsoDate: String,
        toIsoDate: String,
    ): List<TransactionEntity>

    /**
     * Finds transactions that may already be this bank alert (issue 3.9; FR-OCR-006's mirror).
     *
     * Why:    the same event seen from the other side. FR-OCR-006 makes a receipt check for a
     *         matching `manual` or `sms` row; an alert must check for a matching `manual` or **`ocr`**
     *         row, because the receipt the user photographed at the till and the alert the bank sent
     *         thirty seconds later are one purchase.
     *
     *         **A separate query rather than a `sources` parameter on
     *         [findDuplicateCandidates].** The source set is not a caller's preference, it is each
     *         requirement's own wording, and a shared query taking a list would let a call site pass
     *         a set nobody argued for — including one containing its own source, which would offer
     *         to merge two alerts and quietly delete a real transaction. Every other clause is
     *         identical and documented on [findDuplicateCandidates].
     * Result: the rows to offer as a merge, newest first. Empty is the common answer.
     * Input:  as [findDuplicateCandidates]. Output: `List<TransactionEntity>`.
     */
    @Query(
        "SELECT * FROM transactions WHERE profile_id = :profileId AND deleted_at_utc_millis IS NULL " +
            "AND source IN ('manual', 'ocr') AND transfer_id IS NULL " +
            "AND ABS(amount_minor) BETWEEN :minMinor AND :maxMinor " +
            "AND booked_on_iso_date BETWEEN :fromIsoDate AND :toIsoDate " +
            "ORDER BY booked_on_iso_date DESC, id",
    )
    suspend fun findAlertDuplicateCandidates(
        profileId: String,
        minMinor: Long,
        maxMinor: Long,
        fromIsoDate: String,
        toIsoDate: String,
    ): List<TransactionEntity>

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
     * Counts what a category is currently attached to (issue 4.1; FR-SET-001).
     *
     * Why:    deleting a category does not delete the money spent in it — the rows keep their
     *         `category_id` and, because `CategoryDao.observeForProfile` excludes soft-deleted rows,
     *         they start reading as "Uncategorised". That is the right behaviour and a surprising
     *         one, so the editor states the consequence before the user confirms it (P-02). This is
     *         the number it states.
     * Result: the count of live transactions plus live split lines carrying [categoryId].
     * Input:  [categoryId]. Output: [Int].
     * Changelog: 2026-08-08 — Created for issue 4.1.
     *
     * **Split lines are counted, and counting only `transactions` would under-report badly.** A
     * split parent carries no category at all (FR-TXN-004 puts them on the lines), so a category
     * used exclusively inside splits would report zero and the dialog would promise nothing was
     * affected while every one of those lines went Uncategorised.
     */
    @Query(
        "SELECT (SELECT COUNT(*) FROM transactions " +
            "WHERE category_id = :categoryId AND deleted_at_utc_millis IS NULL) + " +
            "(SELECT COUNT(*) FROM transaction_splits " +
            "WHERE category_id = :categoryId AND deleted_at_utc_millis IS NULL)",
    )
    suspend fun countForCategory(categoryId: String): Int

    /**
     * How this profile has categorised one merchant before (issue 4.2; SRS §8.1(a)).
     *
     * Why:    §8.1's first precedence tier is "merchant-rule lookup from the user's **correction
     *         history**", and this table already *is* that history — every categorised transaction
     *         is a decision the user made or accepted. A separate `user_merchant_rule` table would
     *         be a second copy of the same fact, able to disagree with the ledger it was derived
     *         from, and it would need a migration to hold nothing new.
     *
     *         **The merchant is compared normalised on both sides.** `LOWER(TRIM(merchant))` is the
     *         SQL half of `normaliseMerchant`; the caller applies the Kotlin half to the argument.
     *         SQLite's `LOWER` folds ASCII only, so the two halves could differ on an accented Latin
     *         merchant name — a case that costs a suggestion, never a wrong one, because a
     *         mismatched key simply returns no rows.
     * Result: one row per category the user has used for this merchant, with how many live
     *         transactions carry it; empty when they have never categorised it. Ordered by count so
     *         a caller reading only the first row still gets the settled answer.
     * Input:  [profileId] — the active profile, so the demo's history never reaches a real one;
     *         [normalisedMerchant] — trimmed and lower-cased by the caller. Output:
     *         `List<MerchantCategoryCountRow>`.
     * Changelog: 2026-08-10 — Created for issue 4.2.
     *
     * **Split lines are deliberately *not* counted here, and that is the opposite of
     * [countForCategory]'s choice.** A split parent carries the merchant while its lines carry the
     * categories, so one such transaction is the user saying "this merchant is several things at
     * once" — evidence against a single suggestion, not for one. Joining the lines in would turn
     * every split into votes for two or three categories and suppress the tier by diluting it,
     * which is a worse answer than the honest one: a split contributes nothing.
     *
     * **Transfers are excluded** because they carry no merchant worth learning from — FR-TXN-003
     * gives them no payee, and no category either.
     */
    @Query(
        "SELECT category_id AS category_id, COUNT(*) AS occurrences FROM transactions " +
            "WHERE profile_id = :profileId AND deleted_at_utc_millis IS NULL " +
            "AND transfer_id IS NULL AND category_id IS NOT NULL " +
            "AND merchant IS NOT NULL AND LOWER(TRIM(merchant)) = :normalisedMerchant " +
            "GROUP BY category_id ORDER BY occurrences DESC, category_id",
    )
    suspend fun categoryCountsForMerchant(
        profileId: String,
        normalisedMerchant: String,
    ): List<MerchantCategoryCountRow>

    /**
     * How this profile has re-natured one merchant before (issue 4.3; SRS §8.3.1 step 4).
     *
     * Why:    §8.3 makes nature "auto-assigned, user-correctable, **learned**", and `nature` holds
     *         nothing but corrections — the automatic value is derived on read and never written. So
     *         `nature IS NOT NULL` is not a filter on incomplete rows, it *is* the definition of the
     *         signal: every row this returns is a decision the user made on purpose.
     *
     *         The merchant is compared normalised on both sides, the SQL half of
     *         `normaliseMerchant`, exactly as [categoryCountsForMerchant] does — the two learned
     *         tiers must agree about what "the same merchant" means or one would fire where the
     *         other did not.
     * Result: one row per nature the user has chosen for this merchant, ordered by count so a caller
     *         reading only the first still gets the settled answer; empty when they never have.
     * Input:  [profileId]; [normalisedMerchant] — trimmed and lower-cased by the caller.
     * Output: `List<MerchantNatureCountRow>`.
     * Changelog: 2026-08-10 — Created for issue 4.3.
     */
    @Query(
        "SELECT nature AS nature, COUNT(*) AS occurrences FROM transactions " +
            "WHERE profile_id = :profileId AND deleted_at_utc_millis IS NULL " +
            "AND nature IS NOT NULL " +
            "AND merchant IS NOT NULL AND LOWER(TRIM(merchant)) = :normalisedMerchant " +
            "GROUP BY nature ORDER BY occurrences DESC, nature",
    )
    suspend fun natureCountsForMerchant(
        profileId: String,
        normalisedMerchant: String,
    ): List<MerchantNatureCountRow>

    /**
     * Every nature override on this profile, by merchant (issue 4.3; SRS §8.3.1 step 4).
     *
     * Why:    the monthly breakdown classifies a whole month, and asking
     *         [natureCountsForMerchant] per row would be one query per transaction on the screen the
     *         user opens first. This is the same signal fetched once: the caller builds a map and
     *         hands each row its own slice.
     *
     *         **Deliberately not scoped to the month.** A correction the user made in March is still
     *         their decision about that merchant in August; scoping it would make a merchant's nature
     *         depend on which month happened to be on screen.
     * Result: one row per (merchant, nature) pair the user has chosen, merchant already normalised.
     * Input:  [profileId]. Output: `List<MerchantNatureOverrideRow>`.
     * Changelog: 2026-08-10 — Created for issue 4.3.
     */
    @Query(
        "SELECT LOWER(TRIM(merchant)) AS merchant, nature AS nature, COUNT(*) AS occurrences " +
            "FROM transactions " +
            "WHERE profile_id = :profileId AND deleted_at_utc_millis IS NULL " +
            "AND nature IS NOT NULL AND merchant IS NOT NULL AND TRIM(merchant) <> '' " +
            "GROUP BY LOWER(TRIM(merchant)), nature",
    )
    suspend fun natureOverridesByMerchant(profileId: String): List<MerchantNatureOverrideRow>

    /**
     * The typical size of a transaction in one category (issue 4.3; SRS §8.3.1 step 6).
     *
     * Why:    §8.3.1's context modifier asks whether an amount is "> 3× category median", and SQLite
     *         has no median function. The lower median — the middle row of an ordered list, or the
     *         earlier of the two middles — is one `ORDER BY … LIMIT 1 OFFSET n/2`, and picking the
     *         *lower* middle deterministically matters more than picking the average of two: it makes
     *         the flag reproducible (P-08) rather than dependent on a rounding choice.
     *
     *         **The count comes back with it, in one statement.** The rules require a minimum sample
     *         before a median means anything, and fetching the two separately would let a caller use
     *         a median it had not checked the size of — the second grocery run a user ever recorded
     *         compared against the first.
     *
     *         The **median of magnitudes**, not of signed amounts: expenses are negative, so a signed
     *         median would order them backwards and compare the largest spend against the smallest.
     * Result: the sample size and the median magnitude in paise (MNY-001); the median is `null` when
     *         the category has no transactions at all.
     * Input:  [profileId]; [categoryId]. Output: [CategoryMedianRow].
     * Changelog: 2026-08-10 — Created for issue 4.3.
     */
    @Query(
        "SELECT (SELECT COUNT(*) FROM transactions " +
            "WHERE profile_id = :profileId AND category_id = :categoryId " +
            "AND deleted_at_utc_millis IS NULL AND transfer_id IS NULL) AS sample_size, " +
            "(SELECT ABS(amount_minor) FROM transactions " +
            "WHERE profile_id = :profileId AND category_id = :categoryId " +
            "AND deleted_at_utc_millis IS NULL AND transfer_id IS NULL " +
            "ORDER BY ABS(amount_minor) LIMIT 1 OFFSET " +
            "(SELECT COUNT(*) FROM transactions " +
            "WHERE profile_id = :profileId AND category_id = :categoryId " +
            "AND deleted_at_utc_millis IS NULL AND transfer_id IS NULL) / 2) AS median_minor",
    )
    suspend fun categoryMedian(
        profileId: String,
        categoryId: String,
    ): CategoryMedianRow

    /**
     * Everything §8.3.1 needs about one transaction, in one row (issue 4.3).
     *
     * Why:    the decision order branches on the account's type, the counterpart account's type and
     *         the category's nature, which live in three tables. Fetching them per transaction would
     *         be three queries per row; this is the same information as one join, so a month costs
     *         one statement.
     *
     *         **The counterpart is found through `transfer_id`, not through a foreign key**, because
     *         a transfer is two `transactions` rows rather than a row with a destination (issue 3.2,
     *         DB-004). The subquery excludes the row itself and soft-deleted siblings; for anything
     *         that is not a transfer, `transfer_id` is null, the comparison matches nothing, and the
     *         column comes back null — which is exactly what "not a transfer leg" means to the engine.
     *
     *         **A split transaction is emitted as its lines, not as itself** (issue 4.4, ADR-0018).
     *         The `UNION ALL` is what makes that true: the first leg takes transactions with no live
     *         split lines, the second takes the lines. Each line carries its **own** amount and
     *         category while inheriting the parent's type, merchant, nature override and account
     *         types, because those are facts about the payment rather than about the line.
     *
     *         The first draft read `t.category_id` for every row, which for a split transaction is
     *         almost always null — so ₹4,000 divided between groceries and a gift arrived at §8.3.1
     *         with no category at all and fell to the low-confidence fallback. The natures were not
     *         merely imprecise; a fixture that was two-thirds NEED was being counted as entirely
     *         WANT, which inflates the true-spend figure Safe-to-Spend and the health score are
     *         calibrated against.
     * Result: the profile's rows in the date window, oldest first. **One row per unsplit transaction
     *         and one per live split line**, so `id` is the row's own identity — a split line's `id`,
     *         not its parent's. `transaction_id` is the parent either way.
     * Input:  [profileId]; [fromIsoDate] and [toIsoDate] — inclusive ISO `yyyy-MM-dd` bounds derived
     *         by the caller from the injected `Clock` (TIM-001/TIM-002).
     * Output: `Flow<List<NatureCandidateRow>>`.
     * Changelog: 2026-08-10 — Created for issue 4.3.
     *            2026-08-11 — Issue 4.4: split-line aware; gained `transaction_id` and
     *            `booked_on_iso_date` (the latter so the `UNION ALL` has an output column to order
     *            by).
     */
    @Query(
        "SELECT t.id AS id, t.id AS transaction_id, t.booked_on_iso_date AS booked_on_iso_date, " +
            "t.type AS type, t.amount_minor AS amount_minor, " +
            "t.nature AS override_nature, t.merchant AS merchant, t.category_id AS category_id, " +
            "c.nature AS category_nature, a.type AS account_type, " +
            "(SELECT a2.type FROM transactions t2 JOIN account a2 ON a2.id = t2.account_id " +
            "WHERE t2.transfer_id = t.transfer_id AND t2.id <> t.id " +
            "AND t2.deleted_at_utc_millis IS NULL LIMIT 1) AS counterpart_account_type " +
            "FROM transactions t " +
            "JOIN account a ON a.id = t.account_id " +
            "LEFT JOIN category c ON c.id = t.category_id " +
            "WHERE t.profile_id = :profileId AND t.deleted_at_utc_millis IS NULL " +
            "AND t.booked_on_iso_date >= :fromIsoDate AND t.booked_on_iso_date <= :toIsoDate " +
            "AND NOT EXISTS (SELECT 1 FROM transaction_splits s WHERE s.transaction_id = t.id " +
            "AND s.deleted_at_utc_millis IS NULL) " +
            "UNION ALL " +
            "SELECT s.id AS id, t.id AS transaction_id, t.booked_on_iso_date AS booked_on_iso_date, " +
            "t.type AS type, s.amount_minor AS amount_minor, " +
            "t.nature AS override_nature, t.merchant AS merchant, s.category_id AS category_id, " +
            "c.nature AS category_nature, a.type AS account_type, " +
            "(SELECT a2.type FROM transactions t2 JOIN account a2 ON a2.id = t2.account_id " +
            "WHERE t2.transfer_id = t.transfer_id AND t2.id <> t.id " +
            "AND t2.deleted_at_utc_millis IS NULL LIMIT 1) AS counterpart_account_type " +
            "FROM transaction_splits s " +
            "JOIN transactions t ON t.id = s.transaction_id " +
            "JOIN account a ON a.id = t.account_id " +
            "LEFT JOIN category c ON c.id = s.category_id " +
            "WHERE t.profile_id = :profileId AND t.deleted_at_utc_millis IS NULL " +
            "AND s.deleted_at_utc_millis IS NULL " +
            "AND t.booked_on_iso_date >= :fromIsoDate AND t.booked_on_iso_date <= :toIsoDate " +
            "ORDER BY booked_on_iso_date, id",
    )
    fun observeNatureCandidates(
        profileId: String,
        fromIsoDate: String,
        toIsoDate: String,
    ): Flow<List<NatureCandidateRow>>

    /**
     * What each category actually cost in a date window (issue 4.4; FR-BUD-003).
     *
     * Why:    this is the first query in the app that totals spending **per category**, and it has
     *         three things to get right that no existing aggregation does.
     *
     *         **It sums split lines, not their parent** (ADR-0009, ADR-0018). A ₹4,000 payment
     *         divided between groceries and a gift belongs to two budgets, and the parent row's
     *         `category_id` is null in that case — so reading the parent would credit ₹0 to both and
     *         quietly make every split invisible to the feature whose whole job is to notice
     *         spending. The `UNION ALL` takes transactions with **no live split lines** from the
     *         first leg and the lines themselves from the second, so a payment is counted exactly
     *         once whichever shape it has.
     *
     *         **Transfers are excluded in SQL** (`transfer_id IS NULL`), the same way `dayTotals`
     *         and `categoryMedian` do it. Moving ₹50,000 from savings to current is not spending,
     *         and a budget that counted it would be unusable in the month someone rebalances.
     *
     *         **Only `expense` rows count.** A refund arrives as `income` and would otherwise
     *         subtract from a budget through `ABS`, which is the wrong sign and the wrong idea —
     *         `ABS` is here to make an outflow positive, not to fold two directions together.
     *
     *         Future-dated rows (FR-TXN-010) are excluded by the **caller's window**, not here: the
     *         repository passes today as `toIsoDate` for a live month, and a whole month for a
     *         closed one. Putting a `date('now')` in this statement would be a wall-clock read in
     *         SQL, which is TIM-001's whole complaint.
     * Result: one row per category that had spending, plus one with a null `category_id` for
     *         uncategorised spending. **Categories with no spending are absent** — the repository
     *         fills them in as zero, because a `GROUP BY` cannot invent rows that do not exist.
     * Input:  [profileId]; [fromIsoDate] and [toIsoDate] — inclusive ISO `yyyy-MM-dd` bounds derived
     *         by the caller from the injected `Clock` (TIM-001/TIM-002).
     * Output: `Flow<List<CategorySpendRow>>`.
     * Changelog: 2026-08-11 — Created for issue 4.4.
     */
    @Query(
        "SELECT category_id AS category_id, SUM(amount_minor) AS spent_minor FROM (" +
            "SELECT t.category_id AS category_id, ABS(t.amount_minor) AS amount_minor " +
            "FROM transactions t " +
            "WHERE t.profile_id = :profileId AND t.deleted_at_utc_millis IS NULL " +
            "AND t.transfer_id IS NULL AND t.type = 'expense' " +
            "AND t.booked_on_iso_date >= :fromIsoDate AND t.booked_on_iso_date <= :toIsoDate " +
            "AND NOT EXISTS (SELECT 1 FROM transaction_splits s WHERE s.transaction_id = t.id " +
            "AND s.deleted_at_utc_millis IS NULL) " +
            "UNION ALL " +
            "SELECT s.category_id AS category_id, ABS(s.amount_minor) AS amount_minor " +
            "FROM transaction_splits s " +
            "JOIN transactions t ON t.id = s.transaction_id " +
            "WHERE t.profile_id = :profileId AND t.deleted_at_utc_millis IS NULL " +
            "AND s.deleted_at_utc_millis IS NULL " +
            "AND t.transfer_id IS NULL AND t.type = 'expense' " +
            "AND t.booked_on_iso_date >= :fromIsoDate AND t.booked_on_iso_date <= :toIsoDate" +
            ") GROUP BY category_id",
    )
    fun observeCategorySpend(
        profileId: String,
        fromIsoDate: String,
        toIsoDate: String,
    ): Flow<List<CategorySpendRow>>

    /**
     * The same totals as [observeCategorySpend], broken down by calendar month (issue 4.4).
     *
     * Why:    FR-BUD-002's suggestion is a **median over months**, so it needs the months kept
     *         apart. Running [observeCategorySpend] three times would be three statements and three
     *         windows for the caller to get right; grouping by the ISO date's `yyyy-MM` prefix costs
     *         one statement and cannot disagree with itself about where a month starts.
     *
     *         `substr(booked_on_iso_date, 1, 7)` works precisely because TIM-002 stores date-only
     *         fields as ISO strings — a midnight timestamp would need a timezone to slice, and would
     *         put a December 31st payment in January for anyone east of UTC.
     * Result: one row per (category, month) that had spending, ordered oldest first so the caller
     *         can take a window off the end without sorting.
     * Input:  [profileId]; [fromIsoDate] and [toIsoDate] — inclusive bounds spanning the months
     *         wanted, derived by the caller from the injected `Clock`.
     * Output: `Flow<List<MonthlyCategorySpendRow>>`.
     * Changelog: 2026-08-11 — Created for issue 4.4.
     */
    @Query(
        "SELECT category_id AS category_id, month_key AS month_key, " +
            "SUM(amount_minor) AS spent_minor FROM (" +
            "SELECT t.category_id AS category_id, ABS(t.amount_minor) AS amount_minor, " +
            "substr(t.booked_on_iso_date, 1, 7) AS month_key " +
            "FROM transactions t " +
            "WHERE t.profile_id = :profileId AND t.deleted_at_utc_millis IS NULL " +
            "AND t.transfer_id IS NULL AND t.type = 'expense' " +
            "AND t.booked_on_iso_date >= :fromIsoDate AND t.booked_on_iso_date <= :toIsoDate " +
            "AND NOT EXISTS (SELECT 1 FROM transaction_splits s WHERE s.transaction_id = t.id " +
            "AND s.deleted_at_utc_millis IS NULL) " +
            "UNION ALL " +
            "SELECT s.category_id AS category_id, ABS(s.amount_minor) AS amount_minor, " +
            "substr(t.booked_on_iso_date, 1, 7) AS month_key " +
            "FROM transaction_splits s " +
            "JOIN transactions t ON t.id = s.transaction_id " +
            "WHERE t.profile_id = :profileId AND t.deleted_at_utc_millis IS NULL " +
            "AND s.deleted_at_utc_millis IS NULL " +
            "AND t.transfer_id IS NULL AND t.type = 'expense' " +
            "AND t.booked_on_iso_date >= :fromIsoDate AND t.booked_on_iso_date <= :toIsoDate" +
            ") GROUP BY category_id, month_key ORDER BY month_key",
    )
    fun observeMonthlyCategorySpend(
        profileId: String,
        fromIsoDate: String,
        toIsoDate: String,
    ): Flow<List<MonthlyCategorySpendRow>>

    /**
     * The same row shape as [observeNatureCandidates], for one transaction (issue 4.3).
     * Why:    the detail sheet classifies exactly one transaction, and re-using the month query with
     *         a one-day window would be a date filter standing in for an id lookup — right today and
     *         wrong the moment a row is back-dated (ADR-0012).
     *
     *         **This one is deliberately NOT split-aware**, unlike [observeNatureCandidates] since
     *         issue 4.4. The detail sheet labels the *transaction* the user tapped, and it shows one
     *         label; a split payment resolving to two natures would have to show two, which is a
     *         screen nobody has designed (ADR-0018). A breakdown sums lines because a total must; a
     *         label names the thing it is attached to.
     * Result: the row, or `null` when the id names nothing live. `id` and `transaction_id` hold the
     *         same value here, because the row *is* the transaction.
     * Input:  [transactionId]. Output: `NatureCandidateRow?`.
     * Changelog: 2026-08-10 — Created for issue 4.3.
     *            2026-08-11 — Issue 4.4: projects the two columns [NatureCandidateRow] gained.
     */
    @Query(
        "SELECT t.id AS id, t.id AS transaction_id, t.booked_on_iso_date AS booked_on_iso_date, " +
            "t.type AS type, t.amount_minor AS amount_minor, " +
            "t.nature AS override_nature, t.merchant AS merchant, t.category_id AS category_id, " +
            "c.nature AS category_nature, a.type AS account_type, " +
            "(SELECT a2.type FROM transactions t2 JOIN account a2 ON a2.id = t2.account_id " +
            "WHERE t2.transfer_id = t.transfer_id AND t2.id <> t.id " +
            "AND t2.deleted_at_utc_millis IS NULL LIMIT 1) AS counterpart_account_type " +
            "FROM transactions t " +
            "JOIN account a ON a.id = t.account_id " +
            "LEFT JOIN category c ON c.id = t.category_id " +
            "WHERE t.id = :transactionId AND t.deleted_at_utc_millis IS NULL",
    )
    suspend fun natureCandidate(transactionId: String): NatureCandidateRow?

    /**
     * Records or clears the user's nature override (issue 4.3; §8.3, P-07).
     * Why:    a targeted `UPDATE` rather than a read-modify-write of the whole entity, for the reason
     *         [softDelete] gives: two screens changing different fields of one transaction must not
     *         overwrite each other's work.
     * Result: the rows changed — `1`, or `0` when the id names nothing live, which the repository
     *         turns into `NotFound` rather than a silent success.
     * Input:  [transactionId]; [nature] — a `CategoryNature.storedValue`, or `null` to return the
     *         transaction to whatever §8.3.1 currently decides; [updatedAtUtcMillis] — TIM-001.
     * Output: [Int].
     * Changelog: 2026-08-10 — Created for issue 4.3.
     */
    @Query(
        "UPDATE transactions SET nature = :nature, updated_at_utc_millis = :updatedAtUtcMillis " +
            "WHERE id = :transactionId AND deleted_at_utc_millis IS NULL",
    )
    suspend fun setNature(
        transactionId: String,
        nature: String?,
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
 * One transaction as the recurring detector sees it (issue 3.7; FR-TXN-006).
 *
 * Why:  four columns, not a whole [TransactionEntity]. The detector matches on merchant, amount and
 *       date and nothing else, and a projection this narrow means the query reads four columns
 *       instead of eighteen across a two-year window — but more importantly it means the engine
 *       *cannot* start matching on a field nobody agreed it should.
 * Result: mapped straight onto the engine's `RecurringCandidate` by the repository.
 * Changelog: 2026-08-05 — Created for issue 3.7.
 *
 * Input:  [id] — the transaction id; [merchant] — non-null and non-blank by the query's `WHERE`,
 *         but typed nullable because the column is; [amountMinor] — signed paise (MNY-001);
 *         [bookedOnIsoDate] — ISO `yyyy-MM-dd` (TIM-002).
 * Output: a Room projection.
 */
data class RecurringCandidateRow(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "merchant") val merchant: String?,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    @ColumnInfo(name = "booked_on_iso_date") val bookedOnIsoDate: String,
)

/**
 * How many times one merchant was filed under one category (issue 4.2; SRS §8.1(a)).
 *
 * Why:  two columns, not a whole [TransactionEntity]. The classifier's history tier decides on
 *       nothing but "which category, how often", and a projection this narrow means it *cannot*
 *       start deciding on an amount or a date — which is the same reason [RecurringCandidateRow] is
 *       four columns wide.
 * Result: mapped straight onto the engine's `MerchantHistoryRow` by the repository.
 * Changelog: 2026-08-10 — Created for issue 4.2.
 *
 * Input:  [categoryId] — non-null by the query's `WHERE`, but typed nullable because the column is;
 *         [occurrences] — live, non-transfer transactions with this merchant carrying it, always at
 *         least one because the row exists.
 * Output: a Room projection.
 */
data class MerchantCategoryCountRow(
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "occurrences") val occurrences: Int,
)

/**
 * How many times one merchant was re-natured to one nature (issue 4.3; SRS §8.3.1 step 4).
 *
 * Why:  the nature twin of [MerchantCategoryCountRow], kept the same shape on purpose — §8.3.1's
 *       learned step and §8.1's behave identically, and one reader should learn the rule once.
 * Result: mapped straight onto the engine's `NatureHistoryRow` by the repository.
 * Changelog: 2026-08-10 — Created for issue 4.3.
 *
 * Input:  [nature] — a `CategoryNature.storedValue`, non-null by the query's `WHERE` but typed
 *         nullable because the column is; [occurrences] — always at least one, because the row exists.
 * Output: a Room projection.
 */
data class MerchantNatureCountRow(
    @ColumnInfo(name = "nature") val nature: String?,
    @ColumnInfo(name = "occurrences") val occurrences: Int,
)

/**
 * The same counts, carrying the merchant they belong to (issue 4.3; SRS §8.3.1 step 4).
 *
 * Why:  the monthly breakdown needs every merchant's overrides at once rather than one merchant's,
 *       so this is [MerchantNatureCountRow] plus the key the caller groups by. Two projections
 *       rather than one shared nullable-merchant shape, because a row whose merchant *could* be
 *       null would push a null check into the grouping code for a case the query excludes.
 * Result: grouped into a map by the repository.
 * Changelog: 2026-08-10 — Created for issue 4.3.
 *
 * Input:  [merchant] — already trimmed and lower-cased by the query; [nature]; [occurrences].
 * Output: a Room projection.
 */
data class MerchantNatureOverrideRow(
    @ColumnInfo(name = "merchant") val merchant: String?,
    @ColumnInfo(name = "nature") val nature: String?,
    @ColumnInfo(name = "occurrences") val occurrences: Int,
)

/**
 * A category's sample size and median magnitude (issue 4.3; SRS §8.3.1 step 6).
 *
 * Why:  the two travel together because using one without the other is the mistake — a median over
 *       two transactions is not a typical amount, it is the two amounts. Carrying [sampleSize] here
 *       means the caller cannot reach the median without having been handed the reason to distrust it.
 * Result: the input to §8.3.1's `> 3x category median` comparison.
 * Changelog: 2026-08-10 — Created for issue 4.3.
 *
 * Input:  [sampleSize] — live, non-transfer transactions in the category; [medianMinor] — the lower
 *         median of their **magnitudes** in paise (MNY-001), `null` when the category has none.
 * Output: a Room projection.
 */
data class CategoryMedianRow(
    @ColumnInfo(name = "sample_size") val sampleSize: Int,
    @ColumnInfo(name = "median_minor") val medianMinor: Long?,
)

/**
 * One category's spending in a window (issue 4.4; FR-BUD-003).
 *
 * Why:  a projection rather than a `Map<String, Long>` so the null category — genuinely
 *       uncategorised spending — is a value the caller has to handle rather than a key it may
 *       forget. Budgets are per category, and the money that belongs to none of them is exactly the
 *       money a budget screen must not silently drop.
 * Result: the total, positive, already summed across split lines.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 *
 * Input:  [categoryId] — the category, or `null` for uncategorised; [spentMinor] — paise (MNY-001),
 *         always positive because the query takes `ABS` of an outflow.
 * Output: a Room projection.
 */
data class CategorySpendRow(
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "spent_minor") val spentMinor: Long,
)

/**
 * One category's spending in one calendar month (issue 4.4; FR-BUD-002).
 *
 * Why:  the suggestion is a median **over months**, so the months have to arrive separated and
 *       labelled — a flat total cannot be turned back into the series it came from.
 * Result: a point in the history the median is taken over.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 *
 * Input:  [categoryId] — the category, or `null` for uncategorised; [monthKey] — ISO `yyyy-MM`,
 *         sliced from `booked_on_iso_date` (TIM-002); [spentMinor] — paise, positive.
 * Output: a Room projection.
 */
data class MonthlyCategorySpendRow(
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "month_key") val monthKey: String,
    @ColumnInfo(name = "spent_minor") val spentMinor: Long,
)

/**
 * One classifiable amount as §8.3.1's decision order sees it (issue 4.3).
 *
 * Why:  eleven columns across three tables, and not one more. The decision order branches on the
 *       account, the counterpart account, the category's nature, the type, the amount and the
 *       user's own override — a projection this narrow means the engine *cannot* start deciding on
 *       a note, which is the same narrowing [RecurringCandidateRow] applies to the recurring
 *       detector.
 *
 *       **A row is not always a transaction.** Since 4.4 the query emits one row per *live split
 *       line* where a transaction has them, and one row per transaction where it does not, so a
 *       payment split across two categories is classified as the two things it bought rather than
 *       falling to the uncategorised fallback. [id] is therefore the row's own identity — a split
 *       line's id, not its parent's — and [transactionId] is what the caller groups by when it needs
 *       the payment back whole.
 * Result: mapped onto the engine's `NatureInput` by the repository.
 * Changelog: 2026-08-10 — Created for issue 4.3.
 *            2026-08-11 — Issue 4.4: split-line aware; gained [transactionId] and [bookedOnIsoDate].
 *
 * Input:  [id] — the split line's id, or the transaction's when it has no lines; [transactionId] —
 *         the parent either way; [bookedOnIsoDate] — ISO `yyyy-MM-dd` (TIM-002), the `UNION ALL`'s
 *         sort key; [type] — a `TransactionType.storedValue`; [amountMinor] — signed paise
 *         (MNY-001), the **line's** amount for a split line; [overrideNature] — the user's
 *         correction, `null` when they have not made one, which is the ordinary case; [merchant] —
 *         for the learned step, inherited from the parent; [categoryId] — the line's own category
 *         for a split line; [categoryNature] — a `CategoryNature.storedValue`, `null` when the row
 *         has no category or its category was deleted; [accountType] — an `AccountType.storedValue`;
 *         [counterpartAccountType] — the other leg's account type, `null` for anything that is not
 *         a transfer leg.
 * Output: a Room projection.
 */
data class NatureCandidateRow(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "transaction_id") val transactionId: String,
    @ColumnInfo(name = "booked_on_iso_date") val bookedOnIsoDate: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    @ColumnInfo(name = "override_nature") val overrideNature: String?,
    @ColumnInfo(name = "merchant") val merchant: String?,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "category_nature") val categoryNature: String?,
    @ColumnInfo(name = "account_type") val accountType: String,
    @ColumnInfo(name = "counterpart_account_type") val counterpartAccountType: String?,
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

    /**
     * Counts every category row a profile has, **including soft-deleted ones** (issue 4.1).
     *
     * Why:    this is what decides whether `ensureSeeded` writes. Counting only live rows would make
     *         a user who deleted all fifteen defaults get them back on the next cold start — the app
     *         overruling a decision they made on purpose (P-07). The question being asked is "has
     *         this profile ever been seeded?", and a soft-deleted row is proof that it has.
     * Result: the total, zero for a profile that has never been seeded.
     * Input:  [profileId]. Output: [Int].
     * Changelog: 2026-08-08 — Created for issue 4.1.
     */
    @Query("SELECT COUNT(*) FROM category WHERE profile_id = :profileId")
    suspend fun countForProfile(profileId: String): Int

    /**
     * Reads one category, soft-deleted or not.
     * Why:    every write in `CategoryRepository` reads the row first — to prove it exists, and to
     *         keep the fields it is not changing. Excluding deleted rows here would turn "edit a row
     *         that was deleted on another screen" into "row not found", which is the right outcome
     *         but the wrong error; the repository decides that, not the query.
     * Result: the row, or `null`. Input: [id]. Output: `CategoryEntity?`.
     * Changelog: 2026-08-08 — Created for issue 4.1.
     */
    @Query("SELECT * FROM category WHERE id = :id")
    suspend fun findById(id: String): CategoryEntity?

    /**
     * Reads a profile's live categories once, rather than observing them.
     * Why:    the uniqueness and nesting checks in `CategoryRepository` need the current taxonomy
     *         inside the same transaction as the write that depends on it. Collecting the Flow for
     *         one value would read outside that transaction, which is the window where two saves
     *         race into two categories with the same name.
     * Result: the live rows. Input: [profileId]. Output: `List<CategoryEntity>`.
     * Changelog: 2026-08-08 — Created for issue 4.1.
     */
    @Query("SELECT * FROM category WHERE profile_id = :profileId AND deleted_at_utc_millis IS NULL")
    suspend fun liveForProfile(profileId: String): List<CategoryEntity>

    /**
     * Counts a category's live children (issue 4.1).
     * Why:    §8's taxonomy is one level deep, so a category with children may not be deleted out
     *         from under them — the alternative is orphan rows pointing at a `parent_id` that no
     *         longer resolves, which no query would report and no screen would show.
     * Result: the count. Input: [parentId]. Output: [Int].
     * Changelog: 2026-08-08 — Created for issue 4.1.
     */
    @Query(
        "SELECT COUNT(*) FROM category WHERE parent_id = :parentId AND deleted_at_utc_millis IS NULL",
    )
    suspend fun countLiveChildren(parentId: String): Int

    /** Updates a category in place. Input: [category]. Output: none. */
    @Update
    suspend fun update(category: CategoryEntity)
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

    /**
     * Writes one budget (issue 4.4; FR-BUD-001).
     * Why: [upsertAll] exists because quick setup writes three envelopes at once; the budget
     *         editor writes exactly one, and passing a singleton list to say so reads as an
     *         accident. `REPLACE` is safe here only because the id is **derived** from the profile,
     *         category and period (`categoryBudgetId`), so saving the same budget twice updates one
     *         row rather than minting a second (P-08).
     * Result: the row is created or replaced. Input: [budget]. Output: none.
     * Changelog: 2026-08-11 — Created for issue 4.4.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(budget: BudgetEntity)

    /**
     * Reads one budget by id, **including tombstones** (issue 4.4).
     * Why:    the same reasoning `CategoryDao.findById` gives — filtering the soft-deleted here would
     *         turn "you are editing a budget someone deleted on another screen" into "row not
     *         found", which is the right outcome but the wrong error. The repository decides that.
     * Result: the row, or `null`. Input: [id]. Output: `BudgetEntity?`.
     * Changelog: 2026-08-11 — Created for issue 4.4.
     */
    @Query("SELECT * FROM budget WHERE id = :id")
    suspend fun findById(id: String): BudgetEntity?

    /**
     * Reads a profile's live per-category budgets for a period, once (issue 4.4; FR-BUD-001).
     * Why:    rollover needs **last** month's budgets and last month's spend to work out what was
     *         left over, and it needs them inside the same read as this month's — collecting a Flow
     *         for one value would be a subscription standing in for a lookup.
     *
     *         `category_id IS NOT NULL` is the filter that separates these from quick setup's
     *         nature-level envelopes, which share the table and are read by
     *         `observeLatestEnvelopes` instead. One table, two shapes, and neither read sees the
     *         other's rows (ADR-0004).
     * Result: the live per-category rows for that period. Input: [profileId];
     *         [periodStartIsoDate] — the first of the month, ISO (TIM-002).
     * Output: `List<BudgetEntity>`.
     * Changelog: 2026-08-11 — Created for issue 4.4.
     */
    @Query(
        "SELECT * FROM budget WHERE profile_id = :profileId " +
            "AND period_start_iso_date = :periodStartIsoDate " +
            "AND category_id IS NOT NULL AND deleted_at_utc_millis IS NULL",
    )
    suspend fun categoryBudgetsForPeriod(
        profileId: String,
        periodStartIsoDate: String,
    ): List<BudgetEntity>

    /**
     * Observes a profile's live per-category budgets for a period (issue 4.4; FR-BUD-001).
     * Why:    the budget screen has to move when a transaction is added on another screen, which is
     *         what makes this a Flow rather than the suspend read above.
     * Result: the live per-category rows, re-emitted on every write.
     * Input:  [profileId]; [periodStartIsoDate]. Output: `Flow<List<BudgetEntity>>`.
     * Changelog: 2026-08-11 — Created for issue 4.4.
     */
    @Query(
        "SELECT * FROM budget WHERE profile_id = :profileId " +
            "AND period_start_iso_date = :periodStartIsoDate " +
            "AND category_id IS NOT NULL AND deleted_at_utc_millis IS NULL " +
            "ORDER BY category_id",
    )
    fun observeCategoryBudgets(
        profileId: String,
        periodStartIsoDate: String,
    ): Flow<List<BudgetEntity>>
}

/**
 * Records and reads what the user has already been told about their budgets (issue 4.5; FR-BUD-004).
 *
 * Why:  this DAO exists to answer one question — "has this person already heard about this?" — and
 *       to make the answer binding. Every write goes through [insertIfNew], whose `IGNORE` leans on
 *       the table's unique index rather than on the caller checking first, so a duplicate
 *       notification is impossible even if two workers run at once.
 * What: one guarded insert, one live read for the screen, one point read for the worker.
 * Result: at most one notification per budget, per month, per band (`RULE-BUD-ALERT`).
 * Changelog: 2026-08-13 — Created for issue 4.5.
 */
@Dao
interface BudgetAlertDao {
    /**
     * Records that the user was notified, unless they already had been.
     *
     * Why:    `IGNORE`, and this is the whole design. The alternative — read the existing bands,
     *         decide, then insert — has a window between the read and the write, and a worker that
     *         retried after a partial failure would sit in exactly that window. Here the database
     *         refuses the duplicate, so "told once" is a property of the schema rather than a
     *         property of the code being careful. `REPLACE` would be the opposite of what is wanted:
     *         it would happily overwrite the first alert and let the caller believe it was new.
     * Result: `-1` when the row already existed (nothing was written, and nothing should be sent);
     *         the new rowid otherwise.
     * Input:  [alert]. Output: [Long] rowid, or `-1`.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(alert: BudgetAlertEntity): Long

    /**
     * Observes every alert recorded for a profile's month.
     * Why:    the in-app banner is not deduplicated the way the notification is — a band that has
     *         been crossed stays true until the month ends, and a banner that vanished because the
     *         notification had already been sent would hide the state it exists to show (P-02).
     * Result: the month's rows, re-emitted on every write.
     * Input:  [profileId]; [monthStartIsoDate] — TIM-002. Output: `Flow<List<BudgetAlertEntity>>`.
     */
    @Query(
        "SELECT * FROM budget_alert WHERE profile_id = :profileId " +
            "AND month_start_iso_date = :monthStartIsoDate ORDER BY budget_id, band",
    )
    fun observeForMonth(
        profileId: String,
        monthStartIsoDate: String,
    ): Flow<List<BudgetAlertEntity>>

    /**
     * Reads the month's alerts once.
     * Why:    the worker needs to know what has already been sent before it decides what to send,
     *         and collecting a Flow for one value would be a subscription standing in for a lookup —
     *         the same argument `BudgetDao.categoryBudgetsForPeriod` makes.
     * Result: the month's rows. Input: [profileId]; [monthStartIsoDate]. Output: the list.
     */
    @Query(
        "SELECT * FROM budget_alert WHERE profile_id = :profileId " +
            "AND month_start_iso_date = :monthStartIsoDate",
    )
    suspend fun forMonth(
        profileId: String,
        monthStartIsoDate: String,
    ): List<BudgetAlertEntity>
}

/**
 * Records and reads whether a closed month's budget review has been shown (issue 4.6; §5.5).
 *
 * Why:  the same one-question shape as [BudgetAlertDao] — "has this been claimed?" — and the same
 *       reason for [insertIfNew]: the table's unique index on (profile, month) is what makes
 *       "reviewed once" true, not the caller checking first.
 * What: one guarded insert, one live read the repository folds into `observeReview`.
 * Result: at most one open review card per profile, per closed month (`RULE-BUD-REVIEW`).
 * Changelog: 2026-08-15 — Created for issue 4.6.
 */
@Dao
interface BudgetReviewDao {
    /**
     * Records that this month's review has been dismissed or acted on, unless it already was.
     * Result: `-1` when a claim already existed; the new rowid otherwise.
     * Input:  [review]. Output: [Long] rowid, or `-1`.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(review: BudgetReviewEntity): Long

    /**
     * Observes whether a profile's reviewed month has been claimed.
     * Why:    `observeReview` combines this with the freshly computed [BudgetReviewEntity] to decide
     *         whether the card should still show — a non-null row collapses the result to `null`,
     *         the same fold `pendingAlerts` does with `forMonth`'s rows.
     * Result: the claim row, or `null` when nothing has been claimed yet.
     * Input:  [profileId]; [monthStartIsoDate] — the reviewed month, TIM-002.
     * Output: `Flow<BudgetReviewEntity?>`.
     */
    @Query(
        "SELECT * FROM budget_review WHERE profile_id = :profileId " +
            "AND month_start_iso_date = :monthStartIsoDate LIMIT 1",
    )
    fun observeForMonth(
        profileId: String,
        monthStartIsoDate: String,
    ): Flow<BudgetReviewEntity?>
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
            "AND deleted_at_utc_millis IS NULL AND dismissed_at_utc_millis IS NULL " +
            "ORDER BY next_due_iso_date, id",
    )
    fun observeForProfile(profileId: String): Flow<List<RecurringRuleEntity>>

    /**
     * Observes the names of every rule a profile has *decided about* (issue 3.7; FR-TXN-006).
     *
     * Why:    FR-TXN-006's detector must propose a merchant only once. What stops it re-proposing
     *         is not code but data — this list — which is what "decisions feed back as data, not
     *         code" means in practice.
     *
     *         **No filter at all, deliberately.** Confirmed, still-pending, dismissed and
     *         soft-deleted rows all count: each one is a merchant the user has already been shown.
     *         Filtering tombstones here would resurrect a proposal the moment someone deleted the
     *         rule it produced, which reads to the user as the app forgetting.
     * Result: the merchant names to exclude from detection, lower-cased so the comparison matches
     *         the engine's own normalisation without a second pass in Kotlin.
     * Input:  [profileId]. Output: `Flow<List<String>>`; empty for a profile with no rules.
     */
    @Query("SELECT DISTINCT LOWER(name) FROM recurring_rule WHERE profile_id = :profileId AND name IS NOT NULL")
    fun observeDecidedNames(profileId: String): Flow<List<String>>

    /**
     * Records that the user rejected a proposed series (issue 3.7; FR-TXN-006).
     * Why:    P-07 — the app proposes and the user decides, so "no" has to be as durable as "yes".
     *         Stamped rather than flagged so a future review can say *when* the user declined.
     * Result: the rule leaves [observeForProfile] and its merchant stays in [observeDecidedNames].
     * Input:  [id]; [dismissedAtUtcMillis] and [updatedAtUtcMillis] — from the injected `Clock`
     *         (TIM-001). Output: rows affected.
     */
    @Query(
        "UPDATE recurring_rule SET dismissed_at_utc_millis = :dismissedAtUtcMillis, " +
            "updated_at_utc_millis = :updatedAtUtcMillis WHERE id = :id",
    )
    suspend fun dismiss(
        id: String,
        dismissedAtUtcMillis: Long,
        updatedAtUtcMillis: Long,
    ): Int

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
     * Result: rows removed from `budget_alert`. Input: [profileId]. Output: the count.
     *
     * Added by issue 4.5, which introduced the table. Called **before** [deleteBudgets]: an alert is
     * a child of a budget, and clearing the parents first would orphan it if the caller failed in
     * between — the ordering argument [deleteTransactionSplits] makes.
     */
    @Query("DELETE FROM budget_alert WHERE profile_id = :profileId")
    suspend fun deleteBudgetAlerts(profileId: String): Int

    /**
     * Result: rows removed from `budget_review`. Input: [profileId]. Output: the count.
     *
     * Added by issue 4.6, which introduced the table. Unlike [deleteBudgetAlerts] there is no
     * ordering requirement against [deleteBudgets] — `budget_review` carries no `budget_id`, since
     * it claims a whole reviewed month rather than one budget (ADR-0020) — but it is still a
     * profile-scoped table the wipe must reach, per [countRowsFor].
     */
    @Query("DELETE FROM budget_review WHERE profile_id = :profileId")
    suspend fun deleteBudgetReviews(profileId: String): Int

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

    /**
     * Removes the demo's attachment rows (issue 3.8; ADR-0006).
     * Why:    a table the demo wipe cannot reach is residue, and this one is the worst kind — a
     *         receipt row surviving a wipe would point at a blob the erase also has to remove.
     *         **Deleting the ciphertext is the repository's job, not this query's**: a DAO cannot
     *         touch the filesystem, so the wipe reads the file names first and erases them itself.
     * Result: rows removed from `attachments`. Input: [profileId]. Output: the count.
     */
    @Query("DELETE FROM attachments WHERE profile_id = :profileId")
    suspend fun deleteAttachments(profileId: String): Int

    /**
     * Lists the blob file names the wipe still has to erase (issue 3.8).
     * Why:    read **before** [deleteAttachments], because after it there is nothing left to say
     *         which files on disk belonged to this profile — and an orphaned ciphertext blob is data
     *         a "delete everything" did not delete (P-01). Includes tombstoned rows: a row whose
     *         blob failed to erase earlier is exactly the one this must catch.
     * Result: the file names. Input: [profileId]. Output: `List<String>`.
     */
    @Query("SELECT file_name FROM attachments WHERE profile_id = :profileId")
    suspend fun attachmentFileNames(profileId: String): List<String>

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
     * Result: rows removed from `sms_draft`. Input: [profileId]. Output: the count.
     *
     * Issue 3.9: **not written by `enter()`**, but produced while the user browses — the daily scan
     * writes drafts against whichever profile is active, so a demo session on a phone whose owner
     * opted in accumulates inferences drawn from their *real* inbox under the demo profile. A
     * profile-scoped table the wipe does not reach is the residue ADR-0006 forbids, and this one
     * would be residue about the user's spending.
     *
     * **All statuses, not just pending.** `SmsRepository.onConsentRevoked` keeps accepted and
     * dismissed rows because they are decisions the user made; the demo wipe keeps nothing, because
     * the profile they belonged to is being destroyed.
     */
    @Query("DELETE FROM sms_draft WHERE profile_id = :profileId")
    suspend fun deleteSmsDrafts(profileId: String): Int

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
     * Input:  [profileId]. Output: the total row count across all fourteen tables.
     */
    @Query(
        "SELECT (SELECT COUNT(*) FROM profile WHERE id = :profileId) + " +
            "(SELECT COUNT(*) FROM account WHERE profile_id = :profileId) + " +
            "(SELECT COUNT(*) FROM transactions WHERE profile_id = :profileId) + " +
            "(SELECT COUNT(*) FROM transaction_splits WHERE profile_id = :profileId) + " +
            "(SELECT COUNT(*) FROM transaction_tags WHERE profile_id = :profileId) + " +
            "(SELECT COUNT(*) FROM attachments WHERE profile_id = :profileId) + " +
            "(SELECT COUNT(*) FROM tags WHERE profile_id = :profileId) + " +
            "(SELECT COUNT(*) FROM category WHERE profile_id = :profileId) + " +
            "(SELECT COUNT(*) FROM budget WHERE profile_id = :profileId) + " +
            "(SELECT COUNT(*) FROM budget_alert WHERE profile_id = :profileId) + " +
            "(SELECT COUNT(*) FROM budget_review WHERE profile_id = :profileId) + " +
            "(SELECT COUNT(*) FROM recurring_rule WHERE profile_id = :profileId) + " +
            "(SELECT COUNT(*) FROM net_worth_snapshot WHERE profile_id = :profileId) + " +
            "(SELECT COUNT(*) FROM sms_draft WHERE profile_id = :profileId)",
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

    /**
     * The earliest stored day whose figure a later write has invalidated (ADR-0012).
     *
     * Why:    a snapshot is frozen on purpose — a *trend* must not move under the user (FR-ACC-005),
     *         and `snapshotUpToToday` therefore never rewrites a day it has already recorded. That
     *         is right for the ordinary case and wrong for exactly one: a transaction **booked into
     *         a day that has already been snapshotted**. Back-dating a receipt, or deleting an old
     *         row, changes what those days were worth, and without this nothing would ever correct
     *         them.
     *
     *         **The staleness is derived, not tracked.** No flag, no queue, no "dirty" column a
     *         write path could forget to set: a stored day is wrong iff some transaction booked on or
     *         before it was written or removed *after* that day's figure was computed. Both halves
     *         are needed — `updated_at` catches a row created or edited, and `deleted_at` catches one
     *         removed, because `softDelete` deliberately does not touch `updated_at`. Tombstoned rows
     *         are therefore **not** filtered out here, unlike every other query in this file: a
     *         deleted transaction is precisely the change being looked for.
     * Result: the earliest affected `as_of_iso_date`, or `null` when the stored history is correct —
     *         which is the normal answer, and the one this returns on almost every run.
     * Input:  [profileId]. Output: `String?` — ISO `yyyy-MM-dd` (TIM-002).
     */
    @Query(
        "SELECT MIN(s.as_of_iso_date) FROM net_worth_snapshot s " +
            "JOIN transactions t ON t.profile_id = s.profile_id " +
            "AND t.booked_on_iso_date <= s.as_of_iso_date " +
            "WHERE s.profile_id = :profileId AND s.deleted_at_utc_millis IS NULL " +
            "AND (t.updated_at_utc_millis > s.computed_at_utc_millis " +
            "OR t.deleted_at_utc_millis > s.computed_at_utc_millis)",
    )
    suspend fun findEarliestStaleDay(profileId: String): String?
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

/**
 * Reads and writes the files linked to a transaction (issue 3.8; FR-OCR-005).
 *
 * Why:  four queries, and the shape of two of them is the requirement. [softDelete] tombstones the
 *       row while the caller erases the blob, which together are FR-OCR-005's "delete image while
 *       keeping the transaction" — the transaction is never touched. And [findById] exists **without
 *       a soft-delete filter**, because erasing a blob needs the file name of a row that may already
 *       be tombstoned; every other read here filters, as §20 requires.
 * Result: the receipt a transaction carries, and the ability to lose it on purpose.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 */
@Dao
interface AttachmentDao {
    /**
     * Inserts an attachment, replacing any with the same id.
     * Why:    REPLACE with a **derived** id, the mechanism `netWorthSnapshotId` and `recurringRuleId`
     *         already use: re-scanning the same receipt onto the same transaction updates one row
     *         rather than accumulating a second pointing at an orphaned blob.
     * Result: the row is present. Input: [attachment]. Output: none.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(attachment: AttachmentEntity)

    /**
     * Observes the live attachments on one transaction.
     * Result: emits on every change; empty when the image was deleted or never existed — both real
     *         answers rather than errors.
     * Input:  [transactionId]. Output: `Flow<List<AttachmentEntity>>`, oldest first.
     */
    @Query(
        "SELECT * FROM attachments WHERE transaction_id = :transactionId " +
            "AND deleted_at_utc_millis IS NULL ORDER BY created_at_utc_millis",
    )
    fun observeForTransaction(transactionId: String): Flow<List<AttachmentEntity>>

    /**
     * Finds one attachment by id, **tombstoned or not**.
     * Why:    the one query here that does not filter `deleted_at_utc_millis`, deliberately. Erasing
     *         a blob needs its file name, and a caller retrying a failed erase would otherwise be
     *         unable to find the row it is trying to finish deleting — leaving ciphertext on disk
     *         with nothing pointing at it, which is the opposite of what FR-OCR-005 asks for.
     * Result: the row, or `null`. Input: [id]. Output: `AttachmentEntity?`.
     */
    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun findById(id: String): AttachmentEntity?

    /**
     * Tombstones one attachment (FR-OCR-005).
     * Why:    the transaction is untouched — that is the requirement's whole second half. The blob
     *         itself is erased by the caller; a row with no blob is recoverable-looking and wrong, so
     *         the repository does both inside one operation.
     * Result: the number of rows stamped, `0` when the id names nothing.
     * Input:  [id]; [deletedAtUtcMillis] — from the injected `Clock` (TIM-001). Output: [Int].
     */
    @Query("UPDATE attachments SET deleted_at_utc_millis = :deletedAtUtcMillis WHERE id = :id")
    suspend fun softDelete(
        id: String,
        deletedAtUtcMillis: Long,
    ): Int
}

/**
 * Reads and writes the drafts parsed from bank alerts (issue 3.9; §18, §23, P-01).
 *
 * Why:  the queries are shaped by two obligations rather than by convenience. **Revocation has to
 *       bite** — [deletePending] is what makes the consent revocable in the sense P-01 means, which
 *       is that the data goes, not merely that the toggle flips. And **a decision has to stick**:
 *       [findBySmsId] is how a re-scan discovers that an alert has already been judged, so a
 *       dismissed message is never proposed twice.
 *
 *       Nothing here selects a message body, because the table has no column for one — see
 *       [com.aicfo.core.database.entity.SmsDraftEntity].
 * Result: the review screen's list, and the guarantees around it.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
@Dao
interface SmsDraftDao {
    /**
     * Inserts a draft, ignoring one whose alert is already recorded.
     * Why:    **IGNORE, not REPLACE**, and it is the difference between a working dismissal and a
     *         broken one. The unique index is `(profile_id, sms_id)`, so a re-scan of an already
     *         judged alert conflicts — and REPLACE would overwrite the user's `dismissed` row with a
     *         fresh `pending` one, re-proposing exactly what they said no to. IGNORE keeps the
     *         decision. (Contrast `AttachmentDao.upsert`, where REPLACE is right because re-scanning
     *         a receipt genuinely supersedes the previous read.)
     * Result: the row is present, either newly inserted or as it already was.
     * Input:  [draft]. Output: the new rowid, or `-1` when the insert was ignored.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(draft: SmsDraftEntity): Long

    /**
     * Observes this profile's drafts awaiting a decision.
     * Result: emits on every change; empty when nothing is pending, which is the ordinary state.
     * Input:  [profileId]. Output: `Flow<List<SmsDraftEntity>>`, newest alert first — the order the
     *         user thinks in, since the message that just arrived is the one they are looking for.
     */
    @Query(
        "SELECT * FROM sms_draft WHERE profile_id = :profileId AND status = 'pending' " +
            "ORDER BY sms_id DESC",
    )
    fun observePending(profileId: String): Flow<List<SmsDraftEntity>>

    /**
     * Finds a draft by id.
     * Result: the row, or `null`. Input: [id]. Output: `SmsDraftEntity?`.
     */
    @Query("SELECT * FROM sms_draft WHERE id = :id")
    suspend fun findById(id: String): SmsDraftEntity?

    /**
     * Finds the draft already recorded for one alert, whatever the user decided about it.
     * Why:    **unfiltered by status on purpose.** A scan asks "have I judged this message?", not
     *         "is this message pending?" — filtering would make a dismissed alert look unseen and
     *         re-propose it on the next scan.
     * Result: the row, or `null` when this alert has never been drafted.
     * Input:  [profileId]; [smsId]. Output: `SmsDraftEntity?`.
     */
    @Query("SELECT * FROM sms_draft WHERE profile_id = :profileId AND sms_id = :smsId")
    suspend fun findBySmsId(
        profileId: String,
        smsId: Long,
    ): SmsDraftEntity?

    /**
     * Records what the user decided about one draft.
     * Result: the number of rows changed, `0` when the id names nothing.
     * Input:  [id]; [status] — `accepted` or `dismissed`; [transactionId] — the row it became, or
     *         `null` for a dismissal; [updatedAtUtcMillis] — from the injected `Clock` (TIM-001).
     * Output: [Int].
     */
    @Query(
        "UPDATE sms_draft SET status = :status, transaction_id = :transactionId, " +
            "updated_at_utc_millis = :updatedAtUtcMillis WHERE id = :id",
    )
    suspend fun setStatus(
        id: String,
        status: String,
        transactionId: String?,
        updatedAtUtcMillis: Long,
    ): Int

    /**
     * Deletes every undecided draft for a profile — what revoking the consent does (P-01).
     *
     * Why:    a **hard** delete, and the only one in this schema. Everywhere else a tombstone is
     *         right because the row is the user's financial history and a sync may need to know it
     *         was removed. This row is not history: it is a proposal derived from a message the user
     *         has just withdrawn permission to read. Leaving a tombstone would mean the app still
     *         held what it inferred from their inbox after being told to stop — which is what P-01's
     *         "revocable" exists to prevent.
     *
     *         **Only the pending ones.** An accepted draft has become a transaction the user
     *         deliberately saved; deleting its provenance would leave a row in the ledger that could
     *         no longer explain where it came from (AI-ARC-003). A dismissed one is kept for the
     *         same reason `insertIfNew` ignores conflicts — so that if the consent is granted again,
     *         a decision already made is not re-asked.
     * Result: the number of rows deleted. Input: [profileId]. Output: [Int].
     */
    @Query("DELETE FROM sms_draft WHERE profile_id = :profileId AND status = 'pending'")
    suspend fun deletePending(profileId: String): Int

    /**
     * Deletes every undecided draft, **for every profile** — what revoking the consent does (P-01).
     *
     * Why:    the **only unscoped query in this schema**, and it is unscoped on purpose. Every other
     *         read and write here is bounded by `profile_id` so no query can span the demo and the
     *         user's own data (ADR-0006). But the SMS consent is not per profile — there is one
     *         `ConsentFeature.SMS_PARSING` for the device — so a revocation scoped to whichever
     *         profile happened to be active would leave the other's inferences on disk. A user who
     *         revoked while browsing the demo would keep every draft drawn from their real inbox,
     *         which is precisely the outcome "revocable" is supposed to prevent.
     *
     *         Safe to leave unscoped because of what it deletes rather than where: only `pending`
     *         rows, which are proposals the app made and nobody accepted. No row the user created,
     *         confirmed or dismissed is reachable from here.
     * Result: the number of rows deleted. Input: none. Output: [Int].
     */
    @Query("DELETE FROM sms_draft WHERE status = 'pending'")
    suspend fun deleteAllPending(): Int
}
