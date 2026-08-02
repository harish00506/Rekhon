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
import com.aicfo.core.model.TransactionType
import com.aicfo.core.model.Transfer
import com.aicfo.data.repository.AccountDraft
import com.aicfo.data.repository.AccountRepository
import com.aicfo.data.repository.TransactionDraft
import com.aicfo.data.repository.TransactionRepository
import com.aicfo.data.repository.TransferDraft
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
     * Every draft passed to [createTransfer], in order (issue 3.2).
     *
     * The **draft**, not the resulting transfer: what these tests check is what the ViewModel *asks
     * for*, and a transfer whose source and destination were swapped would be a real bug that
     * recording the outcome would hide.
     */
    val transfersCreated: MutableList<TransferDraft> = mutableListOf()

    /** Every transaction id passed to [delete], in order (issue 3.2). */
    val deleted: MutableList<String> = mutableListOf()

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
                type = if (draft.amount < Money.ZERO) TransactionType.EXPENSE else TransactionType.INCOME,
            )
        transactions.value = transactions.value + transaction
        return Ok(transaction)
    }

    override suspend fun createTransfer(draft: TransferDraft): Result<Transfer, AppError> {
        transfersCreated += draft
        failWith?.let { return Err(it) }
        // Both legs, as the real store writes them: the fake holds the same two rows so a list test
        // can assert the collapsing without a database.
        val transferId = "tfr:${transfersCreated.size}"
        val legs =
            listOf(
                draft.fromAccountId to Money.ZERO - draft.amount,
                draft.toAccountId to draft.amount,
            ).mapIndexed { index, (accountId, amount) ->
                Transaction(
                    id = "txn:${transactions.value.size + index + 1}",
                    accountId = accountId,
                    amount = amount,
                    occurredAtUtcMillis = 0L,
                    bookedOn = "2026-08-02",
                    categoryId = null,
                    merchant = null,
                    note = draft.note,
                    source = TransactionSource.MANUAL,
                    type = if (amount < Money.ZERO) TransactionType.TRANSFER_OUT else TransactionType.TRANSFER_IN,
                    transferId = transferId,
                )
            }
        transactions.value = transactions.value + legs
        return Ok(
            Transfer(
                id = transferId,
                fromAccountId = draft.fromAccountId,
                toAccountId = draft.toAccountId,
                amount = draft.amount,
                bookedOn = "2026-08-02",
                note = draft.note,
            ),
        )
    }

    override suspend fun delete(transactionId: String): Result<Unit, AppError> {
        deleted += transactionId
        val target = transactions.value.firstOrNull { it.id == transactionId }
        return when {
            failWith != null -> Err(failWith!!)
            target == null -> Err(AppError.NotFound)
            else -> {
                // Mirrors the real store: a transfer leg takes its sibling with it (FR-TXN-003), so
                // a list test sees the whole row disappear rather than half of it.
                transactions.value =
                    target.transferId
                        ?.let { id -> transactions.value.filterNot { it.transferId == id } }
                        ?: transactions.value.filterNot { it.id == transactionId }
                Ok(Unit)
            }
        }
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
        type = TransactionType.EXPENSE,
    )

/**
 * A pair of transfer legs for a list test to seed (issue 3.2; FR-TXN-003).
 * Why:    a transfer is two rows sharing an id, and building them by hand in each test invites the
 *         one mistake the collapsing logic must never make — legs that do not actually balance.
 *         Here the second leg is derived from the first, so they always do.
 * Result: the outgoing leg then the incoming one, both booked on the same day.
 * Input:  [transferId]; [amount] — positive; [from], [to] — the two accounts; [bookedOn].
 * Output: `List<Transaction>`.
 * Changelog: 2026-08-02 — Created for issue 3.2.
 */
internal fun transferLegs(
    transferId: String = "tfr:1",
    amount: Money = Money(5_000_00L),
    from: String = "account:1",
    to: String = "account:2",
    bookedOn: String = "2026-08-02",
): List<Transaction> =
    listOf(
        transaction {
            copy(
                id = "$transferId:out",
                accountId = from,
                amount = Money.ZERO - amount,
                bookedOn = bookedOn,
                type = TransactionType.TRANSFER_OUT,
                transferId = transferId,
            )
        },
        transaction {
            copy(
                id = "$transferId:in",
                accountId = to,
                amount = amount,
                bookedOn = bookedOn,
                type = TransactionType.TRANSFER_IN,
                transferId = transferId,
            )
        },
    )
