package com.aicfo.app.work

import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.crypto.SessionLock
import com.aicfo.core.model.Account
import com.aicfo.core.model.Money
import com.aicfo.core.model.Reconciliation
import com.aicfo.data.repository.AccountDraft
import com.aicfo.data.repository.AccountRepository
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
 * Tests for [BalanceIntegrityWorker] — DB-001's integrity job (issue 2.7; DB-001, SEC-002, P-04).
 *
 * Why:  the same single assertion that justifies [NetWorthSnapshotWorkerTest] justifies this file.
 *       `CoreModule.provideDatabase` **throws** while the session is locked, so reaching for the
 *       repository on a locked device would take the process down from a job the user never
 *       started. 2.6 proved that path on hardware (`SUCCESS → RETRY → SUCCESS` in logcat); this
 *       keeps it proven for the second worker without needing a device each time.
 * What: the locked path, the unlocked path, a failed refresh, and the unique name.
 * Result: a second background job that cannot crash a locked app.
 * Changelog: 2026-08-02 — Created for issue 2.7.
 */
@RunWith(RobolectricTestRunner::class)
class BalanceIntegrityWorkerTest {
    private val sessionLock = SessionLock()
    private val repository = RecordingAccountRepository()

    @Test
    fun `a locked app defers without touching the repository`() =
        runTest {
            // `callCount` staying at zero is the assertion; the retry is only the consequence.
            val result = worker().doWork()

            assertEquals(0, repository.callCount)
            assertTrue("nothing is wrong — it just cannot run yet", result is ListenableWorker.Result.Retry)
        }

    @Test
    fun `an unlocked app refreshes the cached balances`() =
        runTest {
            sessionLock.unlock()

            val result = worker().doWork()

            assertEquals(1, repository.callCount)
            assertTrue(result is ListenableWorker.Result.Success)
        }

    @Test
    fun `no drift found is a success, not a failure`() =
        runTest {
            // The normal outcome once the caches are in step. Reporting it as a failure would make
            // WorkManager back off from a job that is working exactly as intended.
            sessionLock.unlock()
            repository.result = Ok(0)

            assertTrue(worker().doWork() is ListenableWorker.Result.Success)
        }

    @Test
    fun `a failed refresh retries rather than giving up`() =
        runTest {
            sessionLock.unlock()
            repository.result = Err(AppError.Storage("disk"))

            assertTrue(worker().doWork() is ListenableWorker.Result.Retry)
        }

    @Test
    fun `the work is scheduled under its own stable unique name`() {
        // Its own name, not the snapshot's: two jobs sharing a unique name would silently replace
        // each other and only one of them would ever run.
        assertEquals("balance-integrity-daily", BalanceIntegrityWorker.WORK_NAME)
        assertTrue(BalanceIntegrityWorker.WORK_NAME != NetWorthSnapshotWorker.WORK_NAME)
    }

    /** Result: a worker wired to the test's lock and repository. Output: [BalanceIntegrityWorker]. */
    private fun worker(): BalanceIntegrityWorker =
        TestListenableWorkerBuilder<BalanceIntegrityWorker>(ApplicationProvider.getApplicationContext())
            .setWorkerFactory(
                object : androidx.work.WorkerFactory() {
                    override fun createWorker(
                        appContext: android.content.Context,
                        workerClassName: String,
                        workerParameters: androidx.work.WorkerParameters,
                    ) = BalanceIntegrityWorker(
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
 * An [AccountRepository] that counts whether it was reached (issue 2.7).
 *
 * Why:    the assertion that matters is about **absence** — a locked worker must not call this at
 *         all — and only a recording double can make that visible. Same shape as issue 2.6's
 *         `RecordingNetWorthRepository`, for the same reason.
 * Result: the call count and an injectable outcome; every other member is unreachable from a worker
 *         and throws if that assumption ever stops holding.
 * Changelog: 2026-08-02 — Created for issue 2.7.
 */
private class RecordingAccountRepository : AccountRepository {
    /** How many times the worker asked for a refresh. Zero is the point, when locked. */
    var callCount: Int = 0
        private set

    /** What [refreshCachedBalances] returns; one drifting account unless a test says otherwise. */
    var result: Result<Int, AppError> = Ok(1)

    override suspend fun refreshCachedBalances(): Result<Int, AppError> {
        callCount++
        return result
    }

    override fun observeAccounts(includeArchived: Boolean): Flow<List<Account>> = flowOf(emptyList())

    override fun observeAccounts(
        profileId: String,
        includeArchived: Boolean,
    ): Flow<List<Account>> = flowOf(emptyList())

    override suspend fun find(id: String): Result<Account, AppError> = unreachable()

    override suspend fun create(draft: AccountDraft): Result<Account, AppError> = unreachable()

    override suspend fun update(
        id: String,
        draft: AccountDraft,
    ): Result<Account, AppError> = unreachable()

    override suspend fun setArchived(
        id: String,
        archived: Boolean,
    ): Result<Unit, AppError> = unreachable()

    override suspend fun delete(id: String): Result<Unit, AppError> = unreachable()

    override suspend fun reconcile(
        accountId: String,
        statementBalance: Money,
    ): Result<Reconciliation, AppError> = unreachable()

    /** Result: never returns — the worker calls exactly one method and this is not it. */
    private fun unreachable(): Nothing = throw AssertionError("the integrity worker must only refresh caches")
}
