package com.aicfo.domain.engines.healthscore

import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The golden-file gate for AI-FHS (issue 9.4; §21.5, the acceptance criterion "Golden-file test").
 *
 * Why:  the score is a chain of roundings — signal, pillar, total, apportioned contributions — and a
 *       chain like that drifts by a point without any single step looking wrong. Seven cases, from
 *       an established profile to a brand-new one to exact anchors and overspending, are compared
 *       line for line with an **independent** oracle (`golden/health_oracle.py`), which reads the
 *       rulebook itself and works in exact fractions.
 * What: the `expect` line (score, band, lever) and the five `pillar` lines of every case.
 * Result: a change to any weight, anchor or rounding fails naming the case and the line.
 * Changelog: 2026-09-19 — Created for issue 9.4.
 */
class HealthGoldenTest {
    private val blocks: List<List<String>> by lazy { load() }

    @Test
    fun `every case matches the oracle line for line`() {
        assertTrue("the golden file should hold seven cases", blocks.size == CASES)
        blocks.forEach { block ->
            val id = block.first().split(' ')[1]
            val result = score(parse(block.first()))
            assertEquals("$id: expect", block[1], expectLine(result))
            assertEquals("$id: pillars", block.drop(2), result.pillars.map(::pillarLine))
        }
    }

    private fun expectLine(result: HealthScore): String =
        "expect score=${result.score ?: "-"} band=${result.band ?: "-"} " +
            "lever=${result.lever?.let { "${it.signal}:${it.gain}" } ?: "-"}"

    private fun pillarLine(pillar: PillarScore): String {
        val signals =
            pillar.signals.joinToString(",") { "${it.signal}:${it.points}:${it.measureBps}:${it.targetBps}" }
                .ifEmpty { "-" }
        return "pillar ${pillar.pillar} eff=${pillar.effectiveWeightBps} points=${pillar.points ?: "-"} " +
            "contribution=${pillar.contribution} signals=$signals"
    }

    private fun score(input: HealthInput): HealthScore =
        when (val result = HealthScoreEngineFactory.create().score(input)) {
            is Ok -> result.value
            is Err -> throw AssertionError("${result.error}")
        }

    /** Parses one `case` line into the engine's input — the oracle's `fmt_case`, reversed. */
    private fun parse(line: String): HealthInput {
        val fields = line.split(' ').drop(2).associate { it.substringBefore('=') to it.substringAfter('=') }

        fun nums(key: String): List<Long>? = fields.getValue(key).takeIf { it != "-" }?.split('/')?.map(String::toLong)
        return HealthInput(
            runway = nums("runway")?.let { RunwayInput(it[0].toInt(), it[1].toInt()) },
            obligations = nums("obligations")?.let { ObligationInput(Money(it[0]), Money(it[1]), it[2].toInt()) },
            cards = nums("cards")?.let { UtilisationInput(Money(it[0]), Money(it[1])) },
            savings =
                fields.getValue("savings").takeIf { it != "-" }?.let { raw ->
                    SavingsInput(
                        raw.split(';').map { cell ->
                            val (month, income, saved) = cell.split(':')
                            MonthFlow(month, Money(income.toLong()), Money(saved.toLong()))
                        },
                    )
                },
            budgets = nums("budgets")?.let { ShareInput(it[0].toInt(), it[1].toInt()) },
            goals = nums("goals")?.let { ShareInput(it[0].toInt(), it[1].toInt()) },
            nowUtcMillis = 0L,
        )
    }

    private fun load(): List<List<String>> {
        val lines =
            (
                javaClass.classLoader.getResource("golden/health.txt")?.readText()
                    ?: throw AssertionError("golden/health.txt is missing")
            ).lines().filter { it.isNotBlank() && !it.startsWith("#") }
        return lines.chunked(BLOCK)
    }

    private companion object {
        const val CASES = 7
        const val BLOCK = 7 // case, expect, five pillars
    }
}
