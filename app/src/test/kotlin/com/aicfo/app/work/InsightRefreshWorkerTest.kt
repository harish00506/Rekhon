package com.aicfo.app.work

import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.crypto.SessionLock
import com.aicfo.data.repository.FeedInsight
import com.aicfo.data.repository.InsightRepository
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
 * The daily orchestrator run (issue 9.5; §7.2's `day_rollover` trigger, AI-ARC-005).
 *
 * Why:  the worker exists so the feed is true when the user next opens the app, and it must do that
 *       without ever touching the database while the session is locked — the gated provider would
 *       throw, and a crash in a background job is a crash the user cannot explain. A refusal from
 *       the pipeline is a retry, not a swallowed failure.
 * What: the locked case, the ordinary case, and the refusal.
 * Result: the trigger is checked on every `unitTests` run.
 * Changelog: 2026-09-20 — Created for issue 9.5.
 */
@RunWith(RobolectricTestRunner::class)
class InsightRefreshWorkerTest {
    private val sessionLock = SessionLock()
    private val repository = RecordingInsightRepository()

    @Test
    fun `a locked app defers without touching the pipeline`() =
        runTest {
            // `callCount` staying at zero is the assertion; the retry is only the consequence.
            val result = worker().doWork()

            assertEquals(0, repository.callCount)
            assertTrue("nothing is wrong — it just cannot run yet", result is ListenableWorker.Result.Retry)
        }

    @Test
    fun `an unlocked app recomputes the feed once`() =
        runTest {
            sessionLock.unlock()

            val result = worker().doWork()

            assertEquals(1, repository.callCount)
            assertTrue(result is ListenableWorker.Result.Success)
        }

    @Test
    fun `a pipeline that refuses its input is retried rather than reported as done`() =
        runTest {
            sessionLock.unlock()
            repository.refusal = AppError.Validation("insight.forecast")

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Retry)
        }

    private fun worker(): InsightRefreshWorker =
        TestListenableWorkerBuilder<InsightRefreshWorker>(ApplicationProvider.getApplicationContext())
            .setWorkerFactory(
                object : androidx.work.WorkerFactory() {
                    override fun createWorker(
                        appContext: android.content.Context,
                        workerClassName: String,
                        workerParameters: androidx.work.WorkerParameters,
                    ) = InsightRefreshWorker(appContext, workerParameters, sessionLock, Provider { repository })
                },
            )
            .build()

    /** Counts the refreshes, and can refuse one. */
    private class RecordingInsightRepository : InsightRepository {
        var callCount: Int = 0
        var refusal: AppError? = null

        override fun observeFeed(): Flow<List<FeedInsight>> = flowOf(emptyList())

        override fun observeDashboard(): Flow<List<FeedInsight>> = flowOf(emptyList())

        override suspend fun refresh(): Result<Int, AppError> {
            callCount++
            return refusal?.let { Err(it) } ?: Ok(0)
        }

        override suspend fun dismiss(id: String): Result<Unit, AppError> = Ok(Unit)

        override suspend fun snooze(id: String): Result<Unit, AppError> = Ok(Unit)

        override suspend fun act(id: String): Result<Unit, AppError> = Ok(Unit)
    }
}
