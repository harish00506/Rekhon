package com.aicfo.data.repository

import androidx.room.withTransaction
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.IdGenerator
import com.aicfo.core.common.Result
import com.aicfo.core.common.flatMap
import com.aicfo.core.common.getOrNull
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.PurchaseTraceEntity
import com.aicfo.core.database.entity.PurchaseTraceGateEntity
import com.aicfo.core.model.Category
import com.aicfo.core.model.CategoryNature
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import com.aicfo.domain.engines.emergencyfund.EmergencyFundPlan
import com.aicfo.domain.engines.forecast.CashFlowForecast
import com.aicfo.domain.engines.goals.GoalProjection
import com.aicfo.domain.engines.loan.AmortisationRow
import com.aicfo.domain.engines.purchase.Alternatives
import com.aicfo.domain.engines.purchase.GateFigure
import com.aicfo.domain.engines.purchase.GateId
import com.aicfo.domain.engines.purchase.GateOutcome
import com.aicfo.domain.engines.purchase.GateResult
import com.aicfo.domain.engines.purchase.ImpactStrip
import com.aicfo.domain.engines.purchase.PaymentMethod
import com.aicfo.domain.engines.purchase.PurchaseAdvisorEngine
import com.aicfo.domain.engines.purchase.PurchaseInput
import com.aicfo.domain.engines.purchase.PurchaseRequest
import com.aicfo.domain.engines.purchase.PurchaseRules
import com.aicfo.domain.engines.purchase.PurchaseSignals
import com.aicfo.domain.engines.purchase.PurchaseVerdictCard
import com.aicfo.domain.engines.purchase.Urgency
import com.aicfo.domain.engines.purchase.Verdict
import com.aicfo.domain.engines.safetospend.SafeToSpend
import com.aicfo.domain.engines.stream.StreamProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * The Purchase Advisor's data side (issue 10.1; §13, AI-PA, AI-ARC-001/006).
 *
 * Why:  AI-PA is pure and knows nothing about this household. Something has to read what every
 *       engine below it has already published — the emergency fund, Safe-to-Spend, the forecast,
 *       the obligations, the goals, the category's budget — hand them over in one consistent read,
 *       and keep the card that comes back. §13.2's last line is why the keeping matters: a verdict
 *       is only arguable later if its figures survive.
 * What: [advise] answers a question and stores the answer; [observeRecent] and [find] read the
 *       history back.
 * Result: a ViewModel sees a [PurchaseVerdictCard] and never a DAO (ARC-005).
 * Changelog: 2026-09-25 — Created for issue 10.1.
 */
interface PurchaseAdvisorRepository {
    /**
     * Judges one purchase and keeps the card.
     * Why:    **one read of every source**, so the gates cannot disagree with each other about the
     *         balance — a forecast read a second after the emergency fund would be a card whose own
     *         figures did not add up.
     * Result: `Ok(card)`; `Err` when a source or the write failed. The card is stored before it is
     *         returned, so what the screen shows is what the history holds.
     * Input:  [request] — item, price, method, urgency, and the category when known.
     * Output: `Result<PurchaseVerdictCard, AppError>`.
     */
    suspend fun advise(request: PurchaseRequest): Result<PurchaseVerdictCard, AppError>

    /**
     * The verdicts already given, newest first.
     * Result: re-emits on every new verdict. Input: [limit]. Output: `Flow<List<KeptVerdict>>`.
     */
    fun observeRecent(limit: Int = DEFAULT_HISTORY): Flow<List<KeptVerdict>>

    /**
     * One kept verdict, whole — the card as it was decided, gates and all.
     * Result: `Ok(null)` when there is no such card. Input: [id]. Output: the card.
     */
    suspend fun find(id: String): Result<PurchaseVerdictCard?, AppError>

    companion object {
        /** How many past verdicts the advisor screen lists. */
        const val DEFAULT_HISTORY = 10
    }
}

/**
 * One row of the advisor's history — enough to list it without reading its gates.
 * Input:  [id]; [item]; [price]; [verdict]; [decidedOnIsoDate] — the day it was given (TIM-002).
 * Output: an immutable value.
 * Changelog: 2026-09-25 — Created for issue 10.1.
 */
data class KeptVerdict(
    val id: String,
    val item: String,
    val price: Money,
    val verdict: Verdict,
    val decidedOnIsoDate: String,
)

/**
 * The flows AI-PA's signals are built from (issue 10.1).
 * Why:  flows rather than the repositories themselves, for the reason `HealthSources` gives: each
 *       already follows the active profile and the demo (ADR-0006), and holding flows keeps this
 *       class free of every method it does not read.
 * Changelog: 2026-09-25 — Created for issue 10.1.
 */
internal data class PurchaseSources(
    val emergency: Flow<EmergencyFundPlan>,
    val safeToSpend: Flow<SafeToSpend?>,
    val forecast: Flow<Result<CashFlowForecast, AppError>>,
    val ledger: Flow<List<MonthlyLedger>>,
    val streams: Flow<Result<StreamProfile, AppError>>,
    val instalments: Flow<Map<String, AmortisationRow>>,
    val categories: Flow<List<Category>>,
    val goals: Flow<List<GoalProjection>>,
    val budgets: Flow<List<CategoryBudget>>,
)

/**
 * [PurchaseAdvisorRepository] over `purchase_trace` (issue 10.1).
 * Input:  [database]; [sources]; [engine]; [clock] — the profile's today (TIM-001); [dispatchers];
 *         [activeProfileId]; [idGenerator]. Output: the repository.
 * Changelog: 2026-09-25 — Created for issue 10.1.
 */
@Suppress("LongParameterList") // the store, the sources, the engine and four seams
internal class StoredPurchaseAdvisorRepository(
    private val database: CfoDatabase,
    private val sources: PurchaseSources,
    private val engine: PurchaseAdvisorEngine,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
    private val idGenerator: IdGenerator,
) : PurchaseAdvisorRepository {
    override suspend fun advise(request: PurchaseRequest): Result<PurchaseVerdictCard, AppError> =
        withContext(dispatchers.io) {
            val profileId = activeProfileId.first()
            runCatchingToResult { signals(request) }
                .flatMap { signals ->
                    engine.advise(PurchaseInput(request, signals, clock.today(), clock.nowUtcMillis(), PurchaseRules()))
                }
                .flatMap { card ->
                    runCatchingToResult {
                        store(profileId, card)
                        card
                    }
                }
        }

    override fun observeRecent(limit: Int): Flow<List<KeptVerdict>> =
        activeProfileId
            .flatMapLatest { profileId -> database.purchaseTraceDao().observeRecent(profileId, limit) }
            .map { rows -> rows.map(PurchaseTraceMapper::toKept) }

    override suspend fun find(id: String): Result<PurchaseVerdictCard?, AppError> =
        withContext(dispatchers.io) {
            val profileId = activeProfileId.first()
            runCatchingToResult {
                val dao = database.purchaseTraceDao()
                dao.findTrace(profileId, id)?.let { trace -> rebuild(trace, dao.gatesFor(profileId, id)) }
            }
        }

    /**
     * One consistent read of every source AI-PA judges on.
     * Why:    each flow is taken once, together: a card whose emergency fund and forecast were read
     *         seconds apart could contradict itself, and the user would have no way to tell.
     * Result: the signals. Input: [request] — its category picks the budget to read.
     * Output: [PurchaseSignals].
     */
    private suspend fun signals(request: PurchaseRequest): PurchaseSignals {
        val plan = sources.emergency.first()
        val forecast = sources.forecast.first().getOrNull()
        val months = sources.ledger.first()
        val liabilityCategories =
            sources.categories.first().filter { it.nature == CategoryNature.LIABILITY }.map { it.id }.toSet()
        val obligations =
            HealthSignals.obligations(
                months,
                sources.streams.first().getOrNull(),
                sources.instalments.first().values.toList(),
                liabilityCategories,
            )
        return PurchaseSignals(
            liquidFunds = plan.liquidFunds,
            emergencyFloor = plan.target,
            monthlyEssentials = plan.monthlyEssentials ?: Money.ZERO,
            safeToSpend = sources.safeToSpend.first()?.amount ?: Money.ZERO,
            forecastLowest = forecast?.lowest?.p50,
            forecastBuffer = forecast?.buffer ?: Money.ZERO,
            forecastCrunchDays = forecast?.crunchDays?.size ?: 0,
            monthlyIncome = obligations?.income ?: Money.ZERO,
            monthlyObligations = obligations?.obligations ?: Money.ZERO,
            goalContributionsMonthly =
                sources.goals.first().fold(
                    Money.ZERO,
                ) { sum, goal -> sum + goal.plannedMonthly },
            categoryRemaining =
                request.categoryId?.let { categoryId ->
                    sources.budgets.first().firstOrNull { it.category.id == categoryId }?.status?.remaining
                },
            // AI-SEAS's cheaper month is not wired yet (ADR-0049): the gate exists and is tested,
            // and a signal invented here rather than read from the engine that owns seasonality
            // would be exactly the re-derivation ARC-001 forbids.
            cheaperMonth = null,
        )
    }

    /**
     * Stores the card whole (§13.2, AI-ARC-006).
     * Why:    header and gate rows in one transaction, because half a card is not a card: a header
     *         with no gates would read as a verdict with no reasons, which is the one thing §13
     *         promises never to show.
     * Result: the card is in the history. Input: [profileId]; [card]. Output: none.
     */
    private suspend fun store(
        profileId: String,
        card: PurchaseVerdictCard,
    ) {
        val id = idGenerator.newId("purchase")
        val now = clock.nowUtcMillis()
        database.withTransaction {
            val dao = database.purchaseTraceDao()
            dao.insertTrace(toEntity(id, profileId, card, now))
            dao.insertGates(toGateRows(id, profileId, card, now))
        }
    }

    /** Result: the header row. Input: [id]; [profileId]; [card]; [now]. */
    private fun toEntity(
        id: String,
        profileId: String,
        card: PurchaseVerdictCard,
        now: Long,
    ) = PurchaseTraceEntity(
        id = id,
        profileId = profileId,
        item = card.request.item,
        priceMinor = card.request.price.minor,
        method = card.request.method.name,
        urgency = card.request.urgency.name,
        monthlyEmiMinor = card.request.monthlyEmi?.minor,
        categoryId = card.request.categoryId,
        verdict = card.verdict.name,
        hardFail = card.hardFail,
        liquidBeforeMinor = card.impact.liquidBefore.minor,
        liquidAfterMinor = card.impact.liquidAfter.minor,
        runwayBeforeTenths = card.impact.runwayMonthsBeforeTenths,
        runwayAfterTenths = card.impact.runwayMonthsAfterTenths,
        goalDelayDays = card.impact.goalDelayDays,
        comfortablePriceMinor = card.alternatives.comfortablePrice?.minor,
        comfortableFromIsoDate = card.alternatives.comfortableFrom?.toString(),
        coolOffSuggested = card.alternatives.coolOffSuggested,
        engineId = card.provenance.engineId,
        engineVersion = card.provenance.engineVersion,
        citations = card.provenance.evidence.joinToString(", ") { "${it.ruleId} v${it.ruleVersion}" },
        decidedOnIsoDate = clock.today().toString(),
        decidedAtUtcMillis = card.provenance.computedAtUtcMillis,
        createdAtUtcMillis = now,
        updatedAtUtcMillis = now,
    )

    /**
     * Result: one row per figure, and one bare row for a gate that judged nothing — so a gate is
     * never missing from a card that is read back. Input: [id]; [profileId]; [card]; [now].
     */
    private fun toGateRows(
        id: String,
        profileId: String,
        card: PurchaseVerdictCard,
        now: Long,
    ): List<PurchaseTraceGateEntity> =
        card.gates.flatMapIndexed { ordinal, gate ->
            val citations = gate.citations.joinToString(", ") { "${it.ruleId} v${it.ruleVersion}" }
            val figures = gate.figures.ifEmpty { listOf(null) }
            figures.mapIndexed { index, figure ->
                PurchaseTraceGateEntity(
                    id = "$id:${gate.gate.name}:$index",
                    profileId = profileId,
                    traceId = id,
                    gate = gate.gate.name,
                    outcome = gate.outcome.name,
                    ordinal = ordinal,
                    citations = citations,
                    figureKey = figure?.key,
                    amountMinor = figure?.amount?.minor,
                    countValue = figure?.count,
                    bpsValue = figure?.bps,
                    textValue = figure?.text,
                    createdAtUtcMillis = now,
                    updatedAtUtcMillis = now,
                )
            }
        }

    /**
     * Rebuilds a card from its rows.
     * Why:    the history is only worth keeping if it reads back as the card it was — §13.2's
     *         "revisit why a past decision was made". The engine version travels with it, so a card
     *         decided under AI-PA 1.0 still says so after the gates change (AI-ARC-006).
     * Result: the card. Input: [trace]; [gateRows]. Output: [PurchaseVerdictCard].
     */
    private fun rebuild(
        trace: PurchaseTraceEntity,
        gateRows: List<PurchaseTraceGateEntity>,
    ): PurchaseVerdictCard =
        PurchaseVerdictCard(
            request =
                PurchaseRequest(
                    item = trace.item,
                    price = Money(trace.priceMinor),
                    method = PaymentMethod.valueOf(trace.method),
                    urgency = Urgency.valueOf(trace.urgency),
                    monthlyEmi = trace.monthlyEmiMinor?.let(::Money),
                    categoryId = trace.categoryId,
                ),
            verdict = Verdict.valueOf(trace.verdict),
            gates = gateRows.groupBy { it.gate }.entries.sortedBy { it.value.first().ordinal }.map(::toGate),
            impact =
                ImpactStrip(
                    liquidBefore = Money(trace.liquidBeforeMinor),
                    liquidAfter = Money(trace.liquidAfterMinor),
                    runwayMonthsBeforeTenths = trace.runwayBeforeTenths,
                    runwayMonthsAfterTenths = trace.runwayAfterTenths,
                    goalDelayDays = trace.goalDelayDays,
                ),
            alternatives =
                Alternatives(
                    comfortablePrice = trace.comfortablePriceMinor?.let(::Money),
                    comfortableFrom = trace.comfortableFromIsoDate?.let(LocalDate::parse),
                    coolOffSuggested = trace.coolOffSuggested,
                ),
            hardFail = trace.hardFail,
            provenance =
                EngineProvenance(
                    engineId = trace.engineId,
                    engineVersion = trace.engineVersion,
                    computedAtUtcMillis = trace.decidedAtUtcMillis,
                    evidence = parseCitations(trace.citations),
                    inputWindow = trace.decidedOnIsoDate,
                ),
        )

    /** Result: one gate, rebuilt from its rows. Input: [entry] — the gate name and its rows. */
    private fun toGate(entry: Map.Entry<String, List<PurchaseTraceGateEntity>>): GateResult {
        val rows = entry.value
        return GateResult(
            gate = GateId.valueOf(entry.key),
            outcome = GateOutcome.valueOf(rows.first().outcome),
            figures =
                rows.filter { it.figureKey != null }.map { row ->
                    GateFigure(
                        key = row.figureKey.orEmpty(),
                        amount = row.amountMinor?.let(::Money),
                        count = row.countValue,
                        bps = row.bpsValue,
                        text = row.textValue,
                    )
                },
            citations = parseCitations(rows.first().citations),
        )
    }

    /** Result: `ID vVERSION, ID vVERSION` read back into citations. Input: [stored]. */
    internal fun parseCitations(stored: String): List<RuleCitation> =
        stored.split(", ").filter { it.isNotBlank() }.map { cited ->
            val ruleId = cited.substringBefore(" v")
            RuleCitation(ruleId, cited.substringAfter(" v", "1.0"))
        }
}

/**
 * Rows in, listing out (issue 10.1).
 *
 * Why:  extracted when the repository passed detekt's function count — and the split is the honest
 *       one: turning a stored row into a list entry is mapping, not advising.
 * Changelog: 2026-09-25 — Created for issue 10.1.
 */
internal object PurchaseTraceMapper {
    /** Result: the listing row for one stored card. Input: [row]. */
    fun toKept(row: PurchaseTraceEntity) =
        KeptVerdict(
            id = row.id,
            item = row.item,
            price = Money(row.priceMinor),
            verdict = Verdict.valueOf(row.verdict),
            decidedOnIsoDate = row.decidedOnIsoDate,
        )
}
