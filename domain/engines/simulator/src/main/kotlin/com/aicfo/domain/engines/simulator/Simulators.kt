package com.aicfo.domain.engines.simulator

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money

/**
 * AI-SIM — what-if arithmetic that never moves a rupee (issue 10.3; SRS §36, §40.2, P-07).
 *
 * Why:  two questions people reliably get wrong in both directions: whether to prepay a loan or
 *       invest the same money, and which debt to attack first. Both have exact answers, and both
 *       are usually decided on feeling. The simulators compute the figures and show the rate or the
 *       order at which the answer changes — then stop, because the decision is the user's (P-07).
 * What: two engines, one per question, sharing this module's arithmetic.
 * Result: comparisons, not instructions.
 * Changelog: 2026-09-26 — Created for issue 10.3.
 */
interface PrepayVsInvestSimulator {
    /**
     * Compares prepaying a loan with investing the same lump sum (RULE-PREPAY-VS-INVEST, FLT-004).
     * Result: `Ok(PrepayComparison)`; `Err` for a loan that cannot amortise or a negative figure.
     * Input:  [input]. Output: `Result<PrepayComparison, AppError>`.
     */
    fun simulate(input: PrepayInput): Result<PrepayComparison, AppError>
}

/**
 * The debt-payoff simulator (RULE-PAYOFF-ORDER, CRD-005).
 * Why:  §40.2 asks for both strategies side by side, with the interest and date deltas, because the
 *       cheaper one and the one people stick to are not always the same.
 * Changelog: 2026-09-26 — Created for issue 10.3.
 */
interface DebtPayoffSimulator {
    /**
     * Simulates avalanche and snowball over the same debts and the same spare money.
     * Result: `Ok(PayoffComparison)`; `Err` when a minimum cannot cover its own interest, which
     *         would run for ever.
     * Input:  [input]. Output: `Result<PayoffComparison, AppError>`.
     */
    fun simulate(input: PayoffInput): Result<PayoffComparison, AppError>
}

/**
 * Builds the simulators (ARC-003 — the implementations stay `internal`).
 * Changelog: 2026-09-26 — Created for issue 10.3.
 */
object SimulatorFactory {
    /** Result: §36's prepay-vs-invest simulator. Input: none. */
    fun prepayVsInvest(): PrepayVsInvestSimulator = AmortisingPrepaySimulator()

    /** Result: §40.2's payoff simulator. Input: none. */
    fun debtPayoff(): DebtPayoffSimulator = RollingDebtPayoffSimulator()
}

/**
 * What AI-SIM reads for a prepayment question.
 * Input:  [outstanding] — what is left on the loan today; [annualRateBps] — its **current effective**
 *         rate, never the origination one (FLT-004); [remainingMonths]; [emi] — what is paid each
 *         month; [lumpSum] — the money in question; [expectedReturnBps] — the user's own assumption;
 *         [taxOnReturnsBps] — what the return would be taxed at; [nowUtcMillis].
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.3.
 */
data class PrepayInput(
    val outstanding: Money,
    val annualRateBps: Int,
    val remainingMonths: Int,
    val emi: Money,
    val lumpSum: Money,
    val expectedReturnBps: Int,
    val taxOnReturnsBps: Int = 0,
    val nowUtcMillis: Long = 0L,
    val rules: SimulatorRules = SimulatorRules(),
)

/**
 * One path the money could take.
 * Input:  [months] — how long the loan still runs on this path; [totalInterest] — what it costs in
 *         interest; [interestSaved] and [monthsSaved] — against the baseline, zero on the baseline
 *         itself; [remainingMonths] — the same as [months], named for the prepay path's reader.
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.3.
 */
data class LoanPath(
    val months: Int,
    val totalInterest: Money,
    val interestSaved: Money = Money.ZERO,
    val monthsSaved: Int = 0,
) {
    /** The months still to pay on this path — the same figure, read the other way round. */
    val remainingMonths: Int get() = months
}

/**
 * What the lump sum would do if it were invested instead.
 * Input:  [futureValue] — what it grows to over the baseline's horizon; [afterTaxGain] — the growth
 *         left after tax; [horizonMonths] — the horizon both paths are judged over.
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.3.
 */
data class InvestPath(
    val futureValue: Money,
    val afterTaxGain: Money,
    val horizonMonths: Int,
)

/** Which way the arithmetic points. The user still decides (P-07). */
enum class SimulatorVerdict {
    /** The interest saved is larger than the after-tax gain. */
    PREPAY_AHEAD,

    /** The after-tax gain is larger. */
    INVEST_AHEAD,

    /** They are equal to the paise — the breakeven, exactly. */
    LEVEL,
}

/**
 * §36's side-by-side answer.
 * Input:  the figures it was given, so the card can show its working (P-02); [baseline] — the loan
 *         untouched; [prepay]; [invest]; [verdict]; [advantage] — how far ahead the winner is;
 *         [breakevenReturnBps] — the annual return at which the two paths are level;
 *         [provenance].
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.3.
 */
data class PrepayComparison(
    val outstanding: Money,
    val annualRateBps: Int,
    val lumpSum: Money,
    val expectedReturnBps: Int,
    val taxOnReturnsBps: Int,
    val baseline: LoanPath,
    val prepay: LoanPath,
    val invest: InvestPath,
    val verdict: SimulatorVerdict,
    val advantage: Money,
    val breakevenReturnBps: Int,
    val provenance: EngineProvenance,
)

/**
 * One debt in the pile.
 * Input:  [name] — the user's own word for it; [balance]; [annualRateBps]; [minimumPayment] — what
 *         must be paid every month whatever else happens.
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.3.
 */
data class Debt(
    val name: String,
    val balance: Money,
    val annualRateBps: Int,
    val minimumPayment: Money,
)

/**
 * What AI-SIM reads for a payoff question.
 * Input:  [debts]; [extraMonthly] — the spare money above the minimums; [nowUtcMillis].
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.3.
 */
data class PayoffInput(
    val debts: List<Debt>,
    val extraMonthly: Money,
    val nowUtcMillis: Long = 0L,
    val rules: SimulatorRules = SimulatorRules(),
)

/**
 * One strategy's outcome.
 * Input:  [order] — the debts in the order they are cleared; [months] — until the last rupee;
 *         [totalInterest].
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.3.
 */
data class PayoffPlan(
    val order: List<String>,
    val months: Int,
    val totalInterest: Money,
)

/**
 * §40.2's two plans, side by side.
 * Input:  [debts] and [extraMonthly] — what was simulated (P-02); [avalanche] — dearest first;
 *         [snowball] — smallest first; [interestSavedByAvalanche] and [monthsSavedByAvalanche] —
 *         the deltas CRD-005 asks for; [provenance].
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.3.
 */
data class PayoffComparison(
    val debts: List<Debt>,
    val extraMonthly: Money,
    val avalanche: PayoffPlan,
    val snowball: PayoffPlan,
    val interestSavedByAvalanche: Money,
    val monthsSavedByAvalanche: Int,
    val provenance: EngineProvenance,
)
