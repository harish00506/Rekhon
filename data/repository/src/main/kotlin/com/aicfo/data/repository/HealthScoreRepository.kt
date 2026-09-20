package com.aicfo.data.repository

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Category
import com.aicfo.core.model.CategoryNature
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.card.CardStatus
import com.aicfo.domain.engines.emergencyfund.EmergencyFundPlan
import com.aicfo.domain.engines.goals.GoalProjection
import com.aicfo.domain.engines.goals.GoalStatus
import com.aicfo.domain.engines.healthscore.HealthInput
import com.aicfo.domain.engines.healthscore.HealthRules
import com.aicfo.domain.engines.healthscore.HealthScore
import com.aicfo.domain.engines.healthscore.HealthScoreEngine
import com.aicfo.domain.engines.healthscore.MonthFlow
import com.aicfo.domain.engines.healthscore.ObligationInput
import com.aicfo.domain.engines.healthscore.RunwayInput
import com.aicfo.domain.engines.healthscore.SavingsInput
import com.aicfo.domain.engines.healthscore.ShareInput
import com.aicfo.domain.engines.healthscore.UtilisationInput
import com.aicfo.domain.engines.loan.AmortisationRow
import com.aicfo.domain.engines.stream.StreamClass
import com.aicfo.domain.engines.stream.StreamProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

/**
 * The Financial Health Score, for the active profile (issue 9.4; §14, AI-FHS).
 *
 * Why:  the score's inputs are other engines' answers — the emergency fund's runway, AI-CLS's fixed
 *       streams, the loans' instalments, the cards' utilisation, the budgets, the goals — so this is
 *       built over the repositories that own them, not over the database: the score and the screens
 *       beside it can never disagree about a runway or a budget (the ADR-0007 argument, ADR-0045).
 * What: one read; the join is [HealthSignals].
 * Result: a ViewModel sees a [HealthScore] and nothing else.
 * Changelog: 2026-09-19 — Created for issue 9.4.
 */
interface HealthScoreRepository {
    /**
     * Observes the active profile's score.
     * Result: re-emits whenever any source changes; `Err` only for an input the engine refuses, which
     *         the sources' own invariants should make unreachable. A profile with no data at all gets
     *         an `Ok` score of `null`, not an error.
     * Input:  none. Output: `Flow<Result<HealthScore, AppError>>`.
     */
    fun observeHealthScore(): Flow<Result<HealthScore, AppError>>
}

/**
 * The sources, as flows (issue 9.4).
 * Why:  each already follows the active profile and the demo (ADR-0006), so holding flows rather
 *       than repositories keeps this class free of every method it does not read — and lets a test
 *       drive it with plain `MutableStateFlow`s.
 * Input:  one flow per source. Output: an immutable value.
 * Changelog: 2026-09-19 — Created for issue 9.4.
 */
internal data class HealthSources(
    val emergency: Flow<EmergencyFundPlan>,
    val ledger: Flow<List<MonthlyLedger>>,
    val streams: Flow<Result<StreamProfile, AppError>>,
    val instalments: Flow<Map<String, AmortisationRow>>,
    val cards: Flow<Map<String, CardStatus>>,
    val budgets: Flow<List<CategoryBudget>>,
    val goals: Flow<List<GoalProjection>>,
    val categories: Flow<List<Category>>,
)

/**
 * [HealthScoreRepository] over the owning repositories' flows (issue 9.4).
 * Input:  [sources]; [engine]; [clock] — provenance only; [dispatchers]; [rules] — the seam.
 * Output: the repository.
 * Changelog: 2026-09-19 — Created for issue 9.4.
 */
internal class ComposedHealthScoreRepository(
    private val sources: HealthSources,
    private val engine: HealthScoreEngine,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val rules: HealthRules = HealthRules(),
) : HealthScoreRepository {
    override fun observeHealthScore(): Flow<Result<HealthScore, AppError>> {
        val money =
            combine(sources.emergency, sources.ledger, sources.streams, sources.instalments, sources.categories) {
                    plan, months, streams, instalments, categories ->
                MoneySide(plan, months, (streams as? Ok)?.value, instalments.values.toList(), categories)
            }
        val plans =
            combine(
                sources.cards,
                sources.budgets,
                sources.goals,
            ) { cards, budgets, goals -> PlanSide(cards, budgets, goals) }
        return combine(money, plans) { m, p -> engine.score(inputOf(m, p)) }.flowOn(dispatchers.io)
    }

    /** Result: the engine's input from one consistent read. Input: [money]; [plans]. */
    private fun inputOf(
        money: MoneySide,
        plans: PlanSide,
    ): HealthInput {
        val liabilityCategories =
            money.categories.filter { it.nature == CategoryNature.LIABILITY }.map { it.id }.toSet()
        return HealthInput(
            runway = HealthSignals.runway(money.plan),
            obligations =
                HealthSignals.obligations(
                    money.months,
                    money.streams,
                    money.instalments,
                    liabilityCategories,
                ),
            cards = HealthSignals.cards(plans.cards.values),
            savings = HealthSignals.savings(money.months),
            budgets = HealthSignals.budgets(plans.budgets),
            goals = HealthSignals.goals(plans.goals),
            window = money.months.takeIf { it.isNotEmpty() }?.let { "${it.first().monthKey}..${it.last().monthKey}" },
            nowUtcMillis = clock.nowUtcMillis(),
            rules = rules,
        )
    }

    /** The money half of one read. */
    private data class MoneySide(
        val plan: EmergencyFundPlan,
        val months: List<MonthlyLedger>,
        val streams: StreamProfile?,
        val instalments: List<AmortisationRow>,
        val categories: List<Category>,
    )

    /** The plans half of one read. */
    private data class PlanSide(
        val cards: Map<String, CardStatus>,
        val budgets: List<CategoryBudget>,
        val goals: List<GoalProjection>,
    )
}

/**
 * Each signal from the source that owns it (issue 9.4; ADR-0045).
 *
 * Why:  the join decisions are where a plausible score goes wrong — an EMI counted twice, a card with
 *       no known balance scored as empty, a goal with no target scored as behind — so each lives in
 *       one small function with its own test.
 * What: runway, obligations, cards, savings, budgets, goals.
 * Result: the engine's signals; `null` wherever the source has nothing to say.
 * Changelog: 2026-09-19 — Created for issue 9.4.
 */
internal object HealthSignals {
    /** Result: the plan's runway against its M, or `null` while essentials are unknown. Input: [plan]. */
    fun runway(plan: EmergencyFundPlan): RunwayInput? =
        plan.runwayMonthsBps?.let { RunwayInput(it, plan.multiplierMonths) }

    /**
     * Fixed obligations and EMIs over the typical month's income.
     * Why:    §14's "(EMI + rent) / income", read from what the app measures: AI-CLS's FIXED streams
     *         (rent, and any confirmed obligation) at their typical month, plus each loan's next
     *         instalment. **When loans exist, a FIXED stream in a liability category is dropped** —
     *         its payments are that loan's instalments, already counted. Income is the median of the
     *         closed months that had any.
     *
     *         **Nothing fixed and no loan means unknown, not zero** (found on the device, ADR-0045):
     *         AI-CLS cannot call a stream FIXED until three closed months (§8.2), so a young profile
     *         with rent would otherwise score "0% of income, full marks" — a claim its data does not
     *         support. §14's own insufficient-data rule applies, and the signal is absent instead.
     * Result: the signal, or `null` with no month of income, or nothing measured to call an obligation.
     * Input:  [months]; [streams] — `null` when AI-CLS failed; [instalments]; [liabilityCategories].
     * Output: [ObligationInput]?.
     */
    fun obligations(
        months: List<MonthlyLedger>,
        streams: StreamProfile?,
        instalments: List<AmortisationRow>,
        liabilityCategories: Set<String>,
    ): ObligationInput? {
        val incomes = months.map { it.income }.filter { it > Money.ZERO }
        val fixed =
            streams?.streams.orEmpty()
                .filter { it.streamClass == StreamClass.FIXED }
                .filterNot { instalments.isNotEmpty() && it.streamKey in liabilityCategories }
                .fold(Money.ZERO) { sum, stream -> sum + stream.typicalMonthly }
        val measured = fixed > Money.ZERO || instalments.isNotEmpty()
        if (incomes.isEmpty() || !measured) return null
        val emis = instalments.fold(Money.ZERO) { sum, row -> sum + row.amount }
        return ObligationInput(fixed + emis, median(incomes), incomes.size)
    }

    /**
     * Every card's **statement** balance over its limit, summed.
     * Why:    the statement figure is what a credit bureau records and what `RULE-CC-UTIL`'s rationale
     *         is about; the live figure moves with every swipe and would make the score twitch daily,
     *         against §14's "updates at most weekly". A card with no statement yet is left out
     *         entirely — its limit too — rather than scored as empty.
     * Result: the signal, or `null` with no statement to read. Input: [statuses]. Output: [UtilisationInput]?.
     */
    fun cards(statuses: Collection<CardStatus>): UtilisationInput? {
        val known = statuses.mapNotNull { card -> card.statement.used?.let { card to it } }
        if (known.isEmpty()) return null
        return UtilisationInput(
            used = known.fold(Money.ZERO) { sum, (_, used) -> sum + used },
            limit = known.fold(Money.ZERO) { sum, (card, _) -> sum + card.creditLimit },
        )
    }

    /**
     * Each closed month's income and what was kept: income less needs, wants and liability payments.
     * Invested and asset outflows count as kept — money moved into savings is what the rate measures.
     * Result: the signal (the engine decides whether it has enough). Input: [months].
     */
    fun savings(months: List<MonthlyLedger>): SavingsInput =
        SavingsInput(
            months.map { month ->
                MonthFlow(
                    monthKey = month.monthKey,
                    income = month.income,
                    saved = month.income - month.nature.needs - month.nature.wants - month.nature.liabilities,
                )
            },
        )

    /** Result: this month's budgets that are not overspent, of those set; `null` with none set. */
    fun budgets(rows: List<CategoryBudget>): ShareInput? {
        val set = rows.filterNot { it.isUnbudgeted }
        return if (set.isEmpty()) null else ShareInput(set.count { !it.status.isOverspent }, set.size)
    }

    /** Result: goals on track or funded, of those with a target; `null` with none. Input: [goals]. */
    fun goals(goals: List<GoalProjection>): ShareInput? {
        val tracked = goals.filter { it.status != GoalStatus.NO_TARGET }
        val good = tracked.count { it.status == GoalStatus.ON_TRACK || it.status == GoalStatus.OVER_FUNDED }
        return if (tracked.isEmpty()) null else ShareInput(good, tracked.size)
    }

    /** Result: the median, the lower-middle-and-upper-middle midpoint split to the paisa. Input: non-empty. */
    private fun median(values: List<Money>): Money {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]).split(2).first()
    }
}
