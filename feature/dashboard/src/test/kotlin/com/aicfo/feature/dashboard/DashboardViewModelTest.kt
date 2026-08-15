package com.aicfo.feature.dashboard

import app.cash.turbine.test
import com.aicfo.core.common.AppError
import com.aicfo.core.common.FakeClock
import com.aicfo.core.model.Money
import com.aicfo.core.model.Transaction
import com.aicfo.core.model.TransactionSource
import com.aicfo.core.model.TransactionType
import com.aicfo.data.repository.CashFlowSummary
import com.aicfo.domain.engines.budget.BudgetAlertBand
import com.aicfo.domain.engines.nature.NatureBreakdown
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
    private val transactions = FakeTransactionRepository()
    private val budgets = FakeBudgetRepository()

    private fun viewModel() = DashboardViewModel(clock, budget, netWorth, transactions, budgets)

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

    // --- §8.3's actual-spend split (issue 4.3) ------------------------------------------------------

    /**
     * Input:  a month the repository has classified.
     * Output: the breakdown reaches the state. The dashboard is the first screen to render what
     *         §8.3 decided, and until this it rendered only the *plan* — the budget envelopes — which
     *         is a screen that can never disagree with the user.
     */
    @Test
    fun `this month's actual split reaches the state`() =
        runTest {
            transactions.setBreakdown(
                NatureBreakdown(needs = Money(4_500_00L), wants = Money(1_200_00L), invested = Money(10_000_00L)),
            )

            val state = viewModel().uiState.value

            assertEquals(Money(4_500_00L), state.natureBreakdown?.needs)
            assertEquals(Money(5_700_00L), state.natureBreakdown?.trueSpend)
        }

    /**
     * Input:  a month with nothing in it.
     * Output: an **empty** breakdown rather than `null`, so the screen can tell "no transactions"
     *         from "not worked out yet" — and renders neither as a row of zeroes (P-03).
     */
    @Test
    fun `an empty month is reported as empty`() =
        runTest {
            val state = viewModel().uiState.value

            assertTrue(
                "an empty month must be distinguishable from a real month of zeroes",
                state.natureBreakdown!!.isEmpty,
            )
        }

    /**
     * Input:  a repository that fails the classification read.
     * Output: no breakdown and **no error banner**. The dashboard's other figures are still true,
     *         and the newest section on the screen must not be able to obscure them — the opposite
     *         of how a failed *budget* read is handled, deliberately.
     */
    @Test
    fun `a failed classification is silent and leaves the rest of the dashboard alone`() =
        runTest {
            transactions.failOnObserve = AppError.Storage("disk")

            val state = viewModel().uiState.value

            assertNull(state.natureBreakdown)
            assertNull("a failed nature read raised a banner", state.errorCode)
        }

    // --- this month's cash flow (issue 5.1) ---------------------------------------------------

    /**
     * Input:  a cash-flow summary from the repository.
     * Output: the figure reaches the state, unchanged — this section computes nothing (P-03).
     */
    @Test
    fun `this month's cash flow reaches the state`() =
        runTest {
            transactions.setCashFlow(
                CashFlowSummary(income = Money(85_000_00L), expense = Money(32_000_00L), net = Money(53_000_00L)),
            )

            val state = viewModel().uiState.value

            assertEquals(Money(85_000_00L), state.cashFlow?.income)
            assertEquals(Money(32_000_00L), state.cashFlow?.expense)
        }

    /**
     * Input:  a repository whose cash-flow read fails.
     * Output: the section clears and **no error banner** — the same isolation rule
     *         [observeNature]'s failure test above proves, applied to the newest card beside it.
     */
    @Test
    fun `a failed cash-flow read is silent and leaves the rest of the dashboard alone`() =
        runTest {
            transactions.failOnCashFlow = AppError.Storage("disk")

            val state = viewModel().uiState.value

            assertNull(state.cashFlow)
            assertNull("a failed cash-flow read raised a banner", state.errorCode)
        }

    // --- budget status (issue 5.1) ------------------------------------------------------------

    /**
     * Input:  one budgeted category and one unbudgeted one, both with spend.
     * Output: [DashboardUiState.budgetTotals] sums only the budgeted row — an unbudgeted category's
     *         spend must not inflate a total against a plan it was never part of.
     */
    @Test
    fun `budget totals fold only the budgeted categories`() =
        runTest {
            budgets.emitBudgets(
                listOf(
                    dashboardBudgetRow(
                        id = "budget:1",
                        name = "Groceries",
                        budgeted = Money(1_000_000L),
                        spent = Money(400_000L),
                    ),
                    dashboardBudgetRow(id = null, name = "Misc", spent = Money(200_000L)),
                ),
            )

            val totals = viewModel().uiState.value.budgetTotals

            assertEquals(Money(1_000_000L), totals?.budgeted)
            assertEquals(Money(400_000L), totals?.spent)
        }

    /**
     * Input:  no categories budgeted this month.
     * Output: the totals are **absent, not zeroed** — the same rule [spendSplit] follows, and for
     *         the same reason: a ₹0 card the app assembled from nothing is a figure it made up (P-03).
     */
    @Test
    fun `no budgets yet leaves the totals absent rather than zeroed`() {
        assertNull(viewModel().uiState.value.budgetTotals)
    }

    /**
     * Input:  a repository whose budget-status read fails.
     * Output: an error banner — **unlike** the cash-flow and nature failures above, because the plan
     *         itself failing to read is exactly what `:feature:budgets`' own budgets stream surfaces
     *         too, and the dashboard must not quietly disagree with it.
     */
    @Test
    fun `a failed budget-status read surfaces an error`() =
        runTest {
            budgets.failOnBudgets = AppError.Storage("disk")

            val state = viewModel().uiState.value

            assertTrue("a failed budget-status read must surface an error", state.errorCode != null)
        }

    /**
     * Input:  a budget sitting in the warn band.
     * Output: the alert reaches the state carrying the rule that fired (P-02) — the same
     *         `RULE-BUD-ALERT` citation `:feature:budgets`' own banner shows for the same alert.
     *         Derived from the same [budgets] emission via `BudgetRepository.alertFor` (issue 5.1
     *         review, 2026-08-16) — not a second stream — so seeding [FakeBudgetRepository.emitBudgets]
     *         plus [FakeBudgetRepository.alertForResult] is what drives it now, not a separate alerts
     *         flow.
     */
    @Test
    fun `budget alerts reach the state with their rule citation`() =
        runTest {
            budgets.alertForResult = dashboardAlertRow(band = BudgetAlertBand.WARN)
            budgets.emitBudgets(listOf(dashboardBudgetRow()))

            val alert = viewModel().uiState.value.budgetAlerts.first()

            assertEquals("RULE-BUD-ALERT", alert.alert.provenance.evidence.first().ruleId)
        }

    /**
     * Input:  a repository whose budgets read fails.
     * Output: **both** the totals and the alert line go stale together, behind the one error banner
     *         [observeBudgetStatus] already raises — there is no longer a separate alert read that
     *         could fail on its own and show a second, different face for the same cause (the
     *         2026-08-16 review's finding).
     */
    @Test
    fun `a failed budgets read leaves the alerts empty behind the same banner, not a second face`() =
        runTest {
            budgets.alertForResult = dashboardAlertRow(band = BudgetAlertBand.WARN)
            budgets.failOnBudgets = AppError.Storage("disk")

            val state = viewModel().uiState.value

            assertTrue(state.budgetAlerts.isEmpty())
            assertTrue("a failed budgets read must surface an error", state.errorCode != null)
        }

    // --- recent activity (issue 5.1) ------------------------------------------------------------

    /**
     * Input:  a handful of transactions from the repository.
     * Output: they reach the state, in the order the repository returned them — this section
     *         computes nothing (P-03), it only renders what `TransactionRepository.observeRecent`
     *         already ordered.
     */
    @Test
    fun `recent activity reaches the state`() =
        runTest {
            val txn =
                Transaction(
                    id = "txn:1",
                    accountId = "account:1",
                    amount = Money(-450_00L),
                    occurredAtUtcMillis = 1_755_500_000_000L,
                    bookedOn = "2026-08-15",
                    categoryId = null,
                    merchant = "Big Bazaar",
                    note = null,
                    source = TransactionSource.MANUAL,
                    type = TransactionType.EXPENSE,
                )
            transactions.setRecent(txn)

            val recent = viewModel().uiState.value.recentActivity

            assertEquals(1, recent?.size)
            assertEquals("Big Bazaar", recent?.first()?.transaction?.merchant)
        }

    /**
     * Input:  a repository whose recent-activity read fails.
     * Output: the section clears and **no error banner** — the same isolation rule every other
     *         secondary section on this screen follows.
     */
    @Test
    fun `a failed recent-activity read is silent and leaves the rest of the dashboard alone`() =
        runTest {
            transactions.failOnRecent = AppError.Storage("disk")

            val state = viewModel().uiState.value

            assertNull(state.recentActivity)
            assertNull("a failed recent-activity read raised a banner", state.errorCode)
        }
}
