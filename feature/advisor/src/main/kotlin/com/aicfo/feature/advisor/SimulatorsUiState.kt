package com.aicfo.feature.advisor

import com.aicfo.data.repository.SimulatableDebt
import com.aicfo.domain.engines.simulator.PayoffComparison
import com.aicfo.domain.engines.simulator.PrepayComparison

/**
 * Everything the simulators screen draws (issue 10.3; §36, §40.2, ARC-004).
 *
 * Why:  one immutable state, and **no arithmetic anywhere near it**. Every figure on this screen is
 *       one AI-SIM computed; a ViewModel that worked out an interest total here would be a second
 *       definition of a number the engine already owns (P-03).
 * What: the debts to choose from, what the user typed, and whichever comparison came back.
 * Result: a screen that is a function of its state.
 * Changelog: 2026-09-26 — Created for issue 10.3.
 *
 * Input:  [debts] — the household's loans and revolving cards; [selectedAccountId] — the loan a
 *         prepayment is about; [lumpSumRupees] / [expectedReturnPercent] / [taxPercent] /
 *         [extraMonthlyRupees] — as typed; [prepay] and [payoff] — the answers, `null` until asked;
 *         [isSimulating]; [errorCode].
 * Output: an immutable value.
 */
data class SimulatorsUiState(
    val debts: List<SimulatableDebt> = emptyList(),
    val selectedAccountId: String? = null,
    val lumpSumRupees: String = "",
    val expectedReturnPercent: String = "12",
    val taxPercent: String = "30",
    val extraMonthlyRupees: String = "",
    val prepay: PrepayComparison? = null,
    val payoff: PayoffComparison? = null,
    val isSimulating: Boolean = false,
    val errorCode: String? = null,
) {
    /** The loans, which are the only debts a prepayment can shorten — a card has no schedule. */
    val loans: List<SimulatableDebt> get() = debts.filter { it.isLoan }

    /** Whether a prepayment can be simulated: a loan chosen and a sum to put against it. */
    val canSimulatePrepay: Boolean
        get() = selectedAccountId != null && lumpSumRupees.toLongOrNull()?.let { it > 0 } == true

    /** Whether a payoff plan can be drawn: something must be owed. */
    val canSimulatePayoff: Boolean get() = debts.isNotEmpty()
}

/**
 * What the user can do on the simulators screen.
 * Why:  a sealed interface so a new event cannot be silently ignored (ARC-004). Every one of these
 *       asks a question; none of them moves money (P-07).
 * Changelog: 2026-09-26 — Created for issue 10.3.
 */
sealed interface SimulatorsEvent {
    /** Choose the loan a prepayment is about. */
    data class LoanSelected(val accountId: String) : SimulatorsEvent

    /** The lump sum changed, in whole rupees as typed. */
    data class LumpSumChanged(val rupees: String) : SimulatorsEvent

    /** The expected return changed, in whole percent as typed. */
    data class ExpectedReturnChanged(val percent: String) : SimulatorsEvent

    /** The tax on returns changed, in whole percent as typed. */
    data class TaxChanged(val percent: String) : SimulatorsEvent

    /** The spare monthly money changed, in whole rupees as typed. */
    data class ExtraMonthlyChanged(val rupees: String) : SimulatorsEvent

    /** Run the prepay-vs-invest comparison. */
    data object SimulatePrepay : SimulatorsEvent

    /** Run avalanche against snowball. */
    data object SimulatePayoff : SimulatorsEvent

    /** Dismiss the error banner. */
    data object DismissError : SimulatorsEvent
}
