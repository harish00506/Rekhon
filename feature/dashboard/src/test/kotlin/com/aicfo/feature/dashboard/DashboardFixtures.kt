package com.aicfo.feature.dashboard

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
import com.aicfo.domain.engines.safetospend.SafeToSpend
import com.aicfo.domain.engines.safetospend.SafeToSpendComponent
import com.aicfo.domain.engines.safetospend.SafeToSpendLine

/**
 * The one fully populated dashboard both UI tests render (issues 5.1, 5.2, 5.3).
 *
 * Why:  extracted from `DashboardScreenshotTest` by issue 5.3, which needed the same screen for its
 *       Compose UI test. Two copies would be worse than the duplication looks: the screenshot test
 *       proves the layout survives dark mode and 200% font, and the blur test proves no amount
 *       escapes it — and those two claims are only about the same screen while the fixture is
 *       literally the same object. A second copy that quietly lost a section would leave the blur
 *       test passing over a screen the app never draws.
 * What: every section populated, including a full six-line Safe-to-Spend breakdown.
 * Result: one definition of "a full dashboard".
 * Changelog: 2026-08-16 — Extracted for issue 5.3.
 *
 * Result: a fully populated state — every issue-5.1 section has a real row to render, including a
 *         budget alert with its rule citation (P-02), and a full six-line Safe-to-Spend breakdown.
 * Input:  none. Output: [DashboardUiState].
 */
internal fun populatedDashboardState(): DashboardUiState =
    DashboardUiState(
        isLoading = false,
        safeToSpend = safeToSpendFigure(),
        netWorth = Money(2_92_000_00L),
        spendSplit = SpendSplit(needsMinor = 42_500_00L, wantsMinor = 25_500_00L, savingsMinor = 17_000_00L),
        cashFlow =
            CashFlowSummary(income = Money(85_000_00L), expense = Money(32_450_00L), net = Money(52_550_00L)),
        budgets = listOf(budgetRow()),
        budgetAlerts = listOf(alertRow()),
        recentActivity = listOf(transactionRow()),
    )

/**
 * Result: a Safe-to-Spend figure with a full six-line breakdown (issue 5.2).
 *
 * Why:    **every** deduction is present, unlike the ordinary month a user would see. The
 *         breakdown is the part of this card that can regress silently — a label that clips at
 *         200% font or a row that loses its contrast in dark mode is invisible to a unit test —
 *         and a fixture with two lines would only ever prove that two lines fit. The lines sum
 *         to the headline because `SafeToSpend`'s constructor requires it: a fixture that did
 *         not add up could not be built at all.
 */
private fun safeToSpendFigure(): SafeToSpend =
    SafeToSpend(
        // 80 000 − 4 000 − 12 400 − 18 000 − 2 000 − 9 000 = 34 600
        amount = Money(34_600_00L),
        lines =
            listOf(
                SafeToSpendLine(SafeToSpendComponent.INCOME, Money(80_000_00L)),
                SafeToSpendLine(SafeToSpendComponent.BUFFER, Money(4_000_00L)),
                SafeToSpendLine(SafeToSpendComponent.SPENT, Money(12_400_00L)),
                SafeToSpendLine(SafeToSpendComponent.SCHEDULED, Money(18_000_00L)),
                SafeToSpendLine(SafeToSpendComponent.RECURRING, Money(2_000_00L)),
                SafeToSpendLine(SafeToSpendComponent.GOALS, Money(9_000_00L)),
            ),
        provenance =
            EngineProvenance(
                engineId = "safe-to-spend",
                engineVersion = "1.0",
                computedAtUtcMillis = 1_786_000_000_000L,
                evidence = listOf(RuleCitation("RULE-STS", "1.0")),
                inputWindow = "2026-08-01..2026-08-31",
            ),
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
