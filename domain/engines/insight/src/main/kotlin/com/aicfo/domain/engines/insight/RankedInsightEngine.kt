package com.aicfo.domain.engines.insight

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money

/**
 * §7.2's assembly step: engine results in, a ranked and fingerprinted feed out (issue 9.5; AI-ORCH,
 * P-02, P-03, P-08, ADR-0046).
 *
 * Why:  every card here **repeats** a figure another engine published, with that engine's id,
 *       version, rules and confidence attached (AI-ARC-003/006). Nothing is computed: an
 *       orchestrator that did arithmetic would be a second opinion about money, and the first one
 *       would be the engine that owns it.
 * What: validate → one collector per signal, each deciding only whether its signal is worth a card
 *       → sort by RULE-INS-RANK → take the dashboard's few → provenance.
 * Result: an [InsightFeed].
 * Changelog: 2026-09-20 — Created for issue 9.5.
 *
 * No clock, no randomness, no I/O (P-08); the same signals always give the same feed, which is what
 * makes a fingerprint worth dismissing.
 */
internal class RankedInsightEngine : InsightEngine {
    override fun insights(input: InsightInput): Result<InsightFeed, AppError> {
        validate(input)?.let { return Err(it) }
        val raised =
            buildList {
                input.forecast?.let { forecast ->
                    crunch(forecast, input)?.let(::add)
                    addAll(seasonal(forecast))
                }
                input.emergency?.let { fund -> shortfall(fund, input)?.let(::add) }
                input.health?.let { health -> lever(health, input)?.let(::add) }
                addAll(input.budgets.mapNotNull(::overspent))
                addAll(input.goals.mapNotNull(::behind))
            }.sortedWith(RANK)
        return Ok(
            InsightFeed(
                insights = raised,
                dashboard = raised.take(input.rules.dashboardMax),
                provenance = provenance(input),
            ),
        )
    }

    /**
     * The signals that cannot be true.
     * Result: the first refusal by field, or `null`. Input: [input]. Output: `AppError.Validation?`.
     */
    private fun validate(input: InsightInput): AppError.Validation? =
        when {
            input.forecast?.let { it.crunchDays < 0 } == true -> AppError.Validation(FIELD_FORECAST)
            input.emergency?.let { it.shortfall.minor < 0L || it.topUpMonthly.minor < 0L } == true ->
                AppError.Validation(FIELD_EMERGENCY)
            input.health?.let { health -> health.leverGain?.let { it < 0 } == true } == true ->
                AppError.Validation(FIELD_HEALTH)
            input.budgets.any { it.overspentBy.minor < 0L } -> AppError.Validation(FIELD_BUDGET)
            input.goals.any { it.shortfallMonthly.minor < 0L } -> AppError.Validation(FIELD_GOAL)
            else -> null
        }

    /**
     * The forecast's crunch days, if it found any (AI-FCT-002).
     * Why:    the one CRITICAL card the app raises: a day the balance is expected to fall below the
     *         buffer is not a matter of taste. Its amount is the balance on that day, which is what
     *         makes it rank above every warning of any size.
     * Result: the insight, or `null`. Input: [forecast]; [input]. Output: [Insight]?.
     */
    private fun crunch(
        forecast: ForecastSignal,
        input: InsightInput,
    ): Insight? {
        if (forecast.crunchDays <= 0) return null
        return insight(
            type = InsightType.CRUNCH_DAY,
            subject = null,
            label = null,
            period = input.today.toString(),
            amount = forecast.lowest,
            secondary = forecast.buffer,
            date = forecast.firstCrunchDate,
            quantity = forecast.crunchDays,
            from = forecast.provenance,
        )
    }

    /**
     * Each month ahead the season makes dearer.
     * Why:    only the dearer ones. A month the season makes **cheaper** is good news that needs no
     *         action, and §7.2's feed is what needs attention (ADR-0046).
     * Result: one insight per such month. Input: [forecast]. Output: `List<Insight>`.
     */
    private fun seasonal(forecast: ForecastSignal): List<Insight> =
        forecast.seasonalMonths
            .filter { it.adjustment.minor > 0L }
            .map { month ->
                insight(
                    type = InsightType.SEASONAL_MONTH,
                    subject = month.month,
                    label = null,
                    period = month.month,
                    amount = month.adjustment,
                    from = forecast.provenance,
                )
            }

    /** The emergency fund, while it is short of its target. Result: the insight, or `null`. */
    private fun shortfall(
        fund: EmergencyFundSignal,
        input: InsightInput,
    ): Insight? {
        if (fund.shortfall.minor <= 0L) return null
        return insight(
            type = InsightType.EMERGENCY_FUND_SHORT,
            subject = null,
            label = null,
            period = input.today.toString(),
            amount = fund.shortfall,
            secondary = fund.topUpMonthly,
            from = fund.provenance,
        )
    }

    /**
     * The health score's biggest lever (§14's "single highest-leverage action").
     * Why:    it carries no amount, deliberately: its figure is points, not money, so it sorts below
     *         anything with rupees at stake, which is where a "you could improve this" belongs.
     * Result: the insight, or `null` when there is nothing to gain. Input: [health]; [input].
     */
    private fun lever(
        health: HealthSignal,
        input: InsightInput,
    ): Insight? {
        val label = health.leverLabel ?: return null
        val gain = health.leverGain ?: return null
        if (gain <= 0) return null
        return insight(
            type = InsightType.HEALTH_LEVER,
            subject = label,
            label = null,
            period = input.today.toString(),
            amount = null,
            quantity = gain,
            from = health.provenance,
        )
    }

    /** One overspent budget. Result: the insight, or `null` when it is within its plan. */
    private fun overspent(budget: BudgetSignal): Insight? {
        if (budget.overspentBy.minor <= 0L) return null
        return insight(
            type = InsightType.BUDGET_OVERSPENT,
            subject = budget.categoryId,
            label = budget.categoryName,
            period = budget.monthKey,
            amount = budget.overspentBy,
            from = budget.provenance,
        )
    }

    /** One goal short of its plan. Result: the insight, or `null` when the plan clears it. */
    private fun behind(goal: GoalSignal): Insight? {
        if (goal.shortfallMonthly.minor <= 0L) return null
        return insight(
            type = InsightType.GOAL_BEHIND,
            subject = goal.goalId,
            label = goal.name,
            period = goal.targetDateIso,
            amount = goal.shortfallMonthly,
            from = goal.provenance,
        )
    }

    /**
     * One card, attributed to the engine whose figure it repeats (AI-ARC-003/006).
     * Result: the insight. Input: the card's fields; [from] — the source engine's provenance.
     */
    @Suppress("LongParameterList") // one argument per field of the card; the alternative is a second type
    private fun insight(
        type: InsightType,
        subject: String?,
        label: String?,
        period: String,
        amount: Money?,
        secondary: Money? = null,
        date: java.time.LocalDate? = null,
        quantity: Int? = null,
        from: EngineProvenance,
    ) = Insight(
        type = type,
        subject = subject,
        subjectLabel = label,
        period = period,
        amount = amount,
        secondary = secondary,
        date = date,
        quantity = quantity,
        confidenceBps = from.confidenceBps,
        citations = from.evidence,
        sourceEngineId = from.engineId,
        sourceEngineVersion = from.engineVersion,
    )

    /**
     * Provenance (AI-ARC-003). **No confidence:** the orchestrator computes no figure to be
     * confident about; each insight carries the confidence of the engine it repeats.
     * Result: the provenance. Input: [input]. Output: [EngineProvenance].
     */
    private fun provenance(input: InsightInput) =
        EngineProvenance(
            engineId = ENGINE_ID,
            engineVersion = ENGINE_VERSION,
            computedAtUtcMillis = input.nowUtcMillis,
            evidence = listOf(InsightRules.RANK, InsightRules.DEDUP),
            inputWindow = input.today.toString(),
        )

    private companion object {
        const val ENGINE_ID = "AI-ORCH"
        const val ENGINE_VERSION = "1.0"
        const val FIELD_FORECAST = "insight.forecast"
        const val FIELD_EMERGENCY = "insight.emergency"
        const val FIELD_HEALTH = "insight.health"
        const val FIELD_BUDGET = "insight.budget"
        const val FIELD_GOAL = "insight.goal"

        /**
         * RULE-INS-RANK: severity (the enum's own order, worst first), then the amount at stake
         * descending with "no amount" last, then the fingerprint so the order is total.
         */
        val RANK: Comparator<Insight> =
            compareBy<Insight> { it.severity.ordinal }
                .thenBy { if (it.amount == null) 1 else 0 }
                .thenByDescending { it.amount?.minor ?: 0L }
                .thenBy { it.fingerprint }
    }
}
