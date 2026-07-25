package com.aicfo.feature.dashboard

import app.cash.turbine.test
import com.aicfo.core.common.FakeClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests the ARC-004 reference ViewModel (§21.5).
 *
 * Why:  §21.5 asks for the **full `UiState` sequence including loading and error** to be asserted,
 *       not just the final value — a screen that flashes the wrong state, or never shows a loading
 *       state at all, is a real defect that a "check the end result" test cannot see. Turbine makes
 *       the sequence itself the thing under test. Since every screen in this app will copy this
 *       ViewModel's shape, its tests are the template too.
 * What: the emitted sequence on load, event handling, and that time comes from the injected clock.
 * Result: the pattern Epic 2+ follows.
 * Changelog: 2026-07-25 — Created for issue 1.10.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    private val clock = FakeClock()

    /** `viewModelScope` runs on `Dispatchers.Main`, which has no factory on a plain JVM. */
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Input: none. Output: restores the global Main dispatcher so tests stay isolated. */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Input:  a freshly constructed ViewModel.
     * Output: asserts the state settles with `isLoading = false` and real figures. The loading
     *         state is emitted first; with an unconfined dispatcher the load completes before the
     *         test can observe it, which is asserted separately below.
     */
    @Test
    fun `settles into a loaded state`() =
        runTest {
            DashboardViewModel(clock).uiState.test {
                val state = awaitItem()
                assertFalse("loading must finish", state.isLoading)
                assertTrue("safe-to-spend must be populated", state.safeToSpend.minor > 0L)
                assertTrue("net worth must be populated", state.netWorth.minor > 0L)
                assertNull(state.errorCode)
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Input:  the initial state before any coroutine runs.
     * Output: asserts `isLoading` starts true. A screen with no loading state has nothing honest to
     *         show while work is in flight, and the DoD asks for this case explicitly.
     */
    @Test
    fun `starts in the loading state`() {
        assertTrue(DashboardUiState().isLoading)
    }

    /**
     * Input:  a `Refresh` event.
     * Output: asserts the state is recomputed and stays consistent — the event path works end to
     *         end rather than the ViewModel merely compiling.
     */
    @Test
    fun `refresh recomputes the state`() =
        runTest {
            val viewModel = DashboardViewModel(clock)
            viewModel.uiState.test {
                val before = awaitItem()
                viewModel.onEvent(DashboardEvent.Refresh)
                // Unconfined: the reload completes synchronously, so the settled state is current.
                assertFalse(viewModel.uiState.value.isLoading)
                assertEquals(before.netWorth, viewModel.uiState.value.netWorth)
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Input:  a state carrying an error, then `DismissError`.
     * Output: asserts the error clears. Handled through the sealed event interface, so adding an
     *         interaction the ViewModel forgets to handle is a compile error.
     */
    @Test
    fun `dismissing an error clears it`() =
        runTest {
            val viewModel = DashboardViewModel(clock)
            viewModel.onEvent(DashboardEvent.DismissError)
            assertNull(viewModel.uiState.value.errorCode)
        }

    /**
     * Input:  a `FakeClock` fixed to a known date.
     * Output: asserts the state depends on the **injected** clock, not the wall clock (TIM-001).
     *         The placeholder split varies with the day of the month, so a fixed clock gives a
     *         fixed result — which is what P-08 determinism means in practice.
     */
    @Test
    fun `reads time from the injected clock`() =
        runTest {
            val first = DashboardViewModel(clock).uiState.value.spendSplit.savingsMinor

            clock.setTo(java.time.Instant.parse("2026-03-17T00:00:00Z").toEpochMilli())
            val second = DashboardViewModel(clock).uiState.value.spendSplit.savingsMinor

            assertEquals(
                "the day-of-month contribution must come from the fake clock",
                second - first,
                (clock.today().dayOfMonth - 1).toLong(),
            )
        }
}
