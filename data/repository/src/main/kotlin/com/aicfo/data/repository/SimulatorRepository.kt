package com.aicfo.data.repository

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.Result
import com.aicfo.core.common.flatMap
import com.aicfo.core.common.getOrNull
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.model.Account
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.card.CardStatus
import com.aicfo.domain.engines.loan.AmortisationRow
import com.aicfo.domain.engines.simulator.Debt
import com.aicfo.domain.engines.simulator.DebtPayoffSimulator
import com.aicfo.domain.engines.simulator.PayoffComparison
import com.aicfo.domain.engines.simulator.PayoffInput
import com.aicfo.domain.engines.simulator.PrepayComparison
import com.aicfo.domain.engines.simulator.PrepayInput
import com.aicfo.domain.engines.simulator.PrepayVsInvestSimulator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The simulators' data side (issue 10.3; §36, §40.2, AI-ARC-001).
 *
 * Why:  a what-if is only worth running on the user's **own** numbers. AI-SIM is pure and knows
 *       nothing about this household, so something has to turn the loans and cards the app already
 *       tracks into a question it can answer — the outstanding balance from the next instalment's
 *       opening figure, the rate from the loan itself (FLT-004's *current effective* rate), the
 *       card's APR from its terms and the balance and minimum from its statement.
 * What: the debts a simulation could use, and the two simulations.
 * Result: a ViewModel sees comparisons and never a DAO (ARC-005). **Nothing is written** — a
 *         simulation is arithmetic, not a decision (P-07), so this repository has no writes at all.
 * Changelog: 2026-09-26 — Created for issue 10.3.
 */
interface SimulatorRepository {
    /**
     * Every debt the app could simulate, loans and revolving cards alike.
     * Result: re-emits as balances change; empty when nothing is owed. Input: none.
     * Output: `Flow<List<SimulatableDebt>>`.
     */
    fun observeDebts(): Flow<List<SimulatableDebt>>

    /**
     * Prepay a loan, or invest the same money (RULE-PREPAY-VS-INVEST).
     * Result: the comparison; `Err(Validation("simulator.loan"))` when that loan is not there.
     * Input:  [accountId]; [lumpSum]; [expectedReturnBps] — the user's own assumption;
     *         [taxOnReturnsBps]. Output: `Result<PrepayComparison, AppError>`.
     */
    suspend fun prepayVsInvest(
        accountId: String,
        lumpSum: Money,
        expectedReturnBps: Int,
        taxOnReturnsBps: Int,
    ): Result<PrepayComparison, AppError>

    /**
     * Avalanche against snowball over everything owed (RULE-PAYOFF-ORDER).
     * Result: both plans; `Err(Validation("simulator.debts"))` when nothing is owed.
     * Input:  [extraMonthly]. Output: `Result<PayoffComparison, AppError>`.
     */
    suspend fun payoff(extraMonthly: Money): Result<PayoffComparison, AppError>
}

/**
 * One debt as the simulators see it.
 * Input:  [accountId]; [name] — the account's own name; [outstanding]; [annualRateBps];
 *         [minimumPayment] — the EMI for a loan, the minimum due for a card; [isLoan] — only a loan
 *         can be prepaid, because a card has no schedule to shorten.
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.3.
 */
data class SimulatableDebt(
    val accountId: String,
    val name: String,
    val outstanding: Money,
    val annualRateBps: Int,
    val minimumPayment: Money,
    val isLoan: Boolean,
)

/**
 * [SimulatorRepository] over the loans and cards already on file (issue 10.3).
 * Input:  [accounts]; [loans]; [cards]; [prepaySimulator]; [payoffSimulator]; [clock];
 *         [dispatchers]. Output: the repository.
 * Changelog: 2026-09-26 — Created for issue 10.3.
 */
@Suppress("LongParameterList") // three sources, two engines and two seams
internal class LiveSimulatorRepository(
    private val accounts: Flow<List<Account>>,
    private val loans: LoanRepository,
    private val cards: Flow<Map<String, CardStatus>>,
    private val cardTerms: CreditCardRepository,
    private val instalments: Flow<Map<String, AmortisationRow>>,
    private val prepaySimulator: PrepayVsInvestSimulator,
    private val payoffSimulator: DebtPayoffSimulator,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
) : SimulatorRepository {
    override fun observeDebts(): Flow<List<SimulatableDebt>> =
        combine(accounts, instalments, cards) { allAccounts, nextInstalments, allCards ->
            Triple(allAccounts.associate { it.id to it.name }, nextInstalments, allCards)
        }.map { (named, nextInstalments, allCards) ->
            loanDebts(named, nextInstalments) + cardDebts(named, allCards)
        }

    override suspend fun prepayVsInvest(
        accountId: String,
        lumpSum: Money,
        expectedReturnBps: Int,
        taxOnReturnsBps: Int,
    ): Result<PrepayComparison, AppError> =
        withContext(dispatchers.io) {
            val row = instalments.first()[accountId]
            val loan = loans.find(accountId).getOrNull()
            if (row == null || loan == null) {
                Err(AppError.Validation(FIELD_LOAN))
            } else {
                prepaySimulator.simulate(
                    PrepayInput(
                        outstanding = row.openingBalance,
                        // FLT-004: the loan's own rate, which the accounts screen keeps current for
                        // a repo-linked loan. The rate it was written at is history.
                        annualRateBps = loan.annualRateBps,
                        remainingMonths = loan.tenureMonths - row.number + 1,
                        emi = row.amount,
                        lumpSum = lumpSum,
                        expectedReturnBps = expectedReturnBps,
                        taxOnReturnsBps = taxOnReturnsBps,
                        nowUtcMillis = clock.nowUtcMillis(),
                    ),
                )
            }
        }

    override suspend fun payoff(extraMonthly: Money): Result<PayoffComparison, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult { observeDebts().first() }
                .flatMap { debts ->
                    if (debts.isEmpty()) {
                        Err(AppError.Validation(FIELD_DEBTS))
                    } else {
                        payoffSimulator.simulate(
                            PayoffInput(
                                debts =
                                    debts.map {
                                        Debt(
                                            it.name,
                                            it.outstanding,
                                            it.annualRateBps,
                                            it.minimumPayment,
                                        )
                                    },
                                extraMonthly = extraMonthly,
                                nowUtcMillis = clock.nowUtcMillis(),
                            ),
                        )
                    }
                }
        }

    /**
     * Result: every loan with an instalment ahead of it. Input: [named]; [nextInstalments].
     *
     * The outstanding figure is the next instalment's **opening** balance — what is owed before it
     * is paid — so a simulation starts where the schedule does.
     */
    private suspend fun loanDebts(
        named: Map<String, String>,
        nextInstalments: Map<String, AmortisationRow>,
    ): List<SimulatableDebt> =
        nextInstalments.mapNotNull { (accountId, row) ->
            // The rate is read from the loan itself rather than inferred from the instalment: an
            // EMI is a consequence of the rate, and reversing that arithmetic would be a second,
            // lossier definition of a number the loan already states (FLT-004).
            loans.find(accountId).getOrNull()?.let { loan ->
                SimulatableDebt(
                    accountId = accountId,
                    name = named[accountId] ?: accountId,
                    outstanding = row.openingBalance,
                    annualRateBps = loan.annualRateBps,
                    minimumPayment = row.amount,
                    isLoan = true,
                )
            }
        }

    /**
     * Result: every card carrying a statement balance it is paying interest on.
     * Why:    a card with no APR recorded, no statement or no minimum cannot be simulated honestly,
     *         and guessing one of them would be exactly the invented figure P-03 forbids — so it is
     *         left out and the screen says the list is short rather than pretending.
     * Input:  [named]; [allCards]. Output: `List<SimulatableDebt>`.
     */
    private suspend fun cardDebts(
        named: Map<String, String>,
        allCards: Map<String, CardStatus>,
    ): List<SimulatableDebt> =
        allCards.values.mapNotNull { status ->
            val balance = status.statement.used ?: return@mapNotNull null
            val minimum = status.minimumDue ?: return@mapNotNull null
            // The APR is on the card's own terms, not on its status — and a card with none recorded
            // is left out rather than given a guessed rate (P-03).
            val apr = cardTerms.find(status.accountId).getOrNull()?.aprBps ?: return@mapNotNull null
            if (balance <= Money.ZERO || minimum <= Money.ZERO) {
                null
            } else {
                SimulatableDebt(
                    accountId = status.accountId,
                    name = named[status.accountId] ?: status.accountId,
                    outstanding = balance,
                    annualRateBps = apr,
                    minimumPayment = minimum,
                    isLoan = false,
                )
            }
        }

    private companion object {
        const val FIELD_LOAN = "simulator.loan"
        const val FIELD_DEBTS = "simulator.debts"
    }
}
