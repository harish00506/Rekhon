package com.aicfo.domain.engines.simulator

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money

/**
 * Prepay or invest (issue 10.3; SRS §36 stages 6–7, FLT-004, RULE-PREPAY-VS-INVEST).
 *
 * Why:  the comparison people get wrong in both directions. Both sides are run over **the same
 *       horizon** — the months the loan would have had left — because comparing a saving that ends
 *       in nine years with a return that compounds for twenty is how a simulator lies. The loan is
 *       judged at its *current effective* rate (FLT-004): a repo-linked loan's origination rate is
 *       history.
 * What: amortise the loan as it stands; amortise it again after the lump sum; grow the same lump
 *       sum at the user's own expectation net of tax; say which is ahead and at what return they
 *       would be level.
 * Result: a [PrepayComparison]. It computes; the user decides (P-07).
 * Changelog: 2026-09-26 — Created for issue 10.3.
 *
 * `internal` per ARC-003; pure, with no clock and no I/O (P-08).
 */
internal class AmortisingPrepaySimulator : PrepayVsInvestSimulator {
    override fun simulate(input: PrepayInput): Result<PrepayComparison, AppError> {
        validate(input)?.let { return Err(it) }
        val baseline = SimulatorMath.amortise(input.outstanding, input.annualRateBps, input.emi)
        return if (baseline.neverClears) {
            // An instalment that never covers the interest has no schedule to shorten, so there is
            // nothing to compare — and a "saving" computed from one would be a fiction.
            Err(AppError.Validation(FIELD_EMI))
        } else {
            val prepaid = SimulatorMath.amortise(input.outstanding - input.lumpSum, input.annualRateBps, input.emi)
            val invest = invest(input, baseline.months)
            Ok(comparison(input, baseline, prepaid, invest, baseline.totalInterest - prepaid.totalInterest))
        }
    }

    /**
     * Assembles the answer.
     * Result: the comparison. Input: the two amortisations, the investment and the saving.
     */
    private fun comparison(
        input: PrepayInput,
        baseline: SimulatorMath.Amortised,
        prepaid: SimulatorMath.Amortised,
        invest: InvestPath,
        interestSaved: Money,
    ) = PrepayComparison(
        outstanding = input.outstanding,
        annualRateBps = input.annualRateBps,
        lumpSum = input.lumpSum,
        expectedReturnBps = input.expectedReturnBps,
        taxOnReturnsBps = input.taxOnReturnsBps,
        baseline = LoanPath(baseline.months, baseline.totalInterest),
        prepay =
            LoanPath(
                months = prepaid.months,
                totalInterest = prepaid.totalInterest,
                interestSaved = interestSaved,
                monthsSaved = baseline.months - prepaid.months,
            ),
        invest = invest,
        verdict = verdict(interestSaved, invest.afterTaxGain),
        advantage = advantage(interestSaved, invest.afterTaxGain),
        breakevenReturnBps = breakeven(input, interestSaved),
        provenance =
            EngineProvenance(
                engineId = ENGINE_ID,
                engineVersion = ENGINE_VERSION,
                computedAtUtcMillis = input.nowUtcMillis,
                evidence = listOf(SimulatorRules.PREPAY_VS_INVEST),
                inputWindow = "${baseline.months}m",
            ),
    )

    /**
     * What the lump sum would become instead.
     * Why:    tax comes off the **gain**, not the capital, which is the whole reason a 12%
     *         expectation does not beat a 9% loan as easily as it looks.
     * Result: the path. Input: [input]; [horizonMonths]. Output: [InvestPath].
     */
    private fun invest(
        input: PrepayInput,
        horizonMonths: Int,
    ): InvestPath {
        val futureValue = SimulatorMath.futureValue(input.lumpSum, input.expectedReturnBps, horizonMonths)
        val gain = futureValue - input.lumpSum
        val tax = gain.percentOf(input.taxOnReturnsBps)
        return InvestPath(futureValue = futureValue, afterTaxGain = gain - tax, horizonMonths = horizonMonths)
    }

    /**
     * The return at which the two paths are level (RULE-PREPAY-VS-INVEST's `show_breakeven`).
     * Why:    solved by bisection rather than algebra, because the after-tax gain is a rounded
     *         monthly compounding and its inverse has no clean closed form. **A fixed number of
     *         steps**, so the answer is reproducible to the basis point (P-08).
     * Result: the annual rate in basis points. Input: [input]; [interestSaved]. Output: [Int].
     */
    private fun breakeven(
        input: PrepayInput,
        interestSaved: Money,
    ): Int {
        if (!input.rules.showBreakeven || input.lumpSum <= Money.ZERO) return 0
        val horizon = SimulatorMath.amortise(input.outstanding, input.annualRateBps, input.emi).months
        var low = 0
        var high = MAX_SEARCH_BPS
        repeat(input.rules.breakevenSearchSteps) {
            val middle = (low + high) / 2
            if (gainAt(input, middle, horizon) < interestSaved) low = middle else high = middle
            if (high - low <= 1) return high
        }
        return high
    }

    /** Result: the after-tax gain at a candidate rate. Input: [input]; [bps]; [months]. */
    private fun gainAt(
        input: PrepayInput,
        bps: Int,
        months: Int,
    ): Money {
        val gain = SimulatorMath.futureValue(input.lumpSum, bps, months) - input.lumpSum
        return gain - gain.percentOf(input.taxOnReturnsBps)
    }

    /** Result: which side is ahead. Input: [interestSaved]; [afterTaxGain]. */
    private fun verdict(
        interestSaved: Money,
        afterTaxGain: Money,
    ): SimulatorVerdict =
        when {
            interestSaved > afterTaxGain -> SimulatorVerdict.PREPAY_AHEAD
            afterTaxGain > interestSaved -> SimulatorVerdict.INVEST_AHEAD
            else -> SimulatorVerdict.LEVEL
        }

    /** Result: how far ahead the winner is, as a positive figure. */
    private fun advantage(
        interestSaved: Money,
        afterTaxGain: Money,
    ): Money = if (interestSaved >= afterTaxGain) interestSaved - afterTaxGain else afterTaxGain - interestSaved

    /**
     * The inputs no comparison can be made from.
     * Result: the first refusal by field, or `null`. Input: [input]. Output: `AppError.Validation?`.
     */
    private fun validate(input: PrepayInput): AppError.Validation? =
        when {
            input.outstanding < Money.ZERO -> AppError.Validation(FIELD_OUTSTANDING)
            input.lumpSum < Money.ZERO -> AppError.Validation(FIELD_LUMP)
            input.annualRateBps < 0 -> AppError.Validation(FIELD_RATE)
            input.expectedReturnBps < 0 -> AppError.Validation(FIELD_RETURN)
            input.taxOnReturnsBps !in 0..BPS -> AppError.Validation(FIELD_TAX)
            input.emi <= Money.ZERO -> AppError.Validation(FIELD_EMI)
            else -> null
        }

    private companion object {
        const val ENGINE_ID = "AI-SIM"
        const val ENGINE_VERSION = "1.0"
        const val FIELD_OUTSTANDING = "prepay.outstanding"
        const val FIELD_LUMP = "prepay.lumpSum"
        const val FIELD_RATE = "prepay.annualRateBps"
        const val FIELD_RETURN = "prepay.expectedReturnBps"
        const val FIELD_TAX = "prepay.taxOnReturnsBps"
        const val FIELD_EMI = "prepay.emi"
        const val BPS = 10_000

        /** 100% a year. No expectation this simulator entertains needs a wider search. */
        const val MAX_SEARCH_BPS = 10_000
    }
}
