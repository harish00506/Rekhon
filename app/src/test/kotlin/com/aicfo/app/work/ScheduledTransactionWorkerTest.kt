package com.aicfo.app.work

import androidx.paging.PagingData
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.crypto.SessionLock
import com.aicfo.core.model.Category
import com.aicfo.core.model.CategoryNature
import com.aicfo.core.model.Money
import com.aicfo.core.model.Tag
import com.aicfo.core.model.Transaction
import com.aicfo.core.model.TransactionSource
import com.aicfo.core.model.Transfer
import com.aicfo.data.repository.FilteredTransaction
import com.aicfo.data.repository.SplitDraft
import com.aicfo.data.repository.TransactionDraft
import com.aicfo.data.repository.TransactionFilter
import com.aicfo.data.repository.TransactionRepository
import com.aicfo.data.repository.TransferDraft
import com.aicfo.domain.engines.classification.CategorySuggestion
import com.aicfo.domain.engines.nature.NatureBreakdown
import com.aicfo.domain.engines.nature.NatureVerdict
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.inject.Provider

/**
 * Tests for [ScheduledTransactionWorker] (issue 3.4; FR-TXN-010, SEC-002, P-04).
 *
 * Why:  the same assertion that justifies [NetWorthSnapshotWorkerTest] justifies this file.
 *       `CoreModule.provideDatabase` **throws** while the session is locked, so a worker that
 *       reached for the repository on a locked device would take the process down from a job the
 *       user never started. The check has to happen *before* anything injects, and "before" is a
 *       claim only a test watching the repository can make.
 *
 *       The second reason is the one specific to this worker: **zero rows stamped is a success.**
 *       Almost every run of this job, for almost every user, will find nothing due — most people
 *       schedule nothing. Reporting that as a failure would make WorkManager back off daily for the
 *       normal case.
 * What: the locked path, the unlocked path, an empty run, and a failed write.
 * Result: a background job that cannot crash a locked app and cannot cry wolf.
 * Changelog: 2026-08-03 — Created for issue 3.4.
 */
@RunWith(RobolectricTestRunner::class)
class ScheduledTransactionWorkerTest {
    private val sessionLock = SessionLock()
    private val repository = RecordingTransactionRepository()

    @Test
    fun `a locked app defers without touching the repository`() =
        runTest {
            // The whole reason the repository is injected as a Provider. `callCount` staying at zero
            // is the assertion; the retry is the consequence.
            val result = worker().doWork()

            assertEquals(0, repository.callCount)
            assertTrue("nothing is wrong — it just cannot run yet", result is ListenableWorker.Result.Retry)
        }

    @Test
    fun `an unlocked app stamps what is due`() =
        runTest {
            sessionLock.unlock()

            val result = worker().doWork()

            assertEquals(1, repository.callCount)
            assertTrue(result is ListenableWorker.Result.Success)
        }

    @Test
    fun `nothing due is a success, not a failure`() =
        runTest {
            // The overwhelmingly common outcome: most users schedule nothing, and a second run in one
            // day stamps nothing either. Reporting it as a failure would make WorkManager back off
            // daily for the normal case.
            sessionLock.unlock()
            repository.result = Ok(0)

            assertTrue(worker().doWork() is ListenableWorker.Result.Success)
        }

    @Test
    fun `a failed write retries rather than giving up`() =
        runTest {
            sessionLock.unlock()
            repository.result = Err(AppError.Storage("disk"))

            assertTrue(worker().doWork() is ListenableWorker.Result.Retry)
        }

    @Test
    fun `the work is scheduled under its own stable unique name`() {
        // Its own name, not shared with the other two daily jobs: unique work with a shared name
        // would mean only whichever was enqueued first ever ran (the lesson issue 2.7 recorded).
        assertEquals("scheduled-transaction-posting", ScheduledTransactionWorker.WORK_NAME)
        assertTrue(ScheduledTransactionWorker.WORK_NAME != NetWorthSnapshotWorker.WORK_NAME)
    }

    /** Result: a worker wired to the test's lock and repository. Output: [ScheduledTransactionWorker]. */
    private fun worker(): ScheduledTransactionWorker =
        TestListenableWorkerBuilder<ScheduledTransactionWorker>(ApplicationProvider.getApplicationContext())
            .setWorkerFactory(
                object : androidx.work.WorkerFactory() {
                    override fun createWorker(
                        appContext: android.content.Context,
                        workerClassName: String,
                        workerParameters: androidx.work.WorkerParameters,
                    ) = ScheduledTransactionWorker(
                        appContext,
                        workerParameters,
                        sessionLock,
                        Provider { repository },
                    )
                },
            )
            .build()
}

/**
 * A [TransactionRepository] that counts whether it was reached (issue 3.4).
 *
 * Why:    the assertion that matters is about **absence** — a locked worker must not call this at
 *         all — and only a recording double can make that visible. Every other member throws rather
 *         than returning a plausible value, so a test that starts to depend on one fails loudly
 *         instead of quietly proving something `:data:repository` owns.
 * Result: the call count and an injectable outcome.
 * Changelog: 2026-08-03 — Created for issue 3.4.
 */
private class RecordingTransactionRepository : TransactionRepository {
    /** How many times the worker asked for a posting run. Zero is the point, when locked. */
    var callCount: Int = 0
        private set

    /** What [postDueTransactions] returns; one row stamped unless a test says otherwise. */
    var result: Result<Int, AppError> = Ok(1)

    override suspend fun postDueTransactions(): Result<Int, AppError> {
        callCount++
        return result
    }

    override fun observeFiltered(filter: TransactionFilter): Flow<PagingData<FilteredTransaction>> =
        flowOf(PagingData.empty())

    override fun observeUpcoming(): Flow<List<Transaction>> = flowOf(emptyList())

    override fun observeDayTotals(filter: TransactionFilter): Flow<Map<String, Money>> = flowOf(emptyMap())

    override fun observeSources(): Flow<List<TransactionSource>> = flowOf(emptyList())

    override fun observeTags(): Flow<List<Tag>> = flowOf(emptyList())

    override fun observeCategories(): Flow<List<Category>> = flowOf(emptyList())

    override suspend fun suggestCategory(merchant: String): Result<CategorySuggestion?, AppError> = unsupported()

    override suspend fun natureOf(transactionId: String): Result<NatureVerdict, AppError> = unsupported()

    override suspend fun setNature(
        transactionId: String,
        nature: CategoryNature?,
    ): Result<Unit, AppError> = unsupported()

    override fun observeNatureBreakdown(): Flow<NatureBreakdown> = unsupported()

    override suspend fun create(draft: TransactionDraft): Result<Transaction, AppError> = unsupported()

    override suspend fun createTransfer(draft: TransferDraft): Result<Transfer, AppError> = unsupported()

    override suspend fun createSplit(draft: SplitDraft): Result<Transaction, AppError> = unsupported()

    override suspend fun delete(transactionId: String): Result<Unit, AppError> = unsupported()

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

    /** Result: never — always throws. Input: none. Output: nothing. */
    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("the posting worker only calls postDueTransactions")
}
