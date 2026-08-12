package com.aicfo.data.repository

import androidx.room.withTransaction
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.BudgetEntity
import com.aicfo.core.model.Category
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.budget.BudgetEngine
import com.aicfo.domain.engines.budget.BudgetStatus
import com.aicfo.domain.engines.budget.BudgetStatusInput
import com.aicfo.domain.engines.budget.BudgetSuggestion
import com.aicfo.domain.engines.budget.BudgetSuggestionInput
import com.aicfo.domain.engines.budget.MonthlySpend
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * One category's budget for a month, with everything the screen shows about it (FR-BUD-003).
 *
 * Why:  a budget row on its own says nothing useful — the screen's question is always "how is this
 *       going?", which needs the category's name, the amount planned and the engine's status
 *       together. Assembling them here rather than on the screen keeps the ViewModel free of any
 *       arithmetic (P-03) and free of Room types (ARC-005).
 * What: the category, the stored budget's identity and provenance, and the computed status.
 * Result: a row the UI renders without computing anything.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 *
 * Input:  [id] — the `budget` row, `null` when this category has no budget yet; [category];
 *         [status] — the engine's figures; [rolloverEnabled] — FR-BUD-001's opt-in; [source] —
 *         `manual` when the user typed the number, `suggested` when they accepted one (P-02).
 * Output: an immutable value.
 */
data class CategoryBudget(
    val id: String?,
    val category: Category,
    val status: BudgetStatus,
    val rolloverEnabled: Boolean,
    val source: String,
) {
    /** Result: true when nothing has been planned for this category — it is spend without a budget. */
    val isUnbudgeted: Boolean get() = id == null
}

/**
 * A budget the app proposes for a category, with the reasoning attached (FR-BUD-002).
 *
 * Why:  P-07 — the app recommends and the user decides, so a suggestion has to be a *different
 *       thing* from a budget in the type system, not a budget with a flag on it. Nothing writes a
 *       `budget` row until [BudgetRepository.acceptSuggestion] is called from a tap.
 * What: the category it is for and the engine's [BudgetSuggestion], which carries the amount, the
 *       unadjusted median, the seasonal event and the cited rules.
 * Result: the screen can show the number **and** why it is that number (P-02).
 * Changelog: 2026-08-11 — Created for issue 4.4.
 */
data class CategoryBudgetSuggestion(
    val category: Category,
    val suggestion: BudgetSuggestion,
)

/**
 * The per-category budget store (issue 4.4; FR-BUD-001, FR-BUD-002, FR-BUD-003).
 *
 * Why:  ARC-005 — this is the only class allowed to touch the budget, transaction and category DAOs,
 *       and the only place the engine's inputs are assembled. Everything above it sees domain types.
 * What: reads budgets with their live status, offers suggestions from history, and writes the
 *       amounts the user chooses.
 * Result: a budget screen that recomputes itself whenever a transaction lands, with no arithmetic
 *       above this layer.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 *
 * **Budgets share the `budget` table with quick setup's nature-level envelopes** (ADR-0004). The two
 * are separated by `category_id`: null for an envelope, set for one of these. Neither read sees the
 * other's rows, which is why `observeLatestEnvelopes` still drives the dashboard rings unchanged.
 */
interface BudgetRepository {
    /**
     * Every category with its budget and live status, for the current month.
     * Why:    categories with **no** budget are included, as unbudgeted rows with zero planned. A
     *         screen that listed only budgeted categories would hide the spending a user most needs
     *         to see — the category they have not thought about yet.
     * Result: one row per category, re-emitted whenever a transaction or a budget changes.
     * Input:  none — the active profile and the current month are resolved internally.
     * Output: `Flow<List<CategoryBudget>>`.
     */
    fun observeBudgets(): Flow<List<CategoryBudget>>

    /**
     * Proposals for categories that have enough history and no budget yet (FR-BUD-002).
     * Why:    a category the user has already budgeted is a decision already made; re-suggesting
     *         over it would be the app arguing with them (P-07).
     * Result: one row per suggestible category, empty when history is too thin.
     * Input:  none. Output: `Flow<List<CategoryBudgetSuggestion>>`.
     */
    fun observeSuggestions(): Flow<List<CategoryBudgetSuggestion>>

    /**
     * Sets or replaces one category's budget for the current month (FR-BUD-001).
     * Result: `Ok` with the stored budget's id, or `Validation` when the amount is negative or the
     *         category does not exist.
     * Input:  [categoryId]; [amount] — paise (MNY-001); [rolloverEnabled] — FR-BUD-001's opt-in.
     * Output: `Result<String, AppError>`.
     */
    suspend fun setBudget(
        categoryId: String,
        amount: Money,
        rolloverEnabled: Boolean,
    ): Result<String, AppError>

    /**
     * Accepts a suggestion as this month's budget for one category (FR-BUD-002's one-tap accept).
     * Why:    a separate call from [setBudget] because the two record different things. An accepted
     *         suggestion stores `source = 'suggested'` **and the rule that produced it**, so a
     *         reviewer can later tell an amount the app proposed from one the user chose — which is
     *         the audit trail §29's governance clause asks for (AI-ARC-006).
     * Result: `Ok` with the stored id, or `NotFound` when nothing is suggestible for that category.
     * Input:  [categoryId]. Output: `Result<String, AppError>`.
     */
    suspend fun acceptSuggestion(categoryId: String): Result<String, AppError>

    /**
     * Removes a budget, leaving the category unbudgeted (FR-BUD-001).
     * Result: `Ok`, or `NotFound` when the id names nothing live. Soft delete (DB-002).
     * Input:  [id] — the budget row's id. Output: `Result<Unit, AppError>`.
     */
    suspend fun deleteBudget(id: String): Result<Unit, AppError>

    companion object {
        /** `manual` — the user typed this number themselves. */
        const val SOURCE_MANUAL = "manual"

        /** `suggested` — the user accepted a number the app proposed, which cites its rule. */
        const val SOURCE_SUGGESTED = "suggested"
    }
}

/**
 * The Room-backed [BudgetRepository].
 *
 * Why:    takes the whole [database] because a budget's status spans three tables — the budget, the
 *         month's transactions (and their split lines) and the taxonomy — and rollover additionally
 *         needs last month's budgets read in the same breath as last month's spend.
 * Result: a [BudgetRepository] over the encrypted database.
 * Input:  [database]; [engine] — takes the engine rather than constructing one, so the number and
 *         the code that produced it stay assembled in the DI graph (ARC-003, P-03); [clock] —
 *         TIM-001, the **only** clock in this feature: the engine gets days, never dates;
 *         [dispatchers]; [activeProfileId] — which profile's budgets, so the demo keeps its own.
 * Output: [BudgetRepository].
 * Changelog: 2026-08-11 — Created for issue 4.4.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RoomBudgetRepository(
    private val database: CfoDatabase,
    private val engine: BudgetEngine,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
) : BudgetRepository {
    override fun observeBudgets(): Flow<List<CategoryBudget>> =
        // flatMapLatest, not map: entering or leaving the demo must *switch* which queries are
        // observed, cancelling the previous ones.
        activeProfileId.flatMapLatest { profileId ->
            val month = MonthWindow.current(clock.today())
            combine(
                database.categoryDao().observeForProfile(profileId),
                database.budgetDao().observeCategoryBudgets(profileId, month.startIsoDate),
                database.transactionDao()
                    .observeCategorySpend(profileId, month.startIsoDate, month.actualsEndIsoDate),
            ) { categories, budgets, spend ->
                val spentByCategory = spend.associate { it.categoryId to Money(it.spentMinor) }
                val budgetByCategory = budgets.associateBy { it.categoryId }
                val carried = carriedOverFor(profileId, month, budgetByCategory.keys.filterNotNull())
                categories.mapNotNull { it.toCategory() }.map { category ->
                    categoryBudget(
                        category = category,
                        entity = budgetByCategory[category.id],
                        spent = spentByCategory[category.id] ?: Money.ZERO,
                        carriedOver = carried[category.id] ?: Money.ZERO,
                        month = month,
                    )
                }
            }.flowOn(dispatchers.io)
        }

    override fun observeSuggestions(): Flow<List<CategoryBudgetSuggestion>> =
        activeProfileId.flatMapLatest { profileId ->
            val month = MonthWindow.current(clock.today())
            val history = historyWindow(clock.today())
            combine(
                database.categoryDao().observeForProfile(profileId),
                database.budgetDao().observeCategoryBudgets(profileId, month.startIsoDate),
                database.transactionDao()
                    .observeMonthlyCategorySpend(profileId, history.first, history.second),
            ) { categories, budgets, monthlySpend ->
                val alreadyBudgeted = budgets.mapNotNull { it.categoryId }.toSet()
                val byCategory = monthlySpend.groupBy { it.categoryId }
                val monthsObserved = byCategory.values.flatten().map { it.monthKey }.distinct().size
                categories.mapNotNull { it.toCategory() }
                    .filterNot { it.id in alreadyBudgeted }
                    .mapNotNull { category ->
                        suggestionFor(category, byCategory[category.id].orEmpty(), monthsObserved)
                    }
            }.flowOn(dispatchers.io)
        }

    override suspend fun setBudget(
        categoryId: String,
        amount: Money,
        rolloverEnabled: Boolean,
    ): Result<String, AppError> {
        if (amount < Money.ZERO) return Err(AppError.Validation(FIELD_AMOUNT))
        return write(categoryId, amount, rolloverEnabled, BudgetRepository.SOURCE_MANUAL, citation = null)
    }

    override suspend fun acceptSuggestion(categoryId: String): Result<String, AppError> {
        // Read the suggestion through the same Flow the screen saw, so an accepted number is
        // provably the number that was shown rather than one recomputed a moment later — the two
        // could differ across a month boundary, and the user tapped the one they read.
        val suggestion =
            observeSuggestions().first().firstOrNull { it.category.id == categoryId }
                ?: return Err(AppError.NotFound)
        return write(
            categoryId = categoryId,
            amount = suggestion.suggestion.amount,
            rolloverEnabled = false,
            source = BudgetRepository.SOURCE_SUGGESTED,
            // The first citation is the rule; a seasonal suggestion appends the calendar event.
            // Storing the rule is what lets a later reviewer reproduce this amount (AI-ARC-006).
            citation = suggestion.suggestion.provenance.evidence.firstOrNull(),
        )
    }

    override suspend fun deleteBudget(id: String): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult {
                val changed = database.budgetDao().softDelete(id, clock.nowUtcMillis())
                if (changed == 0) Err(AppError.NotFound) else Ok(Unit)
            }.flatten()
        }

    // --- writes ---------------------------------------------------------------------------------

    /**
     * The one place a `budget` row with a `category_id` is written.
     * Why:    both public writers land here so the id derivation, the profile scoping and the
     *         created/updated stamps cannot diverge between them — the difference between a typed
     *         budget and an accepted suggestion is two columns, not two code paths.
     * Result: `Ok` with the row's id, or `Validation` when the category does not exist.
     * Input:  [categoryId]; [amount]; [rolloverEnabled]; [source]; [citation] — the rule that
     *         produced the amount, `null` when the user typed it.
     * Output: `Result<String, AppError>`.
     */
    private suspend fun write(
        categoryId: String,
        amount: Money,
        rolloverEnabled: Boolean,
        source: String,
        citation: com.aicfo.core.model.RuleCitation?,
    ): Result<String, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult {
                val profileId = activeProfileId.first()
                val now = clock.nowUtcMillis()
                val periodStart = MonthWindow.current(clock.today()).startIsoDate
                database.withTransaction {
                    // Checked inside the transaction that then writes: a category deleted on the
                    // taxonomy screen must not gain a budget from a form opened before it went.
                    val category = database.categoryDao().findById(categoryId)
                    if (category == null || category.deletedAtUtcMillis != null) {
                        return@withTransaction Err(AppError.Validation(FIELD_CATEGORY))
                    }
                    val id = categoryBudgetId(profileId, categoryId, periodStart)
                    val existing = database.budgetDao().findById(id)
                    database.budgetDao().upsert(
                        BudgetEntity(
                            id = id,
                            profileId = profileId,
                            categoryId = categoryId,
                            nature = null,
                            periodStartIsoDate = periodStart,
                            amountMinor = amount.minor,
                            rolloverEnabled = rolloverEnabled,
                            source = source,
                            ruleId = citation?.ruleId ?: UNATTRIBUTED_RULE_ID,
                            ruleVersion = citation?.ruleVersion ?: UNATTRIBUTED_RULE_VERSION,
                            // Preserved across a re-save, so "when was this budget first set?"
                            // survives editing it. REPLACE writes a whole row, so without this the
                            // creation stamp would silently become the last edit's.
                            createdAtUtcMillis = existing?.createdAtUtcMillis ?: now,
                            updatedAtUtcMillis = now,
                        ),
                    )
                    Ok(id)
                }
            }.flatten()
        }

    // --- reads ----------------------------------------------------------------------------------

    /**
     * Assembles one category's row, budgeted or not.
     * Why:    an unbudgeted category still has spending, and the screen shows it — so it gets a
     *         status against a zero budget rather than being filtered out. `isUnbudgeted` is what
     *         the UI branches on, not a null status it would have to guard everywhere.
     * Result: the row. Input: [category]; [entity] — the stored budget or `null`; [spent];
     *         [carriedOver]; [month]. Output: [CategoryBudget].
     */
    private fun categoryBudget(
        category: Category,
        entity: BudgetEntity?,
        spent: Money,
        carriedOver: Money,
        month: MonthWindow,
    ): CategoryBudget {
        val status =
            engine.status(
                BudgetStatusInput(
                    categoryId = category.id,
                    plannedAmount = Money(entity?.amountMinor ?: 0L),
                    carriedOver = carriedOver,
                    spent = spent,
                    daysInPeriod = month.daysInMonth,
                    daysElapsed = month.daysElapsed,
                    nowUtcMillis = clock.nowUtcMillis(),
                ),
            )
        return CategoryBudget(
            id = entity?.id,
            category = category,
            status = (status as Ok).value,
            rolloverEnabled = entity?.rolloverEnabled ?: false,
            source = entity?.source ?: BudgetRepository.SOURCE_MANUAL,
        )
    }

    /**
     * What each category has left over from last month, when its budget rolls over (FR-BUD-001).
     * Why:    rollover is defined as "unused amount", so it is last month's budget minus last
     *         month's spend — which means reading a second month. **Only positive leftovers carry**:
     *         rolling a *deficit* forward would silently shrink a budget the user set, turning one
     *         bad month into two without ever saying so.
     *
     *         Read with the suspend DAO rather than a third Flow, because it changes only when a
     *         past month's data changes — which the current month's Flows will re-trigger anyway.
     * Result: leftover per category id; absent when rollover is off or nothing was left.
     * Input:  [profileId]; [month] — the current window; [categoryIds] — the budgeted categories.
     * Output: `Map<String, Money>`.
     */
    private suspend fun carriedOverFor(
        profileId: String,
        month: MonthWindow,
        categoryIds: List<String>,
    ): Map<String, Money> {
        if (categoryIds.isEmpty()) return emptyMap()
        val previous = MonthWindow.closed(LocalDate.parse(month.startIsoDate).minusMonths(1))
        val lastMonthBudgets =
            database.budgetDao()
                .categoryBudgetsForPeriod(profileId, previous.startIsoDate)
                .filter { it.rolloverEnabled }
        if (lastMonthBudgets.isEmpty()) return emptyMap()

        val lastMonthSpend =
            database.transactionDao()
                .observeCategorySpend(profileId, previous.startIsoDate, previous.endIsoDate)
                .first()
                .associate { it.categoryId to Money(it.spentMinor) }

        return lastMonthBudgets.mapNotNull { budget ->
            val categoryId = budget.categoryId ?: return@mapNotNull null
            val leftover = Money(budget.amountMinor) - (lastMonthSpend[categoryId] ?: Money.ZERO)
            if (leftover > Money.ZERO) categoryId to leftover else null
        }.toMap()
    }

    /**
     * Turns one category's monthly totals into a suggestion, or nothing.
     * Why:    the months arrive from a `GROUP BY` that only produces rows for months with spending,
     *         so a category that was untouched in February has two rows, not three — and the engine
     *         must see that as two months of history, which is exactly what it does. Inventing zero
     *         rows for the missing months would drag the median toward zero and suggest a budget
     *         nobody could live on.
     * Result: the suggestion, or `null` when the engine declines for want of history.
     * Input:  [category]; [rows] — this category's months, oldest first; [monthsObserved] — how many
     *         distinct months the *profile* has, which shrinks the seasonal prior.
     * Output: [CategoryBudgetSuggestion]?.
     */
    private fun suggestionFor(
        category: Category,
        rows: List<com.aicfo.core.database.dao.MonthlyCategorySpendRow>,
        monthsObserved: Int,
    ): CategoryBudgetSuggestion? {
        if (rows.isEmpty()) return null
        val suggestion =
            engine.suggest(
                BudgetSuggestionInput(
                    categoryId = category.id,
                    categoryName = category.name,
                    monthlySpends = rows.map { MonthlySpend("${it.monthKey}-01", Money(it.spentMinor)) },
                    targetMonth = clock.today().monthValue,
                    monthsObserved = monthsObserved,
                    nowUtcMillis = clock.nowUtcMillis(),
                ),
            )
        return ((suggestion as Ok).value)?.let { CategoryBudgetSuggestion(category, it) }
    }

    /**
     * The date range covering the months the suggestion may read.
     * Why:    the engine takes the last `lookback_months` off whatever it is given, so this only has
     *         to be *at least* that wide — it asks for the three whole months **before** this one,
     *         because a month still being spent in is not a month's spending.
     * Result: inclusive ISO bounds. Input: [today]. Output: a `from` to `to` pair.
     */
    private fun historyWindow(today: LocalDate): Pair<String, String> {
        val lastClosedMonth = today.withDayOfMonth(1).minusMonths(1)
        val firstMonth = lastClosedMonth.minusMonths(HISTORY_MONTHS - 1L)
        return firstMonth.toString() to lastClosedMonth.withDayOfMonth(lastClosedMonth.lengthOfMonth()).toString()
    }

    private companion object {
        const val FIELD_AMOUNT = "amount"
        const val FIELD_CATEGORY = "categoryId"

        /**
         * How many closed months to fetch. Deliberately wider than `RULE-BUD-SUGGEST.lookback_months`
         * is today: the engine trims to the rule's window, so a widened rule keeps working without a
         * matching edit here, and the cost of over-fetching is one extra month per category.
         */
        const val HISTORY_MONTHS = 4
    }
}

/**
 * The id of a per-category budget.
 *
 * Why:  derived, not generated, for the reason `budgetId` gives for envelopes — `REPLACE` upserts
 *       are only idempotent if saving the same budget twice produces the same id (P-08). The
 *       envelope form (`profile:budget:<nature>:<period>`) has no slot for a category, so this is a
 *       sibling rather than an extension of it, and the `cat` segment makes a collision between the
 *       two shapes impossible even if a category were ever named `need`.
 * Result: a stable id. Input: [profileId], [categoryId], [periodStartIsoDate]. Output: [String].
 * Changelog: 2026-08-11 — Created for issue 4.4.
 */
internal fun categoryBudgetId(
    profileId: String,
    categoryId: String,
    periodStartIsoDate: String,
): String = "$profileId:budget:cat:$categoryId:$periodStartIsoDate"

/**
 * Collapses a `Result<Result<T, E>, E>` produced by `runCatchingToResult` around a block that
 * already returns a `Result`.
 * Why:    the transaction body has to be able to return `Err` for a validation failure while the
 *         wrapper catches genuine exceptions; without this every caller unwraps two layers.
 *
 *         **Deliberately a third copy**, not a shared utility. `CategoryRepository` and
 *         `TransactionNature` each carry their own, and `TransactionNature`'s KDoc records why:
 *         file-private in each, so the *reason* for the double wrapping travels with the use rather
 *         than being a generic in a utility file that explains none of them.
 * Result: the inner result. Input: the receiver. Output: `Result<T, AppError>`.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 */
private fun <T> Result<Result<T, AppError>, AppError>.flatten(): Result<T, AppError> =
    when (this) {
        is Ok -> value
        is Err -> this
    }
