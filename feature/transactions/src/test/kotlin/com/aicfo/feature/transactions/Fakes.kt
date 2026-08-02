package com.aicfo.feature.transactions

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Account
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Category
import com.aicfo.core.model.Money
import com.aicfo.core.model.Reconciliation
import com.aicfo.core.model.Transaction
import com.aicfo.core.model.TransactionSource
import com.aicfo.data.repository.AccountDraft
import com.aicfo.data.repository.AccountRepository
import com.aicfo.data.repository.TransactionDraft
import com.aicfo.data.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * An in-memory [TransactionRepository] for the ViewModel tests (issue 3.1).
 *
 * Why:  a ViewModel test's subject is *what the screen does with what it is given* — whether the
 *       first account is preselected, whether the expense toggle becomes a sign, whether a refused
 *       write clears `isSaving`. Driving that through Robolectric and real SQLite would prove the
 *       repository again, slowly, and would make a failure ambiguous between the two.
 *       `TransactionRepositoryTest` is where the SQL is proven.
 * What: mutable lists behind the interface, with an injectable failure and call recording.
 * Result: every branch of the ViewModel, including the error paths, is reachable without a database.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 *
 * **[created] records the draft, not the resulting transaction.** The claim these tests exist to
 * check is what the ViewModel *asks for* — an expense arriving as a positive amount would be a real
 * bug that recording the outcome would hide.
 *
 * Input:  [failWith] — when non-null, [create] returns `Err` with it.
 * Output: a fake repository.
 */
internal class FakeTransactionRepository(
    var failWith: AppError? = null,
) : TransactionRepository {
    private val transactions = MutableStateFlow<List<Transaction>>(emptyList())
    private val categories = MutableStateFlow<List<Category>>(emptyList())

    /** Every draft passed to [create], in order. */
    val created: MutableList<TransactionDraft> = mutableListOf()

    /**
     * When non-null, **both** observation flows throw this instead of emitting.
     *
     * Separate from [failWith] because they are different failures: a write that is refused leaves
     * the list intact, while a read that throws is a database that would not open at all — and that
     * failure does not pick one query, which is why one switch covers both here.
     */
    var failOnObserve: AppError? = null

    /** Seeds the recent list. Input: [seed] — the transactions to hold. Output: none. */
    fun setTransactions(vararg seed: Transaction) {
        transactions.value = seed.toList()
    }

    /** Seeds the category chips. Input: [seed]. Output: none. */
    fun setCategories(vararg seed: Category) {
        categories.value = seed.toList()
    }

    override fun observeRecent(): Flow<List<Transaction>> =
        transactions.map { list ->
            failOnObserve?.let { throw IllegalStateException(it.code) }
            list
        }

    override fun observeCategories(): Flow<List<Category>> =
        categories.map { list ->
            failOnObserve?.let { throw IllegalStateException(it.code) }
            list
        }

    override suspend fun create(draft: TransactionDraft): Result<Transaction, AppError> {
        created += draft
        failWith?.let { return Err(it) }
        val transaction =
            Transaction(
                id = "txn:${transactions.value.size + 1}",
                accountId = draft.accountId,
                amount = draft.amount,
                occurredAtUtcMillis = 0L,
                bookedOn = "2026-08-02",
                categoryId = draft.categoryId,
                merchant = draft.merchant,
                note = draft.note,
                source = TransactionSource.MANUAL,
            )
        transactions.value = transactions.value + transaction
        return Ok(transaction)
    }
}

/**
 * A minimal in-memory [AccountRepository] for the add screen's picker (issue 3.1).
 *
 * Why:  the add-transaction ViewModel reads accounts from the real interface (there is one
 *       definition of "which accounts are pickable" and it is not duplicated onto the transactions
 *       store), so its tests need a double for it. Only the observation is implemented — this module
 *       never writes an account, and a fake that pretended to would invite a test to assert
 *       something `:feature:accounts` owns.
 * What: a mutable list behind `observeAccounts`; every write path throws.
 * Result: the picker's states — none, one, several — are all reachable.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 */
internal class FakeAccountRepository : AccountRepository {
    private val accounts = MutableStateFlow<List<Account>>(emptyList())

    /** Seeds the picker. Input: [seed] — the accounts to offer. Output: none. */
    fun setAccounts(vararg seed: Account) {
        accounts.value = seed.toList()
    }

    override fun observeAccounts(includeArchived: Boolean): Flow<List<Account>> =
        accounts.map { list -> list.filter { includeArchived || !it.isArchived } }

    override fun observeAccounts(
        profileId: String,
        includeArchived: Boolean,
    ): Flow<List<Account>> = observeAccounts(includeArchived)

    override suspend fun find(id: String): Result<Account, AppError> =
        accounts.value.firstOrNull { it.id == id }?.let { Ok(it) } ?: Err(AppError.NotFound)

    // Nothing in :feature:transactions writes an account. Throwing rather than returning a plausible
    // value means a test that starts to depend on one fails loudly instead of quietly proving
    // something that belongs to :feature:accounts.
    override suspend fun create(draft: AccountDraft): Result<Account, AppError> = unsupported()

    override suspend fun update(
        id: String,
        draft: AccountDraft,
    ): Result<Account, AppError> = unsupported()

    override suspend fun setArchived(
        id: String,
        archived: Boolean,
    ): Result<Unit, AppError> = unsupported()

    override suspend fun delete(id: String): Result<Unit, AppError> = unsupported()

    override suspend fun reconcile(
        accountId: String,
        statementBalance: Money,
    ): Result<Reconciliation, AppError> = unsupported()

    override suspend fun refreshCachedBalances(): Result<Int, AppError> = unsupported()

    /** Result: never — always throws. Input: none. Output: nothing. */
    private fun unsupported(): Nothing =
        throw UnsupportedOperationException(":feature:transactions does not write accounts")
}

/**
 * A ready-made account for a test to seed, built by copying [BASE_ACCOUNT].
 * Why:    `copy` rather than a builder with a parameter per field — an `Account` has nine of them and
 *         a caller should name only what the test is actually about. The same shape
 *         `:feature:accounts` settled on.
 * Result: the base account with [changes] applied. Input: [changes]. Output: [Account].
 * Changelog: 2026-08-02 — Created for issue 3.1.
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

/**
 * A ready-made transaction for a test to seed.
 * Result: the base transaction with [changes] applied. Input: [changes]. Output: [Transaction].
 * Changelog: 2026-08-02 — Created for issue 3.1.
 */
internal fun transaction(changes: Transaction.() -> Transaction = { this }): Transaction = BASE_TRANSACTION.changes()

/** The default fixture every [transaction] call starts from: a ₹250 expense booked today. */
private val BASE_TRANSACTION =
    Transaction(
        id = "txn:1",
        accountId = "account:1",
        amount = Money(-250_00L),
        occurredAtUtcMillis = 1_785_000_000_000L,
        bookedOn = "2026-08-02",
        categoryId = null,
        merchant = null,
        note = null,
        source = TransactionSource.MANUAL,
    )
