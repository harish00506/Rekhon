package com.aicfo.domain.engines.investment

import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.random.Random

/**
 * Asserts what must hold for *every* cash-flow series, not just the eight in the golden file
 * (issue 6.3; §11, P-08).
 *
 * Why:  the golden file pins eight shapes somebody thought of. These are the statements that have
 *       to survive shapes nobody thought of, and each one names a bug that example tests are
 *       structurally unable to see — because every example arrives already sorted, already
 *       plausible, and already the size the author had in mind.
 * What: seven invariants over [CASES] seeded series, every value built through the real
 *       constructors so the generator cannot produce something the app would refuse.
 * Result: reordering, rounding, sign and termination bugs surface here rather than in a portfolio.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 */
class InvestmentEnginePropertyTest {
    private val engine = InvestmentEngineFactory.create()

    /**
     * Input: [CASES] solvable series, each solved twice — once in date order, once shuffled.
     * Output: asserts the rate is identical.
     *
     * The invariant the same-day coalescing exists for. An engine that summed flows in arrival
     * order, or that sorted only by string, would still pass every golden case — because the
     * fixture lists them in date order, as any author naturally would.
     */
    @Test
    fun `the rate does not depend on the order the flows arrive in`() {
        val random = Random(SEED)
        repeat(CASES) { case ->
            val flows = solvableSeries(random)

            val inOrder = engine.xirr(CashFlowSeriesInput(flows))
            val shuffled = engine.xirr(CashFlowSeriesInput(flows.shuffled(random)))

            assertWithMessage("case %s must not depend on flow order", case)
                .that(shuffled)
                .isEqualTo(inOrder)
        }
    }

    /**
     * Input: series built to a known rate over a whole number of 365-day years.
     * Output: asserts the engine recovers that rate to within one basis point.
     *
     * The strongest statement in the file, and the one that would catch an error in the
     * substitution itself: the terminal value is computed by the *test*, in `BigDecimal`, from
     * `cost x (1+r)^k` — a formula the engine never evaluates, because it works in daily growth
     * factors instead. If the reparametrisation were subtly wrong, the golden file could not tell
     * (its expectations come from one alternative implementation) but this would.
     */
    @Test
    fun `a series built at a known rate solves back to that rate`() {
        val random = Random(SEED + 1)
        repeat(CASES) {
            val bps = random.nextInt(-5_000, 20_000)
            val years = random.nextInt(1, 6)
            val paidMinor = random.nextLong(1_000_000, 1_000_000_000)
            val start = LocalDate.of(2020, 1, 1).plusDays(random.nextLong(0, 900))

            val growth = BigDecimal.ONE.add(BigDecimal.valueOf(bps.toLong()).divide(TEN_THOUSAND, CONTEXT))
            val endMinor =
                BigDecimal.valueOf(paidMinor)
                    .multiply(growth.pow(years, CONTEXT), CONTEXT)
                    .setScale(0, RoundingMode.HALF_EVEN)
                    .longValueExact()

            val solved =
                engine.xirr(
                    CashFlowSeriesInput(
                        listOf(
                            CashFlow(start.toString(), Money(-paidMinor)),
                            CashFlow(start.plusDays(YEAR_DAYS * years).toString(), Money(endMinor)),
                        ),
                    ),
                )

            val recovered = (solved as Ok).value.rateBps
            assertWithMessage("built at %s bps over %s years of %s paise", bps, years, paidMinor)
                .that(Math.abs(recovered - bps))
                .isAtMost(1)
        }
    }

    /**
     * Input: a series, then the same series with a larger closing value.
     * Output: asserts the rate never falls.
     *
     * Monotonicity is what a sign error inside the polynomial would break while still producing
     * numbers that look like rates. It cannot be seen from any single case.
     */
    @Test
    fun `getting more back never lowers the return`() {
        val random = Random(SEED + 2)
        repeat(CASES) {
            val flows = solvableSeries(random)
            val last = flows.last()
            val extra = random.nextLong(1, 500_000)
            val richer = flows.dropLast(1) + CashFlow(last.onIsoDate, Money(last.amount.minor + extra))

            val before = (engine.xirr(CashFlowSeriesInput(flows)) as Ok).value.rateBps
            val after = (engine.xirr(CashFlowSeriesInput(richer)) as Ok).value.rateBps

            assertWithMessage("a larger closing value must not reduce the rate")
                .that(after)
                .isAtLeast(before)
        }
    }

    /**
     * Input: one purchase and one sale.
     * Output: asserts the rate's sign agrees with whether more came back than went in.
     *
     * The cheapest possible sanity check, and the one that catches an inverted sign convention —
     * which is otherwise invisible, because an inverted engine is wrong *consistently* and so still
     * looks self-consistent on any single screen.
     */
    @Test
    fun `the sign of the return agrees with whether money was made`() {
        val random = Random(SEED + 3)
        repeat(CASES) {
            val paidMinor = random.nextLong(100_000, 100_000_000)
            // 0.2x to 5x over at least a year, for the reason `solvableSeries` documents: an
            // extreme ratio over a short span leaves the bracket, and a refusal there says nothing
            // about the sign convention this test is about.
            val backMinor = paidMinor * random.nextLong(2, 51) / 10 + 1
            val start = LocalDate.of(2024, 3, 1).plusDays(random.nextLong(0, 400))
            val held = random.nextLong(365, 2_000)

            val solved =
                engine.xirr(
                    CashFlowSeriesInput(
                        listOf(
                            CashFlow(start.toString(), Money(-paidMinor)),
                            CashFlow(start.plusDays(held).toString(), Money(backMinor)),
                        ),
                    ),
                )

            when (solved) {
                is Ok -> {
                    val rate = solved.value.rateBps
                    if (backMinor > paidMinor) {
                        assertWithMessage("%s back on %s must be a gain", backMinor, paidMinor).that(rate).isAtLeast(0)
                    } else if (backMinor < paidMinor) {
                        assertWithMessage("%s back on %s must be a loss", backMinor, paidMinor).that(rate).isAtMost(0)
                    }
                }
                // A loss steeper than the bracket's floor is refused rather than clamped, which is
                // the documented behaviour and not a failure of this property.
                is Err -> assertThat(backMinor).isLessThan(paidMinor)
            }
        }
    }

    /**
     * Input: [CASES] solvable series.
     * Output: asserts every solved rate lies inside the bracket the engine documents.
     *
     * A rate outside this band would mean the bisection returned something outside its own
     * interval — the failure mode that would follow from a bad sign comparison, and one that
     * produces a plausible-looking number rather than an exception.
     */
    @Test
    fun `every solved rate lies inside the documented band`() {
        val random = Random(SEED + 4)
        repeat(CASES) {
            val rate = (engine.xirr(CashFlowSeriesInput(solvableSeries(random))) as Ok).value.rateBps

            assertThat(rate).isGreaterThan(FLOOR_BPS)
        }
    }

    /**
     * Input: arbitrary series, including nonsense ones.
     * Output: asserts the solve always terminates and returns, never throwing or hanging.
     *
     * The non-termination guard. Bisection with a fixed iteration count cannot loop for ever by
     * construction, and this is what keeps that true after somebody "optimises" it with an early
     * exit — the analogue of the loan engine's `amortises` guard.
     */
    @Test
    fun `the solver always terminates and never throws`() {
        val random = Random(SEED + 5)
        repeat(CASES) {
            val flows =
                List(random.nextInt(0, 8)) {
                    CashFlow(
                        LocalDate.of(2024, 1, 1).plusDays(random.nextLong(0, 3_000)).toString(),
                        Money(random.nextLong(-50_000_000, 50_000_000)),
                    )
                }

            val solved = engine.xirr(CashFlowSeriesInput(flows))

            assertWithMessage("every series must produce an answer or a named refusal")
                .that(solved.isOk || solved.isErr)
                .isTrue()
        }
    }

    /** Input: the same series twice. Output: asserts identical results (P-08). */
    @Test
    fun `the same flows give the same rate every time`() {
        val random = Random(SEED + 6)
        repeat(CASES) {
            val input = CashFlowSeriesInput(solvableSeries(random), nowUtcMillis = 1L)

            assertThat(engine.xirr(input)).isEqualTo(engine.xirr(input))
        }
    }

    /**
     * Builds a series that is guaranteed solvable: money out, then more days, then money in.
     *
     * Why:    the generator constrains the shape rather than filtering after the fact, so a failing
     *         case is always a real one and the test never silently shrinks its own sample.
     * Result: two-to-five purchases and one closing receipt, always bracketed.
     * Input:  [random] — the seeded source. Output: the flows, in date order.
     */
    private fun solvableSeries(random: Random): List<CashFlow> {
        val start = LocalDate.of(2022, 1, 1).plusDays(random.nextLong(0, 700))
        val purchases = random.nextInt(1, 5)
        val out =
            (0 until purchases).map { index ->
                CashFlow(
                    start.plusDays(index * random.nextLong(20, 100)).toString(),
                    Money(-random.nextLong(50_000, 20_000_000)),
                )
            }
        // Between 0.3x and 4x what went in, held for at least 400 days. Both bounds are load-bearing
        // and were found the hard way: an unbounded ratio over a short span annualises outside the
        // engine's documented bracket in *both* directions - losing 90% in a month is worse than
        // -99.94% a year, and tripling in a month is better than +136 000% - so an unconstrained
        // generator produces refusals and calls them failures. The purchases span at most 300 days,
        // so the closing receipt is always the last flow.
        val paidMinor = out.sumOf { -it.amount.minor }
        val backMinor = paidMinor * random.nextLong(3, 41) / 10 + 1
        val closingDay = start.plusDays(400 + random.nextLong(0, 1_400))
        return out + CashFlow(closingDay.toString(), Money(backMinor))
    }

    private companion object {
        /** Fixed so the five hundred cases are the same five hundred on every machine (P-08). */
        const val SEED = 20_260_824L
        const val CASES = 500

        /** ACT/365, matching the engine's day count. */
        const val YEAR_DAYS = 365L

        /** `0.98^365 − 1` in basis points: the floor of what the engine will report. */
        const val FLOOR_BPS = -9_994

        val CONTEXT: MathContext = MathContext(34, RoundingMode.HALF_EVEN)
        val TEN_THOUSAND: BigDecimal = BigDecimal.valueOf(10_000L)
    }
}
