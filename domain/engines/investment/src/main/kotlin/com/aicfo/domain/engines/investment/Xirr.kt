package com.aicfo.domain.engines.investment

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Solves the money-weighted return over dated cash flows (issue 6.3; §11, P-08, MNY-002).
 *
 * Why:  XIRR is normally written as `NPV(r) = Σ cf_i · (1+r)^(−d_i/365) = 0` and solved with
 *       Newton-Raphson from a guess. Both halves of that are hostile to P-08. The fractional
 *       exponent has no exact decimal form, so an implementation reaches for `Double` and inherits
 *       its drift; and Newton's answer depends on the starting guess, can diverge on a series with
 *       one large early outflow, and stops on a tolerance test — three separate places where an
 *       innocent refactor silently changes what the app said last year about a holding nobody
 *       touched.
 *
 *       This does two things differently. **First, a substitution.** Let `x = (1+r)^(1/365)`. Then
 *       `(1+r)^(d/365) = x^d` with `d` a whole number of days, and multiplying the whole equation
 *       through by `x^dMax` clears every division:
 *
 *           F(x) = Σ cf_i · x^(dMax − d_i)
 *
 *       — a polynomial in `x` with non-negative integer exponents, sharing NPV's positive roots.
 *       The solve therefore needs only `BigDecimal.pow(Int, MathContext)`, `multiply` and `add`.
 *       That is what makes determinism provable here rather than merely asserted.
 *
 *       **Second, bisection over a literal bracket with a fixed iteration count and no early
 *       exit.** It is slower than Newton and it does not care: there is no guess to depend on and
 *       no tolerance branch to drift, so the same flows give the same basis points on every machine
 *       and every build, which is the only property that matters for a figure the app will still be
 *       showing in five years.
 * What: coalescing, the three refusals, the bracket, the bisection, and the one rounding.
 * Result: an annualised rate in integer basis points, or the reason there is none.
 * Changelog: 2026-08-24 — Created for issue 6.3 (§11).
 *
 * **[PRECISION], [X_LOW], [X_HIGH], [ITERATIONS] and [DAYS_PER_YEAR] are the version contract.**
 * Changing any one of them changes every historical answer, so it is an `ENGINE_VERSION` bump
 * (AI-ARC-006), not a tuning. `ENGINE.md` says so too.
 */
internal object Xirr {
    /**
     * Days in a year, fixed.
     *
     * ACT/365 rather than ACT/365F-with-leap-correction or ACT/360, because it is what Excel's
     * `XIRR` uses — so a user who checks the app against a spreadsheet gets the same number. That
     * makes a 366-day span 366/365 of a year, deliberately; the golden file pins it.
     */
    const val DAYS_PER_YEAR: Int = 365

    /**
     * Solves the rate.
     * Why:    one entry point, so the coalescing that makes the answer order-independent cannot be
     *         skipped by a caller in a hurry.
     * What:   sums same-day flows, refuses the three unsolvable shapes, then bisects.
     * Result: [Outcome.Solved] with the rate, the coalesced flow count and the ACT span, or
     *         [Outcome.Unavailable] naming why not.
     * Input:  [flows] — dated signed amounts in any order; dates must already be valid ISO days,
     *         which the models guarantee.
     * Output: [Outcome].
     * Changelog: 2026-08-24 — Created for issue 6.3.
     */
    fun solve(flows: List<CashFlow>): Outcome {
        // Same-day flows are summed before anything else. This is what makes the answer independent
        // of the order the caller happened to list them in, and it is why `flowCount` counts
        // distinct days rather than rows.
        val byDay =
            flows.groupBy { LocalDate.parse(it.onIsoDate) }
                .mapValues { (_, sameDay) -> sameDay.sumOf { it.amount.minor } }
                .toSortedMap()
        val values = byDay.values.toList()

        return when {
            byDay.size < 2 -> Outcome.Unavailable(XirrUnavailable.TOO_FEW_FLOWS)
            values.all { it >= 0L } || values.all { it <= 0L } ->
                Outcome.Unavailable(XirrUnavailable.SAME_SIGN)
            else -> bracketAndSolve(byDay.keys.toList(), values, byDay.size)
        }
    }

    /**
     * Brackets the root and, if it is inside, finds it.
     * Why:    split from [solve] so the shape of the refusals stays readable there: the first two
     *         are facts about the series, this one is a fact about where its answer lies.
     * Result: [Outcome.Solved], or [Outcome.Unavailable] when the rate is outside the bracket.
     * Input:  [days] — the distinct days, ascending; [values] — the coalesced paise, matching order;
     *         [flowCount] — how many distinct days there are.
     * Output: [Outcome].
     * Changelog: 2026-08-24 — Created for issue 6.3.
     */
    private fun bracketAndSolve(
        days: List<LocalDate>,
        values: List<Long>,
        flowCount: Int,
    ): Outcome {
        val first = days.first()
        val span = ChronoUnit.DAYS.between(first, days.last()).toInt()
        val exponents = days.map { span - ChronoUnit.DAYS.between(first, it).toInt() }

        val atLow = polynomialAt(X_LOW, values, exponents)
        val atHigh = polynomialAt(X_HIGH, values, exponents)
        return if (atLow.signum() == atHigh.signum()) {
            Outcome.Unavailable(XirrUnavailable.NOT_BRACKETED)
        } else {
            Outcome.Solved(
                rateBps = toBasisPoints(bisect(atLow.signum(), values, exponents)),
                flowCount = flowCount,
                spanDays = span,
            )
        }
    }

    /**
     * Halves the bracket a fixed number of times.
     * Why:    no early exit and no tolerance test, so the loop's result is a function of its inputs
     *         alone. [ITERATIONS] halvings of a 0.04-wide bracket leave about 1.2e-40, far below
     *         the resolution of the basis point this eventually rounds to — so stopping early could
     *         only ever make the answer machine-dependent, never faster in a way anyone can see.
     * Result: the daily growth factor at the midpoint of the final bracket.
     * Input:  [signAtLow] — the polynomial's sign at [X_LOW]; [values], [exponents] — the series.
     * Output: [BigDecimal].
     * Changelog: 2026-08-24 — Created for issue 6.3.
     */
    private fun bisect(
        signAtLow: Int,
        values: List<Long>,
        exponents: List<Int>,
    ): BigDecimal {
        var low = X_LOW
        var high = X_HIGH
        repeat(ITERATIONS) {
            val mid = low.add(high).divide(TWO, CONTEXT)
            if (polynomialAt(mid, values, exponents).signum() == signAtLow) {
                low = mid
            } else {
                high = mid
            }
        }
        return low.add(high).divide(TWO, CONTEXT)
    }

    /**
     * Evaluates `F(x) = Σ cf_i · x^e_i`.
     * Why:    the whole point of the substitution — every exponent is a non-negative `Int`, so this
     *         is exact decimal arithmetic to [CONTEXT]'s precision with no fractional power and no
     *         division anywhere.
     * Result: the polynomial's value at [growth].
     * Input:  [growth] — the daily growth factor; [values] — paise, signed; [exponents] — the
     *         matching whole-day powers.
     * Output: [BigDecimal].
     * Changelog: 2026-08-24 — Created for issue 6.3.
     */
    private fun polynomialAt(
        growth: BigDecimal,
        values: List<Long>,
        exponents: List<Int>,
    ): BigDecimal {
        var total = BigDecimal.ZERO
        for (index in values.indices) {
            val term = growth.pow(exponents[index], CONTEXT).multiply(BigDecimal.valueOf(values[index]), CONTEXT)
            total = total.add(term, CONTEXT)
        }
        return total
    }

    /**
     * Turns a daily growth factor into an annual rate in basis points.
     * Why:    one rounding, at the very end, on the answer — the discipline `Emi.kt` keeps. Rounding
     *         anywhere earlier would put the error inside the exponentiation, where 365 compoundings
     *         would amplify it.
     * Result: the annualised rate; 1000 is 10%, negative is a loss.
     * Input:  [growth] — the solved daily growth factor. Output: [Int] basis points (MNY-002).
     * Changelog: 2026-08-24 — Created for issue 6.3.
     */
    private fun toBasisPoints(growth: BigDecimal): Int =
        growth.pow(DAYS_PER_YEAR, CONTEXT)
            .subtract(BigDecimal.ONE, CONTEXT)
            .multiply(BPS_PER_UNIT, CONTEXT)
            .setScale(0, RoundingMode.HALF_EVEN)
            .intValueExact()

    /** What [solve] found. */
    sealed interface Outcome {
        /** A rate was found. */
        data class Solved(
            val rateBps: Int,
            val flowCount: Int,
            val spanDays: Int,
        ) : Outcome

        /** No rate exists for this series, for an ordinary reason. */
        data class Unavailable(val reason: XirrUnavailable) : Outcome
    }

    /**
     * Significant digits carried through the solve — DECIMAL128's precision, named rather than
     * inlined so the one number that governs every intermediate rounding is visible on its own
     * line. Part of the version contract.
     */
    private const val PRECISION = 34

    /** [PRECISION] digits with banker's rounding, the context every operation below runs under. */
    private val CONTEXT = MathContext(PRECISION, RoundingMode.HALF_EVEN)

    /** `0.98^365 − 1` ≈ −99.94% a year: the floor of what this engine will report. */
    private val X_LOW = BigDecimal("0.98")

    /** `1.02^365 − 1` ≈ +1 360 000%: high enough that no real holding sits above it. */
    private val X_HIGH = BigDecimal("1.02")

    /**
     * Halvings of the bracket. 0.04 · 2⁻¹²⁸ ≈ 1.2e-40, which is forty orders of magnitude finer
     * than the basis point the answer is rounded to.
     */
    private const val ITERATIONS = 128

    /** 10 000 bps = 100% (MNY-002). */
    private val BPS_PER_UNIT = BigDecimal("10000")

    /** The bisection's divisor. A named constant because `BigDecimal("2")` in a loop is noise. */
    private val TWO = BigDecimal("2")
}
