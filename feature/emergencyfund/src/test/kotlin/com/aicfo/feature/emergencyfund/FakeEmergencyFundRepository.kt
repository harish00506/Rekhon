package com.aicfo.feature.emergencyfund

import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import com.aicfo.data.repository.EmergencyFundRepository
import com.aicfo.domain.engines.emergencyfund.EmergencyFundEngineFactory
import com.aicfo.domain.engines.emergencyfund.EmergencyFundInput
import com.aicfo.domain.engines.emergencyfund.EmergencyFundPlan
import com.aicfo.domain.engines.emergencyfund.EssentialsBasis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.time.LocalDate

/**
 * An in-memory [EmergencyFundRepository] for the feature tests (issue 7.2).
 *
 * Why:  a fake rather than a mock, the convention this repo keeps — a hand-written double reads
 *       better and fails loudly on an unimplemented method.
 *
 *       **It emits plans built by the real engine.** A fake that returned hand-written plans would
 *       let the ViewModel and the screen agree on a runway the engine would never produce, and every
 *       test would pass. That is the trap `FakeInvestmentRepository` fell into in issue 6.5: it
 *       silently dropped `priceKey`, so no test noticed the editor never set one.
 *
 *       A `MutableSharedFlow` with no replay rather than a `StateFlow`, so a test can assert the
 *       **loading** state before anything arrives — the one state a state-holding flow makes
 *       unreachable.
 * What: a hot flow of assessments, plus a builder that runs the engine.
 * Result: the feature's tests exercise real arithmetic without a database.
 * Changelog: 2026-09-02 — Created for issue 7.2.
 */
internal class FakeEmergencyFundRepository : EmergencyFundRepository {
    private val plans = MutableSharedFlow<EmergencyFundPlan>(replay = 1)

    override fun observeEmergencyFund(): Flow<EmergencyFundPlan> = plans

    /** Result: pushes one assessment to every collector. Input: [plan]. Output: none. */
    suspend fun emit(plan: EmergencyFundPlan) {
        plans.emit(plan)
    }

    companion object {
        /** The day every fixture is reckoned from. Fixed, so the suite is reproducible (P-08). */
        val TODAY: LocalDate = LocalDate.parse("2026-09-02")

        /** Three identical months: a cv of exactly zero, so no bump and M is the base six months. */
        val STEADY = listOf(Money(50_000_00L), Money(50_000_00L), Money(50_000_00L))

        /**
         * Builds an assessment **through the real engine**.
         *
         * Why:    so a fixture cannot state a runway the engine would not produce. Every expectation
         *         in this module's tests is therefore a claim about the app.
         * Result: the plan. Input: [essentials] — a month's essentials, or null for the unknown
         *   case; [liquid]; [incomes]; [basis]; [accountNames]. Output: [EmergencyFundPlan].
         */
        fun plan(
            essentials: Money? = Money(40_000_00L),
            liquid: Money = Money(1_00_000_00L),
            incomes: List<Money> = STEADY,
            basis: EssentialsBasis =
                if (essentials == null) EssentialsBasis.NONE else EssentialsBasis.OBSERVED_MEDIAN,
            accountNames: List<String> = listOf("HDFC Savings"),
        ): EmergencyFundPlan =
            (
                EmergencyFundEngineFactory.create().assess(
                    EmergencyFundInput(
                        monthlyEssentials = essentials,
                        essentialsBasis = basis,
                        monthlyIncomes = incomes,
                        liquidFunds = liquid,
                        liquidAccountNames = accountNames,
                        essentialCategoryNames = listOf("NEED"),
                        today = TODAY,
                    ),
                ) as Ok
            ).value
    }
}
