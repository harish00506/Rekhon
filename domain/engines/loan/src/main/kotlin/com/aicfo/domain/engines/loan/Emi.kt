package com.aicfo.domain.engines.loan

import com.aicfo.core.model.Loan
import com.aicfo.core.model.Money
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * The closed-form monthly instalment (issue 6.2; FR-ACC-003, §11, MNY-001/MNY-002).
 *
 * Why:  every Indian retail loan quotes one number, the EMI, and it is the root of the whole
 *       schedule — get it wrong by a paise and 240 rows are wrong. The standard reducing-balance
 *       formula is `EMI = P·i·(1+i)^n / ((1+i)^n − 1)`, where `i` is the *monthly* rate.
 *
 *       **This is the one place `BigDecimal` exponentiation is allowed in this app, and it is
 *       pinned.** `Money` cannot express `(1+i)^240` — that is not an amount, it is a growth factor
 *       — and a `Double` here would make the EMI machine-dependent in the last paise, which P-08
 *       forbids. So the factor is computed in `BigDecimal` at a **fixed** [MathContext]: same
 *       precision and same rounding on every JVM, every device, for ever. Changing [PRECISION]
 *       changes historical answers and is a version bump on the engine, not a tidy-up.
 *
 *       **Zero rate is a separate branch, not an edge case.** At `i = 0` the formula's denominator
 *       is zero, and 0% consumer-durable EMIs and interest-free family loans are both real (`Loan`
 *       accepts `annualRateBps = 0` deliberately). There the instalment is simply the principal
 *       spread across the tenure.
 * What: the derived instalment, and the check that any instalment can actually amortise the loan.
 * Result: a whole number of paise, HALF_EVEN, rounded **once** at the end.
 * Changelog: 2026-08-19 — Created for issue 6.2.
 *
 * `internal` — the public seam is [LoanEngine.emi] (ARC-003).
 */
internal object Emi {
    /**
     * Derives the instalment from the loan's terms.
     * Why:    see the class note. The rounding is HALF_EVEN and happens once, on the final rupee
     *         figure, rather than at each step — rounding the growth factor first is how a schedule
     *         ends up hundreds of rupees adrift over twenty years.
     * What:   `i = annualRateBps / 120_000` (bps → fraction is ÷10 000, year → month is ÷12);
     *         `factor = (1+i)^n`; `EMI = P·i·factor / (factor − 1)`. Zero rate takes the `P / n`
     *         branch.
     * Result: the monthly instalment as whole paise. Always positive: `Loan` has already refused a
     *         non-positive principal and a tenure below 1.
     * Input:  [loan] — validated terms. Output: [Money].
     */
    fun derive(loan: Loan): Money {
        // `sanctioned`, not `principal`: MNY-001 bans a floating-point declaration with a monetary
        // name and `CfoMoneyAsFloatingPoint` fails the build for one, which is the right rule — this
        // is the single place in the app where an amount is deliberately lifted into BigDecimal, for
        // the growth factor below, and it comes straight back down to whole paise before it leaves.
        // The name says which figure it is without pretending it is a Money.
        val sanctioned = BigDecimal.valueOf(loan.principal.minor)
        if (loan.annualRateBps == 0) {
            return Money(
                sanctioned.divide(BigDecimal.valueOf(loan.tenureMonths.toLong()), 0, RoundingMode.HALF_EVEN)
                    .longValueExact(),
            )
        }
        val monthlyRate =
            BigDecimal.valueOf(loan.annualRateBps.toLong())
                .divide(MONTHLY_BPS_DENOMINATOR, CONTEXT)
        val growth = BigDecimal.ONE.add(monthlyRate).pow(loan.tenureMonths, CONTEXT)
        val instalment =
            sanctioned.multiply(monthlyRate, CONTEXT)
                .multiply(growth, CONTEXT)
                .divide(growth.subtract(BigDecimal.ONE), CONTEXT)
        return Money(instalment.setScale(0, RoundingMode.HALF_EVEN).longValueExact())
    }

    /**
     * Whether an instalment is large enough to ever repay the loan.
     * Why:    the guard that keeps [Amortisation] from looping for ever. If the instalment does not
     *         exceed the first month's interest, the principal never falls — it rises — and the
     *         "loan" is one that can never be closed. A user can reach this by typing a lender EMI
     *         with a digit missing, so it has to be an answer rather than a hang.
     *
     *         **Strictly greater, not "at least".** An instalment exactly equal to the interest
     *         leaves the balance unchanged month after month, which is the same non-terminating walk
     *         with a friendlier-looking number.
     * Result: `true` when the loan amortises. Zero-rate loans always do — there is no interest for
     *         the instalment to fail to cover, and `Loan` has already refused a zero instalment.
     * Input:  [loan]; [instalment] — the EMI under test. Output: [Boolean].
     */
    fun amortises(
        loan: Loan,
        instalment: Money,
    ): Boolean = instalment > loan.principal.percentOf(loan.annualRateBps, overPeriods = MONTHS_PER_YEAR)

    /** Months in a year — the divisor that turns an annual rate into a monthly rest. */
    const val MONTHS_PER_YEAR = 12

    /**
     * `10 000 bps per unit × 12 months` = 120 000, so an annual bps figure becomes a monthly
     * fraction in one division rather than two roundings.
     *
     * Composed from its two factors rather than written as the literal, so the *reason* it is
     * 120 000 is in the expression: change the rest to quarterly and only [MONTHS_PER_YEAR] moves.
     */
    private val MONTHLY_BPS_DENOMINATOR: BigDecimal = BigDecimal.valueOf(BPS_PER_UNIT * MONTHS_PER_YEAR)

    /**
     * The pinned precision for the growth factor.
     *
     * 34 significant digits with HALF_EVEN is `MathContext.DECIMAL128`, spelled out rather than
     * referenced so that nobody can "simplify" it to `DECIMAL64` without seeing what it costs.
     * P-08 needs this fixed: the same terms must produce the same paise on every device and in
     * every future build, so this constant is part of the engine's version contract.
     */
    private val CONTEXT = MathContext(PRECISION, RoundingMode.HALF_EVEN)
}

/** @see Emi.CONTEXT */
private const val PRECISION = 34

/** Basis points in a whole unit (MNY-002): 10 000 bps = 100%. */
private const val BPS_PER_UNIT = 10_000L
