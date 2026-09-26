package com.aicfo.domain.engines.simulator

import com.aicfo.core.model.Money

/**
 * The arithmetic both simulators run on (issue 10.3; MNY-001/002).
 *
 * Why:  one month's interest, and one loan run to its end. Both are computed with
 *       `Money.percentOf(bps, overPeriods = 12)` — **the same helper `:domain:engines:loan` uses**,
 *       so a payoff simulated here and a schedule shown on the accounts screen cannot disagree
 *       about a rupee. A test pins that: a single debt paid at its EMI must produce exactly the
 *       loan engine's interest total.
 * What: monthly interest, and an amortisation loop that reports when it would never finish.
 * Result: figures in whole paise, rounded once a month, half-even.
 * Changelog: 2026-09-26 — Created for issue 10.3.
 */
internal object SimulatorMath {
    /** A loan that never amortises is reported rather than looped over for ever. */
    const val NEVER_AMORTISES = -1

    /**
     * Result: one month's interest on [balance] at [annualRateBps], half-even to the paise.
     * Input:  [balance]; [annualRateBps]. Output: [Money].
     */
    fun monthlyInterest(
        balance: Money,
        annualRateBps: Int,
    ): Money = balance.percentOf(annualRateBps, overPeriods = MONTHS_PER_YEAR)

    /**
     * Runs a balance to zero at a fixed payment.
     * Why:    the payment may be less than the month's interest, in which case the balance grows
     *         and no number of months clears it — the loop says so instead of running to its cap
     *         and reporting a number that means nothing.
     * Result: the months taken and the interest paid, or [NEVER_AMORTISES] months when it cannot
     *         be cleared. Input: [balance]; [annualRateBps]; [payment]; [capMonths] — a guard, not
     *         a business rule. Output: [Amortised].
     */
    fun amortise(
        balance: Money,
        annualRateBps: Int,
        payment: Money,
        capMonths: Int = CAP_MONTHS,
    ): Amortised {
        if (balance <= Money.ZERO) return Amortised(0, Money.ZERO)
        var outstanding = balance
        var interestPaid = Money.ZERO
        var months = 0
        while (outstanding > Money.ZERO && months < capMonths) {
            val interest = monthlyInterest(outstanding, annualRateBps)
            if (payment <= interest) return Amortised(NEVER_AMORTISES, Money.ZERO)
            interestPaid += interest
            outstanding = settle(outstanding + interest - payment, payment)
            months += 1
        }
        return if (outstanding > Money.ZERO) Amortised(NEVER_AMORTISES, Money.ZERO) else Amortised(months, interestPaid)
    }

    /**
     * The last month absorbs a rounding residue.
     * Why:    a fixed payment rarely divides a balance exactly, so after the final instalment a few
     *         paise can remain. A lender folds that into the last payment; a simulator that instead
     *         ran an extra month to collect ₹3 would report a tenure one month longer than the
     *         amortisation schedule the accounts screen shows — which is exactly what the
     *         cross-check against `:domain:engines:loan` caught. **A residue under 1% of a payment
     *         is rounding, not debt**; anything larger is a real balance and gets its own month.
     * Result: the balance to carry forward. Input: [remaining] — what is left after the payment;
     *         [payment] — what was paid. Output: [Money].
     *
     * Used by both simulators, so a loan run here and a debt run in the payoff loop round the same
     * way. It is the one place this rule exists.
     */
    fun settle(
        remaining: Money,
        payment: Money,
    ): Money =
        if (remaining > Money.ZERO && remaining < payment.percentOf(ROUNDING_TOLERANCE_BPS)) Money.ZERO else remaining

    /**
     * Result: what [amount] grows to after [months] at [annualRateBps], compounded monthly and
     *         rounded once at the end. Input: [amount]; [annualRateBps]; [months]. Output: [Money].
     */
    fun futureValue(
        amount: Money,
        annualRateBps: Int,
        months: Int,
    ): Money {
        var value = amount
        repeat(months) { value += monthlyInterest(value, annualRateBps) }
        return value
    }

    /** One run of [amortise]: how long it took, and what the interest came to. */
    data class Amortised(
        val months: Int,
        val totalInterest: Money,
    ) {
        /** Whether the payment was too small ever to clear the balance. */
        val neverClears: Boolean get() = months == NEVER_AMORTISES
    }

    private const val MONTHS_PER_YEAR = 12

    /** One percent of a payment: above any accumulated rounding, far below a real instalment. */
    private const val ROUNDING_TOLERANCE_BPS = 100

    /**
     * The loop's guard: a thousand years of months. Nothing real reaches it — a loan that would
     * needs a payment below its interest, which [amortise] catches on the first month.
     */
    private const val CAP_MONTHS = 12_000
}
