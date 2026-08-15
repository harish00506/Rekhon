package com.aicfo.feature.dashboard

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
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
import com.aicfo.domain.engines.budget.BudgetStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * A [BudgetRepository] the dashboard tests drive directly (issue 5.1).
 *
 * Why:  the dashboard reads exactly two things from this repository — the current month's
 *       per-category status, for the budget summary card, and the alert bands, for the "needs
 *       attention" line — and everything else here **throws rather than returning a plausible
 *       value**, the same bargain [FakeTransactionRepository] strikes beside it.
 * What: two `MutableStateFlow`s, each independently failable.
 * Result: both branches of the dashboard's budget section are reachable from a unit test.
 * Changelog: 2026-08-15 — Created for issue 5.1.
 */
internal class FakeBudgetRepository : BudgetRepository {
    private val budgets = MutableStateFlow<List<CategoryBudget>>(emptyList())
    private val alerts = MutableStateFlow<List<CategoryBudgetAlert>>(emptyList())

    /** When non-null, [observeBudgets] throws this instead of emitting. */
    var failOnBudgets: AppError? = null

    /** When non-null, [observeAlerts] throws this instead of emitting. */
    var failOnAlerts: AppError? = null

    /** Replaces what the budgets stream is emitting. Input: [value]. Output: none. */
    fun emitBudgets(value: List<CategoryBudget>) {
        budgets.value = value
    }

    /** Replaces what the alert stream is emitting. Input: [value]. Output: none. */
    fun emitAlerts(value: List<CategoryBudgetAlert>) {
        alerts.value = value
    }

    override fun observeBudgets(): Flow<List<CategoryBudget>> =
        budgets.map { value ->
            failOnBudgets?.let { throw IllegalStateException(it.code) }
            value
        }

    override fun observeAlerts(): Flow<List<CategoryBudgetAlert>> =
        alerts.map { value ->
            failOnAlerts?.let { throw IllegalStateException(it.code) }
            value
        }

    override fun observeSuggestions(): Flow<List<CategoryBudgetSuggestion>> = unsupported()

    override suspend fun setBudget(
        categoryId: String,
        amount: Money,
        rolloverEnabled: Boolean,
    ): Result<String, AppError> = unsupported()

    override suspend fun acceptSuggestion(categoryId: String): Result<String, AppError> = unsupported()

    override suspend fun deleteBudget(id: String): Result<Unit, AppError> = unsupported()

    override suspend fun pendingAlerts(): Result<List<CategoryBudgetAlert>, AppError> = unsupported()

    override suspend fun markNotified(alert: CategoryBudgetAlert): Result<Boolean, AppError> = unsupported()

    override fun observeReview(): Flow<BudgetReview?> = unsupported()

    override suspend fun acceptReviewProposal(categoryId: String): Result<String, AppError> = unsupported()

    override suspend fun dismissReview(): Result<Boolean, AppError> = unsupported()

    /**
     * Result: never — fails the test loudly. Why: a dashboard test that reached one of these would
     *         be asserting something `:feature:budgets` owns, and a plausible empty value would hide
     *         that rather than reveal it. Output: [Nothing].
     */
    private fun unsupported(): Nothing = error("the dashboard must not read this from BudgetRepository")
}

/**
 * Builds a [CategoryBudget] for a dashboard test, with figures the test states rather than computes.
 * Why:    the dashboard's summary card sums [CategoryBudget.status] fields; a fixture builder keeps
 *         each test to the one figure it is about, matching `:feature:budgets`' own `budgetRow`.
 * Result: a row. Input: [id] — `null` for an unbudgeted category; [name]; [budgeted]; [spent].
 *         Output: [CategoryBudget].
 * Changelog: 2026-08-15 — Created for issue 5.1.
 */
internal fun dashboardBudgetRow(
    id: String? = "budget:1",
    name: String = "Groceries",
    budgeted: Money = Money(1_000_000L),
    spent: Money = Money(400_000L),
): CategoryBudget =
    CategoryBudget(
        id = id,
        category = Category(id = "category:$name", name = name, nature = CategoryNature.NEED),
        status =
            BudgetStatus(
                budgeted = if (id == null) Money.ZERO else budgeted,
                carriedOver = Money.ZERO,
                spent = spent,
                remaining = (if (id == null) Money.ZERO else budgeted) - spent,
                safePaceToDate = budgeted,
                projectedEndOfMonth = null,
                provenance = testProvenance(),
            ),
        rolloverEnabled = false,
        source = BudgetRepository.SOURCE_MANUAL,
    )

/**
 * Builds a [CategoryBudgetAlert] for a dashboard test (FR-BUD-004).
 * Result: an alert. Input: [name] — the category; [band]. Output: [CategoryBudgetAlert].
 * Changelog: 2026-08-15 — Created for issue 5.1.
 */
internal fun dashboardAlertRow(
    name: String = "Groceries",
    band: BudgetAlertBand = BudgetAlertBand.WARN,
): CategoryBudgetAlert =
    CategoryBudgetAlert(
        budgetId = "budget:$name",
        category = Category(id = "category:$name", name = name, nature = CategoryNature.NEED),
        alert =
            BudgetAlert(
                band = band,
                usedBps = 8_000,
                budgeted = Money(1_000_000L),
                spent = Money(800_000L),
                overspentBy = null,
                provenance = testProvenance(),
            ),
    )

/** Result: a provenance a fixture can carry, citing RULE-BUD-ALERT (P-02). Output: [EngineProvenance]. */
private fun testProvenance(): EngineProvenance =
    EngineProvenance(
        engineId = "budget-planner",
        engineVersion = "1.0",
        computedAtUtcMillis = 1_786_000_000_000L,
        evidence = listOf(RuleCitation("RULE-BUD-ALERT", "1.0")),
    )
