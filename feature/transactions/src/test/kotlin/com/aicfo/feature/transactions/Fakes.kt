package com.aicfo.feature.transactions

import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Account
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Category
import com.aicfo.core.model.Money
import com.aicfo.core.model.Reconciliation
import com.aicfo.core.model.Tag
import com.aicfo.core.model.Transaction
import com.aicfo.core.model.TransactionSource
import com.aicfo.core.model.TransactionSplit
import com.aicfo.core.model.TransactionType
import com.aicfo.core.model.Transfer
import com.aicfo.data.repository.AccountDraft
import com.aicfo.data.repository.AccountRepository
import com.aicfo.data.repository.FilteredTransaction
import com.aicfo.data.repository.SplitDraft
import com.aicfo.data.repository.TransactionDraft
import com.aicfo.data.repository.TransactionFilter
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
    private val upcoming = MutableStateFlow<List<Transaction>>(emptyList())
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
     * Every draft passed to [createSplit], in order (issue 3.3).
     *
     * The **draft**, because what these tests check is what the ViewModel *asks for* — lines that
     * arrived unsigned, or not summing to their parent, would be a real bug that recording the
     * outcome would hide.
     */
    val splitsCreated: MutableList<SplitDraft> = mutableListOf()

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

    /**
     * Seeds the **scheduled** list (issue 3.4; FR-TXN-010).
     *
     * Separate from [setTransactions] because the real store keeps them separate: `observeRecent`
     * stops at today and `observeUpcoming` starts tomorrow, so a fake that served one list to both
     * would let a ViewModel test pass while the screen showed a scheduled payment among its actuals.
     *
     * Input: [seed] — the future-dated transactions to hold. Output: none.
     */
    fun setUpcoming(vararg seed: Transaction) {
        upcoming.value = seed.toList()
    }

    /** Seeds the category chips. Input: [seed]. Output: none. */
    fun setCategories(vararg seed: Category) {
        categories.value = seed.toList()
    }

    /** Every call to [postDueTransactions], counted (issue 3.4). */
    var postDueCalls: Int = 0
        private set

    /** Every bulk call, in order, as `"operation:id,id"` (issue 3.6; FR-TXN-008). */
    val bulkCalls: MutableList<String> = mutableListOf()

    /**
     * What [deleteAll] reports as removed, when it should differ from what it was asked (issue 3.6).
     *
     * Why: deleting one leg of a transfer removes both (FR-TXN-003), and the undo batch has to name
     *      both. Injecting the extra ids is how a ViewModel test drives that without a database — a
     *      fake that always echoed its input could never fail the test that matters.
     */
    var deleteAllReturns: List<String>? = null

    /** The tags the filter sheet offers (issue 3.6). */
    private val tags = MutableStateFlow<List<Tag>>(emptyList())

    /** Seeds the tag chips (issue 3.6). Input: [seed]. Output: none. */
    fun setTags(vararg seed: Tag) {
        tags.value = seed.toList()
    }

    /**
     * The filter every [observeFiltered] call has been made with, in order (issue 3.6).
     *
     * The claim the ViewModel tests make is that a chip tap becomes a *query*, and this is the only
     * place that can be observed — the rows a fake returns would look the same either way.
     */
    val filtersQueried: MutableList<TransactionFilter> = mutableListOf()

    override fun observeFiltered(filter: TransactionFilter): Flow<PagingData<FilteredTransaction>> {
        filtersQueried += filter
        return transactions.map { list ->
            failOnObserve?.let { throw IllegalStateException(it.code) }
            // Filtered in Kotlin rather than ignored: a ViewModel test asserting "typing narrows the
            // list" would otherwise pass against a fake that never narrowed anything. Only the
            // facets the ViewModel is actually tested on — the SQL is proven in `TransactionSearchTest`.
            PagingData.from(
                data = list.filter { it.matches(filter) }.map { FilteredTransaction(it) },
                sourceLoadStates = LOADED,
            )
        }
    }

    override fun observeDayTotals(filter: TransactionFilter): Flow<Map<String, Money>> =
        transactions.map { list ->
            failOnObserve?.let { throw IllegalStateException(it.code) }
            list.filter { it.matches(filter) }
                .groupBy { it.bookedOn }
                .mapValues { (_, rows) -> rows.fold(Money.ZERO) { running, row -> running + row.amount } }
        }

    override fun observeSources(): Flow<List<TransactionSource>> =
        transactions.map { list ->
            failOnObserve?.let { throw IllegalStateException(it.code) }
            val present = list.mapTo(mutableSetOf()) { it.source }
            TransactionSource.entries.filter { it in present }
        }

    override fun observeTags(): Flow<List<Tag>> =
        tags.map { list ->
            failOnObserve?.let { throw IllegalStateException(it.code) }
            list
        }

    override suspend fun recategoriseAll(
        ids: List<String>,
        categoryId: String?,
    ): Result<Int, AppError> {
        bulkCalls += "recategorise:${ids.joinToString(",")}:${categoryId.orEmpty()}"
        failWith?.let { return Err(it) }
        return Ok(ids.size)
    }

    override suspend fun retagAll(
        ids: List<String>,
        tagNames: List<String>,
    ): Result<Int, AppError> {
        bulkCalls += "retag:${ids.joinToString(",")}:${tagNames.joinToString(",")}"
        failWith?.let { return Err(it) }
        return Ok(ids.size)
    }

    override suspend fun deleteAll(ids: List<String>): Result<List<String>, AppError> {
        bulkCalls += "deleteAll:${ids.joinToString(",")}"
        failWith?.let { return Err(it) }
        val removed = deleteAllReturns ?: ids
        transactions.value = transactions.value.filterNot { it.id in removed }
        return Ok(removed)
    }

    override suspend fun restoreAll(ids: List<String>): Result<Int, AppError> {
        bulkCalls += "restoreAll:${ids.joinToString(",")}"
        failWith?.let { return Err(it) }
        return Ok(ids.size)
    }

    override fun observeUpcoming(): Flow<List<Transaction>> =
        upcoming.map { list ->
            failOnObserve?.let { throw IllegalStateException(it.code) }
            list
        }

    override suspend fun postDueTransactions(): Result<Int, AppError> {
        postDueCalls++
        failWith?.let { return Err(it) }
        return Ok(0)
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

    override suspend fun createSplit(draft: SplitDraft): Result<Transaction, AppError> {
        splitsCreated += draft
        failWith?.let { return Err(it) }
        val parentId = "txn:${transactions.value.size + 1}"
        val parent =
            Transaction(
                id = parentId,
                accountId = draft.accountId,
                amount = draft.amount,
                occurredAtUtcMillis = 0L,
                bookedOn = "2026-08-02",
                // The lines carry the categories; the parent deliberately carries none.
                categoryId = null,
                merchant = draft.merchant,
                note = draft.note,
                source = TransactionSource.MANUAL,
                type = if (draft.amount < Money.ZERO) TransactionType.EXPENSE else TransactionType.INCOME,
                splits =
                    draft.lines.mapIndexed { index, line ->
                        TransactionSplit(
                            id = "spl:$parentId:$index",
                            transactionId = parentId,
                            amount = line.amount,
                            categoryId = line.categoryId,
                            note = line.note,
                        )
                    },
            )
        transactions.value = transactions.value + parent
        return Ok(parent)
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

/**
 * Whether a transaction would survive a filter (issue 3.6; FR-TXN-007).
 * Why: [FakeTransactionRepository] narrows the rows it returns so a ViewModel test asserting "typing
 *       narrows the list" is actually asserting something — a fake that ignored the filter would pass
 *       whether or not the ViewModel ever built one. **Only the facets the ViewModel is tested on**:
 *       the full predicate is SQL and is proven against a real engine in `TransactionSearchTest`, and
 *       reimplementing it here would be a second definition to drift.
 * Result: `true` when the row matches every facet the fake honours.
 * Input:  the receiver; [filter]. Output: [Boolean].
 * Changelog: 2026-08-04 — Created for issue 3.6.
 */
internal fun Transaction.matches(filter: TransactionFilter): Boolean {
    val term = filter.searchTerm
    val textMatches =
        term == null ||
            merchant?.contains(term, ignoreCase = true) == true ||
            note?.contains(term, ignoreCase = true) == true
    return textMatches &&
        (filter.source == null || source == filter.source) &&
        (filter.accountId == null || accountId == filter.accountId) &&
        (filter.type == null || type == filter.type)
}

/**
 * The load states a fully-loaded, single-page [PagingData] carries (issue 3.6).
 *
 * Why:  `PagingData.from(list)` **without** them leaves `refresh` on `Loading` for ever, and two
 *       things then hang rather than fail: `asSnapshot` waits for a pager that never goes idle, and
 *       the screen's empty state — which keys off `loadState.refresh is NotLoading` so it cannot
 *       flash "add your first one" at a user whose first page has not arrived — never renders.
 *       Both cost a minute of test timeout each and neither names paging in its failure.
 * Result: a page that says it is complete, which is what a hand-built fixture is.
 * Changelog: 2026-08-04 — Created for issue 3.6.
 */
internal val LOADED: LoadStates =
    LoadStates(
        refresh = LoadState.NotLoading(endOfPaginationReached = true),
        prepend = LoadState.NotLoading(endOfPaginationReached = true),
        append = LoadState.NotLoading(endOfPaginationReached = true),
    )
