package com.aicfo.app.work

import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.crypto.SessionLock
import com.aicfo.core.model.EngineProvenance
import com.aicfo.data.repository.NetWorthRepository
import com.aicfo.domain.engines.networth.NetWorthRange
import com.aicfo.domain.engines.networth.NetWorthResult
import com.aicfo.domain.engines.networth.NetWorthTrend
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
    fun `the repair runs before the backfill`() =
        runTest {
            // The order is the claim (ADR-0012). `snapshotUpToToday` will not rewrite a day it has
            // already recorded, so correcting the past *after* extending it would leave one run's
            // gap between the two halves of the same series.
            sessionLock.unlock()

            worker().doWork()

            assertEquals(listOf("repair", "snapshot"), repository.calls)
        }

    @Test
    fun `a locked app does not repair either`() =
        runTest {
            // The repair reads the same gated database, so it must sit behind the same guard.
            worker().doWork()

            assertEquals(emptyList<String>(), repository.calls)
        }

    @Test
    fun `a failed repair retries and does not go on to write a new day`() =
        runTest {
            // Extending a series whose past could not be corrected would bake the wrong figure into
            // one more day, and the next run would have two problems instead of one.
            sessionLock.unlock()
            repository.repairResult = Err(AppError.Storage("disk"))

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Retry)
            assertEquals(listOf("repair"), repository.calls)
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

    /** What the worker did, in order — so a test can assert the repair ran *before* the backfill. */
    val calls: MutableList<String> = mutableListOf()

    /** What [snapshotUpToToday] returns; one day written unless a test says otherwise. */
    var result: Result<Int, AppError> = Ok(1)

    /** What [repairStaleHistory] returns; nothing stale unless a test says otherwise. */
    var repairResult: Result<Int, AppError> = Ok(0)

    override fun observeLatest(): Flow<NetWorthResult?> = flowOf(null)

    /**
     * The stored series (issue 6.6). This worker never reads history — it only writes days — so an
     * empty trend is the honest answer rather than a fabricated one.
     */
    override fun observeHistory(range: NetWorthRange): Flow<NetWorthTrend> =
        flowOf(
            NetWorthTrend(
                points = emptyList(),
                first = null,
                last = null,
                change = null,
                changeBps = null,
                high = null,
                low = null,
                provenance =
                    EngineProvenance(
                        engineId = "net-worth-trend",
                        engineVersion = "1.0",
                        computedAtUtcMillis = 1L,
                        inputWindow = range.name,
                    ),
            ),
        )

    override fun observeCurrent(): Flow<NetWorthResult> = flowOf()

    override suspend fun computeAsOf(asOfIsoDate: String): Result<NetWorthResult, AppError> = Err(AppError.NotFound)

    override suspend fun snapshotUpToToday(): Result<Int, AppError> {
        callCount++
        calls += "snapshot"
        return result
    }

    override suspend fun repairStaleHistory(): Result<Int, AppError> {
        calls += "repair"
        return repairResult
    }
}
