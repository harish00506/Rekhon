package com.aicfo.app.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.aicfo.app.FakeAppSettingsStore
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Result
import com.aicfo.core.crypto.SessionLock
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import com.aicfo.data.repository.NetWorthRepository
import com.aicfo.data.repository.SafeToSpendRepository
import com.aicfo.domain.engines.networth.NetWorthRange
import com.aicfo.domain.engines.networth.NetWorthResult
import com.aicfo.domain.engines.networth.NetWorthTrend
import com.aicfo.domain.engines.safetospend.SafeToSpend
import com.aicfo.domain.engines.safetospend.SafeToSpendComponent
import com.aicfo.domain.engines.safetospend.SafeToSpendLine
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
 * Tests for [WidgetRefreshWorker] — filling the widget's cache (issue 5.5; SEC-002, P-03/P-04).
 *
 * Why:  the widget renders from a cache it cannot fill itself (ADR-0024), so this worker is the
 *       only thing standing between the home screen and a permanently blank widget. Two of its
 *       behaviours are not obvious from reading it:
 *
 *       **It must not touch the database while the app is locked.** `CoreModule.provideDatabase`
 *       *throws* when the session is locked, so resolving a repository there would take the process
 *       down from a job the user never started — the failure `NetWorthSnapshotWorker` was built
 *       around, and the reason both repositories arrive as `Provider`s. That the check happens
 *       *before* injection is a claim only a test watching the repositories can make.
 *
 *       **A missing figure is a success, not a failure.** `SafeToSpendRepository` emits `null` for
 *       a profile with no income basis. Retrying that would back off for ever against a state that
 *       is not going to change on its own, and the widget would never learn to say "not yet worked
 *       out" (P-03).
 * What: the locked path, the unlocked path, the no-figure path, idempotency, and the schedule.
 * Result: a refresh that is safe to run on a locked phone and safe to run twice.
 * Changelog: 2026-08-17 — Created for issue 5.5.
 *
 * **What this file does not cover, deliberately.** It asserts what the worker *reads* and what it
 * returns, not the pixels that result: writing Glance state needs a placed widget and an
 * `AppWidgetHost`, which does not exist on the JVM. The key mapping either side of that write is
 * covered by `:widget`'s `WidgetSnapshotTest`, and the end-to-end is the emulator step in the
 * tracker's phase 8 — where it is logged as an observation, not claimed as a test.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetRefreshWorkerTest {
    private val sessionLock = SessionLock()
    private val safeToSpend = RecordingSafeToSpendRepository()
    private val netWorth = RecordingWidgetNetWorthRepository()
    private val settings = FakeAppSettingsStore()

    @Test
    fun `a locked app defers without touching either repository`() =
        runTest {
            // The whole reason both repositories are Providers. The two zeros are the assertion;
            // the retry is the consequence. A `failure()` here would be a lie — nothing is wrong.
            val result = worker().doWork()

            assertEquals(0, safeToSpend.reads)
            assertEquals(0, netWorth.reads)
            assertTrue("nothing is wrong — it just cannot read yet", result is ListenableWorker.Result.Retry)
        }

    @Test
    fun `an unlocked app reads both figures`() =
        runTest {
            sessionLock.unlock()

            val result = worker().doWork()

            assertEquals(1, safeToSpend.reads)
            assertEquals(1, netWorth.reads)
            assertTrue(result is ListenableWorker.Result.Success)
        }

    @Test
    fun `no Safe-to-Spend figure is still a success`() =
        runTest {
            // A profile with neither envelopes nor posted income: the repository answers `null`
            // rather than a zero, and the widget shows its pending label. Retrying would back off
            // for ever against a state that only the user can change.
            sessionLock.unlock()
            safeToSpend.figure = null

            assertTrue(worker().doWork() is ListenableWorker.Result.Success)
        }

    @Test
    fun `running twice is a no-op the second time`() =
        runTest {
            // Idempotency (the `workmanager-jobs` rule). Nothing written is derived from the clock,
            // so a second run with unchanged data writes the same bytes — there is no timestamp to
            // make two identical refreshes differ, which is why this worker needs no period claim.
            sessionLock.unlock()

            val first = worker().doWork()
            val second = worker().doWork()

            assertTrue(first is ListenableWorker.Result.Success)
            assertTrue(second is ListenableWorker.Result.Success)
            assertEquals(2, safeToSpend.reads)
        }

    @Test
    fun `the two work names are distinct and stable`() {
        // They must not collide: the on-demand refresh uses REPLACE, so sharing a name with the
        // periodic job would cancel the six-hourly cadence on every app launch — the widget would
        // then only ever update while the app was being opened.
        assertEquals("home-widget-refresh", WidgetRefreshWorker.WORK_NAME)
        assertEquals("home-widget-refresh-now", WidgetRefreshWorker.REFRESH_NOW_WORK_NAME)
    }

    /** Result: a worker wired to the test's lock, repositories and settings. Output: the worker. */
    private fun worker(): WidgetRefreshWorker =
        TestListenableWorkerBuilder<WidgetRefreshWorker>(ApplicationProvider.getApplicationContext())
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ) = WidgetRefreshWorker(
                        appContext,
                        workerParameters,
                        sessionLock,
                        Provider { safeToSpend },
                        Provider { netWorth },
                        settings,
                    )
                },
            )
            .build()
}

/**
 * A [SafeToSpendRepository] that counts whether it was reached (issue 5.5).
 *
 * Why:    the assertion that matters is about **absence** — a locked worker must not read this at
 *         all — and only a recording double makes that visible. A mock would answer the same
 *         question with less to read.
 * Result: the read count and an injectable figure.
 * Changelog: 2026-08-17 — Created for issue 5.5.
 */
private class RecordingSafeToSpendRepository : SafeToSpendRepository {
    /** How many times the worker asked for the figure. Zero is the point, when locked. */
    var reads: Int = 0
        private set

    /**
     * What the flow emits; `null` models a profile with no income basis (P-03).
     *
     * The breakdown line is not decoration: `SafeToSpend` refuses to exist without one *and*
     * refuses to exist unless the lines add up to the figure they explain — a figure with no
     * derivation is the black-box verdict §5.2 forbids (P-02). So the single income line here
     * equals the amount. The widget shows only the headline, because a home screen has no room for
     * a breakdown; the app it opens on tap is where that breakdown is read.
     */
    var figure: SafeToSpend? =
        SafeToSpend(
            amount = Money(12_345_67),
            lines = listOf(SafeToSpendLine(SafeToSpendComponent.INCOME, Money(12_345_67))),
            provenance = WIDGET_TEST_PROVENANCE,
        )

    override fun observeSafeToSpend(): Flow<SafeToSpend?> {
        reads++
        return flowOf(figure)
    }
}

/**
 * A [NetWorthRepository] that counts whether it was reached (issue 5.5).
 *
 * Why:    the same absence assertion as its Safe-to-Spend counterpart. Separate from
 *         `RecordingNetWorthRepository` in `NetWorthSnapshotWorkerTest` because that one records
 *         write calls and returns nothing from `observeCurrent()`, which this worker reads — a
 *         shared double would have to serve two opposite purposes.
 * Result: the read count and a fixed figure.
 * Changelog: 2026-08-17 — Created for issue 5.5.
 */
private class RecordingWidgetNetWorthRepository : NetWorthRepository {
    /** How many times the worker asked for net worth. */
    var reads: Int = 0
        private set

    override fun observeCurrent(): Flow<NetWorthResult> {
        reads++
        return flowOf(
            NetWorthResult(
                asOfIsoDate = "2026-08-17",
                assets = Money(6_00_000_00),
                liabilities = Money(1_00_000_00),
                netWorth = Money(5_00_000_00),
                provenance = WIDGET_TEST_PROVENANCE,
            ),
        )
    }

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

    override suspend fun computeAsOf(asOfIsoDate: String): Result<NetWorthResult, AppError> = Err(AppError.NotFound)

    override suspend fun snapshotUpToToday(): Result<Int, AppError> = Err(AppError.NotFound)

    override suspend fun repairStaleHistory(): Result<Int, AppError> = Err(AppError.NotFound)
}

/**
 * Provenance for the doubles above (issue 5.5).
 *
 * The widget never reads any of it — a home screen has no room for a breakdown — but `SafeToSpend`
 * refuses to exist without a cited rule (P-02, AI-ARC-006), so a fixture that omitted it would not
 * be modelling the type the worker actually receives.
 */
private val WIDGET_TEST_PROVENANCE =
    EngineProvenance(
        engineId = "test",
        engineVersion = "1.0",
        inputWindow = "2026-08",
        computedAtUtcMillis = 0L,
        evidence = listOf(RuleCitation(ruleId = "RULE-STS", ruleVersion = "1.0")),
    )
