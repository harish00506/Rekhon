package com.aicfo.feature.dashboard

import androidx.paging.PagingData
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.Category
import com.aicfo.core.model.CategoryNature
import com.aicfo.core.model.Money
import com.aicfo.core.model.Tag
import com.aicfo.core.model.Transaction
import com.aicfo.core.model.TransactionSource
import com.aicfo.core.model.Transfer
import com.aicfo.data.repository.CashFlowSummary
import com.aicfo.data.repository.FilteredTransaction
import com.aicfo.data.repository.MonthlyLedger
import com.aicfo.data.repository.SplitDraft
import com.aicfo.data.repository.TransactionDraft
import com.aicfo.data.repository.TransactionFilter
import com.aicfo.data.repository.TransactionRepository
import com.aicfo.data.repository.TransferDraft
import com.aicfo.domain.engines.classification.CategorySuggestion
import com.aicfo.domain.engines.nature.NatureBreakdown
import com.aicfo.domain.engines.nature.NatureVerdict
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * A transactions store that answers exactly what the dashboard reads (issue 4.3; issue 5.1).
 *
 * Why:  the dashboard reads three things from this repository — §8.3's monthly split, this month's
 *       cash flow, and a recent-activity preview — and everything else here **throws rather than
 *       returning a plausible value**, the shape `:app`'s `RecordingTransactionRepository`
 *       established. A fake that quietly returned empty lists for the other sixteen members would
 *       let this screen start depending on one of them without any test noticing.
 * Result: three controllable flows, each independently failable, and a loud failure for anything
 *       else — so a test can prove one stream's failure does not blank the others.
 * Changelog: 2026-08-10 — Created for issue 4.3.
 *            2026-08-15 — Issue 5.1: cash flow and recent activity joined the breakdown.
 */
internal class FakeTransactionRepository : TransactionRepository {
    private val breakdown = MutableStateFlow(NatureBreakdown())
    private val cashFlow =
        MutableStateFlow(CashFlowSummary(income = Money.ZERO, expense = Money.ZERO, net = Money.ZERO))
    private val recent = MutableStateFlow<List<FilteredTransaction>>(emptyList())

    /** When non-null, [observeNatureBreakdown] throws this instead of emitting. */
    var failOnObserve: AppError? = null

    /** When non-null, [observeMonthCashFlow] throws this instead of emitting (issue 5.1). */
    var failOnCashFlow: AppError? = null

    /** When non-null, [observeRecent] throws this instead of emitting (issue 5.1). */
    var failOnRecent: AppError? = null

    /** Sets what this month became. Input: [value]. Output: none. */
    fun setBreakdown(value: NatureBreakdown) {
        breakdown.value = value
    }

    /** Sets this month's cash flow (issue 5.1). Input: [value]. Output: none. */
    fun setCashFlow(value: CashFlowSummary) {
        cashFlow.value = value
    }

    /** Sets the recent-activity preview rows (issue 5.1). Input: [value]. Output: none. */
    fun setRecent(vararg value: Transaction) {
        recent.value = value.map { FilteredTransaction(it) }
    }

    override fun observeNatureBreakdown(): Flow<NatureBreakdown> =
        breakdown.map { value ->
            failOnObserve?.let { throw IllegalStateException(it.code) }
            value
        }

    // Issue 7.2 added this to the interface. Empty rather than unsupported: nothing in this
    // module reads a multi-month history, and a fake that threw would fail a test that only
    // happens to share the repository.
    override fun observeMonthlyLedger(months: Int): Flow<List<MonthlyLedger>> = MutableStateFlow(emptyList())

    override fun observeMonthCashFlow(): Flow<CashFlowSummary> =
        cashFlow.map { value ->
            failOnCashFlow?.let { throw IllegalStateException(it.code) }
            value
        }

    override fun observeRecent(limit: Int): Flow<List<FilteredTransaction>> =
        recent.map { value ->
            failOnRecent?.let { throw IllegalStateException(it.code) }
            value.take(limit)
        }

    override fun observeFiltered(filter: TransactionFilter): Flow<PagingData<FilteredTransaction>> =
        flowOf(PagingData.empty())

    override fun observeUpcoming(): Flow<List<Transaction>> = unsupported()

    override fun observeDayTotals(filter: TransactionFilter): Flow<Map<String, Money>> = unsupported()

    override fun observeSources(): Flow<List<TransactionSource>> = unsupported()

    override fun observeTags(): Flow<List<Tag>> = unsupported()

    override fun observeCategories(): Flow<List<Category>> = unsupported()

    override suspend fun recategoriseAll(
        ids: List<String>,
        categoryId: String?,
    ): Result<Int, AppError> = unsupported()

    override suspend fun retagAll(
        ids: List<String>,
        tagNames: List<String>,
    ): Result<Int, AppError> = unsupported()

    override suspend fun deleteAll(ids: List<String>): Result<List<String>, AppError> = unsupported()

    override suspend fun restoreAll(ids: List<String>): Result<Int, AppError> = unsupported()

    override suspend fun postDueTransactions(): Result<Int, AppError> = unsupported()

    override suspend fun suggestCategory(merchant: String): Result<CategorySuggestion?, AppError> = unsupported()

    override suspend fun natureOf(transactionId: String): Result<NatureVerdict, AppError> = unsupported()

    override suspend fun setNature(
        transactionId: String,
        nature: CategoryNature?,
    ): Result<Unit, AppError> = unsupported()

    override suspend fun create(draft: TransactionDraft): Result<Transaction, AppError> = unsupported()

    override suspend fun createTransfer(draft: TransferDraft): Result<Transfer, AppError> = unsupported()

    override suspend fun createSplit(draft: SplitDraft): Result<Transaction, AppError> = unsupported()

    override suspend fun delete(transactionId: String): Result<Unit, AppError> = unsupported()

    /**
     * Result: never — fails the test loudly. Why: a dashboard test that reached one of these would
     *         be asserting something `:data:repository` owns, and a plausible empty value would hide
     *         that rather than reveal it. Output: [Nothing].
     */
    private fun unsupported(): Nothing = error("the dashboard must not read this from TransactionRepository")
}
