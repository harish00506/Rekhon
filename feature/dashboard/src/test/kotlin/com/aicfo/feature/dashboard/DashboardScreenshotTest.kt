package com.aicfo.feature.dashboard

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.Category
import com.aicfo.core.model.CategoryNature
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import com.aicfo.core.model.Transaction
import com.aicfo.core.model.TransactionSource
import com.aicfo.core.model.TransactionType
import com.aicfo.data.repository.BudgetRepository
import com.aicfo.data.repository.CashFlowSummary
import com.aicfo.data.repository.CategoryBudget
import com.aicfo.data.repository.CategoryBudgetAlert
import com.aicfo.data.repository.FilteredTransaction
import com.aicfo.domain.engines.budget.BudgetAlert
import com.aicfo.domain.engines.budget.BudgetAlertBand
import com.aicfo.domain.engines.budget.BudgetStatus
import org.junit.Rule
import org.junit.Test

/**
 * Screenshot tests for the dashboard — light, dark and 200% font (§21.5, ACC-*, issue 5.1).
 *
 * Why:  this project has no emulator, so these renders are the only way anyone sees the budget
 *       status, cash-flow and recent-activity sections issue 5.1 added actually look right — the
 *       same reasoning `DesignSystemScreenshotTest`'s doc comment gives, applied to a real screen
 *       rather than the component gallery.
 * What: the loaded state (every 5.1 section populated) and the all-empty state, in three
 *       configurations each.
 * Result: a visual diff fails the build when a token or layout changes unintentionally.
 * Changelog: 2026-08-15 — Created for issue 5.1.
 *
 * Record baselines with `./gradlew :feature:dashboard:recordPaparazziDebug`; verify with
 * `verifyPaparazziDebug`. Baselines are committed — a screenshot test with no committed baseline
 * checks nothing.
 */
class DashboardScreenshotTest {
    @get:Rule
    val paparazzi: Paparazzi =
        Paparazzi(
            deviceConfig = DeviceConfig.PIXEL_5,
            // Pinned: the renderer's platform version decides the pixels, so leaving it floating
            // would make baselines change under an unrelated SDK update.
            theme = "android:Theme.Material.Light.NoActionBar",
        )

    /** Input: the loaded, populated dashboard in light mode. Output: records/verifies the baseline. */
    @Test
    fun loaded_light() {
        paparazzi.snapshot { Screen(loadedState(), darkTheme = false) }
    }

    /** Input: the same state in dark mode. Output: proves dark mode is genuinely themed. */
    @Test
    fun loaded_dark() {
        paparazzi.snapshot { Screen(loadedState(), darkTheme = true) }
    }

    /**
     * Input:  the loaded state at a 2x font scale.
     * Output: the accessibility case the DoD asks for — if a label clips or a row collapses at
     *         200% font, it is visible here rather than on a user's phone.
     */
    @Test
    fun loaded_largeFont() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = LARGE_FONT_SCALE))
        paparazzi.snapshot { Screen(loadedState(), darkTheme = false) }
    }

    /**
     * Input:  a brand-new profile — no net worth snapshot, no budget, no transactions.
     * Output: every issue-5.1 section's empty-state line renders instead of a figure the app made
     *         up (P-03) — the state most likely to regress silently, since it has no numbers to
     *         eyeball wrong.
     */
    @Test
    fun empty_light() {
        paparazzi.snapshot { Screen(DashboardUiState(isLoading = false), darkTheme = false) }
    }

    @Composable
    private fun Screen(
        uiState: DashboardUiState,
        darkTheme: Boolean,
    ) {
        // No wrapping Column, and no padding of its own: `DashboardContent` already applies
        // `fillMaxWidth().padding(CfoDimens.spaceMd)` and its own vertical arrangement, so the
        // harness that used to wrap it here rendered every baseline at double the real padding —
        // a picture of a screen the app never draws. Found by review, 2026-08-16.
        CfoTheme(darkTheme = darkTheme) {
            Surface {
                DashboardContent(
                    uiState = uiState,
                    onEvent = {},
                    actions =
                        DashboardActions(
                            onNavigateToTransactions = {},
                            onNavigateToAccounts = {},
                            onNavigateToBudgets = {},
                        ),
                )
            }
        }
    }

    /**
     * Result: a fully populated state — every issue-5.1 section has a real row to render, including
     *         a budget alert with its rule citation (P-02).
     */
    private fun loadedState(): DashboardUiState =
        DashboardUiState(
            isLoading = false,
            safeToSpend = Money(12_500_00L),
            netWorth = Money(2_92_000_00L),
            spendSplit = SpendSplit(needsMinor = 42_500_00L, wantsMinor = 25_500_00L, savingsMinor = 17_000_00L),
            cashFlow =
                CashFlowSummary(income = Money(85_000_00L), expense = Money(32_450_00L), net = Money(52_550_00L)),
            budgets = listOf(budgetRow()),
            budgetAlerts = listOf(alertRow()),
            recentActivity = listOf(transactionRow()),
        )

    private fun budgetRow(): CategoryBudget =
        CategoryBudget(
            id = "budget:1",
            category = Category(id = "category:groceries", name = "Groceries", nature = CategoryNature.NEED),
            status =
                BudgetStatus(
                    budgeted = Money(10_000_00L),
                    carriedOver = Money.ZERO,
                    spent = Money(8_000_00L),
                    remaining = Money(2_000_00L),
                    safePaceToDate = Money(8_000_00L),
                    projectedEndOfMonth = null,
                    provenance = provenance(),
                ),
            rolloverEnabled = false,
            source = BudgetRepository.SOURCE_MANUAL,
        )

    private fun alertRow(): CategoryBudgetAlert =
        CategoryBudgetAlert(
            budgetId = "budget:1",
            category = Category(id = "category:groceries", name = "Groceries", nature = CategoryNature.NEED),
            alert =
                BudgetAlert(
                    band = BudgetAlertBand.WARN,
                    usedBps = 8_000,
                    budgeted = Money(10_000_00L),
                    spent = Money(8_000_00L),
                    overspentBy = null,
                    provenance = provenance(),
                ),
        )

    private fun transactionRow(): FilteredTransaction =
        FilteredTransaction(
            transaction =
                Transaction(
                    id = "txn:1",
                    accountId = "account:1",
                    amount = Money(-2_450_00L),
                    occurredAtUtcMillis = 1_755_500_000_000L,
                    // A **real** stored value, ISO `yyyy-MM-dd` (TIM-002) — not the pre-formatted
                    // "2 Jan" this fixture used to carry. That shortcut is exactly why the baselines
                    // could not reveal that the screen was rendering raw ISO dates to users: the
                    // fixture had already done the formatting the screen was failing to do. A
                    // fixture the repository could never produce proves nothing about the screen.
                    bookedOn = "2026-08-15",
                    categoryId = "category:groceries",
                    merchant = "Big Bazaar",
                    note = null,
                    source = TransactionSource.MANUAL,
                    type = TransactionType.EXPENSE,
                ),
        )

    private fun provenance(): EngineProvenance =
        EngineProvenance(
            engineId = "budget-planner",
            engineVersion = "1.0",
            computedAtUtcMillis = 1_786_000_000_000L,
            evidence = listOf(RuleCitation("RULE-BUD-ALERT", "1.0")),
        )

    private companion object {
        /** The DoD's accessibility case: text at twice the default size. */
        const val LARGE_FONT_SCALE = 2.0f
    }
}
