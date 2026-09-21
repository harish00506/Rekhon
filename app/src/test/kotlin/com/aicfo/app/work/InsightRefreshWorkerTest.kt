package com.aicfo.app.work

import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.aicfo.app.FakeAppSettingsStore
import com.aicfo.app.FakeNotificationRepository
import com.aicfo.app.notification.InsightNotifications
import com.aicfo.app.notification.InsightNotifier
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.crypto.SessionLock
import com.aicfo.core.model.Money
import com.aicfo.data.repository.FeedInsight
import com.aicfo.data.repository.InsightRepository
import com.aicfo.data.repository.InsightStatus
import com.aicfo.domain.engines.insight.Insight
import com.aicfo.domain.engines.insight.InsightType
import com.aicfo.domain.engines.notification.NotificationKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
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
 *            2026-09-20 — Issue 9.6: stage 6 — what is offered to the gate, in what order, and what is posted.
 */
@RunWith(RobolectricTestRunner::class)
class InsightRefreshWorkerTest {
    private val sessionLock = SessionLock()
    private val repository = RecordingInsightRepository()
    private val gate = FakeNotificationRepository()
    private val settings = FakeAppSettingsStore()
    private val notifier = RecordingInsightNotifier()

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

    @Test
    fun `only the insights §17 notifies are offered, in the feed's order and under their rows`() =
        runTest {
            sessionLock.unlock()
            repository.feed =
                listOf(
                    card(InsightType.GOAL_BEHIND, "goal:car"),
                    card(InsightType.SEASONAL_MONTH),
                    card(InsightType.CRUNCH_DAY),
                )

            worker().doWork()

            assertEquals(
                listOf("GOAL_BEHIND" to NotificationKind.GOAL_EVENT, "CRUNCH_DAY" to NotificationKind.CRITICAL_MONEY),
                gate.offered.single().map { it.key.removePrefix("insight:").substringBefore('|') to it.kind },
            )
            assertEquals(listOf(InsightType.GOAL_BEHIND, InsightType.CRUNCH_DAY), notifier.posted)
        }

    @Test
    fun `a card the user dismissed or snoozed is not offered`() =
        runTest {
            // "Not now" from the user outranks the policy's "yes".
            sessionLock.unlock()
            repository.feed =
                listOf(
                    card(InsightType.CRUNCH_DAY, status = InsightStatus.DISMISSED),
                    card(InsightType.GOAL_BEHIND, "goal:car", status = InsightStatus.SNOOZED),
                )

            worker().doWork()

            assertTrue("nothing to offer is not a call to the gate", gate.offered.isEmpty())
            assertTrue(notifier.posted.isEmpty())
        }

    @Test
    fun `what the gate holds is not posted`() =
        runTest {
            sessionLock.unlock()
            val goal = card(InsightType.GOAL_BEHIND, "goal:car")
            repository.feed = listOf(goal, card(InsightType.CRUNCH_DAY))
            gate.held = setOf(InsightNotifications.keyFor(goal.insight))

            worker().doWork()

            assertEquals(listOf(InsightType.CRUNCH_DAY), notifier.posted)
        }

    @Test
    fun `a gate that cannot answer retries, and nothing is posted`() =
        runTest {
            sessionLock.unlock()
            repository.feed = listOf(card(InsightType.CRUNCH_DAY))
            gate.failure = AppError.Storage("disk")

            assertTrue(worker().doWork() is ListenableWorker.Result.Retry)
            assertTrue(notifier.posted.isEmpty())
        }

    @Test
    fun `the privacy blur reaches the notification, and its absence too`() =
        runTest {
            sessionLock.unlock()
            repository.feed = listOf(card(InsightType.CRUNCH_DAY))
            worker().doWork()

            settings.setPrivacyBlurEnabled(true)
            gate.held = emptySet()
            worker().doWork()

            assertEquals(listOf(false, true), notifier.blurFlags)
        }

    private fun card(
        type: InsightType,
        subject: String? = null,
        status: InsightStatus = InsightStatus.ACTIVE,
    ) = FeedInsight(
        id = "insight:${type.name}",
        insight =
            Insight(
                type = type,
                subject = subject,
                subjectLabel = subject?.let { "Car" },
                period = "2026-09-20",
                amount = Money(1_000_00L),
                date = LocalDate.parse("2026-09-28"),
                sourceEngineId = "AI-FCT",
                sourceEngineVersion = "1.0",
            ),
        status = status,
    )

    private fun worker(): InsightRefreshWorker =
        TestListenableWorkerBuilder<InsightRefreshWorker>(ApplicationProvider.getApplicationContext())
            .setWorkerFactory(
                object : androidx.work.WorkerFactory() {
                    override fun createWorker(
                        appContext: android.content.Context,
                        workerClassName: String,
                        workerParameters: androidx.work.WorkerParameters,
                    ) = InsightRefreshWorker(
                        appContext,
                        workerParameters,
                        sessionLock,
                        Provider { repository },
                        Provider { gate },
                        settings,
                        notifier,
                    )
                },
            )
            .build()

    /** Counts the refreshes, and can refuse one. */
    private class RecordingInsightRepository : InsightRepository {
        var callCount: Int = 0
        var refusal: AppError? = null

        var feed: List<FeedInsight> = emptyList()

        override fun observeFeed(): Flow<List<FeedInsight>> = flowOf(feed)

        override fun observeDashboard(): Flow<List<FeedInsight>> = flowOf(emptyList())

        override suspend fun refresh(): Result<Int, AppError> {
            callCount++
            return refusal?.let { Err(it) } ?: Ok(0)
        }

        override suspend fun dismiss(id: String): Result<Unit, AppError> = Ok(Unit)

        override suspend fun snooze(id: String): Result<Unit, AppError> = Ok(Unit)

        override suspend fun act(id: String): Result<Unit, AppError> = Ok(Unit)
    }

    /** Records what it was asked to post, and with which blur flag. */
    private class RecordingInsightNotifier : InsightNotifier {
        val posted: MutableList<InsightType> = mutableListOf()
        val blurFlags: MutableList<Boolean> = mutableListOf()

        override fun notify(
            insight: Insight,
            kind: NotificationKind,
            blurAmounts: Boolean,
        ): Boolean {
            posted += insight.type
            blurFlags += blurAmounts
            return true
        }
    }
}
