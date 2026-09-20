package com.aicfo.feature.dashboard

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import com.aicfo.data.repository.FeedInsight
import com.aicfo.data.repository.InsightRepository
import com.aicfo.data.repository.InsightStatus
import com.aicfo.domain.engines.insight.Insight
import com.aicfo.domain.engines.insight.InsightType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * An [InsightRepository] the test drives (issue 9.5).
 * Why:  the ViewModel is tested for what it does with a feed and what it does with a tap — not for
 *       how a feed is built, which is the engine's and the repository's own tests.
 * What: a state flow of cards, plus a record of the refreshes and verdicts it was asked for.
 * Changelog: 2026-09-20 — Created for issue 9.5.
 */
internal class FakeInsightRepository : InsightRepository {
    private val feed = MutableStateFlow<List<FeedInsight>>(emptyList())

    /** How many times the screen asked for a recomputation. */
    var refreshes: Int = 0
        private set

    /** The verdicts recorded, in order: the row id and whether it was a snooze. */
    val verdicts: MutableList<Pair<String, Boolean>> = mutableListOf()

    override fun observeFeed(): Flow<List<FeedInsight>> = feed

    override fun observeDashboard(): Flow<List<FeedInsight>> = feed.map { it.take(DASHBOARD_MAX) }

    override suspend fun refresh(): Result<Int, AppError> {
        refreshes++
        return Ok(feed.value.size)
    }

    override suspend fun dismiss(id: String): Result<Unit, AppError> {
        verdicts += id to false
        return Ok(Unit)
    }

    override suspend fun snooze(id: String): Result<Unit, AppError> {
        verdicts += id to true
        return Ok(Unit)
    }

    override suspend fun act(id: String): Result<Unit, AppError> = Ok(Unit)

    /** Pushes a feed. Input: [insights]. Output: none. */
    fun emit(insights: List<FeedInsight> = fixtureFeed()) {
        feed.value = insights
    }

    private companion object {
        const val DASHBOARD_MAX = 3
    }
}

/**
 * A realistic feed: a crunch day, an overspent budget, a short fund, and a health lever — in
 * RULE-INS-RANK's order (issue 9.5).
 * Result: four cards. Input: none. Output: `List<FeedInsight>`.
 */
internal fun fixtureFeed(): List<FeedInsight> = listOf(crunchCard(), fundCard(), budgetCard(), leverCard())

/** The critical card: three days ahead fall under the buffer, the first on 11 October. */
private fun crunchCard() =
    card(
        id = "i-crunch",
        insight =
            Insight(
                type = InsightType.CRUNCH_DAY,
                subject = null,
                subjectLabel = null,
                period = "2026-09-20",
                amount = Money(1_200_00L),
                secondary = Money(5_000_00L),
                date = LocalDate.parse("2026-10-11"),
                quantity = 3,
                confidenceBps = 7_000,
                citations = listOf(RuleCitation("RULE-FCT-CRUNCH", "1.0")),
                sourceEngineId = "AI-FCT",
                sourceEngineVersion = "1.1",
            ),
    )

/** The biggest warning by amount: the fund is ₹1,20,000 short. */
private fun fundCard() =
    card(
        id = "i-fund",
        insight =
            Insight(
                type = InsightType.EMERGENCY_FUND_SHORT,
                subject = null,
                subjectLabel = null,
                period = "2026-09-20",
                amount = Money(1_20_000_00L),
                secondary = Money(6_500_00L),
                citations = listOf(RuleCitation("RULE-EMF-COACH", "1.0")),
                sourceEngineId = "AI-EMF",
                sourceEngineVersion = "1.0",
            ),
    )

/** A smaller warning, so the two are ordered by size. */
private fun budgetCard() =
    card(
        id = "i-budget",
        insight =
            Insight(
                type = InsightType.BUDGET_OVERSPENT,
                subject = "dining",
                subjectLabel = "Dining",
                period = "2026-09",
                amount = Money(1_250_00L),
                citations = listOf(RuleCitation("RULE-BUD-ALERT", "1.0")),
                sourceEngineId = "budget-planner",
                sourceEngineVersion = "1.0",
            ),
    )

/** The info card with no amount, so it sorts last. */
private fun leverCard() =
    card(
        id = "i-lever",
        insight =
            Insight(
                type = InsightType.HEALTH_LEVER,
                subject = "SAVINGS_RATE",
                subjectLabel = null,
                period = "2026-09-20",
                amount = null,
                quantity = 42,
                citations = listOf(RuleCitation("RULE-FHS-PILLARS", "1.0")),
                sourceEngineId = "AI-FHS",
                sourceEngineVersion = "1.0",
            ),
    )

private fun card(
    id: String,
    insight: Insight,
) = FeedInsight(id = id, insight = insight, status = InsightStatus.ACTIVE)
