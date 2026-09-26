package com.aicfo.feature.advisor

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import com.aicfo.data.repository.SimulatableDebt
import com.aicfo.data.repository.SimulatorRepository
import com.aicfo.domain.engines.simulator.Debt
import com.aicfo.domain.engines.simulator.InvestPath
import com.aicfo.domain.engines.simulator.LoanPath
import com.aicfo.domain.engines.simulator.PayoffComparison
import com.aicfo.domain.engines.simulator.PayoffPlan
import com.aicfo.domain.engines.simulator.PrepayComparison
import com.aicfo.domain.engines.simulator.SimulatorVerdict
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A stand-in for the simulators' data side (issue 10.3).
 *
 * Why:  the screen's tests are about what it draws and what it asks for — in which units. The
 *       arithmetic is proven in `:domain:engines:simulator` and the wiring in `:data:repository`.
 * Result: the comparisons a test sets, and a record of what was asked.
 * Changelog: 2026-09-26 — Created for issue 10.3.
 */
class FakeSimulatorRepository : SimulatorRepository {
    /** The debts the screen lists. */
    val debts: MutableStateFlow<List<SimulatableDebt>> = MutableStateFlow(DEBTS)

    /** Every prepay question, as (account, lump sum, return bps, tax bps). */
    val prepayAsked: MutableList<PrepayAsk> = mutableListOf()

    /** Every payoff question's spare monthly figure. */
    val payoffAsked: MutableList<Money> = mutableListOf()

    /** When set, both calls fail with it. */
    var failure: AppError? = null

    override fun observeDebts(): Flow<List<SimulatableDebt>> = debts

    override suspend fun prepayVsInvest(
        accountId: String,
        lumpSum: Money,
        expectedReturnBps: Int,
        taxOnReturnsBps: Int,
    ): Result<PrepayComparison, AppError> {
        prepayAsked += PrepayAsk(accountId, lumpSum, expectedReturnBps, taxOnReturnsBps)
        return failure?.let { Err(it) } ?: Ok(PREPAY)
    }

    override suspend fun payoff(extraMonthly: Money): Result<PayoffComparison, AppError> {
        payoffAsked += extraMonthly
        return failure?.let { Err(it) } ?: Ok(PAYOFF)
    }

    /** One question asked of §36's simulator. */
    data class PrepayAsk(
        val accountId: String,
        val lumpSum: Money,
        val expectedReturnBps: Int,
        val taxOnReturnsBps: Int,
    )

    companion object {
        val DEBTS =
            listOf(
                SimulatableDebt(
                    "account:loan",
                    "Home loan",
                    Money(20_00_000_00L),
                    900,
                    Money(20_285_00L),
                    isLoan = true,
                ),
                SimulatableDebt(
                    "account:card",
                    "Credit card",
                    Money(80_000_00L),
                    4_200,
                    Money(4_000_00L),
                    isLoan = false,
                ),
            )

        /** Prepaying saves ₹4,78,250.36; investing would leave ₹6,99,412.23 — investing is ahead. */
        val PREPAY =
            PrepayComparison(
                outstanding = Money(20_00_000_00L),
                annualRateBps = 900,
                lumpSum = Money(2_00_000_00L),
                expectedReturnBps = 1_200,
                taxOnReturnsBps = 3_000,
                baseline = LoanPath(180, Money(16_51_425_57L)),
                prepay = LoanPath(147, Money(11_73_175_21L), Money(4_78_250_36L), 33),
                invest = InvestPath(Money(8_99_412_23L), Money(6_99_412_23L), 180),
                verdict = SimulatorVerdict.INVEST_AHEAD,
                advantage = Money(2_21_161_87L),
                breakevenReturnBps = 1_050,
                provenance =
                    EngineProvenance(
                        "AI-SIM",
                        "1.0",
                        0L,
                        listOf(RuleCitation("RULE-PREPAY-VS-INVEST", "1.0")),
                    ),
            )

        val PAYOFF =
            PayoffComparison(
                debts = listOf(Debt("Credit card", Money(80_000_00L), 4_200, Money(4_000_00L))),
                extraMonthly = Money(5_000_00L),
                avalanche = PayoffPlan(listOf("Credit card", "Home loan"), 25, Money(59_950_55L)),
                snowball = PayoffPlan(listOf("Home loan", "Credit card"), 27, Money(68_295_02L)),
                interestSavedByAvalanche = Money(8_344_47L),
                monthsSavedByAvalanche = 2,
                provenance = EngineProvenance("AI-SIM", "1.0", 0L, listOf(RuleCitation("RULE-PAYOFF-ORDER", "1.0"))),
            )
    }
}
