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

/**
 * AI-SEAS's behaviour, one decision per test (issue 9.3; §9.3, P-02, P-08).
 *
 * Why:  each branch of §9.3 is a place a plausible number can be wrong without looking wrong — a
 *       prior applied at full strength to a week-old install, a monsoon carried into October, a
 *       median of zero divided into — so each is pinned by hand-computed values.
 * What: cold start; the calendar prior and its shrinkage; own history over the prior; the zero
 *       median; the factor's weighting and its lookback division; the named events; refusals;
 *       provenance; the injected rules.
 * Result: a regression in any of them names itself.
 * Changelog: 2026-09-19 — Created for issue 9.3.
 */
class SeasonalityEngineTest {
    private val engine = SeasonalityEngineFactory.create()

    @Test
    fun `with no history and no everyday spend every month is ordinary`() {
        val result = index(history = emptyList(), lookback = emptyList())

        assertEquals(0, result.monthsObserved)
        assertTrue(result.indices.isEmpty())
        assertEquals(List(3) { BPS }, result.factors.map { it.factorBps })
        assertTrue(result.factors.all { it.rising.isEmpty() && it.easing.isEmpty() && !it.fromOwnHistory })
    }

    @Test
    fun `a young profile gets the calendar prior shrunk by the months it has`() {
        // Six observed months, none of them an earlier October: Diwali's 1.38 is the strongest
        // Shopping prior in October (Dussehra and Onam are 1.20) and is shrunk by k = 6/24.
        val result = index(history = months(6, "shopping", "Shopping", 1_000_00L))

        val october = result.indices.single { it.month == OCT && it.categoryId == "shopping" }
        assertEquals(13_800, october.rawBps)
        assertEquals(10_000 + 3_800 * 6 / 24, october.indexBps)
        assertEquals(IndexSource.CALENDAR_PRIOR, october.source)
        assertEquals("diwali", october.eventId)
    }

    @Test
    fun `the user's own earlier years replace the prior once a month has recurred`() {
        // Two years of Shopping at ₹1,000 a month except October at ₹2,000: raw = 2000/1000, k = 1.
        val history =
            (0 until 24).map { back ->
                val month = YearMonth.of(2026, 8).minusMonths(back.toLong())
                spend("shopping", "Shopping", month, if (month.monthValue == 10) 2_000_00L else 1_000_00L)
            }
        val october = index(history = history).indices.single { it.month == OCT }

        assertEquals(20_000, october.rawBps)
        assertEquals(20_000, october.indexBps)
        assertEquals(IndexSource.OWN_HISTORY, october.source)
        assertEquals(null, october.eventId)
    }

    @Test
    fun `one earlier year is shrunk by half, as section 9_3 says`() {
        val history =
            (0 until 12).map { back ->
                val month = YearMonth.of(2026, 8).minusMonths(back.toLong())
                spend("dining", "Dining", month, if (month.monthValue == 10) 3_000_00L else 1_000_00L)
            }

        assertEquals(10_000 + 20_000 / 2, index(history = history).indices.single { it.month == OCT }.indexBps)
    }

    @Test
    fun `more than two years never amplifies an index beyond its raw value`() {
        val history =
            (0 until 36).map { back ->
                val month = YearMonth.of(2026, 8).minusMonths(back.toLong())
                spend("dining", "Dining", month, if (month.monthValue == 10) 1_500_00L else 1_000_00L)
            }

        assertEquals(15_000, index(history = history).indices.single { it.month == OCT }.indexBps)
    }

    @Test
    fun `a category whose typical month is zero falls back to the prior instead of dividing by it`() {
        // School fees: paid once a year, so the median month is zero and a ratio would be infinite.
        val history =
            months(12, "other", "Groceries", 1_000_00L) +
                spend("education", "Education", YearMonth.of(2026, 4), 40_000_00L)
        val april =
            index(history = history, months = listOf(YearMonth.of(2027, 4))).indices
                .single { it.categoryId == "education" }

        assertEquals(IndexSource.CALENDAR_PRIOR, april.source)
        assertEquals("school_admission", april.eventId)
        assertEquals(10_000 + 5_000 * 12 / 24, april.indexBps)
    }

    @Test
    fun `a month the category was never spent in falls back to the calendar instead of predicting nothing`() {
        // Dining two months in three, and never in either October: §9.3's literal ratio is 0 — a
        // forecast of no dining at all. The own index needs the month to have been spent in at least
        // once; otherwise the calendar decides (ADR-0044), which errs toward more spend, the safe side.
        val history =
            (0 until 24).map { back ->
                val month = YearMonth.of(2026, 8).minusMonths(back.toLong())
                spend("dining", "Dining", month, if (month.monthValue == 10) 0L else 1_000_00L)
            }
        val october = index(history = history).indices.single { it.month == OCT }

        assertEquals(IndexSource.CALENDAR_PRIOR, october.source)
        assertEquals("diwali", october.eventId)
        assertEquals(13_800, october.indexBps)
    }

    @Test
    fun `an effect under the rulebook's minimum is noise, neither applied nor named`() {
        // Shopping is 1% of the lookback: Diwali's ~+8% on it moves October by under 10 bps. At the
        // shipped 1% minimum that is noise — the factor is exactly one and nothing is named. With a
        // minimum of 0, the same effect is applied and Diwali is named.
        val lookback =
            listOf(
                CategorySpend("shopping", "Shopping", Money(1_00L)),
                CategorySpend(null, null, Money(99_00L)),
            )
        val history = months(6, "shopping", "Shopping", 1_000_00L)
        val quiet = index(history = history, lookback = lookback).factors.single { it.month == OCT }
        val loud =
            index(history = history, lookback = lookback, rules = SeasonalityRules(minEffectBps = 0))
                .factors.single { it.month == OCT }

        assertEquals(BPS, quiet.factorBps)
        assertTrue(quiet.rising.isEmpty())
        assertTrue(loud.factorBps > BPS)
        assertEquals(listOf("diwali"), loud.rising)
    }

    @Test
    fun `a category the calendar does not know and the user has no year of is ordinary`() {
        // The demo names its category "Dining Out"; the knowledge base says "Dining". Names match
        // exactly (case aside), as they do for the budget suggestion — recorded in ADR-0044.
        val october = index(history = months(6, "dining", "Dining Out", 1_000_00L)).indices.single { it.month == OCT }

        assertEquals(IndexSource.NONE, october.source)
        assertEquals(BPS, october.indexBps)
    }

    @Test
    fun `the factor weights each category by its share of everyday spend`() {
        // Shopping is a quarter of the lookback. No October has been recorded at all — a month with
        // no row is a month that cost nothing, so October is left out of the whole history, not just
        // Shopping's — so Shopping's October is Diwali's prior shrunk by 11/24; its June is its own
        // ordinary month. Groceries and the uncategorised quarter stay at the lookback's pace.
        val history =
            (months(12, "shopping", "Shopping", 1_000_00L) + months(12, "groceries", "Groceries", 1_000_00L))
                .filter { it.month.monthValue != 10 }
        val result =
            index(
                history = history,
                lookback =
                    listOf(
                        CategorySpend("shopping", "Shopping", Money(1_000_00L)),
                        CategorySpend("groceries", "Groceries", Money(2_000_00L)),
                        CategorySpend(null, null, Money(1_000_00L)),
                    ),
                lookbackStart = LocalDate.parse("2026-06-01"),
                lookbackEnd = LocalDate.parse("2026-06-30"),
            )

        val shoppingOct = result.indices.single { it.month == OCT && it.categoryId == "shopping" }.indexBps
        assertEquals(10_000 + 3_800 * 11 / 24, shoppingOct)
        val expected = (1_000_00L * shoppingOct + 3_000_00L * BPS) * BPS / (4_000_00L * BPS)
        assertEquals(expected.toInt(), result.factors.single { it.month == OCT }.factorBps)
        assertEquals(listOf("diwali"), result.factors.single { it.month == OCT }.rising)
    }

    @Test
    fun `a seasonal lookback is divided out, and the season that ended is named`() {
        // Transport over a monsoon lookback (Jun–Aug): the monsoon lifts every lookback day, and
        // October has no Transport event, so October is *below* the lookback's pace.
        val result =
            index(
                history = months(24, "transport", "Transport", 1_000_00L).filter { it.month.monthValue !in 6..9 },
                lookback = listOf(CategorySpend("transport", "Transport", Money(3_000_00L))),
                lookbackStart = LocalDate.parse("2026-06-01"),
                lookbackEnd = LocalDate.parse("2026-08-31"),
            )
        val october = result.factors.single { it.month == OCT }

        assertTrue("October should be cheaper than a monsoon lookback", october.factorBps < BPS)
        assertEquals(listOf("monsoon"), october.easing)
        assertTrue(october.rising.isEmpty())
    }

    @Test
    fun `a month identical to its lookback has a factor of exactly one`() {
        val result =
            index(
                history = months(6, "groceries", "Groceries", 1_000_00L),
                lookback = listOf(CategorySpend("groceries", "Groceries", Money(3_000_00L))),
            )

        assertEquals(List(3) { BPS }, result.factors.map { it.factorBps })
    }

    @Test
    fun `own history marks the factor it moved`() {
        val history =
            (0 until 24).map { back ->
                val month = YearMonth.of(2026, 8).minusMonths(back.toLong())
                spend("groceries", "Groceries", month, if (month.monthValue == 10) 1_200_00L else 1_000_00L)
            }
        val result = index(history = history, lookback = listOf(CategorySpend("groceries", "Groceries", Money(1L))))

        assertTrue(result.factors.single { it.month == OCT }.fromOwnHistory)
        assertEquals(12_000, result.factors.single { it.month == OCT }.factorBps)
    }

    @Test
    fun `every month with any row counts as observed, uncategorised ones too`() {
        val history =
            months(3, "groceries", "Groceries", 1_000_00L) +
                spend(null, null, YearMonth.of(2025, 1), 5_00L)

        assertEquals(4, index(history = history).monthsObserved)
    }

    @Test
    fun `negative spend is refused`() {
        val error = error(input(history = listOf(spend("x", "X", OCT.minusYears(1), -1L))))

        assertEquals(AppError.Validation("seasonality.spend"), error)
        assertEquals(
            AppError.Validation("seasonality.spend"),
            error(input(lookback = listOf(CategorySpend("x", "X", Money(-1L))))),
        )
    }

    @Test
    fun `a lookback that ends before it starts is refused`() {
        val refused = input(lookbackStart = LocalDate.parse("2026-09-02"), lookbackEnd = LocalDate.parse("2026-09-01"))

        assertEquals(AppError.Validation("seasonality.lookback"), error(refused))
    }

    @Test
    fun `provenance names the engine, the rule, the windows and how much history it rests on`() {
        val result = index(history = months(6, "shopping", "Shopping", 1_000_00L))
        val provenance = result.provenance

        assertEquals("AI-SEAS", provenance.engineId)
        assertEquals("1.0", provenance.engineVersion)
        assertEquals(NOW, provenance.computedAtUtcMillis)
        assertEquals(SeasonalityRules.INDEX, provenance.evidence.first())
        assertEquals(6 * BPS / 24, provenance.confidenceBps)
        assertEquals("2026-03..2026-08 · 2026-06-21..2026-09-18 → 2026-09..2026-11", provenance.inputWindow)
    }

    @Test
    fun `the calendar events that moved a factor are cited with the knowledge base's version`() {
        val result =
            index(
                history = months(6, "shopping", "Shopping", 1_000_00L),
                lookback = listOf(CategorySpend("shopping", "Shopping", Money(1_000_00L))),
            )

        assertTrue(
            com.aicfo.core.model.RuleCitation("diwali", SeasonalityPriors.KB_VERSION) in result.provenance.evidence,
        )
    }

    @Test
    fun `the shrinkage denominator is the rulebook's, not the engine's`() {
        val result = index(history = months(6, "shopping", "Shopping", 1_000_00L), rules = SeasonalityRules(12))

        assertEquals(10_000 + 3_800 * 6 / 12, result.indices.single { it.month == OCT }.indexBps)
    }

    @Test
    fun `indices come ordered by month then category, one per category per month`() {
        val result =
            index(history = months(6, "shopping", "Shopping", 1L) + months(6, "dining", "Dining", 1L))

        assertEquals(
            listOf(
                SEP to "dining",
                SEP to "shopping",
                OCT to "dining",
                OCT to "shopping",
                NOV to "dining",
                NOV to "shopping",
            ),
            result.indices.map { it.month to it.categoryId },
        )
    }

    // --- fixtures -----------------------------------------------------------------------------------

    /** Result: [count] closed months of one category at [paise] each, ending August 2026. */
    private fun months(
        count: Int,
        id: String?,
        name: String?,
        paise: Long,
    ) = (0 until count).map { spend(id, name, YearMonth.of(2026, 8).minusMonths(it.toLong()), paise) }

    private fun spend(
        id: String?,
        name: String?,
        month: YearMonth,
        paise: Long,
    ) = CategoryMonthSpend(id, name, month, Money(paise))

    @Suppress("LongParameterList") // one argument per input field a test varies
    private fun input(
        history: List<CategoryMonthSpend> = emptyList(),
        lookback: List<CategorySpend> = emptyList(),
        lookbackStart: LocalDate = LocalDate.parse("2026-06-21"),
        lookbackEnd: LocalDate = LocalDate.parse("2026-09-18"),
        months: List<YearMonth> = listOf(SEP, OCT, NOV),
        rules: SeasonalityRules = SeasonalityRules(),
    ) = SeasonalityInput(history, lookback, lookbackStart, lookbackEnd, months, NOW, rules)

    @Suppress("LongParameterList") // one argument per input field a test varies
    private fun index(
        history: List<CategoryMonthSpend> = emptyList(),
        lookback: List<CategorySpend> = emptyList(),
        lookbackStart: LocalDate = LocalDate.parse("2026-06-21"),
        lookbackEnd: LocalDate = LocalDate.parse("2026-09-18"),
        months: List<YearMonth> = listOf(SEP, OCT, NOV),
        rules: SeasonalityRules = SeasonalityRules(),
    ): SeasonalityResult = engine.index(input(history, lookback, lookbackStart, lookbackEnd, months, rules)).expectOk()

    private fun error(input: SeasonalityInput): AppError =
        when (val result = engine.index(input)) {
            is Ok -> throw AssertionError("expected Err, got ${result.value}")
            is Err -> result.error
        }

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val BPS = 10_000
        const val NOW = 1_789_800_000_000L
        val SEP: YearMonth = YearMonth.of(2026, 9)
        val OCT: YearMonth = YearMonth.of(2026, 10)
        val NOV: YearMonth = YearMonth.of(2026, 11)
    }
}
