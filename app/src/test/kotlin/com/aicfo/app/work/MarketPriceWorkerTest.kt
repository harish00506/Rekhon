package com.aicfo.app.work

import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.crypto.SessionLock
import com.aicfo.data.repository.MarketPriceRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.inject.Provider

/**
 * Tests for [MarketPriceWorker] — the only job in the app that can reach a network (issue 6.5).
 *
 * Why:  the same argument `NetWorthSnapshotWorkerTest` makes, and one more. `CoreModule
 *       .provideDatabase` **throws** when the session is locked, so a worker that reached for the
 *       repository on a locked device would take the process down from a job the user never started.
 *       "The lock check happens before anything injects" is a claim only a test watching the
 *       repository can make.
 *
 *       The other claim is about **what counts as failure**. Every ordinary outcome of this job is
 *       zero rows — no consent, no keyed holdings, nothing due, no backend. If any of those were
 *       reported as a failure, WorkManager would back off with retries for ever against a condition
 *       that will never change, on every device that has not opted in. That is the whole install
 *       base today, since there is no proxy.
 * What: the locked path, the unlocked path, the zero-rows path, and a storage failure.
 * Result: a background job that cannot crash a locked app and cannot retry-storm a quiet one.
 * Changelog: 2026-08-29 — Created for issue 6.5.
 */
@RunWith(RobolectricTestRunner::class)
class MarketPriceWorkerTest {
    private val sessionLock = SessionLock()
    private val repository = RecordingMarketPriceRepository()

    @Test
    fun `a locked app defers without touching the repository`() =
        runTest {
            // `callCount` staying at zero is the assertion; the retry is the consequence. This is
            // the whole reason the repository is injected as a Provider.
            val result = worker().doWork()

            assertEquals(0, repository.callCount)
            assertTrue("nothing is wrong — it just cannot run yet", result is ListenableWorker.Result.Retry)
        }

    @Test
    fun `an unlocked app refreshes`() =
        runTest {
            sessionLock.unlock()

            val result = worker().doWork()

            assertEquals(1, repository.callCount)
            assertTrue(result is ListenableWorker.Result.Success)
        }

    @Test
    fun `updating nothing is a success, not a failure`() =
        runTest {
            // The ordinary outcome on every device today: no consent, or no backend to ask. Reported
            // as a failure it would become a permanent retry loop on every install.
            sessionLock.unlock()
            repository.result = Ok(0)

            assertTrue(worker().doWork() is ListenableWorker.Result.Success)
        }

    @Test
    fun `a storage failure retries rather than giving up`() =
        runTest {
            // The database, not the network. A network failure never reaches here — the repository
            // absorbs it and returns Ok(0), because a price it could not refresh is still the best
            // price the app has (P-04).
            sessionLock.unlock()
            repository.result = Err(AppError.Storage("disk"))

            assertTrue(worker().doWork() is ListenableWorker.Result.Retry)
        }

    @Test
    fun `the daily job and the on-open job have separate stable names`() {
        // Separate, so enqueuing one cannot cancel the other: they share a unique-name space, and a
        // single name would mean opening the app silently replaced the scheduled daily refresh.
        assertEquals("market-price-refresh", MarketPriceWorker.WORK_NAME)
        assertEquals("market-price-refresh-now", MarketPriceWorker.OPEN_WORK_NAME)
        assertNotEquals(MarketPriceWorker.WORK_NAME, MarketPriceWorker.OPEN_WORK_NAME)
    }

    /** Result: a worker wired to the test's lock and repository. Output: [MarketPriceWorker]. */
    private fun worker(): MarketPriceWorker =
        TestListenableWorkerBuilder<MarketPriceWorker>(ApplicationProvider.getApplicationContext())
            .setWorkerFactory(
                object : androidx.work.WorkerFactory() {
                    override fun createWorker(
                        appContext: android.content.Context,
                        workerClassName: String,
                        workerParameters: androidx.work.WorkerParameters,
                    ) = MarketPriceWorker(appContext, workerParameters, sessionLock, Provider { repository })
                },
            )
            .build()
}

/**
 * A [MarketPriceRepository] that counts whether it was reached (issue 6.5).
 *
 * Why:    the assertion that matters is about **absence** — a locked worker must not call this at
 *         all — and only a recording double can make that visible.
 * Result: the call count and an injectable outcome.
 * Changelog: 2026-08-29 — Created for issue 6.5.
 */
private class RecordingMarketPriceRepository : MarketPriceRepository {
    /** How many times the worker asked for a refresh. Zero is the point, when locked. */
    var callCount: Int = 0
        private set

    /** What [refresh] returns; one row repriced unless a test says otherwise. */
    var result: Result<Int, AppError> = Ok(1)

    override suspend fun refresh(): Result<Int, AppError> {
        callCount++
        return result
    }
}
