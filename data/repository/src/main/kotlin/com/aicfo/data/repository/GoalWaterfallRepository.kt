package com.aicfo.data.repository

import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.emergencyfund.EmergencyFundPlan
import com.aicfo.domain.engines.emergencyfund.EmergencyFundRules
import com.aicfo.domain.engines.goals.GoalWaterfall
import com.aicfo.domain.engines.goals.GoalWaterfallEngine
import com.aicfo.domain.engines.goals.GoalWaterfallInput
import com.aicfo.domain.engines.goals.SurplusBasis
import com.aicfo.domain.engines.quicksetup.BudgetEnvelope
import com.aicfo.domain.engines.quicksetup.BudgetNature
import com.aicfo.domain.engines.quicksetup.QuickSetupRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

/**
 * Resolves what `GoalWaterfallEngine` needs, and hands back its answer (issue 7.3; §15.1, ARC-005).
 *
 * Why:  the engine is a fold over three figures somebody has to decide — what the month has spare,
 *       what the emergency fund still wants, and how deep a runway `RULE-EMERG-FIRST` calls
 *       sufficient. **All three are storage questions**, so they belong on this side of the
 *       boundary, not in the engine and certainly not in a ViewModel.
 * What: watch the plan, recomputed on every change to the goals, the ledger or the emergency fund.
 * Result: a ViewModel sees a [GoalWaterfall] and nothing else — no Room types, no DAOs.
 * Changelog: 2026-09-03 — Created for issue 7.3.
 *
 * **Nothing derived is stored**, for the reason `GoalRepository` and `EmergencyFundRepository` both
 * give: an allocation written to the database would outlive the surplus that produced it, and would
 * go stale simply because a month closed — the one input the user never edits. The single thing that
 * *is* stored is the order, because that is the user's own decision rather than a derived figure,
 * and it lives on the `goal` row (`sort_order`, schema 21) rather than here.
 */
interface GoalWaterfallRepository {
    /**
     * Watches the active profile's contribution plan.
     * Why:    every term of it moves on its own schedule — a transaction changes the surplus, a
     *         reconciliation changes the runway, a drag changes the order — so a one-shot read would
     *         be stale before the user finished reading it.
     * Result: re-emitted on every relevant change. **Never null and never an error**: a profile the
     *         app has never watched spend yields an `UNKNOWN` plan, which the screen
     *         renders as "we cannot size this yet" rather than an error state (P-04).
     * Input:  none — the active profile. Output: `Flow<GoalWaterfall>`.
     */
    fun observeWaterfall(): Flow<GoalWaterfall>
}

/**
 * The Room-backed [GoalWaterfallRepository] (issue 7.3).
 *
 * Why:  `internal`, reached through [RepositoryFactory], like every other repository here.
 * What: combines three sources, resolves the surplus and the gate, calls the engine once per
 *       emission.
 * Result: a [GoalWaterfall] per emission.
 * Changelog: 2026-09-03 — Created for issue 7.3.
 *
 * **Seven constructor parameters is detekt's ceiling, and none of them is spare.** Four sources,
 * because the plan is genuinely the intersection of four things — what the goals want, what the
 * months had spare, what the buffer still claims, and what the user declared before any of it
 * existed — plus the engine, the clock and the dispatchers every repository here takes. Collapsing
 * any pair into a wrapper would hide a dependency rather than remove one.
 *
 * **It composes three repositories rather than reaching for their DAOs.** `GoalRepository` already
 * projects the goals through `GoalEngine`, and `EmergencyFundRepository` already resolves the
 * runway; duplicating either here would give the app two answers to the same question, which is the
 * failure `GoalRepository.project`'s own KDoc warns about one level down.
 */
@Suppress("LongParameterList") // Seven, and every one is a source the plan genuinely needs.
internal class RoomGoalWaterfallRepository(
    private val goals: GoalRepository,
    private val transactions: TransactionRepository,
    private val emergencyFund: EmergencyFundRepository,
    private val quickSetup: QuickSetupRepository,
    private val engine: GoalWaterfallEngine,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
) : GoalWaterfallRepository {
    override fun observeWaterfall(): Flow<GoalWaterfall> =
        combine(
            goals.observeGoals(),
            transactions.observeMonthlyLedger(SURPLUS_RULES.essentialsLookbackMonths),
            emergencyFund.observeEmergencyFund(),
            quickSetup.observeLatestEnvelopes(),
        ) { projections, history, fund, envelopes ->
            // Read once per emission (TIM-001). The engine reads no clock of its own.
            val today = clock.today()
            val surplus = surplusFrom(history, envelopes)
            val result =
                engine.allocate(
                    GoalWaterfallInput(
                        goals = projections,
                        monthlySurplus = surplus.amount,
                        surplusBasis = surplus.basis,
                        emergencyTopUpMonthly = fund.topUpMonthly,
                        emergencyRunwayMonthsBps = fund.runwayMonthsBps,
                        emergencyGateMonths = GATE_RULES.emergencyRunwayMonths,
                        today = today,
                        nowUtcMillis = clock.nowUtcMillis(),
                    ),
                )
            // An `Err` here means an amount that will not fit in a `Long` (MNY-001) — absurd data,
            // not a broken app. Reporting UNKNOWN keeps the screen renderable, which is what the
            // engine's own null-surplus branch exists to say.
            (result as? Ok)?.value ?: unknownFor(projections, fund)
        }.flowOn(dispatchers.io)

    /**
     * What the month has spare for goals, and where that figure came from.
     *
     * Why:    **§15.1 asks for the P50 *forecast* surplus, and this app has no forecast.**
     *         `:domain:engines:forecast` is still the placeholder issue 1.1 scaffolded; issue 9.2
     *         was never built. Rather than invent one inside this issue, the substitution is the
     *         P50 of what the closed months actually had spare — a genuine median, of observed
     *         rather than projected surplus — and it is **named on the result** so the screen can
     *         say which it is (P-02). ADR-0035 records what changes when 9.2 lands.
     *
     *         The median rather than the mean, for the reason issue 7.2 found in its own fixture:
     *         one replaced fridge moves a mean by a sixth of the fridge and moves a median by
     *         nothing. A surplus is exactly the figure a single unusual month distorts most.
     *
     *         **`invested`, `assets` and `liabilities` are deliberately not subtracted.** Investing
     *         *is* goal funding; netting it out would hide the very money this plan allocates, and
     *         the user would be told to find a surplus they had already found.
     *
     *         **Safe-to-Spend is not the source, and using it would be a bug.** `RULE-STS` has
     *         `include_goal_contributions: true`, and `SafeToSpendRepository` already feeds it
     *         `GoalPlan.totalRequiredMonthly` — so Safe-to-Spend is the surplus *net of* goals.
     *         Feeding it back in would double-count the goals and make the answer depend on itself.
     * Result: the amount and its [SurplusBasis]. **`null` with `NONE` when neither source is
     *         available** — never a zero, which would read as "this month has no room" and tell a
     *         day-one user that every goal they own is impossible.
     * Input:  [history] — the closed months; [envelopes] — the onboarding envelopes.
     * Output: [Surplus].
     */
    private fun surplusFrom(
        history: List<MonthlyLedger>,
        envelopes: List<BudgetEnvelope>,
    ): Surplus {
        val observed = history.map { it.income - (it.nature.needs + it.nature.wants) }
        if (observed.size >= SURPLUS_RULES.minMonthsObserved) {
            return Surplus(observed.median(), SurplusBasis.OBSERVED_MEDIAN)
        }
        val declared = envelopes.firstOrNull { it.nature == BudgetNature.INVEST }?.amount
        return if (declared != null && declared > Money.ZERO) {
            Surplus(declared, SurplusBasis.DECLARED_ENVELOPE)
        } else {
            Surplus(null, SurplusBasis.NONE)
        }
    }

    /**
     * The median of a run of monthly figures.
     * Why:    the statistic that survives one unusual month. Written here rather than taken from a
     *         library because `:data:repository` has no statistics dependency and this is one line —
     *         the same call `EmergencyFundRepository` makes for its essentials.
     * Result: the middle value, or the **lower** of the two middles for an even count — the
     *         conservative choice for a surplus, and one that needs no division and so cannot lose
     *         a paise. Unlike the essentials median, **this one may legitimately be negative**: a
     *         profile spending more than it earns has a negative surplus, and saying so is more use
     *         than clamping it out of sight.
     * Input:  the receiver — a non-empty list. Output: [Money].
     */
    private fun List<Money>.median(): Money = sorted()[(size - 1) / 2]

    /**
     * The plan for a profile whose figures could not be resolved.
     * Why:    the engine's own `UNKNOWN` branch, reached without it — used only when `allocate`
     *         returns an `Err`, which means an overflow rather than missing data.
     * Result: a [GoalWaterfall] the screen can render. Input: [projections]; [fund]. Output: the plan.
     */
    private fun unknownFor(
        projections: List<com.aicfo.domain.engines.goals.GoalProjection>,
        fund: EmergencyFundPlan,
    ): GoalWaterfall =
        (
            engine.allocate(
                GoalWaterfallInput(
                    goals = projections,
                    monthlySurplus = null,
                    surplusBasis = SurplusBasis.NONE,
                    emergencyRunwayMonthsBps = fund.runwayMonthsBps,
                    emergencyGateMonths = GATE_RULES.emergencyRunwayMonths,
                    today = clock.today(),
                    nowUtcMillis = clock.nowUtcMillis(),
                ),
            ) as Ok
        ).value

    /** The amount and its provenance, returned together so the two cannot be mismatched. */
    private data class Surplus(
        val amount: Money?,
        val basis: SurplusBasis,
    )

    private companion object {
        /**
         * The history window and the observed-months floor, **borrowed from `RULE-EMF-MULT`**.
         *
         * A surplus median and an essentials median are the same shape of question over the same
         * ledger read, so they take the same window rather than minting a second pair of thresholds
         * that could drift from it. Issue 7.3 therefore adds no rulebook parameter at all (ADR-0035,
         * on ADR-0033's precedent), and the goals drift test asserts the absence of the two keys
         * somebody would otherwise reach for.
         */
        val SURPLUS_RULES = EmergencyFundRules()

        /**
         * `RULE-EMERG-FIRST.min_runway_months` — **the repository's one and only mirror of it**.
         *
         * `QuickSetupRules` has mirrored this row since issue 2.3. ADR-0017's second trigger says a
         * *second* mirror of a shared row is the point at which the drift tests stop being enough
         * and the runtime rulebook loader should be built instead — so `GoalWaterfallEngine` takes
         * the threshold as an input and reads it from here, rather than declaring its own copy.
         * Reaching across to the quick-setup engine for it looks odd for exactly one moment; the
         * alternative is a third copy of the number three, which is how thresholds drift.
         */
        val GATE_RULES = QuickSetupRules()
    }
}
