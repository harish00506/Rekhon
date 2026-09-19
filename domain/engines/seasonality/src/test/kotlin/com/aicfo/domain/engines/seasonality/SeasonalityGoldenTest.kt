package com.aicfo.domain.engines.seasonality

import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * The golden-file gate for AI-SEAS, across twelve seasonal months (issue 9.3; §21.5).
 *
 * Why:  the acceptance criterion asks for a golden file across seasonal months. This fixes thirty
 *       months of five categories — a festival-heavy Shopping, a Dining with gaps, a monsoon-shaped
 *       Transport, a once-a-year Education, a young Utilities — and compares every index and every
 *       factor for September 2026 to August 2027 with an **independent** oracle
 *       (`golden/seasonality_oracle.py`), which reads the knowledge base itself and works in exact
 *       fractions, so the engine and its expectation share no arithmetic.
 * What: sixty indices (raw, shrunk, source, event), twelve factors with their named events.
 * Result: a change to §9.3's arithmetic, the fallbacks or the naming fails naming the line.
 * Changelog: 2026-09-19 — Created for issue 9.3.
 */
class SeasonalityGoldenTest {
    private val golden: List<String> by lazy {
        (
            javaClass.classLoader.getResource("golden/seasonality.txt")?.readText()
                ?: throw AssertionError("golden/seasonality.txt is missing")
        ).lines().filter { it.isNotBlank() && !it.startsWith("#") }
    }

    @Test
    fun `every index matches the oracle`() {
        val result = result()
        val actual =
            result.indices.map {
                "index ${it.month} ${it.categoryId} ${it.rawBps} ${it.indexBps} ${it.source} ${it.eventId ?: "-"}"
            }

        assertEquals("observed ${result.monthsObserved}", golden.first())
        assertEquals(golden.filter { it.startsWith("index ") }, actual)
    }

    @Test
    fun `every factor and its named events match the oracle`() {
        val actual =
            result().factors.map {
                "factor ${it.month} ${it.factorBps} ${it.rising.joined()} ${it.easing.joined()} ${it.fromOwnHistory}"
            }

        assertEquals(golden.filter { it.startsWith("factor ") }, actual)
    }

    private fun List<String>.joined(): String = if (isEmpty()) "-" else joinToString(",")

    private fun result(): SeasonalityResult {
        val input =
            SeasonalityInput(
                history = history(),
                lookback =
                    listOf(
                        CategorySpend("shopping", "Shopping", Money(1_200_000L)),
                        CategorySpend("dining", "Dining", Money(600_000L)),
                        CategorySpend("transport", "Transport", Money(900_000L)),
                        CategorySpend("utilities", "Utilities", Money(450_000L)),
                        CategorySpend(null, null, Money(300_000L)),
                    ),
                lookbackStart = LocalDate.parse("2026-06-21"),
                lookbackEnd = LocalDate.parse("2026-09-18"),
                months = (0L until 12L).map { YearMonth.of(2026, 9).plusMonths(it) },
                nowUtcMillis = 0L,
            )
        return when (val result = SeasonalityEngineFactory.create().index(input)) {
            is Ok -> result.value
            is Err -> throw AssertionError("${result.error}")
        }
    }

    /** The oracle's `history()`, line for line. */
    @Suppress("MagicNumber") // the scenario is the data
    private fun history(): List<CategoryMonthSpend> =
        (0 until 30).flatMap { k ->
            val month = YearMonth.of(2024, 3).plusMonths(k.toLong())
            val m = month.monthValue
            val wobble = (k * 37) % 11
            val shopBase = 400_000L + wobble * 1_000L
            val shop =
                when (m) {
                    10 -> shopBase * 16 / 10
                    11 -> shopBase * 13 / 10
                    else -> shopBase
                }
            val transportBase = 250_000L + wobble * 300L
            val transport = if (m in 6..9) transportBase * 12 / 10 else transportBase
            listOfNotNull(
                row("shopping", "Shopping", month, shop),
                if (k % 3 != 1) row("dining", "Dining", month, 150_000L + wobble * 500L) else null,
                row("transport", "Transport", month, transport),
                if (m == 4) row("education", "Education", month, 3_000_000L) else null,
                if (month >= YearMonth.of(2026, 1)) {
                    row("utilities", "Utilities", month, 180_000L + if (m in 4..5) 40_000L else 0L)
                } else {
                    null
                },
                if (k % 4 == 0) row(null, null, month, 50_000L) else null,
            )
        }

    private fun row(
        id: String?,
        name: String?,
        month: YearMonth,
        paise: Long,
    ) = CategoryMonthSpend(id, name, month, Money(paise))
}
