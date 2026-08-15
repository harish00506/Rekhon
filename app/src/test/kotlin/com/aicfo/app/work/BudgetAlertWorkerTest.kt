package com.aicfo.app.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.aicfo.app.notification.BudgetAlertNotifier
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.crypto.SessionLock
import com.aicfo.core.model.Category
import com.aicfo.core.model.CategoryNature
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import com.aicfo.data.repository.BudgetRepository
import com.aicfo.data.repository.CategoryBudget
import com.aicfo.data.repository.CategoryBudgetAlert
import com.aicfo.data.repository.CategoryBudgetSuggestion
import com.aicfo.domain.engines.budget.BudgetAlert
import com.aicfo.domain.engines.budget.BudgetAlertBand
import com.aicfo.domain.engines.budget.BudgetReview
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
 * Tests for [BudgetAlertWorker] — the job that decides whether to interrupt someone (issue 4.5).
 *
 * Why:  three claims are worth a test here, and none of them is "it sends a notification".
 *
 *       1. **A locked app must not reach the repository at all.** `CoreModule.provideDatabase`
 *          throws when locked (SEC-002), so a worker that injected before checking would crash the
 *          process from a job the user never started. Only a recording double can prove absence.
 *       2. **Claim before notify.** The claim is what makes a duplicate impossible; reversing the
 *          order would re-notify after a crash between the two.
 *       3. **An unclaimed alert is never posted.** That is the once-per-band promise as the user
 *          experiences it.
 * What: the locked path, the ordering, the already-claimed path, a failed read, and a notifier that
 *       refuses.
 * Result: a job that cannot crash a locked app and cannot say the same thing twice.
 * Changelog: 2026-08-13 — Created for issue 4.5.
 */
@RunWith(RobolectricTestRunner::class)
class BudgetAlertWorkerTest {
    private val sessionLock = SessionLock()
    private val repository = RecordingBudgetRepository()
    private val notifier = CountingNotifier()

    @Test
    fun `a locked app defers without touching the repository`() =
        runTest {
            repository.pending = listOf(alert())

            val result = worker().doWork()

            assertEquals("the whole reason the repository is a Provider", emptyList<String>(), repository.calls)
            assertEquals(0, notifier.posted.size)
            assertTrue("nothing is wrong — it just cannot run yet", result is ListenableWorker.Result.Retry)
        }

    @Test
    fun `a pending alert is claimed and then posted, in that order`() =
        runTest {
            sessionLock.unlock()
            repository.pending = listOf(alert())

            val result = worker().doWork()

            assertEquals(listOf("pending", "claim"), repository.calls)
            assertEquals(listOf("Groceries"), notifier.posted)
            assertTrue(result is ListenableWorker.Result.Success)
        }

    @Test
    fun `an alert another run already claimed is not posted`() =
        runTest {
            // The once-per-band promise, at the seam the user feels it. `markNotified` answering
            // false is the database refusing the duplicate, and the only correct response is silence.
            sessionLock.unlock()
            repository.pending = listOf(alert())
            repository.claimResult = Ok(false)

            val result = worker().doWork()

            assertEquals(0, notifier.posted.size)
            assertTrue(
                "still a success — nothing failed, there was just nothing to say",
                result is ListenableWorker.Result.Success,
            )
        }

    @Test
    fun `nothing pending is a success, not a failure`() =
        runTest {
            // The normal outcome on most days. Reporting it as a failure would make WorkManager back
            // off for no reason and delay the day it matters.
            sessionLock.unlock()

            assertTrue(worker().doWork() is ListenableWorker.Result.Success)
            assertEquals(0, notifier.posted.size)
        }

    @Test
    fun `a failed read retries rather than giving up`() =
        runTest {
            sessionLock.unlock()
            repository.pendingResult = Err(AppError.Storage("disk"))

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Retry)
            assertEquals(0, notifier.posted.size)
        }

    @Test
    fun `a notification the user cannot receive does not retry`() =
        runTest {
            // Permission denied, or the guardrail refused the text. Neither is recoverable by trying
            // again, the claim already stands, and the in-app banner still shows the band. Retrying
            // would eventually post the same message twice.
            sessionLock.unlock()
            repository.pending = listOf(alert())
            notifier.accepts = false

            assertTrue(worker().doWork() is ListenableWorker.Result.Success)
        }

    @Test
    fun `both bands pending are both sent`() =
        runTest {
            // Two categories, two bands. A worker that stopped after the first would leave the more
            // urgent message unsent whenever it happened to be second in the list.
            sessionLock.unlock()
            repository.pending =
                listOf(alert(), alert(name = "Dining", band = BudgetAlertBand.EXCEEDED))

            worker().doWork()

            assertEquals(listOf("Groceries", "Dining"), notifier.posted)
        }

    @Test
    fun `the work is scheduled under a stable unique name`() {
        // `KEEP` on a unique name is what stops rescheduling on every launch from resetting the
        // period — a user who opens the app each morning would otherwise never reach the first run.
        assertEquals("budget-threshold-alerts", BudgetAlertWorker.WORK_NAME)
    }

    /** Result: a worker wired to the test's lock, repository and notifier. Output: the worker. */
    private fun worker(): BudgetAlertWorker =
        TestListenableWorkerBuilder<BudgetAlertWorker>(ApplicationProvider.getApplicationContext())
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ) = BudgetAlertWorker(
                        appContext,
                        workerParameters,
                        sessionLock,
                        Provider { repository },
                        notifier,
                    )
                },
            )
            .build()

    /** Result: one alert a test can hand to the repository. Input: [name]; [band]. */
    private fun alert(
        name: String = "Groceries",
        band: BudgetAlertBand = BudgetAlertBand.WARN,
    ): CategoryBudgetAlert =
        CategoryBudgetAlert(
            budgetId = "budget:$name",
            category = Category(id = "category:$name", name = name, nature = CategoryNature.NEED),
            alert =
                BudgetAlert(
                    band = band,
                    usedBps = if (band == BudgetAlertBand.EXCEEDED) 10_100 else 8_000,
                    budgeted = Money(1_000_000L),
                    spent = if (band == BudgetAlertBand.EXCEEDED) Money(1_010_000L) else Money(800_000L),
                    overspentBy = if (band == BudgetAlertBand.EXCEEDED) Money(10_000L) else null,
                    provenance =
                        EngineProvenance(
                            engineId = "budget-planner",
                            engineVersion = "1.0",
                            computedAtUtcMillis = 1_786_000_000_000L,
                            evidence = listOf(RuleCitation("RULE-BUD-ALERT", "1.0")),
                        ),
                ),
        )
}

/**
 * A [BudgetRepository] that records what the worker asked of it (issue 4.5).
 *
 * Why:    two assertions here are about *order* and one is about *absence*, and neither is visible
 *         through a mock's return values alone — the same argument `RecordingNetWorthRepository`
 *         makes. Everything the worker does not call throws, so a worker that quietly grew a new
 *         dependency fails loudly rather than passing on a stub's default.
 * Result: the call log and injectable outcomes.
 * Changelog: 2026-08-13 — Created for issue 4.5.
 */
private class RecordingBudgetRepository : BudgetRepository {
    /** What the worker did, in order — so a test can assert the claim came before the post. */
    val calls: MutableList<String> = mutableListOf()

    /** What is waiting to be sent. */
    var pending: List<CategoryBudgetAlert> = emptyList()

    /** Overrides the pending read's outcome; the list above is used when this is null. */
    var pendingResult: Result<List<CategoryBudgetAlert>, AppError>? = null

    /** What `markNotified` returns — `Ok(false)` is another run having claimed it first. */
    var claimResult: Result<Boolean, AppError> = Ok(true)

    override suspend fun pendingAlerts(): Result<List<CategoryBudgetAlert>, AppError> {
        calls += "pending"
        return pendingResult ?: Ok(pending)
    }

    override suspend fun markNotified(alert: CategoryBudgetAlert): Result<Boolean, AppError> {
        calls += "claim"
        return claimResult
    }

    override fun observeBudgets(): Flow<List<CategoryBudget>> = error("the worker must not read budgets")

    override fun observeSuggestions(): Flow<List<CategoryBudgetSuggestion>> =
        error("the worker must not read suggestions")

    override fun observeAlerts(): Flow<List<CategoryBudgetAlert>> = flowOf(emptyList())

    override suspend fun setBudget(
        categoryId: String,
        amount: Money,
        rolloverEnabled: Boolean,
    ): Result<String, AppError> = error("the worker must never write a budget (P-07)")

    override suspend fun acceptSuggestion(categoryId: String): Result<String, AppError> =
        error("the worker must never accept a suggestion on the user's behalf (P-07)")

    override suspend fun deleteBudget(id: String): Result<Unit, AppError> = error("the worker must never delete")

    override fun observeReview(): Flow<BudgetReview?> = error("the worker must not read the monthly review")

    override suspend fun acceptReviewProposal(categoryId: String): Result<String, AppError> =
        error("the worker must never accept a review proposal on the user's behalf (P-07)")

    override suspend fun dismissReview(): Result<Boolean, AppError> =
        error("the worker must never dismiss the review on the user's behalf")
}

/**
 * A [BudgetAlertNotifier] that records what it was asked to post (issue 4.5).
 *
 * Why:    the real notifier needs a context, a channel and a granted runtime permission, none of
 *         which a JVM test can arrange honestly — and none of which is what these tests are about.
 *         What they are about is *which* alerts reach it, and in what order.
 * Result: the posted category names, and a switch for the refused case.
 * Changelog: 2026-08-13 — Created for issue 4.5.
 */
private class CountingNotifier : BudgetAlertNotifier {
    /** The categories a notification was posted for, in order. */
    val posted: MutableList<String> = mutableListOf()

    /** `false` stands for permission denied or a guardrail refusal — the caller cannot tell them apart. */
    var accepts: Boolean = true

    override fun notify(alert: CategoryBudgetAlert): Boolean {
        if (!accepts) return false
        posted += alert.category.name
        return true
    }
}
