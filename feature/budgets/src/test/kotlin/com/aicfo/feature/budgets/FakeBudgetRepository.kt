package com.aicfo.feature.budgets

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
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
import com.aicfo.domain.engines.budget.BudgetSuggestion
import com.aicfo.domain.engines.budget.ReviewedCategory
import com.aicfo.domain.engines.budget.VarianceDirection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import java.io.IOException

/**
 * An in-memory [BudgetRepository] for this module's tests (issue 4.4).
 *
 * Why:  the ViewModel's job is state, not storage, and `BudgetRepositoryTest` already proves the SQL
 *       and the engine wiring against a real database. What the tests here need is control over the
 *       *outcome* — a save that is refused, a list that changes underneath the screen, a suggestion
 *       stream that throws — which a real database makes awkward to arrange and a fake makes one
 *       line.
 * What: two `MutableStateFlow`s plus per-method overrides for the failure paths, and a record of
 *       every write so a test can assert what the screen actually asked for.
 * Result: every branch in [BudgetsViewModel] is reachable from a test.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 *
 * **It computes nothing.** A real status comes from `BudgetEngine`; here the test states the figures
 * it wants to render. Recomputing them would mean these tests pass against a second, drifting copy of
 * the engine's arithmetic — the mistake `FakeCategoryRepository` avoids by not re-implementing
 * validation.
 *
 * Input:  [initialBudgets], [initialSuggestions] — what the screen starts with.
 * Output: a fake repository.
 */
internal class FakeBudgetRepository(
    initialBudgets: List<CategoryBudget> = emptyList(),
    initialSuggestions: List<CategoryBudgetSuggestion> = emptyList(),
    initialAlerts: List<CategoryBudgetAlert> = emptyList(),
    initialReview: BudgetReview? = null,
) : BudgetRepository {
    private val budgets = MutableStateFlow(initialBudgets)
    private val suggestions = MutableStateFlow(initialSuggestions)
    private val alerts = MutableStateFlow(initialAlerts)
    private val review = MutableStateFlow(initialReview)

    /** What the next write returns, or `null` for success. Cleared after it is used once. */
    var nextError: AppError? = null

    /** Every [setBudget] call, in order, as (categoryId, amount, rolloverEnabled). */
    val written: MutableList<Triple<String, Money, Boolean>> = mutableListOf()

    /** Every [acceptSuggestion] call, in order. */
    val accepted: MutableList<String> = mutableListOf()

    /** Every [deleteBudget] call, in order. */
    val deleted: MutableList<String> = mutableListOf()

    /** Every [acceptReviewProposal] call, in order (issue 4.6). */
    val reviewAccepted: MutableList<String> = mutableListOf()

    /** How many times [dismissReview] was called (issue 4.6). */
    var dismissCount: Int = 0
        private set

    override fun observeBudgets(): Flow<List<CategoryBudget>> = budgets

    override fun observeSuggestions(): Flow<List<CategoryBudgetSuggestion>> = suggestions

    override fun observeAlerts(): Flow<List<CategoryBudgetAlert>> = alerts

    // This module still reads observeAlerts() directly, not alertFor (issue 5.1 gave the dashboard
    // the reason to prefer the latter; :feature:budgets' own screen has no second subscription to
    // avoid). Matched against the same staged list rather than left unsupported, so a future test
    // that does start calling it gets a real answer instead of a surprise crash.
    override fun alertFor(row: CategoryBudget): CategoryBudgetAlert? = alerts.value.find { it.budgetId == row.id }

    // The notification path is the worker's, not this screen's: these exist so the fake satisfies the
    // interface, and every band the screen renders comes from `observeAlerts` above (issue 4.5).
    override suspend fun pendingAlerts(): Result<List<CategoryBudgetAlert>, AppError> = Ok(alerts.value)

    override suspend fun markNotified(alert: CategoryBudgetAlert): Result<Boolean, AppError> = Ok(true)

    override fun observeReview(): Flow<BudgetReview?> = review

    override suspend fun acceptReviewProposal(categoryId: String): Result<String, AppError> {
        takeError()?.let { return Err(it) }
        reviewAccepted += categoryId
        return Ok("budget:$categoryId")
    }

    override suspend fun dismissReview(): Result<Boolean, AppError> {
        takeError()?.let { return Err(it) }
        dismissCount++
        return Ok(true)
    }

    override suspend fun setBudget(
        categoryId: String,
        amount: Money,
        rolloverEnabled: Boolean,
    ): Result<String, AppError> {
        takeError()?.let { return Err(it) }
        written += Triple(categoryId, amount, rolloverEnabled)
        return Ok("budget:$categoryId")
    }

    override suspend fun acceptSuggestion(categoryId: String): Result<String, AppError> {
        takeError()?.let { return Err(it) }
        accepted += categoryId
        return Ok("budget:$categoryId")
    }

    override suspend fun deleteBudget(id: String): Result<Unit, AppError> {
        takeError()?.let { return Err(it) }
        deleted += id
        return Ok(Unit)
    }

    /** Replaces what the budgets stream is emitting, as a write from elsewhere in the app would. */
    fun emitBudgets(value: List<CategoryBudget>) {
        budgets.value = value
    }

    /** Replaces what the suggestions stream is emitting. */
    fun emitSuggestions(value: List<CategoryBudgetSuggestion>) {
        suggestions.value = value
    }

    /** Replaces what the alert stream is emitting, as spending elsewhere in the app would. */
    fun emitAlerts(value: List<CategoryBudgetAlert>) {
        alerts.value = value
    }

    /** Replaces what the review stream is emitting (issue 4.6). */
    fun emitReview(value: BudgetReview?) {
        review.value = value
    }

    /** Result: the staged error, consumed so the *next* call succeeds. Output: [AppError]?. */
    private fun takeError(): AppError? = nextError?.also { nextError = null }
}

/**
 * A [BudgetRepository] whose budget stream throws (issue 4.4).
 *
 * Why:  a database that will not open is the state the screen must not render as "you have planned
 *       nothing" — the distinction [BudgetsUiState.isEmpty] exists to make. A `Flow` that throws is
 *       the only way to reach that branch, and a `MutableStateFlow` cannot be made to.
 *
 *       **An `IOException`, not an `IllegalStateException`.** §21.6 reserves the latter for
 *       programmer errors and `runCatchingToResult` deliberately lets it crash, so a fixture that
 *       threw one would be simulating a bug in this app rather than a disk that would not read.
 * Result: `observeBudgets` fails on collection; everything else is inert.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 */
internal class FailingBudgetRepository : BudgetRepository {
    override fun observeBudgets(): Flow<List<CategoryBudget>> = flow { throw IOException("no database") }

    override fun observeSuggestions(): Flow<List<CategoryBudgetSuggestion>> = MutableStateFlow(emptyList())

    override fun observeAlerts(): Flow<List<CategoryBudgetAlert>> = flow { throw IOException("no database") }

    // Synchronous, so it cannot throw the way the Flow above does; every row it would ever be asked
    // about already came from a failed observeBudgets(), which nothing here reaches in that case.
    override fun alertFor(row: CategoryBudget): CategoryBudgetAlert? = null

    override suspend fun pendingAlerts(): Result<List<CategoryBudgetAlert>, AppError> =
        Err(AppError.Storage("no database"))

    override suspend fun markNotified(alert: CategoryBudgetAlert): Result<Boolean, AppError> =
        Err(AppError.Storage("no database"))

    override fun observeReview(): Flow<BudgetReview?> = flow { throw IOException("no database") }

    override suspend fun acceptReviewProposal(categoryId: String): Result<String, AppError> =
        Err(AppError.Storage("no database"))

    override suspend fun dismissReview(): Result<Boolean, AppError> = Err(AppError.Storage("no database"))

    override suspend fun setBudget(
        categoryId: String,
        amount: Money,
        rolloverEnabled: Boolean,
    ): Result<String, AppError> = Err(AppError.Storage("no database"))

    override suspend fun acceptSuggestion(categoryId: String): Result<String, AppError> =
        Err(AppError.Storage("no database"))

    override suspend fun deleteBudget(id: String): Result<Unit, AppError> = Err(AppError.Storage("no database"))
}

/**
 * A [BudgetRepository] whose *suggestion* stream throws while its budgets are fine (issue 4.4).
 *
 * Why:  the asymmetry is the point. Budgets and suggestions fail independently, and the ViewModel
 *       treats them differently on purpose — a failed read of the plan is a banner, a failed offer is
 *       silence. Only a repository that fails one and not the other can prove it.
 * Result: `observeBudgets` emits [budgets]; `observeSuggestions` fails on collection.
 * Input:  [budgets] — what the screen should still manage to show. Output: a fake repository.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 */
internal class SuggestionlessBudgetRepository(
    private val budgets: List<CategoryBudget>,
) : BudgetRepository {
    override fun observeBudgets(): Flow<List<CategoryBudget>> = MutableStateFlow(budgets)

    override fun observeSuggestions(): Flow<List<CategoryBudgetSuggestion>> =
        flow { throw IOException("cannot read history") }

    override fun observeAlerts(): Flow<List<CategoryBudgetAlert>> = MutableStateFlow(emptyList())

    override fun alertFor(row: CategoryBudget): CategoryBudgetAlert? = null

    override suspend fun pendingAlerts(): Result<List<CategoryBudgetAlert>, AppError> = Ok(emptyList())

    override suspend fun markNotified(alert: CategoryBudgetAlert): Result<Boolean, AppError> = Ok(true)

    override fun observeReview(): Flow<BudgetReview?> = MutableStateFlow(null)

    override suspend fun acceptReviewProposal(categoryId: String): Result<String, AppError> = Err(AppError.NotFound)

    override suspend fun dismissReview(): Result<Boolean, AppError> = Ok(false)

    override suspend fun setBudget(
        categoryId: String,
        amount: Money,
        rolloverEnabled: Boolean,
    ): Result<String, AppError> = Ok("budget:$categoryId")

    override suspend fun acceptSuggestion(categoryId: String): Result<String, AppError> = Err(AppError.NotFound)

    override suspend fun deleteBudget(id: String): Result<Unit, AppError> = Ok(Unit)
}

/**
 * Builds a [CategoryBudget] for a test, with figures the test states rather than computes.
 *
 * Why:  every ViewModel test needs one of these and none of them cares about most of its fields, so
 *       the defaults carry the boring case and each test overrides only what it is about. The
 *       provenance is real — [BudgetStatus] refuses to be built without a cited rule (P-02), and a
 *       fixture that could dodge that would be testing a type the app does not have.
 * Result: a row. Input: [id] — `null` for an unbudgeted category; [name]; [budgeted]; [spent];
 *         [safePace]; [projected]; [carriedOver]; [rollover]. Output: [CategoryBudget].
 * Changelog: 2026-08-11 — Created for issue 4.4.
 */
@Suppress("LongParameterList") // A fixture builder: every parameter is one figure a test may state.
internal fun budgetRow(
    id: String? = "budget:1",
    name: String = "Groceries",
    budgeted: Money = Money(1_000_000L),
    spent: Money = Money(400_000L),
    safePace: Money = Money(500_000L),
    projected: Money? = Money(800_000L),
    carriedOver: Money = Money.ZERO,
    rollover: Boolean = false,
): CategoryBudget =
    CategoryBudget(
        id = id,
        category = Category(id = "category:$name", name = name, nature = CategoryNature.NEED),
        status =
            BudgetStatus(
                budgeted = budgeted,
                carriedOver = carriedOver,
                spent = spent,
                remaining = budgeted - spent,
                safePaceToDate = safePace,
                projectedEndOfMonth = projected,
                provenance = testProvenance(RuleCitation("RULE-BUD-PACE", "1.0")),
            ),
        rolloverEnabled = rollover,
        source = BudgetRepository.SOURCE_MANUAL,
    )

/**
 * Builds a [CategoryBudgetAlert] for a test (issue 4.5; FR-BUD-004).
 *
 * Why:  same bargain as [budgetRow] — the test states the band it wants rendered rather than making
 *       the engine produce it, so a screen test fails for screen reasons. The provenance is real
 *       because [BudgetAlert] refuses to be built without a cited rule (P-02).
 * Result: an alert. Input: [name] — the category; [band]; [usedBps]; [budgeted]; [spent].
 *         Output: [CategoryBudgetAlert].
 * Changelog: 2026-08-13 — Created for issue 4.5.
 */
internal fun alertRow(
    name: String = "Groceries",
    band: BudgetAlertBand = BudgetAlertBand.WARN,
    usedBps: Int = 8_000,
    budgeted: Money = Money(1_000_000L),
    spent: Money = Money(800_000L),
): CategoryBudgetAlert =
    CategoryBudgetAlert(
        budgetId = "budget:$name",
        category = Category(id = "category:$name", name = name, nature = CategoryNature.NEED),
        alert =
            BudgetAlert(
                band = band,
                usedBps = usedBps,
                budgeted = budgeted,
                spent = spent,
                overspentBy =
                    if (band == BudgetAlertBand.EXCEEDED) (spent - budgeted).coerceAtLeast(Money.ZERO) else null,
                provenance = testProvenance(RuleCitation("RULE-BUD-ALERT", "1.0")),
            ),
    )

/**
 * Builds a [CategoryBudgetSuggestion] for a test.
 * Result: a proposal. Input: [name] — the category; [amount]; [median]; [eventId] — the festival, or
 *         `null` for an unadjusted suggestion; [indexBps]. Output: [CategoryBudgetSuggestion].
 * Changelog: 2026-08-11 — Created for issue 4.4.
 */
internal fun suggestionRow(
    name: String = "Shopping",
    amount: Money = Money(690_000L),
    median: Money = Money(500_000L),
    eventId: String? = null,
    indexBps: Int = 10_000,
): CategoryBudgetSuggestion =
    CategoryBudgetSuggestion(
        category = Category(id = "category:$name", name = name, nature = CategoryNature.WANT),
        suggestion =
            BudgetSuggestion(
                amount = amount,
                medianAmount = median,
                seasonalEventId = eventId,
                seasonalIndexBps = indexBps,
                provenance =
                    testProvenance(RuleCitation("RULE-BUD-SUGGEST", "1.0"), window = "2026-05-01..2026-07-31"),
            ),
    )

/**
 * Builds a [BudgetReview] for a test (issue 4.6; §5.5).
 * Why:    same bargain as [alertRow] and [suggestionRow] — the test states the direction and
 *         proposal it wants rendered rather than making the engine derive them, so a screen test
 *         fails for screen reasons. The provenance is real because [BudgetReview] refuses to be
 *         built without a cited rule (P-02).
 * Result: a review with one reviewed category. Input: [name]; [direction]; [budgeted]; [actual];
 *         [proposal] — `null` for an on-plan row or one with too little history to price.
 *         Output: [BudgetReview].
 * Changelog: 2026-08-15 — Created for issue 4.6.
 */
internal fun reviewRow(
    name: String = "Groceries",
    direction: VarianceDirection = VarianceDirection.OVER,
    budgeted: Money = Money(1_000_000L),
    actual: Money = Money(1_300_000L),
    proposal: BudgetSuggestion? = null,
): BudgetReview {
    val variance = actual - budgeted
    return BudgetReview(
        monthStartIsoDate = "2026-07-01",
        totalBudgeted = budgeted,
        totalActual = actual,
        categories =
            listOf(
                ReviewedCategory(
                    categoryId = "category:$name",
                    categoryName = name,
                    budgeted = budgeted,
                    actual = actual,
                    variance = variance,
                    varianceBps = (variance.minor.let { if (it < 0) -it else it } * BPS_FULL / budgeted.minor).toInt(),
                    direction = direction,
                    proposal = proposal,
                ),
            ),
        provenance = testProvenance(RuleCitation("RULE-BUD-REVIEW", "1.0")),
    )
}

/** 10 000 bps = 100% (MNY-002), the unit [reviewRow] computes `varianceBps` in. */
private const val BPS_FULL = 10_000

/**
 * Result: a provenance a fixture can carry. Input: [citation]; [window] — the history read, which a
 *         suggestion must state (AI-ARC-003). Output: [EngineProvenance].
 */
private fun testProvenance(
    citation: RuleCitation,
    window: String? = null,
): EngineProvenance =
    EngineProvenance(
        engineId = "budget-planner",
        engineVersion = "1.0",
        computedAtUtcMillis = 1_786_000_000_000L,
        evidence = listOf(citation),
        inputWindow = window,
    )
