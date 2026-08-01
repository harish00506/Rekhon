package com.aicfo.app.work

import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.crypto.SessionLock
import com.aicfo.data.repository.NetWorthRepository
import com.aicfo.domain.engines.networth.NetWorthResult
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
 * Tests for [NetWorthSnapshotWorker] — the app's first background job (issue 2.6; SEC-002, P-04).
 *
 * Why:  one assertion here is worth the whole file. `CoreModule.provideDatabase` **throws** when the
 *       session is locked, so a worker that reached for the repository on a locked device would take
 *       the process down — from a job the user never started, with no screen open to explain it. The
 *       lock check has to happen *before* anything injects, and "before" is a claim only a test that
 *       watches the repository can make.
 * What: the locked path, the unlocked path, and a failed write.
 * Result: a background job that cannot crash a locked app.
 * Changelog: 2026-08-01 — Created for issue 2.6.
 */
@RunWith(RobolectricTestRunner::class)
class NetWorthSnapshotWorkerTest {
    private val sessionLock = SessionLock()
    private val repository = RecordingNetWorthRepository()

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
    fun `an unlocked app takes the snapshot`() =
        runTest {
            sessionLock.unlock()

            val result = worker().doWork()

            assertEquals(1, repository.callCount)
            assertTrue(result is ListenableWorker.Result.Success)
        }

    @Test
    fun `today already recorded is a success, not a failure`() =
        runTest {
            // The repository returns 0 days written. That is the normal outcome of a second run in
            // one day, and reporting it as a failure would make WorkManager back off for no reason.
            sessionLock.unlock()
            repository.result = Ok(0)

            assertTrue(worker().doWork() is ListenableWorker.Result.Success)
        }

    @Test
    fun `a failed write retries rather than giving up`() =
        runTest {
            // `failure()` would abandon the series until the next scheduled run; `retry()` lets
            // WorkManager back off and try again, and the backfill catches up whenever it lands.
            sessionLock.unlock()
            repository.result = Err(AppError.Storage("disk"))

            assertTrue(worker().doWork() is ListenableWorker.Result.Retry)
        }

    @Test
    fun `the work is scheduled under a stable unique name`() {
        // `KEEP` on a unique name is what stops rescheduling on every launch from resetting the
        // period — a user who opens the app each morning would otherwise never reach the first run.
        assertEquals("net-worth-daily-snapshot", NetWorthSnapshotWorker.WORK_NAME)
    }

    /** Result: a worker wired to the test's lock and repository. Output: [NetWorthSnapshotWorker]. */
    private fun worker(): NetWorthSnapshotWorker =
        TestListenableWorkerBuilder<NetWorthSnapshotWorker>(ApplicationProvider.getApplicationContext())
            .setWorkerFactory(
                object : androidx.work.WorkerFactory() {
                    override fun createWorker(
                        appContext: android.content.Context,
                        workerClassName: String,
                        workerParameters: androidx.work.WorkerParameters,
                    ) = NetWorthSnapshotWorker(
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
 * A [NetWorthRepository] that counts whether it was reached (issue 2.6).
 *
 * Why:    the assertion that matters is about **absence** — a locked worker must not call this at
 *         all — and only a recording double can make that visible. A mock would answer the same
 *         question more slowly and with less to read.
 * Result: the call count and an injectable outcome.
 * Changelog: 2026-08-01 — Created for issue 2.6.
 */
private class RecordingNetWorthRepository : NetWorthRepository {
    /** How many times the worker asked for a snapshot. Zero is the point, when locked. */
    var callCount: Int = 0
        private set

    /** What [snapshotUpToToday] returns; one day written unless a test says otherwise. */
    var result: Result<Int, AppError> = Ok(1)

    override fun observeLatest(): Flow<NetWorthResult?> = flowOf(null)

    override fun observeCurrent(): Flow<NetWorthResult> = flowOf()

    override suspend fun computeAsOf(asOfIsoDate: String): Result<NetWorthResult, AppError> = Err(AppError.NotFound)

    override suspend fun snapshotUpToToday(): Result<Int, AppError> {
        callCount++
        return result
    }
}
