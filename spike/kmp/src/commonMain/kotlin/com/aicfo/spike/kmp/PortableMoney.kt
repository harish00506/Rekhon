package com.aicfo.spike.kmp

import kotlin.jvm.JvmInline

/**
 * Issue 13.7's spike: can this project's money arithmetic run on Kotlin/Native? (§27, ADR-0075.)
 *
 * Why:  §33's forward-compatibility table claims "ARC-002 keeps all engines pure Kotlin; only UI
 *       and platform services need porting". Measuring the codebase shows the second half is wrong:
 *       **40 of 139 files in `:core:model` + `:domain:*` import `java.*`**, and the worst of them
 *       is `Money` itself, which uses `BigDecimal` for every rounded division. `BigDecimal` does not
 *       exist in the Kotlin/Native standard library, so **not one of the thirty engines compiles for
 *       iOS today** — they all depend on `Money`.
 *
 *       Porting a leaf engine would have proved nothing while that is true. So this spike ports the
 *       blocker, and answers the only question that matters: *is half-even money arithmetic
 *       expressible in common Kotlin, and does it agree with the shipped one?*
 * What: `Money`'s rounded operations — `percentOf`, `split`, `allocate` — in pure common Kotlin
 *       with no `java.*`, plus the half-even division they all rest on.
 * Result: it is expressible, and `jvmTest` proves it agrees with the real `Money` over seeded
 *       cases. It costs one thing, documented on [percentOf]: a bounded input range, because
 *       there is no 128-bit integer in common Kotlin to stand in for `BigDecimal`.
 * Changelog: 2026-10-10 — Created for issue 13.7.
 *
 * **This is a spike and is not shipped.** Nothing depends on `:spike:kmp`. It is a measurement with
 * a compiler behind it, kept so the next person does not have to redo it.
 */
@JvmInline
value class PortableMoney(val minor: Long) : Comparable<PortableMoney> {
    /** Result: the sum. Input: [other]. Output: [PortableMoney]. Throws on overflow. */
    operator fun plus(other: PortableMoney): PortableMoney = PortableMoney(addExact(minor, other.minor))

    /** Result: the difference. Input: [other]. Output: [PortableMoney]. */
    operator fun minus(other: PortableMoney): PortableMoney = PortableMoney(addExact(minor, -other.minor))

    /** Result: the amount repeated [count] times. Input: [count]. Output: [PortableMoney]. */
    operator fun times(count: Int): PortableMoney = PortableMoney(multiplyExact(minor, count.toLong()))

    override fun compareTo(other: PortableMoney): Int = minor.compareTo(other.minor)

    /**
     * A rate in basis points applied to this amount, over a number of periods.
     *
     * Why:    the one operation that forced `BigDecimal` in the shipped `Money`. Here it is a single
     *         half-even division instead.
     * Result: the same figure `Money.percentOf` produces, **within a bounded input range**.
     *
     * **The cost of losing `BigDecimal`, stated plainly.** `minor × bps` must not overflow `Long`,
     * so this is exact only while `|minor| <= Long.MAX_VALUE / bps`. At the maximum rate of 10 000
     * bps that is about 9.2 × 10^14 paise — roughly ₹92 billion, far beyond any household figure
     * this app will hold, but **not** the unbounded guarantee `BigDecimal` gives. A production port
     * would either accept this bound explicitly or carry a 128-bit multiply; common Kotlin has no
     * `Int128`, so there is no third option. ADR-0075 records it as the decision an iOS port owes.
     *
     * Input:  [bps] — non-negative basis points (MNY-002); [overPeriods] — positive.
     * Output: [PortableMoney].
     */
    fun percentOf(
        bps: Int,
        overPeriods: Int = 1,
    ): PortableMoney {
        require(bps >= 0) { "Rate must be non-negative basis points (MNY-002), was $bps" }
        require(overPeriods > 0) { "A year splits into a positive number of periods, was $overPeriods" }
        val numerator = multiplyExact(minor, bps.toLong())
        val denominator = multiplyExact(BPS_IN_FULL, overPeriods.toLong())
        return PortableMoney(divideHalfEven(numerator, denominator))
    }

    /**
     * Splits the amount into equal parts that sum back exactly.
     * Result: [intoParts] amounts summing to this one, each within one paise of the others, the
     *         remainder going to the earliest parts — the same rule `Money.split` applies.
     * Input:  [intoParts] — positive. Output: the parts.
     */
    fun split(intoParts: Int): List<PortableMoney> {
        require(intoParts > 0) { "An amount splits into a positive number of parts, was $intoParts" }
        return allocate(List(intoParts) { 1 })
    }

    /**
     * Splits the amount by integer weights, losing nothing.
     * Why:    largest-remainder, identical to `Money.allocate`: integer division first, then the
     *         leftover paise handed out biggest-loss-first with ties going to the earliest index,
     *         so the result is deterministic (P-08).
     * Result: one amount per weight, summing **exactly** to this one.
     * Input:  [weights] — non-empty, non-negative, not all zero. Output: the shares.
     */
    fun allocate(weights: List<Int>): List<PortableMoney> {
        require(weights.isNotEmpty()) { "An allocation needs at least one weight" }
        require(weights.all { it >= 0 }) { "Weights are non-negative" }
        val totalWeight = weights.fold(0L) { acc, weight -> addExact(acc, weight.toLong()) }
        require(totalWeight > 0L) { "Weights must not all be zero" }

        val scaled = weights.map { multiplyExact(minor, it.toLong()) }
        val shares = scaled.map { it / totalWeight }.toMutableList()
        var remainder = minor - shares.fold(0L) { acc, share -> acc + share }

        val biggestLossFirst =
            scaled.mapIndexed { index, value -> index to abs(value % totalWeight) }
                .sortedWith(compareByDescending<Pair<Int, Long>> { it.second }.thenBy { it.first })
        val step = if (remainder < 0L) -1L else 1L
        var cursor = 0
        while (remainder != 0L && biggestLossFirst.isNotEmpty()) {
            val index = biggestLossFirst[cursor % biggestLossFirst.size].first
            shares[index] = shares[index] + step
            remainder -= step
            cursor++
        }
        return shares.map { PortableMoney(it) }
    }

    companion object {
        /** Zero rupees. */
        val ZERO: PortableMoney = PortableMoney(0L)

        /** 10 000 bps = 100% (MNY-002). */
        const val BPS_IN_FULL: Long = 10_000L
    }
}

/** Result: the sum of a collection, exactly. */
fun Iterable<PortableMoney>.sum(): PortableMoney = fold(PortableMoney.ZERO, PortableMoney::plus)

/**
 * Integer division rounded half to even — `BigDecimal`'s `HALF_EVEN`, without `BigDecimal`.
 *
 * Why:    every rounded figure in this app uses half-even (MNY-001), and it is the one piece of
 *         `Money` that cannot be expressed as a plain `/`. Kotlin's `/` truncates toward zero, so
 *         the remainder has to be compared against half the divisor by hand, with ties going to the
 *         even quotient — which is what stops a long run of roundings drifting upward the way
 *         half-up does.
 * Result: the quotient, rounded half-even, sign preserved.
 * Input:  [numerator]; [denominator] — non-zero. Output: [Long].
 * Changelog: 2026-10-10 — Created for issue 13.7.
 */
internal fun divideHalfEven(
    numerator: Long,
    denominator: Long,
): Long {
    require(denominator != 0L) { "A division needs a non-zero denominator" }
    require(numerator != Long.MIN_VALUE && denominator != Long.MIN_VALUE) {
        "Long.MIN_VALUE has no positive magnitude, so it cannot be rounded here"
    }

    val negative = (numerator < 0L) != (denominator < 0L)
    val a = abs(numerator)
    val d = abs(denominator)
    val quotient = a / d
    val remainder = a % d
    if (remainder == 0L) return if (negative) -quotient else quotient

    // Compare 2×remainder with the divisor without overflowing: remainder < d, so d - remainder is
    // safe, and comparing remainder against it is the same test as 2×remainder against d.
    val otherHalf = d - remainder
    val rounded =
        when {
            remainder > otherHalf -> quotient + 1L
            remainder < otherHalf -> quotient
            // Exactly half: round to the even quotient.
            quotient % 2L == 0L -> quotient
            else -> quotient + 1L
        }
    return if (negative) -rounded else rounded
}

/** Result: |[value]|. Input: [value], never `Long.MIN_VALUE`. Output: [Long]. */
private fun abs(value: Long): Long = if (value < 0L) -value else value

/** Result: [a] + [b]. Throws on overflow — `Math.addExact` is JVM-only. */
internal fun addExact(
    a: Long,
    b: Long,
): Long {
    val sum = a + b
    // Overflow iff the operands share a sign and the result does not.
    require(((a xor sum) and (b xor sum)) >= 0L) { "long overflow: $a + $b" }
    return sum
}

/** Result: [a] × [b]. Throws on overflow — `Math.multiplyExact` is JVM-only. */
internal fun multiplyExact(
    a: Long,
    b: Long,
): Long {
    if (a == 0L || b == 0L) return 0L
    val product = a * b
    require(product / b == a && !(a == -1L && b == Long.MIN_VALUE) && !(b == -1L && a == Long.MIN_VALUE)) {
        "long overflow: $a × $b"
    }
    return product
}
