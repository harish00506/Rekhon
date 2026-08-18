package com.aicfo.app.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.aicfo.app.FakeAppSettingsStore
import com.aicfo.app.notification.CardAlertNotifier
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.crypto.SessionLock
import com.aicfo.core.model.CreditCard
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import com.aicfo.data.repository.CardAlertForAccount
import com.aicfo.data.repository.CreditCardRepository
import com.aicfo.domain.engines.card.CardAlert
import com.aicfo.domain.engines.card.CardAlertKind
import com.aicfo.domain.engines.card.CardStatus
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
 * Tests for [CardAlertWorker] — the job that must not cost someone a late fee (issue 6.1).
 *
 * Why:  four behaviours, and none of them is visible by reading the twelve lines of `doWork`:
 *
 *       **The lock check happens before injection.** `CoreModule.provideDatabase` throws while the
 *       session is locked (SEC-002), so resolving the repository on a locked device would take the
 *       process down from a job the user never started. "Before" is a claim only a test watching
 *       the `Provider` can make.
 *
 *       **Claim before notify.** With the order reversed, a crash between them re-notifies on the
 *       next run — on the channel §17.1 calls Critical, which is the one a user must not learn to
 *       mute. The ordering is asserted directly rather than inferred from counts.
 *
 *       **An unclaimed alert is never posted.** The whole point of the claim is that losing it
 *       means staying silent.
 *
 *       **A refusing notifier is not a failure.** Permission denied is a state this feature is
 *       designed for — the in-app card screen carries the same information — so the run must
 *       succeed and the claim must stand, rather than retrying for ever against a switch the user
 *       deliberately turned off.
 * What: the locked path, the ordering, the unclaimed path, the refusal, and the blur flag.
 * Result: a reminder that is sent once, or not at all, and never twice.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 */
@RunWith(RobolectricTestRunner::class)
class CardAlertWorkerTest {
    private val sessionLock = SessionLock()

    /**
     * One list shared by both doubles.
     *
     * The ordering claim spans two objects — the repository claims, then the notifier posts — so a
     * trace per object could only ever prove that each happened, never that one preceded the other.
     * That is exactly the property at stake.
     */
    private val trace = mutableListOf<String>()
    private val repository = RecordingCardRepository(trace)
    private val notifier = RecordingCardNotifier(trace)
    private val settings = FakeAppSettingsStore()

    @Test
    fun `a locked app defers without touching the repository`() =
        runTest {
            // The whole reason the repository is a Provider. `resolutions` staying at zero is the
            // assertion; the retry is the consequence.
            val result = worker().doWork()

            assertEquals(0, repository.resolutions)
            assertTrue("nothing is wrong — it just cannot read yet", result is ListenableWorker.Result.Retry)
        }

    @Test
    fun `an unlocked app claims before it notifies`() =
        runTest {
            sessionLock.unlock()

            val result = worker().doWork()

            assertEquals(listOf("claim:DUE_SOON", "notify:DUE_SOON"), trace)
            assertTrue(result is ListenableWorker.Result.Success)
        }

    @Test
    fun `an alert the run did not claim is never posted`() =
        runTest {
            // Another worker, or an earlier run, already claimed it.
            sessionLock.unlock()
            repository.claimSucceeds = false

            worker().doWork()

            assertEquals("the claim was lost, so nothing may be posted", listOf("claim:DUE_SOON"), trace)
        }

    @Test
    fun `a refusing notifier still succeeds and keeps the claim`() =
        runTest {
            // Permission denied is a supported state, not a failure: retrying would re-run daily
            // against a switch the user turned off, and the claim standing is what stops the
            // reminder queueing up behind it.
            sessionLock.unlock()
            notifier.posts = false

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            assertEquals("the claim stands even though the post did not land", listOf("claim:DUE_SOON"), trace)
        }

    @Test
    fun `a failed read retries rather than giving up`() =
        runTest {
            sessionLock.unlock()
            repository.readFails = true

            assertTrue(worker().doWork() is ListenableWorker.Result.Retry)
        }

    @Test
    fun `the privacy blur reaches the notification`() =
        runTest {
            // NTF-004: a lock-screen notification must respect the hide-amounts setting, and this
            // worker is the only thing that can tell the notifier about it.
            sessionLock.unlock()
            settings.setPrivacyBlurEnabled(true)

            worker().doWork()

            assertEquals(listOf(true), notifier.blurFlags)
        }

    @Test
    fun `the work is scheduled under a stable unique name`() {
        // KEEP on a unique name is what stops rescheduling on every launch resetting the period —
        // a user who opens the app each morning would otherwise never reach the first run.
        assertEquals("card-payment-alerts", CardAlertWorker.WORK_NAME)
    }

    /** Result: a worker wired to the test's lock, repository, settings and notifier. */
    private fun worker(): CardAlertWorker =
        TestListenableWorkerBuilder<CardAlertWorker>(ApplicationProvider.getApplicationContext())
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ) = CardAlertWorker(
                        appContext,
                        workerParameters,
                        sessionLock,
                        Provider { repository.also { it.resolutions++ } },
                        settings,
                        notifier,
                    )
                },
            )
            .build()
}

/**
 * A [CreditCardRepository] that records the order it was used in (issue 6.1).
 *
 * Why:    two assertions need it and neither is a count. **Absence** — a locked worker must not
 *         resolve this at all — and **order** — the claim must precede the notification. A mock
 *         would answer both more slowly and with less to read.
 * Result: the trace, the resolution count, and injectable outcomes.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 */
private class RecordingCardRepository(
    private val trace: MutableList<String>,
) : CreditCardRepository {
    /** How many times the `Provider` was dereferenced. Zero is the point, when locked. */
    var resolutions: Int = 0

    /** Whether this run wins the claim. `false` models a row another run already wrote. */
    var claimSucceeds: Boolean = true

    /** Whether the read fails, so the retry path can be reached. */
    var readFails: Boolean = false

    override fun observeCardStatuses(): Flow<Map<String, CardStatus>> = flowOf(emptyMap())

    override suspend fun find(accountId: String): Result<CreditCard?, AppError> = Ok(null)

    override suspend fun save(card: CreditCard): Result<Unit, AppError> = Ok(Unit)

    override suspend fun pendingAlerts(): Result<List<CardAlertForAccount>, AppError> =
        if (readFails) Err(AppError.Storage("disk")) else Ok(listOf(PENDING))

    override suspend fun markNotified(alert: CardAlert): Result<Boolean, AppError> {
        trace += "claim:${alert.kind.name}"
        return Ok(claimSucceeds)
    }
}

/** A notifier that records what it was asked to post (issue 6.1). */
private class RecordingCardNotifier(
    private val trace: MutableList<String>,
) : CardAlertNotifier {
    /** The blur flag each call received, so NTF-004's path is assertable. */
    val blurFlags: MutableList<Boolean> = mutableListOf()

    /** Whether the post succeeds; `false` models a denied permission. */
    var posts: Boolean = true

    override fun notify(
        alert: CardAlertForAccount,
        blurAmounts: Boolean,
    ): Boolean {
        blurFlags += blurAmounts
        if (posts) trace += "notify:${alert.alert.kind.name}"
        return posts
    }
}

/** One pending alert: a ₹70,000 statement due in three days on a card called "HDFC Card". */
private val PENDING =
    CardAlertForAccount(
        accountName = "HDFC Card",
        alert =
            CardAlert(
                kind = CardAlertKind.DUE_SOON,
                accountId = "account:1",
                cycleStartIsoDate = "2026-08-06",
                amount = Money(70_000_00L),
                creditLimit = Money(2_00_000_00L),
                minimumDue = Money(3_500_00L),
                daysUntilDue = 3,
                ratioBps = null,
                provenance =
                    EngineProvenance(
                        engineId = "card-planner",
                        engineVersion = "1.0",
                        computedAtUtcMillis = 0L,
                        evidence = listOf(RuleCitation("RULE-CC-DUE", "1.0")),
                    ),
            ),
    )
