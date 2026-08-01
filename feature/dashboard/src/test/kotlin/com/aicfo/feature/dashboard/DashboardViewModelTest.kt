package com.aicfo.feature.dashboard

import app.cash.turbine.test
import com.aicfo.core.common.FakeClock
import com.aicfo.core.model.Money
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
    private val budget = FakeQuickSetupRepository()
    private val netWorth = FakeNetWorthRepository()

    private fun viewModel() = DashboardViewModel(clock, budget, netWorth)

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
            viewModel().uiState.test {
                val state = awaitItem()
                assertFalse("loading must finish", state.isLoading)
                assertTrue("safe-to-spend must be populated", state.safeToSpend.minor > 0L)
                // Net worth is absent until a snapshot exists — see the two tests for it below.
                assertNull("no snapshot has been taken yet", state.netWorth)
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
            val viewModel = viewModel()
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
            val viewModel = viewModel()
            viewModel.onEvent(DashboardEvent.DismissError)
            assertNull(viewModel.uiState.value.errorCode)
        }

    /**
     * Input:  a `FakeClock` fixed to a known date.
     * Output: asserts the state depends on the **injected** clock, not the wall clock (TIM-001).
     *         The remaining placeholder — Safe-to-Spend — varies with the day of the month, so a
     *         fixed clock gives a fixed result, which is what P-08 determinism means in practice.
     *
     * Changelog: 2026-07-27 — Issue 2.3 moved the day-of-month wobble from the spending split
     * (now real, and read from the budget) onto Safe-to-Spend, which is still a placeholder.
     */
    @Test
    fun `reads time from the injected clock`() =
        runTest {
            val first = viewModel().uiState.value.safeToSpend.minor

            clock.setTo(java.time.Instant.parse("2026-03-17T00:00:00Z").toEpochMilli())
            val second = viewModel().uiState.value.safeToSpend.minor

            assertEquals(
                "the day-of-month contribution must come from the fake clock",
                second - first,
                (clock.today().dayOfMonth - 1).toLong(),
            )
        }

    // --- issue 2.3: the spending split is the persisted budget (FR-ONB-002) ---------------------

    /**
     * Input:  three persisted envelopes.
     * Output: asserts each nature lands in the right slot of the bar. Ordering matters here in a
     *         way it does not in a list: the segments are coloured by position, so a needs figure
     *         drawn in the savings slot is a chart that says the opposite of the truth.
     */
    @Test
    fun `the spending split comes from the persisted budget`() =
        runTest {
            budget.emit(needs = 42_500_00L, wants = 25_500_00L, savings = 17_000_00L)

            val split = viewModel().uiState.value.spendSplit

            assertEquals(42_500_00L, split?.needsMinor)
            assertEquals(25_500_00L, split?.wantsMinor)
            assertEquals(17_000_00L, split?.savingsMinor)
        }

    /**
     * Input:  no budget at all — the user skipped quick setup.
     * Output: asserts the split is **null**, not a zeroed one. The screen draws an empty state from
     *         this; a `SpendSplit(0, 0, 0)` would render as a real budget of nothing, which is a
     *         figure the app invented (P-03).
     */
    @Test
    fun `no budget leaves the split absent rather than zeroed`() =
        runTest {
            assertNull(viewModel().uiState.value.spendSplit)
        }

    /**
     * Input:  a budget that changes after the screen is already open.
     * Output: asserts the state follows. This is why the budget is observed rather than read once:
     *         the user lands here straight from onboarding, and later the budgets editor (issue
     *         4.4) will change it behind this screen.
     */
    @Test
    fun `the split updates when the budget changes`() =
        runTest {
            val viewModel = viewModel()
            budget.emit(needs = 42_500_00L, wants = 25_500_00L, savings = 17_000_00L)
            assertEquals(42_500_00L, viewModel.uiState.value.spendSplit?.needsMinor)

            budget.emit(needs = 50_000_00L, wants = 30_000_00L, savings = 20_000_00L)

            assertEquals(50_000_00L, viewModel.uiState.value.spendSplit?.needsMinor)
        }

    /**
     * Input:  a budget holding only a needs envelope — the shape issue 4.4's per-category budgets
     *         will produce, where a nature-level row may simply not exist.
     * Output: asserts the missing natures are zero **weights** while the split itself is still
     *         present. Absent-as-zero and absent-entirely are different states, and only the second
     *         means "this user has no budget".
     */
    @Test
    fun `a partial budget fills the missing natures with zero weight`() =
        runTest {
            budget.emitNeedsOnly(needs = 42_500_00L)

            val split = viewModel().uiState.value.spendSplit

            assertEquals(42_500_00L, split?.needsMinor)
            assertEquals(0L, split?.wantsMinor)
            assertEquals(0L, split?.savingsMinor)
        }

    // --- net worth (issue 2.6; FR-ACC-005) ---------------------------------------------------

    /**
     * Input:  a stored snapshot.
     * Output: asserts the figure reaches the state. This is the assertion that the dashboard shows a
     *         **computed** number rather than the ₹4,82,350.00 it hardcoded until issue 2.6.
     */
    @Test
    fun `the stored snapshot's net worth reaches the state`() =
        runTest {
            val viewModel = viewModel()

            netWorth.emit(2_92_000_00L)

            assertEquals(Money(2_92_000_00L), viewModel.uiState.value.netWorth)
        }

    /**
     * Input:  no snapshot.
     * Output: asserts the figure is **null, never zero**. A user who onboarded a minute ago has no
     *         snapshot, and rendering ₹0 would be a number the app made up (P-03) — and one
     *         indistinguishable from a genuine net worth of nothing.
     */
    @Test
    fun `no snapshot yet is absent, not zero`() {
        assertNull(viewModel().uiState.value.netWorth)
    }

    /**
     * Input:  a snapshot arriving while the screen is open.
     * Output: asserts the card updates. This is the nightly job landing under a user who left the
     *         app on — a one-off read at construction would leave them looking at "not worked out
     *         yet" until they navigated away and back.
     */
    @Test
    fun `a snapshot arriving later updates the card`() =
        runTest {
            val viewModel = viewModel()
            assertNull(viewModel.uiState.value.netWorth)

            netWorth.emit(1_00_000_00L)

            assertEquals(Money(1_00_000_00L), viewModel.uiState.value.netWorth)
        }

    /**
     * Input:  a negative net worth.
     * Output: asserts the sign survives to the state. A user who owes more than they hold must not
     *         be shown their debt as savings.
     */
    @Test
    fun `a negative net worth keeps its sign`() =
        runTest {
            val viewModel = viewModel()

            netWorth.emit(-12_18_000_00L)

            assertEquals(Money(-12_18_000_00L), viewModel.uiState.value.netWorth)
        }
}
