package com.aicfo.domain.engines.seasonality

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import kotlin.random.Random

/**
 * AI-SEAS's identities, over many generated histories (issue 9.3; §21.5, P-08).
 *
 * Why:  the index and the factor are ratios, and ratios have identities a reader relies on without
 *       checking: shrinkage never overshoots, scaling every rupee changes nothing, a month the same
 *       as its lookback is ×1, a factor is a weighted average and so lies between its parts.
 * What: 200 seeded scenarios per property.
 * Result: an identity broken anywhere in the input space names its case.
 * Changelog: 2026-09-19 — Created for issue 9.3.
 */
class SeasonalityPropertyTest {
    private val engine = SeasonalityEngineFactory.create()

    @Test
    fun `a shrunk index lies between no change and its raw value`() {
        cases { input ->
            engine.index(input).expectOk().indices.forEach {
                val low = minOf(BPS, it.rawBps)
                val high = maxOf(BPS, it.rawBps)
                assertTrue("$it", it.indexBps in low..high)
            }
        }
    }

    @Test
    fun `scaling every amount by the same whole number changes no index and no factor`() {
        cases { input ->
            val scaled =
                input.copy(
                    history = input.history.map { it.copy(amount = Money(it.amount.minor * 3)) },
                    lookback = input.lookback.map { it.copy(amount = Money(it.amount.minor * 3)) },
                )
            val a = engine.index(input).expectOk()
            val b = engine.index(scaled).expectOk()
            assertEquals(a.indices, b.indices)
            assertEquals(a.factors, b.factors)
        }
    }

    @Test
    fun `a factor that moved lies between the smallest and largest of its categories' ratios`() {
        cases { input ->
            val result = engine.index(input).expectOk()
            result.factors.filter { it.factorBps != BPS }.forEach { factor ->
                val bounds = ratioBounds(input, factor.month)
                assertTrue("${factor.month}: ${factor.factorBps} outside $bounds", factor.factorBps in bounds)
            }
        }
    }

    @Test
    fun `a factor is exactly one or at least the minimum effect away from it, and only a moved one names`() {
        cases { input ->
            engine.index(input).expectOk().factors.forEach { factor ->
                val distance = kotlin.math.abs(factor.factorBps - BPS)
                assertTrue("${factor.month}: $distance", distance == 0 || distance >= input.rules.minEffectBps)
                if (distance == 0) {
                    assertTrue(factor.rising.isEmpty() && factor.easing.isEmpty() && !factor.fromOwnHistory)
                }
            }
        }
    }

    @Test
    fun `input order does not matter`() {
        cases { input ->
            val shuffled = input.copy(history = input.history.reversed(), lookback = input.lookback.reversed())
            assertEquals(engine.index(input).expectOk(), engine.index(shuffled).expectOk())
        }
    }

    @Test
    fun `there is one index per category per month and one factor per month, in the order asked`() {
        cases { input ->
            val result = engine.index(input).expectOk()
            val categories =
                (input.history.mapNotNull { it.categoryId } + input.lookback.mapNotNull { it.categoryId }).toSet()
            assertEquals(categories.size * input.months.size, result.indices.size)
            assertEquals(input.months, result.factors.map { it.month })
        }
    }

    @Test
    fun `named events are never both rising and easing, and follow the knowledge base's order`() {
        val order = SeasonalityPriors.events.map { it.id }
        cases { input ->
            engine.index(input).expectOk().factors.forEach { factor ->
                assertTrue(factor.rising.intersect(factor.easing.toSet()).isEmpty())
                assertEquals(factor.rising.sortedBy(order::indexOf), factor.rising)
                assertEquals(factor.easing.sortedBy(order::indexOf), factor.easing)
            }
        }
    }

    @Test
    fun `with no everyday spend every factor is exactly one`() {
        cases { input ->
            assertTrue(engine.index(input.copy(lookback = emptyList())).expectOk().factors.all { it.factorBps == BPS })
        }
    }

    // --- generation -----------------------------------------------------------------------------------

    /**
     * The smallest and largest `index(m) / lookbackIndex` over the weighted categories, widened by
     * one basis point either way for the final rounding, and including ×1 when anything
     * uncategorised is weighted. Recomputed from the result's own indices plus the lookback's.
     */
    private fun ratioBounds(
        input: SeasonalityInput,
        month: YearMonth,
    ): IntRange {
        val lookbackDays =
            generateSequence(input.lookbackStart) {
                it.plusDays(1)
            }.takeWhile { it <= input.lookbackEnd }.toList()
        val allMonths = (lookbackDays.map(YearMonth::from) + month).distinct()
        val full = engine.index(input.copy(months = allMonths)).expectOk().indices
        val ratios =
            input.lookback.filter { it.amount.minor > 0 }.map { spend ->
                val id = spend.categoryId ?: return@map BPS.toDouble()
                val lookbackIndex =
                    lookbackDays.map {
                            day ->
                        full.single { it.categoryId == id && it.month == YearMonth.from(day) }.indexBps
                    }.average()
                // A lookback indexed to zero throughout carries no information; the engine uses ×1.
                if (lookbackIndex == 0.0) return@map BPS.toDouble()
                full.single { it.categoryId == id && it.month == month }.indexBps.toDouble() * BPS / lookbackIndex
            }
        if (ratios.isEmpty()) return BPS..BPS
        return (kotlin.math.floor(ratios.min()).toInt() - 1)..(kotlin.math.ceil(ratios.max()).toInt() + 1)
    }

    private fun cases(check: (SeasonalityInput) -> Unit) {
        repeat(CASES) { case ->
            val input = generate(Random(case.toLong()))
            try {
                check(input)
            } catch (failure: AssertionError) {
                throw AssertionError("case $case: ${failure.message}", failure)
            }
        }
    }

    private fun generate(random: Random): SeasonalityInput {
        val names = listOf("Shopping", "Dining", "Transport", "Education", "Utilities", "Groceries", "Gifts")
        val latest = YearMonth.of(2026, 8)
        val span = random.nextInt(0, 40)
        val categories = names.shuffled(random).take(random.nextInt(1, names.size + 1))
        val history =
            (0 until span).flatMap { back ->
                val month = latest.minusMonths(back.toLong())
                categories.filter { random.nextInt(10) < 7 }.map { name ->
                    CategoryMonthSpend(name.lowercase(), name, month, Money(random.nextLong(0, 50_000_00L)))
                } + uncategorisedMonth(random, month)
            }
        val lookback =
            categories.filter {
                random.nextBoolean()
            }.map { CategorySpend(it.lowercase(), it, Money(random.nextLong(0, 20_000_00L))) } +
                if (random.nextBoolean()) {
                    listOf(CategorySpend(null, null, Money(random.nextLong(0, 5_000_00L))))
                } else {
                    emptyList()
                }
        val end = LocalDate.parse("2026-09-18").minusDays(random.nextLong(0, 200))
        return SeasonalityInput(
            history = history,
            lookback = lookback,
            lookbackStart = end.minusDays(random.nextLong(0, 90)),
            lookbackEnd = end,
            months = (0L until random.nextLong(1, 13)).map { YearMonth.from(end).plusMonths(it) },
            nowUtcMillis = 0L,
        )
    }

    /** Result: one uncategorised row for [month] one time in five, else none. */
    private fun uncategorisedMonth(
        random: Random,
        month: YearMonth,
    ): List<CategoryMonthSpend> =
        if (random.nextInt(5) == 0) listOf(CategoryMonthSpend(null, null, month, Money(100L))) else emptyList()

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val BPS = 10_000
        const val CASES = 200
    }
}
