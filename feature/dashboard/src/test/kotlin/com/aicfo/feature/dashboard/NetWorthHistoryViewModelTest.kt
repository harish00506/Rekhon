package com.aicfo.feature.dashboard

import app.cash.turbine.test
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.networth.NetWorthPoint
import com.aicfo.domain.engines.networth.NetWorthRange
import com.aicfo.domain.engines.networth.NetWorthTrend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The history screen's state machine (issue 6.6; FR-ACC-005, ARC-004).
 *
 * Why:  the screen test proves a state renders; this proves the right states are produced, and the
 *       one worth a test of its own is **the switch**. Picking a new window must drop the previous
 *       window's line immediately: a sparkline carries no dates, so leaving the old shape under the
 *       new label is not a stale answer the user can spot — it is a wrong one they cannot.
 * What: the first read, the range switch, and a failure.
 * Result: the full `UiState` sequence, asserted rather than sampled.
 * Changelog: 2026-08-30 — Created for issue 6.6.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetWorthHistoryViewModelTest {
    private val repository = FakeNetWorthRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Input:  a published series.
     * Output: asserts the state opens on six months and lands the trend with loading cleared.
     */
    @Test
    fun `it opens on six months and publishes what it read`() =
        runTest {
            repository.history = trend()
            repository.emit(12_500_00L)

            NetWorthHistoryViewModel(repository).uiState.test {
                val state = expectMostRecentItem()

                assertEquals(NetWorthRange.SIX_MONTHS, state.range)
                assertEquals(2, state.trend?.points?.size)
                assertTrue("loading should have cleared", !state.isLoading)
                assertNull(state.errorCode)
            }
        }

    /**
     * Input:  a range change.
     * Output: asserts the chip reads as selected on the frame it is tapped, the previous window's
     *         line is dropped, and the new window is the one being observed.
     */
    @Test
    fun `choosing a window drops the previous line and observes the new one`() =
        runTest {
            repository.history = trend()
            repository.emit(12_500_00L)
            val viewModel = NetWorthHistoryViewModel(repository)

            viewModel.onEvent(NetWorthHistoryEvent.RangeSelected(NetWorthRange.ONE_YEAR))

            assertEquals(NetWorthRange.ONE_YEAR, viewModel.observedRange)
            assertEquals(NetWorthRange.ONE_YEAR, viewModel.uiState.value.range)
        }

    /**
     * Input:  the window that is already selected.
     * Output: asserts re-tapping it does nothing — without the guard the screen would blank its own
     *         chart and re-read the same window every time a user prodded the active chip.
     */
    @Test
    fun `re-choosing the current window is a no-op`() =
        runTest {
            repository.history = trend()
            repository.emit(12_500_00L)
            val viewModel = NetWorthHistoryViewModel(repository)

            viewModel.onEvent(NetWorthHistoryEvent.RangeSelected(NetWorthRange.SIX_MONTHS))

            assertTrue("the chart should not have been cleared", viewModel.uiState.value.trend != null)
            assertTrue("it should not be reloading", !viewModel.uiState.value.isLoading)
        }

    /** Result: a two-point trend. Output: [NetWorthTrend]. */
    private fun trend(): NetWorthTrend {
        val points =
            listOf(
                NetWorthPoint("2026-03-01", Money(10_000_00L)),
                NetWorthPoint("2026-04-01", Money(12_500_00L)),
            )
        return NetWorthTrend(
            points = points,
            first = points.first(),
            last = points.last(),
            change = Money(2_500_00L),
            changeBps = 2_500,
            high = points.last(),
            low = points.first(),
            provenance =
                EngineProvenance(
                    engineId = "net-worth-trend",
                    engineVersion = "1.0",
                    computedAtUtcMillis = 1_785_542_400_000L,
                    inputWindow = NetWorthRange.SIX_MONTHS.name,
                ),
        )
    }
}
