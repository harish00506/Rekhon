package com.aicfo.feature.accounts

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Account
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import com.aicfo.core.model.Reconciliation
import com.aicfo.data.repository.AccountDraft
import com.aicfo.data.repository.AccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * An in-memory [AccountRepository] for the ViewModel tests (issue 2.5).
 *
 * Why:  a ViewModel test's subject is *what the screen does with what it is given* — the loading
 *       state, the archived toggle, whether a failed delete is reported. Driving that through
 *       Robolectric and real SQLite would prove the repository again, slowly, and would make a
 *       failure ambiguous between the two. `AccountRepositoryTest` is where the SQL is proven.
 * What: a mutable list behind the interface, with an injectable failure and call recording.
 * Result: every branch of the ViewModel, including the error paths, is reachable without a database.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 *
 * **The archived filter is applied here**, not ignored, because the ViewModel's `flatMapLatest` over
 * the toggle is exactly what these tests exist to check — a fake that returned everything regardless
 * would let a broken toggle pass.
 *
 * Input:  [failWith] — when non-null, every write returns `Err` with it.
 * Output: a fake repository.
 */
internal class FakeAccountRepository(
    var failWith: AppError? = null,
) : AccountRepository {
    private val accounts = MutableStateFlow<List<Account>>(emptyList())

    /** Ids passed to [setArchived] with `true`, in order — so a test can assert the call happened. */
    val archivedIds: MutableList<String> = mutableListOf()

    /** Ids passed to [delete], in order. */
    val deletedIds: MutableList<String> = mutableListOf()

    /**
     * What [reconcile] was asked to do, in order (issue 2.7).
     *
     * The **statement**, not the delta: the ViewModel must hand the store what the user typed and
     * let it do the subtraction, so recording the delta here would hide the mistake this exists to
     * catch.
     */
    val reconciled: MutableList<Pair<String, Money>> = mutableListOf()

    /**
     * When non-null, the observation flow throws this instead of emitting.
     *
     * Separate from [failWith] because they are different failures: a write that is refused leaves
     * the list intact, while a read that throws is a database that would not open at all — and the
     * ViewModel has to clear loading for one and not the other.
     */
    var failOnObserve: AppError? = null

    /** Seeds the store. Input: [seed] — the accounts to hold. Output: none. */
    fun setAccounts(vararg seed: Account) {
        accounts.value = seed.toList()
    }

    override fun observeAccounts(includeArchived: Boolean): Flow<List<Account>> =
        accounts.map { list ->
            failOnObserve?.let { throw IllegalStateException(it.code) }
            list.filter { includeArchived || !it.isArchived }
        }

    override fun observeAccounts(
        profileId: String,
        includeArchived: Boolean,
    ): Flow<List<Account>> = observeAccounts(includeArchived)

    override suspend fun find(id: String): Result<Account, AppError> {
        failWith?.let { return Err(it) }
        return accounts.value.firstOrNull { it.id == id }?.let { Ok(it) } ?: Err(AppError.NotFound)
    }

    override suspend fun create(draft: AccountDraft): Result<Account, AppError> {
        failWith?.let { return Err(it) }
        val account = draft.toAccount("account:${accounts.value.size + 1}")
        accounts.value = accounts.value + account
        return Ok(account)
    }

    override suspend fun update(
        id: String,
        draft: AccountDraft,
    ): Result<Account, AppError> =
        when {
            failWith != null -> Err(failWith!!)
            accounts.value.none { it.id == id } -> Err(AppError.NotFound)
            else ->
                draft.toAccount(id).also { updated ->
                    accounts.value = accounts.value.map { if (it.id == id) updated else it }
                }.let(::Ok)
        }

    override suspend fun setArchived(
        id: String,
        archived: Boolean,
    ): Result<Unit, AppError> {
        failWith?.let { return Err(it) }
        if (archived) archivedIds += id
        accounts.value = accounts.value.map { if (it.id == id) it.copy(isArchived = archived) else it }
        return Ok(Unit)
    }

    override suspend fun delete(id: String): Result<Unit, AppError> {
        failWith?.let { return Err(it) }
        deletedIds += id
        accounts.value = accounts.value.filterNot { it.id == id }
        return Ok(Unit)
    }

    override suspend fun reconcile(
        accountId: String,
        statementBalance: Money,
    ): Result<Reconciliation, AppError> {
        val account = accounts.value.firstOrNull { it.id == accountId }
        return when {
            failWith != null -> Err(failWith!!)
            account == null -> Err(AppError.NotFound)
            else -> Ok(applyReconciliation(account, statementBalance))
        }
    }

    /**
     * Records the call and moves the balance to the statement.
     * Why:    the fake applies the correction the way the real store does, so a test can assert what
     *         the *list* shows afterwards rather than only that a call happened. The adjustment row
     *         itself is `AccountReconciliationTest`'s to prove, against real SQL.
     * Result: the [Reconciliation] the ViewModel receives; mutates the held accounts.
     * Input:  [account] — the row; [statementBalance] — what the user typed. Output: [Reconciliation].
     * Changelog: 2026-08-02 — Created for issue 2.7.
     */
    private fun applyReconciliation(
        account: Account,
        statementBalance: Money,
    ): Reconciliation {
        reconciled += account.id to statementBalance
        val delta = statementBalance - account.balance
        accounts.value =
            accounts.value.map { if (it.id == account.id) it.copy(balance = statementBalance) else it }
        return Reconciliation(
            accountId = account.id,
            before = account.balance,
            statement = statementBalance,
            delta = delta,
            adjustmentId = if (delta == Money.ZERO) null else "txn:1",
            bookedOnIsoDate = "2026-08-02",
        )
    }

    override suspend fun refreshCachedBalances(): Result<Int, AppError> {
        failWith?.let { return Err(it) }
        return Ok(0)
    }
}

/**
 * Result: the domain account a draft becomes. Input: the receiver; [id]. Output: [Account].
 *
 * The balance equals the opening balance because no transactions exist in a ViewModel test — the
 * same value the real repository returns from `create`, for the same reason (DB-001).
 */
private fun AccountDraft.toAccount(id: String): Account =
    Account(
        id = id,
        profileId = "local",
        name = name.trim(),
        type = type,
        institution = institution?.trim()?.takeIf { it.isNotBlank() },
        openingBalance = openingBalance,
        balance = openingBalance,
        currencyCode = currencyCode,
        isArchived = false,
    )

/**
 * A ready-made account for a test to seed, built by copying [BASE_ACCOUNT].
 *
 * Why:    `copy` rather than a builder function with a parameter per field — an [Account] has nine
 *         of them, and a fixture function taking them all would be the longest parameter list in
 *         the codebase for no gain. A caller names only what the test is actually about.
 * Result: the base account with [changes] applied.
 * Input:  [changes] — applied to [BASE_ACCOUNT]. Output: [Account].
 * Changelog: 2026-07-28 — Created for issue 2.5.
 */
internal fun account(changes: Account.() -> Account = { this }): Account = BASE_ACCOUNT.changes()

/** The default fixture every [account] call starts from. */
private val BASE_ACCOUNT =
    Account(
        id = "account:1",
        profileId = "local",
        name = "HDFC Savings",
        type = AccountType.BANK,
        institution = "HDFC Bank",
        openingBalance = Money(1_00_000_00L),
        balance = Money(1_00_000_00L),
        currencyCode = "INR",
        isArchived = false,
    )
