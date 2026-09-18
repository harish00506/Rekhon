package com.aicfo.data.repository

import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.emergencyfund.EmergencyFundRules
import com.aicfo.domain.engines.goals.SurplusBasis
import com.aicfo.domain.engines.quicksetup.BudgetEnvelope
import com.aicfo.domain.engines.quicksetup.BudgetNature
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

/**
 * What a month has spare, and where that figure came from (issue 7.3, extracted 2026-09-18).
 *
 * Why:  §15.1 and §36 both want the month's surplus, and until now `GoalWaterfallRepository` owned
 *       the derivation while `OrderOfOperationsRepository` read it back out of the waterfall's
 *       result. That made AI-FOO depend on the goal waterfall — the wrong way round once the
 *       waterfall had to allocate what **AI-FOO leaves** (ADR-0038). Extracting the derivation lets
 *       both read the same figure without either depending on the other.
 * What: one flow. The P50 of observed surplus over closed months, falling back to the declared
 *       INVEST envelope, and `null` when neither exists.
 * Result: a [MonthlySurplus] — the figure and its provenance, never one without the other (P-02).
 * Changelog: 2026-09-18 — Extracted unchanged from `RoomGoalWaterfallRepository.surplusFrom`.
 */
interface SurplusRepository {
    /**
     * Observes the month's surplus.
     * Result: emits the figure and its basis, and re-emits whenever the ledger or the envelopes
     *         change. **Null is unknown, never zero**: a profile with no history and no declared
     *         savings has told the app nothing, and reporting ₹0 would say every goal is impossible.
     * Input:  none. Output: `Flow<MonthlySurplus>`.
     */
    fun observeMonthlySurplus(): Flow<MonthlySurplus>
}

/**
 * The month's surplus with its provenance (issue 7.3, extracted 2026-09-18).
 *
 * @property amount what the month has spare, or `null` when it cannot be known. May be negative — a
 *   profile spending more than it earns has a negative surplus, and reporting it is more useful than
 *   clamping it out of sight.
 * @property basis where [amount] came from. `NONE` exactly when [amount] is null.
 */
data class MonthlySurplus(
    val amount: Money?,
    val basis: SurplusBasis,
) {
    init {
        require((amount == null) == (basis == SurplusBasis.NONE)) {
            "The figure and its basis are two halves of one fact: $amount with basis $basis"
        }
    }
}

/**
 * Room-backed [SurplusRepository] (extracted 2026-09-18 from issue 7.3's waterfall repository).
 *
 * **The derivation is unchanged, and so are its reasons** (ADR-0035). §15.1 asks for the P50
 * *forecast* surplus; issue 9.2 was never built, so this is the P50 of `income − (needs + wants)`
 * across **closed** months:
 *
 * - `invested` is not subtracted — that is the money being allocated, and subtracting it would hide
 *   what the plan is about to spend;
 * - a median, not a mean, so one unusual month does not rewrite the plan;
 * - closed months only, or the figure would sag daily and jump back on the 1st;
 * - the window is `RULE-EMF-MULT`'s own, reused rather than a second one that could drift from it.
 */
internal class RoomSurplusRepository(
    private val transactions: TransactionRepository,
    private val quickSetup: QuickSetupRepository,
    private val dispatchers: DispatcherProvider,
) : SurplusRepository {
    override fun observeMonthlySurplus(): Flow<MonthlySurplus> =
        combine(
            transactions.observeMonthlyLedger(RULES.essentialsLookbackMonths),
            quickSetup.observeLatestEnvelopes(),
        ) { history, envelopes -> surplusFrom(history, envelopes) }
            .flowOn(dispatchers.io)

    /**
     * Result: the median observed surplus, else the declared envelope, else unknown.
     * Input:  [history] — closed months; [envelopes] — the quick-setup plan. Output: [MonthlySurplus].
     */
    private fun surplusFrom(
        history: List<MonthlyLedger>,
        envelopes: List<BudgetEnvelope>,
    ): MonthlySurplus {
        val observed = history.map { it.income - (it.nature.needs + it.nature.wants) }
        if (observed.size >= RULES.minMonthsObserved) {
            return MonthlySurplus(observed.median(), SurplusBasis.OBSERVED_MEDIAN)
        }
        val declared = envelopes.firstOrNull { it.nature == BudgetNature.INVEST }?.amount
        return if (declared != null && declared > Money.ZERO) {
            MonthlySurplus(declared, SurplusBasis.DECLARED_ENVELOPE)
        } else {
            MonthlySurplus(null, SurplusBasis.NONE)
        }
    }

    /** The lower middle of an odd or even list — the P50 this file's KDoc describes. */
    private fun List<Money>.median(): Money = sorted()[(size - 1) / 2]

    private companion object {
        /** `RULE-EMF-MULT`'s window and its minimum, reused rather than mirrored again. */
        val RULES = EmergencyFundRules()
    }
}
