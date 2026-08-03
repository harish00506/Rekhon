package com.aicfo.data.repository

import androidx.room.withTransaction
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.IdGenerator
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.dao.AccountWithBalance
import com.aicfo.core.database.entity.AccountEntity
import com.aicfo.core.database.entity.TransactionEntity
import com.aicfo.core.model.Account
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import com.aicfo.core.model.Reconciliation
import com.aicfo.core.model.TransactionType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Create, read, update, archive and soft-delete for accounts (issue 2.5; FR-ACC-001, FR-ACC-007).
 *
 * Why:  the `account` table has existed since issue 1.6 and until now exactly one thing wrote to
 *       it — the demo dataset. A real user had no way to create an account at all, which left
 *       FR-ONB-001's fourth step unbuildable, issue 2.3's recurring rules with a null `account_id`,
 *       and the net-worth engine with nothing but fabricated rows to compute over. This is the
 *       class that closes all three.
 * What: one observation query, one lookup, and four writes.
 * Result: accounts are the user's to manage, and every balance shown is derived rather than stated.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 *
 * **The only class in this issue allowed to touch a DAO (ARC-005).** The accounts and onboarding
 * ViewModels see [Account] — a `:core:model` type — and never a Room entity.
 *
 * **Balances are derived, not read (DB-001).** Every figure this returns is
 * `opening_balance + SUM(live transactions)`. The stored `current_balance_minor` column is a cache
 * this class deliberately does not trust; see
 * `docs/adr/0007-account-balances-derived-not-stored.md`.
 *
 * **Deletes are soft (DB-002), and archiving is a different thing entirely.** Deleting undoes a
 * mistake; archiving retires a real account whose history FR-ACC-007 requires the app to keep.
 */
interface AccountRepository {
    /**
     * Observes the active profile's accounts, newest state first.
     *
     * Why:    the caller does not choose the profile, for the same reason
     *         [QuickSetupRepository.observeLatestEnvelopes] does not: demo mode puts sample data
     *         under a second profile id, and "which profile?" is a question with one right answer
     *         at any moment that several screens would each have to get right. Answered here, the
     *         accounts list follows the demo without importing it.
     * Result: emits on every change to an account **or** to any transaction against one, because
     *         the balance is derived from both. An empty list is a real state the screen must
     *         render as an empty state.
     * Input:  [includeArchived] — `false` for the active list, `true` for the full history
     *         (FR-ACC-007). Output: `Flow<List<Account>>`.
     */
    fun observeAccounts(includeArchived: Boolean = false): Flow<List<Account>>

    /**
     * Observes one named profile's accounts.
     * Why:    kept alongside the form above for callers that genuinely mean one profile — the tests
     *         asserting demo/real isolation, and the future household screen (issue 13.1).
     * Result: emits on every change; an empty list when that profile has none.
     * Input:  [profileId]; [includeArchived] — defaults to the active list, as the form above does.
     *         The two overloads are told apart by the first argument's type, so the default is
     *         unambiguous. Output: `Flow<List<Account>>`.
     */
    fun observeAccounts(
        profileId: String,
        includeArchived: Boolean = false,
    ): Flow<List<Account>>

    /**
     * Fetches one account with its derived balance.
     * Why:    the editor loads the row it is about to change. Archived accounts are found too —
     *         editing is how a user brings one back.
     * Result: `Ok(account)`, or `Err(NotFound)` when the id names nothing live, or `Err(Storage)`.
     * Input:  [id]. Output: `Result<Account, AppError>`.
     */
    suspend fun find(id: String): Result<Account, AppError>

    /**
     * Creates an account under the active profile.
     * Why:    the id is **generated**, not derived the way `budgetId()` derives — a user may
     *         legitimately own two accounts with the same name, so there is no natural key. It comes
     *         from the injected [IdGenerator] rather than `UUID.randomUUID()` so the write stays
     *         reproducible in a test (P-08).
     * Result: `Ok(account)` with its assigned id, `Err(Validation)` with **nothing written** when
     *         the draft is not usable, or `Err(Storage)`.
     * Input:  [draft] — what the user typed. Output: `Result<Account, AppError>`.
     */
    suspend fun create(draft: AccountDraft): Result<Account, AppError>

    /**
     * Applies an edit to an existing account.
     * Why:    takes an [AccountDraft] rather than an [Account] so a caller cannot hand back a
     *         *balance* — that figure is derived and is not the user's to set. Correcting a balance
     *         is FR-ACC-006's reconciliation flow (issue 2.7), which posts an adjustment transaction
     *         rather than silently mutating the row, exactly as DB-001 requires.
     * Result: `Ok(account)` with the balance re-derived, `Err(Validation)`, `Err(NotFound)`, or
     *         `Err(Storage)`.
     * Input:  [id]; [draft]. Output: `Result<Account, AppError>`.
     */
    suspend fun update(
        id: String,
        draft: AccountDraft,
    ): Result<Account, AppError>

    /**
     * Archives or restores an account (FR-ACC-007).
     * Result: `Ok(Unit)`; the account leaves or rejoins the active list, keeping every transaction
     *         it ever had. `Err(NotFound)` when the id names nothing live.
     * Input:  [id]; [archived] — `true` to retire it, `false` to bring it back.
     * Output: `Result<Unit, AppError>`.
     */
    suspend fun setArchived(
        id: String,
        archived: Boolean,
    ): Result<Unit, AppError>

    /**
     * Soft-deletes an account (DB-002).
     * Why:    a tombstone rather than a `DELETE`, so an accidental delete is recoverable and a
     *         future sync can still see the row existed. The demo wipe is the one hard delete in
     *         this app and it is argued for separately (ADR-0006).
     * Result: `Ok(Unit)`; the account disappears from every read. `Err(NotFound)` when the id names
     *         nothing live.
     * Input:  [id]. Output: `Result<Unit, AppError>`.
     */
    suspend fun delete(id: String): Result<Unit, AppError>

    /**
     * Aligns an account with a real statement balance (issue 2.7; FR-ACC-006, P-02).
     *
     * Why:    the balance is derived and the user cannot state it — [AccountDraft] has no field for
     *         one, deliberately (DB-001, ADR-0007). This is the sanctioned way to correct it, and
     *         it corrects it **by adding a transaction, never by editing the past**: FR-ACC-006's
     *         words are "never silently mutated". The opening balance and every existing
     *         transaction are left exactly as they were; the difference is posted as one new
     *         adjustment row the user can see, question, and soft-delete like any other.
     *
     *         **The delta is computed here, against a balance read inside the same transaction** —
     *         not against whatever the screen last rendered. A caller that passed its own figure
     *         would be reconciling against a snapshot that may be seconds old, which is the exact
     *         stale-versus-live split issue 2.6 shipped a defect on.
     *
     *         **A zero delta writes nothing.** The app already agreed with the statement; a
     *         zero-amount transaction would record no fact and would have to be filtered by every
     *         engine downstream.
     * Result: `Ok(Reconciliation)` carrying before, statement, delta and the adjustment's id —
     *         `null` when there was nothing to adjust. `Err(NotFound)` when the id names nothing
     *         live, or `Err(Storage)`. An archived account **can** be reconciled: recording a closed
     *         account's final statement balance is precisely when a user needs this (FR-ACC-007).
     * Input:  [accountId]; [statementBalance] — what the statement says, MNY-001 paise, signed the
     *         same way the account's balance is (negative for a liability).
     * Output: `Result<Reconciliation, AppError>`.
     */
    suspend fun reconcile(
        accountId: String,
        statementBalance: Money,
    ): Result<Reconciliation, AppError>

    /**
     * Rewrites every cached balance in the active profile from the derivation (issue 2.7; DB-001).
     *
     * Why:    `account.current_balance_minor` has been a cache nothing maintains since issue 1.6 —
     *         ADR-0007's own *Bad* consequence says "the two figures can disagree, and until issue
     *         2.7 nothing notices". This is what notices. It is called from a daily background job,
     *         not from a screen: no read depends on it today, so nothing should wait on it.
     *
     *         **It repairs rather than merely reporting.** Drift here is not a symptom of
     *         corruption — it is the guaranteed state of a column that is written once and never
     *         updated, so a job that only flagged it would flag every account with transactions
     *         every night and mean nothing. The count it returns is the observation; the repair is
     *         the point.
     * Result: `Ok(n)` where `n` is how many accounts were out of step **before** the repair — zero
     *         on a healthy profile. `Err(Storage)` if the database could not be written.
     * Input:  none — the active profile, like every other unqualified read here.
     * Output: `Result<Int, AppError>`.
     */
    suspend fun refreshCachedBalances(): Result<Int, AppError>

    companion object {
        /** The [IdGenerator] prefix for account ids, so a database dump reads as itself. */
        const val ID_PREFIX = "account"

        /**
         * The `transactions.source` value marking an adjustment this flow wrote (issue 2.7).
         *
         * **This is the rule that fired** (P-02). A row indistinguishable from something the user
         * typed by hand could never explain itself, and the alternative — an English sentence in
         * `note` — would be un-localised and would repeat the amount in the column beside it.
         */
        const val SOURCE_RECONCILIATION = "reconciliation"
    }
}

/**
 * What the user typed about an account — everything they get to decide (issue 2.5).
 *
 * Why:    separate from [Account] because the two are not the same set of facts. An [Account] has
 *         an id and a derived balance; a draft has neither, because the user does not choose an id
 *         and **must not** be able to state a balance (DB-001 — it is derived, and correcting it is
 *         FR-ACC-006's reconciliation flow). Modelling the difference means the compiler refuses
 *         the mistake rather than a reviewer having to catch it.
 * Result: the argument to [AccountRepository.create] and [AccountRepository.update].
 * Changelog: 2026-07-28 — Created for issue 2.5.
 *
 * Input:  [name] — the user's own label, required; [type] — one of FR-ACC-001's eleven;
 *         [openingBalance] — MNY-001 paise, signed, negative for a liability; [currencyCode] —
 *         ISO-4217; [institution] — the bank or issuer, optional (cash has none);
 *         [includeInNetWorth] — FR-ACC-005, issue 2.6.
 * Output: an immutable value.
 */
data class AccountDraft(
    val name: String,
    val type: AccountType,
    val openingBalance: Money,
    val currencyCode: String,
    val institution: String? = null,
    /**
     * Whether the account counts towards net worth (issue 2.6; FR-ACC-005).
     *
     * Defaults to counting, because an account the user has said nothing about is one they expect
     * to see in their total.
     */
    val includeInNetWorth: Boolean = true,
)

/**
 * The Room-backed [AccountRepository].
 * Why:    ARC-003 — one public interface, an internal implementation, assembled by the DI graph.
 * Result: the implementation injected into the accounts and onboarding ViewModels.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 *
 * Input:  [database] — taken whole rather than as a DAO, matching the two repositories beside it,
 *         so a later multi-table write here needs no constructor change; [clock] — stamps row
 *         timestamps, never the wall clock (TIM-001); [ids] — mints account ids (P-08);
 *         [dispatchers] — database I/O off the caller's thread; [activeProfileId] — which profile
 *         the no-argument read resolves to, supplied by `DemoModeRepository`, so this class knows
 *         only "which profile is current" and never that demo mode exists.
 * Output: a working repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RoomAccountRepository(
    private val database: CfoDatabase,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
) : AccountRepository {
    override fun observeAccounts(includeArchived: Boolean): Flow<List<Account>> =
        // flatMapLatest, not map: entering or leaving the demo must *switch* which query is being
        // observed, cancelling the previous one. A `map` would leave the old profile's Flow running
        // and the screen showing the accounts it was already showing.
        activeProfileId.flatMapLatest { profileId -> observeAccounts(profileId, includeArchived) }

    override fun observeAccounts(
        profileId: String,
        includeArchived: Boolean,
    ): Flow<List<Account>> =
        database.accountDao().observeWithBalances(profileId, includeArchived, clock.today().toString())
            // mapNotNull, not map: a row whose `type` this build does not recognise is dropped
            // rather than thrown on, so an old build reading a newer database shows fewer accounts
            // instead of crashing the list.
            .map { rows -> rows.mapNotNull { it.toAccount() } }
            .flowOn(dispatchers.io)

    override suspend fun find(id: String): Result<Account, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult { database.accountDao().findWithBalance(id, clock.today().toString()) }
                .flatMapToAccount()
        }

    override suspend fun create(draft: AccountDraft): Result<Account, AppError> {
        val validated = draft.validated() ?: return Err(AppError.Validation("name"))
        return withContext(dispatchers.io) {
            runCatchingToResult {
                val now = clock.nowUtcMillis()
                val entity =
                    AccountEntity(
                        id = ids.newId(AccountRepository.ID_PREFIX),
                        profileId = activeProfileIdNow(),
                        name = validated.name,
                        type = validated.type.storedValue,
                        openingBalanceMinor = validated.openingBalance.minor,
                        // Seeded from the opening balance because a brand-new account has no
                        // transactions yet, so the two genuinely agree at this instant. It is a
                        // cache from here on; every read derives the figure (DB-001).
                        currentBalanceMinor = validated.openingBalance.minor,
                        currencyCode = validated.currencyCode,
                        institution = validated.institution,
                        includeInNetWorth = validated.includeInNetWorth,
                        createdAtUtcMillis = now,
                        updatedAtUtcMillis = now,
                    )
                database.accountDao().upsert(entity)
                entity.toAccount(movementMinor = 0L)
            }.flatMapPresent()
        }
    }

    override suspend fun update(
        id: String,
        draft: AccountDraft,
    ): Result<Account, AppError> {
        val validated = draft.validated() ?: return Err(AppError.Validation("name"))
        return withContext(dispatchers.io) {
            runCatchingToResult {
                val existing =
                    database.accountDao().findWithBalance(id, clock.today().toString())
                        ?: return@runCatchingToResult null
                database.accountDao().update(
                    existing.account.copy(
                        name = validated.name,
                        type = validated.type.storedValue,
                        openingBalanceMinor = validated.openingBalance.minor,
                        currencyCode = validated.currencyCode,
                        institution = validated.institution,
                        includeInNetWorth = validated.includeInNetWorth,
                        updatedAtUtcMillis = clock.nowUtcMillis(),
                    ),
                )
                // Re-derived rather than reusing `existing`: the opening balance may have just
                // changed, and the caller is about to render this figure.
                database.accountDao().findWithBalance(id, clock.today().toString())
            }.flatMapToAccount()
        }
    }

    override suspend fun setArchived(
        id: String,
        archived: Boolean,
    ): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult {
                val now = clock.nowUtcMillis()
                database.accountDao().setArchivedAt(
                    id = id,
                    archivedAtUtcMillis = if (archived) now else null,
                    updatedAtUtcMillis = now,
                )
            }.requireRowTouched()
        }

    override suspend fun delete(id: String): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult {
                database.accountDao().softDelete(id, clock.nowUtcMillis())
            }.requireRowTouched()
        }

    override suspend fun reconcile(
        accountId: String,
        statementBalance: Money,
    ): Result<Reconciliation, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult {
                // One transaction around read-compute-write: the balance the delta is measured
                // against and the adjustment that closes the gap must describe the same instant, or
                // a transaction landing between the two would be silently absorbed into the
                // correction and the user would have "fixed" a figure that was already right.
                database.withTransaction {
                    val existing =
                        database.accountDao().findWithBalance(accountId, clock.today().toString())
                            ?: return@withTransaction null
                    val account = existing.toAccount() ?: return@withTransaction null
                    database.writeAdjustment(account, statementBalance, clock, ids)
                }
            }.flatMapPresent()
        }

    override suspend fun refreshCachedBalances(): Result<Int, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult {
                // No `withTransaction`: it is a single statement, which SQLite is already atomic
                // about. Wrapping it would suggest there is a second write to keep it consistent
                // with, and there is not. The row count is the drift count — the DAO's `<>` clause
                // is what makes that true.
                database.accountDao().refreshCachedBalances(activeProfileIdNow(), clock.nowUtcMillis())
            }
        }

    /**
     * Reads the profile the write should land under, right now.
     * Why:    a write needs one value, not a stream. Taking the first emission is safe here because
     *         [activeProfileId] is derived from the settings flow, which always has a current value
     *         — it is a state, not an event stream. Reading it per write rather than caching it at
     *         construction is what makes an account created during a demo land under the demo
     *         profile and not the user's.
     * Result: the profile id every row created here is scoped to.
     * Input:  none. Output: [String].
     */
    private suspend fun activeProfileIdNow(): String = activeProfileId.first()
}

/**
 * Posts the adjustment, or decides none is needed (issue 2.7; FR-ACC-006).
 *
 * Why:    a top-level function rather than a method, for the same reason [toAccount] and
 *         [requireRowTouched] below are: `RoomAccountRepository` is at detekt's function ceiling,
 *         and this is a pure step over the database it is handed rather than a piece of the
 *         repository's own state. Split out of `reconcile` so the transactional boundary there
 *         reads as one thing without folding the *why* comments away.
 *
 *         **Assumes it is already inside `withTransaction`.** Every write below has to land or none
 *         of them can — an adjustment written without its cache refresh would leave the integrity
 *         job reporting drift the user had just fixed.
 *
 *         **The cache is re-derived, not asserted.** Setting the column to the statement figure
 *         would be true by construction today and quietly wrong the moment anything else about the
 *         derivation changes; running the same `UPDATE` DB-001's nightly job runs means there is
 *         exactly one definition of what the cached balance means.
 * Result: the [Reconciliation] describing what happened; writes one transaction row and refreshes
 *         the profile's cached balances, unless the app already agreed with the statement.
 * Input:  the receiver — the database, inside a transaction; [account] — with its balance freshly
 *         derived; [statementBalance] — what the user typed; [clock] — TIM-001; [ids] — P-08.
 * Output: [Reconciliation].
 * Changelog: 2026-08-02 — Created for issue 2.7.
 */
private suspend fun CfoDatabase.writeAdjustment(
    account: Account,
    statementBalance: Money,
    clock: Clock,
    ids: IdGenerator,
): Reconciliation {
    val now = clock.nowUtcMillis()
    val bookedOn = clock.today().toString()
    // Money's checked arithmetic (MNY-001): an absurd statement throws rather than wrapping.
    val delta = statementBalance - account.balance
    if (delta == Money.ZERO) {
        return Reconciliation(account.id, account.balance, statementBalance, delta, null, bookedOn)
    }
    // The prefix lives on TransactionRepository since issue 3.1, which is where this comment's
    // predecessor always said it would move. The literal is unchanged, so ids already written by
    // this flow still read the same way.
    val adjustmentId = ids.newId(TransactionRepository.ID_PREFIX)
    transactionDao().upsert(
        TransactionEntity(
            id = adjustmentId,
            // The **account's** profile, not the active one. Reconciling while the demo is on must
            // leave the row where `DemoDao.deleteTransactions` can reach it (ADR-0006).
            profileId = account.profileId,
            accountId = account.id,
            amountMinor = delta.minor,
            currencyCode = account.currencyCode,
            occurredAtUtcMillis = now,
            bookedOnIsoDate = bookedOn,
            source = AccountRepository.SOURCE_RECONCILIATION,
            // Issue 3.2: §20.2's `adjustment` — the one type whose sign is genuinely free, because a
            // correction runs in whichever direction the user's records were wrong in. Typing it as
            // an expense would put every balance correction into spend totals.
            type = TransactionType.ADJUSTMENT.storedValue,
            // Issue 3.4: an adjustment is booked today and is an actual the instant it is written —
            // it exists to make a balance match a statement *now*. It is never scheduled, so it is
            // stamped here rather than waiting for `ScheduledTransactionWorker` to notice it.
            postedAtUtcMillis = now,
            createdAtUtcMillis = now,
            updatedAtUtcMillis = now,
        ),
    )
    accountDao().refreshCachedBalances(account.profileId, now)
    return Reconciliation(account.id, account.balance, statementBalance, delta, adjustmentId, bookedOn)
}

/**
 * Rejects a draft the user cannot have meant.
 * Why:    a nameless account is unusable — every list, picker and drill-down identifies an account
 *         by its name, so a blank one is a row the user can never find again. Trimming rather than
 *         merely checking means `" HDFC "` and `"HDFC"` cannot become two accounts that look
 *         identical on screen.
 * Result: the draft with its text fields trimmed, or `null` when the name is blank. `null` rather
 *         than an exception because §5 forbids exceptions across a layer boundary.
 * Input:  the receiver. Output: `AccountDraft?`.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 */
internal fun AccountDraft.validated(): AccountDraft? {
    if (name.isBlank()) return null
    return copy(name = name.trim(), institution = institution?.trim()?.takeIf { it.isNotBlank() })
}

/**
 * Converts a row plus its transaction sum into the domain model.
 * Why:    **this is the whole of DB-001's derivation**, and it is one line on purpose — the balance
 *         is `opening + movement`, computed with [Money]'s overflow-checked arithmetic rather than
 *         raw `Long` addition, so an absurd dataset throws instead of wrapping to a negative
 *         fortune (MNY-001).
 * Result: an [Account], or `null` when the stored `type` is one this build does not know.
 * Input:  the receiver. Output: `Account?`.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 */
internal fun AccountWithBalance.toAccount(): Account? = account.toAccount(movementMinor)

/**
 * Converts an entity and a movement into the domain model.
 * Why:    split from the overload above so [RoomAccountRepository.create] can build the result from
 *         the row it just wrote, with a movement of zero, instead of reading it straight back.
 * Result: an [Account], or `null` for an unrecognised stored type.
 * Input:  the receiver; [movementMinor] — MNY-001 paise, signed. Output: `Account?`.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 */
internal fun AccountEntity.toAccount(movementMinor: Long): Account? {
    val parsed = AccountType.fromStored(type) ?: return null
    return Account(
        id = id,
        profileId = profileId,
        name = name,
        type = parsed,
        institution = institution,
        openingBalance = Money(openingBalanceMinor),
        balance = Money(openingBalanceMinor) + Money(movementMinor),
        currencyCode = currencyCode,
        isArchived = archivedAtUtcMillis != null,
        includeInNetWorth = includeInNetWorth,
    )
}

/**
 * Turns a nullable lookup into a `NotFound` rather than a null.
 * Why:    §5 — a repository returns `Result<T, AppError>`; handing a caller a `Result<Account?, _>`
 *         would push the absent case back out to every ViewModel to re-decide.
 * Result: `Ok(account)`, or `Err(NotFound)` when the row is missing **or** carries a type this
 *         build cannot parse — from the caller's side those are the same situation.
 * Input:  the receiver. Output: `Result<Account, AppError>`.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 */
private fun Result<AccountWithBalance?, AppError>.flatMapToAccount(): Result<Account, AppError> =
    when (this) {
        is Ok -> value?.toAccount()?.let { Ok(it) } ?: Err(AppError.NotFound)
        is Err -> this
    }

/**
 * Turns a nullable success value into a `NotFound` rather than a null.
 * Result: `Ok(value)`, or `Err(NotFound)` when the row could not be modelled or did not exist.
 * Input:  the receiver. Output: `Result<T, AppError>`.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 *            2026-08-02 — Made generic for issue 2.7, whose `reconcile` returns a `Reconciliation`.
 *            2026-08-02 — Made `internal` for issue 3.1, so `TransactionRepository` reuses it rather
 *            than declaring a second identical helper in the same package.
 */
internal fun <T : Any> Result<T?, AppError>.flatMapPresent(): Result<T, AppError> =
    when (this) {
        is Ok -> value?.let { Ok(it) } ?: Err(AppError.NotFound)
        is Err -> this
    }

/**
 * Turns "the UPDATE matched no rows" into an error.
 * Why:    a soft delete or an archive of an id that does not exist is silently a no-op in SQL, and
 *         returning `Ok` for it would let a screen report success for something that never happened
 *         — the class of bug where a user taps Delete twice and the second tap lies to them.
 * Result: `Ok(Unit)` when a row was touched, `Err(NotFound)` when none was.
 * Input:  the receiver — the DAO's affected-row count. Output: `Result<Unit, AppError>`.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 *            2026-08-02 — Made `internal` for issue 3.2, whose `delete` needs the same
 *            "the UPDATE matched nothing" → `NotFound` rule for both a plain row and a transfer pair.
 */
internal fun Result<Int, AppError>.requireRowTouched(): Result<Unit, AppError> =
    when (this) {
        is Ok -> if (value > 0) Ok(Unit) else Err(AppError.NotFound)
        is Err -> this
    }
