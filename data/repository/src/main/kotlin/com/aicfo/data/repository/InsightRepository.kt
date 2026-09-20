package com.aicfo.data.repository

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.IdGenerator
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.InsightEntity
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import com.aicfo.domain.engines.emergencyfund.EmergencyFundPlan
import com.aicfo.domain.engines.forecast.CashFlowForecast
import com.aicfo.domain.engines.goals.GoalProjection
import com.aicfo.domain.engines.goals.GoalStatus
import com.aicfo.domain.engines.healthscore.HealthScore
import com.aicfo.domain.engines.insight.BudgetSignal
import com.aicfo.domain.engines.insight.EmergencyFundSignal
import com.aicfo.domain.engines.insight.ForecastSignal
import com.aicfo.domain.engines.insight.GoalSignal
import com.aicfo.domain.engines.insight.HealthSignal
import com.aicfo.domain.engines.insight.Insight
import com.aicfo.domain.engines.insight.InsightEngine
import com.aicfo.domain.engines.insight.InsightInput
import com.aicfo.domain.engines.insight.InsightRules
import com.aicfo.domain.engines.insight.InsightType
import com.aicfo.domain.engines.insight.SeasonalMonthSignal
import com.aicfo.domain.engines.insight.Severity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * The Insight Orchestrator's data side (issue 9.5; §7.2, AI-ARC-005, AI-ORCH).
 *
 * Why:  §7.2's pipeline runs off the UI thread and **persists** what it finds, so a screen reads
 *       rows rather than waiting for six engines. Persisting is also the only way a dismissal can
 *       mean anything: a feed recomputed on every read has nowhere to remember "not now".
 * What: [refresh] runs the pipeline in layer order (the engines below already own their stages) and
 *       writes the result; [observeFeed] and [observeDashboard] read it; [dismiss], [snooze] and
 *       [act] record the user's verdict.
 * Result: a ViewModel sees [FeedInsight]s and nothing else.
 * Changelog: 2026-09-20 — Created for issue 9.5.
 */
interface InsightRepository {
    /**
     * The whole live feed, worst first.
     * Result: re-emits on every write; suppressed cards are absent until their date passes.
     * Input:  none — the active profile. Output: `Flow<List<FeedInsight>>`.
     */
    fun observeFeed(): Flow<List<FeedInsight>>

    /** The dashboard's few (FR-HOME-001's "top 3", RULE-INS-RANK). Output: `Flow<List<FeedInsight>>`. */
    fun observeDashboard(): Flow<List<FeedInsight>>

    /**
     * Runs the pipeline once and stores what it finds.
     * Result: `Ok(count)` — how many insights the feed now holds; `Err` when an engine refuses its
     *         input, which the sources' own invariants should make unreachable.
     * Input:  none. Output: `Result<Int, AppError>`.
     */
    suspend fun refresh(): Result<Int, AppError>

    /** Puts a card away for the rulebook's window. Input: [id]. Output: `Result<Unit, AppError>`. */
    suspend fun dismiss(id: String): Result<Unit, AppError>

    /** The same window, said differently — "remind me later". Input: [id]. */
    suspend fun snooze(id: String): Result<Unit, AppError>

    /** Records that the user acted on it; it returns only if the facts still hold later. Input: [id]. */
    suspend fun act(id: String): Result<Unit, AppError>
}

/**
 * What the user has said about a card (§20.2's `status`).
 * Changelog: 2026-09-20 — Created for issue 9.5.
 */
enum class InsightStatus {
    /** Raised, and not yet ruled on. */
    ACTIVE,

    /** Put away: suppressed until its window ends. */
    DISMISSED,

    /** "Later": suppressed until its window ends. */
    SNOOZED,

    /** Acted on: suppressed, and it returns only if the facts still hold afterwards. */
    ACTED,

    ;

    companion object {
        /** Result: the status stored as [stored], or [ACTIVE] for anything unrecognised. */
        fun fromStored(stored: String): InsightStatus =
            entries.firstOrNull { it.name.equals(stored, ignoreCase = true) } ?: ACTIVE
    }
}

/**
 * One stored card: the engine's insight, its row id, and the user's verdict.
 * Input:  [id] — the row; [insight] — exactly what AI-ORCH raised; [status]; [suppressedUntil].
 * Output: an immutable value.
 * Changelog: 2026-09-20 — Created for issue 9.5.
 */
data class FeedInsight(
    val id: String,
    val insight: Insight,
    val status: InsightStatus,
    val suppressedUntil: LocalDate? = null,
)

/**
 * The upstream results, as flows (issue 9.5).
 * Why:  the same reasoning as `HealthSources` — each already follows the active profile and the
 *       demo (ADR-0006), and holding flows keeps this class free of every method it does not read.
 * Input: one flow per stage of §7.2's pipeline that has an engine today.
 * Changelog: 2026-09-20 — Created for issue 9.5.
 */
internal data class InsightSources(
    val forecast: Flow<Result<CashFlowForecast, AppError>>,
    val health: Flow<Result<HealthScore, AppError>>,
    val emergency: Flow<EmergencyFundPlan>,
    val budgets: Flow<List<CategoryBudget>>,
    val goals: Flow<List<GoalProjection>>,
)

/**
 * [InsightRepository] over the persisted feed (issue 9.5).
 * Input:  [database]; [sources]; [engine]; [clock]; [dispatchers]; [activeProfileId]; [idGenerator];
 *         [rules] — the seam.
 * Output: the repository.
 * Changelog: 2026-09-20 — Created for issue 9.5.
 *
 * `TooManyFunctions` is suppressed: the count is the interface's six published operations plus the
 * five private steps of one pipeline (read, build the row, map it back, parse the citations).
 * Splitting it would put half of one write path in another file — the argument `DashboardViewModel`
 * makes for its own suppression. `LongParameterList` is the store, its five sources and the seams.
 */
@Suppress("LongParameterList", "TooManyFunctions")
internal class RoomInsightRepository(
    private val database: CfoDatabase,
    private val sources: InsightSources,
    private val engine: InsightEngine,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
    private val idGenerator: IdGenerator,
    private val rules: InsightRules = InsightRules(),
) : InsightRepository {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeFeed(): Flow<List<FeedInsight>> =
        activeProfileId
            .flatMapLatest { profileId ->
                database.insightDao().observeFeed(profileId, clock.today().toString()).map {
                        rows ->
                    rows.map(::toFeedInsight)
                }
            }.flowOn(dispatchers.io)

    override fun observeDashboard(): Flow<List<FeedInsight>> = observeFeed().map { it.take(rules.dashboardMax) }

    override suspend fun refresh(): Result<Int, AppError> =
        runCatchingToResult {
            val profileId = activeProfileId.first()
            val today = clock.today()
            val raised = engine.insights(inputOf(today))
            when (raised) {
                is Err -> return raised
                is Ok -> Unit
            }
            val insights = (raised as Ok).value.insights
            val dao = database.insightDao()
            insights.forEach { insight ->
                val existing = dao.find(profileId, insight.fingerprint)
                dao.upsert(rowFor(insight, existing, profileId))
            }
            dao.deleteStale(profileId, insights.map { it.fingerprint }.ifEmpty { listOf("") }, today.toString())
            insights.size
        }

    override suspend fun dismiss(id: String): Result<Unit, AppError> = rule(id, InsightStatus.DISMISSED)

    override suspend fun snooze(id: String): Result<Unit, AppError> = rule(id, InsightStatus.SNOOZED)

    override suspend fun act(id: String): Result<Unit, AppError> = rule(id, InsightStatus.ACTED)

    /**
     * Records a verdict and suppresses the card for the rulebook's window (RULE-INS-DEDUP).
     * Result: `Ok` even when the row has since gone — the user's tap should not raise an error at
     *         them for a card the next refresh had already dropped. Input: [id]; [status].
     */
    private suspend fun rule(
        id: String,
        status: InsightStatus,
    ): Result<Unit, AppError> =
        runCatchingToResult {
            val profileId = activeProfileId.first()
            database.insightDao().setStatus(
                profileId = profileId,
                id = id,
                status = status.name.lowercase(),
                suppressedUntilIsoDate = clock.today().plusDays(rules.snoozeDays.toLong()).toString(),
                updatedAtUtcMillis = clock.nowUtcMillis(),
            )
            Unit
        }

    /**
     * Builds the engine's input from one read of each source.
     * Why:    the stages run in §7.2's order because each source is already the published result of
     *         its layer; a failed engine contributes nothing rather than failing the feed — the
     *         other cards are still true (the `observeStreams` argument).
     * Result: the input. Input: [today]. Output: [InsightInput].
     */
    private suspend fun inputOf(today: LocalDate): InsightInput {
        val forecast = (sources.forecast.first() as? Ok)?.value
        val health = (sources.health.first() as? Ok)?.value
        return InsightInput(
            today = today,
            forecast = forecast?.let { InsightSignals.forecast(it) },
            emergency = InsightSignals.emergency(sources.emergency.first()),
            health = health?.let { InsightSignals.health(it) },
            budgets =
                InsightSignals.budgets(
                    sources.budgets.first(),
                    MonthWindow.current(today).startIsoDate.take(MONTH_KEY_LENGTH),
                ),
            goals = InsightSignals.goals(sources.goals.first()),
            nowUtcMillis = clock.nowUtcMillis(),
            rules = rules,
        )
    }

    /**
     * The row to store for a raised insight.
     * Why:    **the user's verdict survives a recomputation.** When the card already exists its id,
     *         status and suppression are kept and only the figures move; otherwise a new, active row
     *         is created. Without that, dismissing a card would last exactly until the next refresh.
     * Result: the row. Input: [insight]; [existing]; [profileId]. Output: [InsightEntity].
     */
    private fun rowFor(
        insight: Insight,
        existing: InsightEntity?,
        profileId: String,
    ): InsightEntity {
        val now = clock.nowUtcMillis()
        return InsightEntity(
            id = existing?.id ?: idGenerator.newId("insight"),
            profileId = profileId,
            fingerprint = insight.fingerprint,
            type = insight.type.name,
            severity = insight.severity.name,
            subject = insight.subject,
            subjectLabel = insight.subjectLabel,
            period = insight.period,
            amountMinor = insight.amount?.minor,
            secondaryMinor = insight.secondary?.minor,
            dateIso = insight.date?.toString(),
            quantity = insight.quantity,
            confidenceBps = insight.confidenceBps,
            citations = insight.citations.joinToString(",") { "${it.ruleId} v${it.ruleVersion}" },
            sourceEngineId = insight.sourceEngineId,
            sourceEngineVersion = insight.sourceEngineVersion,
            status = existing?.status ?: InsightStatus.ACTIVE.name.lowercase(),
            suppressedUntilIsoDate = existing?.suppressedUntilIsoDate,
            createdAtUtcMillis = existing?.createdAtUtcMillis ?: now,
            updatedAtUtcMillis = now,
        )
    }

    /** Result: the stored row as the domain sees it. Input: [row]. Output: [FeedInsight]. */
    private fun toFeedInsight(row: InsightEntity) =
        FeedInsight(
            id = row.id,
            insight =
                Insight(
                    type = InsightType.valueOf(row.type),
                    subject = row.subject,
                    subjectLabel = row.subjectLabel,
                    period = row.period,
                    amount = row.amountMinor?.let(::Money),
                    secondary = row.secondaryMinor?.let(::Money),
                    date = row.dateIso?.let(LocalDate::parse),
                    quantity = row.quantity,
                    confidenceBps = row.confidenceBps,
                    citations = citationsOf(row.citations),
                    sourceEngineId = row.sourceEngineId,
                    sourceEngineVersion = row.sourceEngineVersion,
                ),
            status = InsightStatus.fromStored(row.status),
            suppressedUntil = row.suppressedUntilIsoDate?.let(LocalDate::parse),
        )

    /** Result: `RULE-X v1.0,RULE-Y v2.0` back into citations; malformed parts are dropped. */
    private fun citationsOf(stored: String): List<RuleCitation> =
        stored.split(',').mapNotNull { part ->
            val trimmed = part.trim()
            val id = trimmed.substringBefore(" v", missingDelimiterValue = "")
            val version = trimmed.substringAfter(" v", missingDelimiterValue = "")
            if (id.isBlank() || version.isBlank()) null else RuleCitation(id, version)
        }

    private companion object {
        /** `yyyy-MM` — the first seven characters of an ISO date (TIM-002). */
        const val MONTH_KEY_LENGTH = 7
    }
}

/**
 * Each engine's published result, as the signal AI-ORCH reads (issue 9.5; ADR-0046).
 *
 * Why:  the orchestrator must not import AI-FCT or AI-FHS, or it could start re-deriving what they
 *       own. The translation lives here, in one small function per source, each with its own test.
 * What: forecast, emergency fund, health score, budgets, goals.
 * Result: the signals; the engine decides whether any of them is worth a card.
 * Changelog: 2026-09-20 — Created for issue 9.5.
 */
internal object InsightSignals {
    /**
     * AI-FCT's forecast.
     * Why:    the amount reported for a crunch is the expected balance **on the first crunch day**,
     *         not the horizon's lowest point: the card is about the day money runs short, and that
     *         day's own figure is the one the forecast card shows beside it.
     * Result: the signal. Input: [forecast]. Output: [ForecastSignal].
     */
    fun forecast(forecast: CashFlowForecast): ForecastSignal {
        val first = forecast.crunchDays.firstOrNull()
        return ForecastSignal(
            crunchDays = forecast.crunchDays.size,
            firstCrunchDate = first,
            lowest = first?.let { date -> forecast.days.firstOrNull { it.date == date }?.p50 },
            buffer = forecast.buffer,
            seasonalMonths =
                forecast.seasonalMonths.map { month ->
                    SeasonalMonthSignal(month.factor.month.toString(), month.adjustment)
                },
            provenance = forecast.provenance,
        )
    }

    /** AI-EMF's plan. Result: the signal. Input: [plan]. Output: [EmergencyFundSignal]. */
    fun emergency(plan: EmergencyFundPlan) =
        EmergencyFundSignal(
            shortfall = plan.shortfall,
            topUpMonthly = plan.topUpMonthly,
            runwayMonthsBps = plan.runwayMonthsBps,
            provenance = plan.provenance,
        )

    /** AI-FHS's score and lever. Result: the signal. Input: [score]. Output: [HealthSignal]. */
    fun health(score: HealthScore) =
        HealthSignal(
            score = score.score,
            leverLabel = score.lever?.signal?.name,
            leverGain = score.lever?.gain,
            provenance = score.provenance,
        )

    /**
     * This month's overspent budgets.
     * Why:    a budget that is merely ahead of pace is not raised — the budget card already says so,
     *         and the feed is for what needs a decision. Overspent means the plan is already gone.
     * Result: one signal per overspent budget. Input: [rows]; [monthKey]. Output: `List<BudgetSignal>`.
     */
    fun budgets(
        rows: List<CategoryBudget>,
        monthKey: String,
    ): List<BudgetSignal> =
        rows.filterNot { it.isUnbudgeted }
            .filter { it.status.isOverspent }
            .map { row ->
                BudgetSignal(
                    categoryId = row.category.id,
                    categoryName = row.category.name,
                    overspentBy = Money(-row.status.remaining.minor),
                    monthKey = monthKey,
                    provenance = row.status.provenance,
                )
            }

    /**
     * The goals whose plan falls short.
     * Why:    `BEHIND` and `PAST_DUE` both mean the plan does not reach the target; a goal with no
     *         target is not behind, it is unfinished, and the goals screen says so already.
     * Result: one signal per such goal. Input: [goals]. Output: `List<GoalSignal>`.
     */
    fun goals(goals: List<GoalProjection>): List<GoalSignal> =
        goals.filter { it.status == GoalStatus.BEHIND || it.status == GoalStatus.PAST_DUE }
            .filter { it.shortfallMonthly > Money.ZERO }
            .map { goal ->
                GoalSignal(
                    goalId = goal.goalId,
                    name = goal.name,
                    shortfallMonthly = goal.shortfallMonthly,
                    targetDateIso = goal.targetDateIso,
                    provenance = PROVENANCE_GOAL,
                )
            }

    /**
     * AI-GOAL's own provenance is on the waterfall, not on a single projection, so a goal card cites
     * the engine and the rule the projection came from (AI-ARC-006 keeps the version with the row).
     */
    private val PROVENANCE_GOAL =
        com.aicfo.core.model.EngineProvenance(
            engineId = "AI-GOAL",
            engineVersion = "1.0",
            computedAtUtcMillis = 0L,
            evidence = listOf(RuleCitation("RULE-HORIZON", "1.0")),
        )
}

/** Keeps the severity names the DAO's `ORDER BY` hard-codes in step with the engine's enum. */
internal val SEVERITY_ORDER: List<String> = Severity.entries.map { it.name }
