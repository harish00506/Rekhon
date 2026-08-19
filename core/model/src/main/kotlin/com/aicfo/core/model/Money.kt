package com.aicfo.core.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A single-currency monetary amount, held as `Long` minor units (paise).
 *
 * Why:  MNY-001 / NFR-012 (SRS §21.4, §6) — floating point cannot represent 0.10 exactly, so a
 *       `Double` rupee amount drifts the moment it is added, split or accumulated. Every amount in
 *       this app is therefore an integer count of paise, from the Room column to the screen, and
 *       this class is the only monetary type. **A `Double`/`Float` on a monetary value fails
 *       review.** Currency is tracked alongside a `Money`, never inside it — a `Money` is only
 *       ever compared with or added to another amount of the same currency.
 * What: overflow-checked arithmetic, basis-point percentages with banker's rounding, and exact
 *       splitting/allocation that can neither create nor lose a paise.
 * Result: any sequence of operations on amounts is exact, deterministic and fail-fast — a wrong
 *       answer throws instead of silently drifting.
 * Changelog: 2026-07-25 — Created for issue 1.2 / task 1.1.2 (MNY-001, MNY-002, §21.4).
 *
 * Input:  [minor] — the amount in minor units (paise); negative means a refund/outflow.
 * Output: a value class that boxes to nothing at runtime in the common case.
 */
@JvmInline
value class Money(val minor: Long) : Comparable<Money> {
    /**
     * Adds two amounts.
     * Why:    totals are the most common money operation and the easiest place to wrap silently.
     * Result: the exact sum, or [ArithmeticException] rather than a wrapped negative total.
     * Input:  [other] — the amount to add. Output: a new [Money].
     */
    operator fun plus(other: Money): Money = Money(Math.addExact(minor, other.minor))

    /**
     * Subtracts an amount.
     * Result: the exact difference, or [ArithmeticException] on underflow.
     * Input:  [other] — the amount to subtract. Output: a new [Money].
     */
    operator fun minus(other: Money): Money = Money(Math.subtractExact(minor, other.minor))

    /**
     * Repeats an amount a whole number of times (3 EMIs, 12 months).
     * Why:    scaling by a *count* is safe; scaling by a *rate* is not — use [percentOf] for that,
     *         which is why no `times(Double)` exists.
     * Result: the exact product, or [ArithmeticException] on overflow.
     * Input:  [count] — how many times. Output: a new [Money].
     */
    operator fun times(count: Int): Money = Money(Math.multiplyExact(minor, count.toLong()))

    /**
     * Takes a percentage expressed in basis points (MNY-002: 1 bps = 0.01%, so 250 bps = 2.5%),
     * optionally for one period of a year.
     * Why:    rates must be integers too — a `Double` interest rate reintroduces exactly the drift
     *         [Money] exists to prevent. Ties round HALF_EVEN (banker's rounding) so repeated
     *         rounding does not bias totals upward the way HALF_UP does.
     *
     *         **[overPeriods] exists because an annual rate divided by twelve is not an integer**
     *         (issue 6.2). A home loan at 8.5% is 850 bps a year and 70.83… bps a month, so a
     *         caller that had to hand this method a whole number would have to truncate first — and
     *         a loan schedule applies that truncated rate 240 times. Dividing inside the same
     *         exact-decimal expression keeps one rounding, at the end, on the paise. It is a
     *         divisor of the *rate*, never of the amount: [split] and [allocate] are what divide an
     *         amount, because only they redistribute the remainder.
     * What:   computes `minor x bps / (10_000 x overPeriods)` in exact decimal arithmetic, then
     *         rounds to a whole paise. [BigDecimal] is an internal detail — it never appears in the
     *         public API.
     * Result: the rounded share, or [ArithmeticException] if the true value exceeds `Long`.
     * Input:  [bps] — a non-negative rate in basis points (10 000 bps = 100%);
     *         [overPeriods] — how many equal periods the year's rate is spread across; must be
     *         positive; `1` (the default) means the whole rate, `12` means one month of it.
     * Output: a new [Money].
     * Changelog: 2026-07-25 — Created for issue 1.2.
     *            2026-08-19 — Added [overPeriods] for issue 6.2's monthly-rest amortisation. Every
     *            existing call site is unaffected: the default reproduces the old expression
     *            exactly.
     */
    fun percentOf(bps: Int, overPeriods: Int = 1): Money {
        require(bps >= 0) { "Rate must be non-negative basis points (MNY-002), was $bps" }
        require(overPeriods > 0) { "A year splits into a positive number of periods, was $overPeriods" }
        val exact =
            BigDecimal.valueOf(minor)
                .multiply(BigDecimal.valueOf(bps.toLong()))
                .divide(
                    BPS_DENOMINATOR.multiply(BigDecimal.valueOf(overPeriods.toLong())),
                    0,
                    RoundingMode.HALF_EVEN,
                )
        return Money(exact.longValueExact())
    }

    /**
     * Splits the amount into equal parts.
     * Why:    "split the bill three ways" must not lose the odd paise; ₹1.00 into 3 is 34+33+33,
     *         never 33+33+33.
     * What:   delegates to [allocate] with equal weights so there is exactly one remainder
     *         algorithm in this class to get right and to test.
     * Result: [intoParts] amounts that sum **exactly** to this one, each within one paise of the
     *         others, with the remainder going to the earliest parts.
     * Input:  [intoParts] — how many parts; must be positive.
     * Output: a read-only list of [Money], size [intoParts].
     */
    fun split(intoParts: Int): List<Money> {
        require(intoParts > 0) { "Part count must be positive, was $intoParts" }
        return allocate(List(intoParts) { 1 })
    }

    /**
     * Distributes the amount across weighted shares (2:1 rent, category budgets, EMI principal).
     * Why:    proportional shares almost never divide evenly, and the naive `amount * w / total`
     *         per share quietly loses the truncated paise. This uses the largest-remainder
     *         (Hamilton) method: hand out the truncated shares, then give the spare paise to the
     *         shares that were cut by the most. The total is exact **by construction** — the
     *         remainder is fully redistributed, never discarded.
     * What:   truncates each share toward zero, then walks the shares in descending order of the
     *         fraction lost (ties by lowest index) adding one paise — signed, so refunds behave
     *         symmetrically to payments.
     * Result: one amount per weight, summing exactly to this amount; a zero weight gets
     *         [Money.ZERO].
     * Input:  [weights] — non-empty, all non-negative, not all zero.
     * Output: a read-only list of [Money], same size and order as [weights].
     */
    fun allocate(weights: List<Int>): List<Money> {
        require(weights.isNotEmpty()) { "Weights must not be empty" }
        require(weights.all { it >= 0 }) { "Weights must be non-negative, was $weights" }
        val totalWeight = weights.fold(0L) { acc, weight -> Math.addExact(acc, weight.toLong()) }
        require(totalWeight > 0L) { "Weights must not all be zero" }

        // Truncated share + the paise it lost, per weight. Both are exact Long arithmetic.
        val scaled = weights.map { Math.multiplyExact(minor, it.toLong()) }
        val shares = scaled.map { it / totalWeight }.toMutableList()
        val lost = scaled.mapIndexed { index, value -> index to Math.abs(value % totalWeight) }

        // Hand the undistributed paise to the biggest losers first; ties keep list order.
        val biggestLossFirst = compareByDescending<Pair<Int, Long>> { it.second }.thenBy { it.first }
        var remainder = minor - shares.fold(0L) { acc, share -> acc + share }
        val step = if (remainder < 0L) -1L else 1L
        for ((index, _) in lost.sortedWith(biggestLossFirst)) {
            if (remainder == 0L) break
            shares[index] += step
            remainder -= step
        }
        return shares.map(::Money)
    }

    /**
     * Orders amounts by their minor units.
     * Result: the standard [Comparable] contract, so amounts sort, `max()` and compare with `<`.
     * Input:  [other] — the amount to compare against. Output: negative/zero/positive Int.
     */
    override fun compareTo(other: Money): Int = minor.compareTo(other.minor)

    companion object {
        /** Nothing — the identity for [plus] and the safe default for an absent amount. */
        val ZERO: Money = Money(0)

        /** Basis-point denominator (MNY-002): 10 000 bps = 100%. */
        private val BPS_DENOMINATOR: BigDecimal = BigDecimal.valueOf(10_000L)
    }
}

/**
 * Totals a collection of amounts.
 * Why:    `Iterable<Money>.sum()` is not provided by the stdlib (which only sums numeric types),
 *         and every call site writing its own fold is a chance to fold into a `Long` and lose the
 *         overflow check.
 * What:   folds with [Money.plus], so an overflowing total throws instead of wrapping.
 * Result: the exact total; [Money.ZERO] for an empty collection.
 * Input:  the receiver — any iterable of [Money].
 * Output: a single [Money].
 * Changelog: 2026-07-25 — Created for issue 1.2.
 */
fun Iterable<Money>.sum(): Money = fold(Money.ZERO, Money::plus)
