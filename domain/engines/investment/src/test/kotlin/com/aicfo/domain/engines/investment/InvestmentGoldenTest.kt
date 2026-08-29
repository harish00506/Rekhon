package com.aicfo.domain.engines.investment

import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Freezes the money-weighted return against expectations computed somewhere else (issue 6.3; §11,
 * P-08).
 *
 * Why:  every number in `golden/investment.txt` came from an independent 60-significant-digit
 *       decimal implementation that solves `NPV(r) = Σ cf_i·(1+r)^(−d_i/365) = 0` *directly*, using
 *       exp/ln for the fractional powers. The engine deliberately does something else — it
 *       substitutes `x = (1+r)^(1/365)` and bisects a polynomial in integer powers of `x` — so
 *       agreement between the two is evidence the answer is **right**, not merely unchanged. A
 *       golden file regenerated from the code it guards asserts only that the code has not moved,
 *       which is a much weaker claim and the one this file exists to avoid making.
 * What: eight series — a hand-checkable full year, a loss, a break-even, a twelve-instalment SIP,
 *       two purchases on one day, a sub-year hold, a multi-year case with a sale and a dividend,
 *       and a leap-year span.
 * Result: a change to the bracket, the iteration count, the precision or the day count shows up
 *         here as a diff rather than as a number nobody notices moving.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 */
class InvestmentGoldenTest {
    private val engine = InvestmentEngineFactory.create()

    /** Input: every block in the fixture. Output: asserts each solves to its recorded rate. */
    @Test
    fun `every golden series still solves to the rate an independent implementation found`() {
        val blocks = blocks()
        assertWithMessage("the fixture must not have been emptied").that(blocks).isNotEmpty()

        for (block in blocks) {
            val solved = engine.xirr(CashFlowSeriesInput(block.flows, nowUtcMillis = 1L))

            assertWithMessage("%s must solve", block.case).that(solved).isInstanceOf(Ok::class.java)
            val rate = (solved as Ok).value
            assertWithMessage("%s — annualised basis points", block.case)
                .that(rate.rateBps)
                .isEqualTo(block.expectBps)
            assertWithMessage("%s — ACT span in days", block.case)
                .that(rate.spanDays)
                .isEqualTo(block.spanDays)
        }
    }

    /**
     * Input: the two blocks that must agree.
     * Output: asserts two purchases on one day give exactly what one purchase of their sum gives.
     *
     * The blocks already pin this individually, but only together do they say *why* both numbers
     * are 1000: same-day coalescing. Read separately, a future edit could change one and leave the
     * other looking coincidentally equal.
     */
    @Test
    fun `two purchases on one day equal one purchase of their sum`() {
        val split = block("same-day-buys")
        val single = block("simple-year")

        assertThat(split.expectBps).isEqualTo(single.expectBps)
    }

    /**
     * Input: the fixture.
     * Output: asserts it still holds every case it was written with.
     *
     * Without this, deleting a block is a silent way to make a failing engine pass — the sweep above
     * only ever asserts about the blocks that are actually there.
     */
    @Test
    fun `the fixture still covers the eight series it says it does`() {
        val cases = blocks().map { it.case }

        assertThat(cases).hasSize(EXPECTED_BLOCKS)
        assertThat(cases)
            .containsExactly(
                "simple-year",
                "loss-year",
                "break-even",
                "sip-12",
                "same-day-buys",
                "quarter",
                "sell-and-income",
                "leap-span",
            )
    }

    /**
     * Reads one named block.
     * Result: the block. Input: [case] — its `# case=` name. Output: [Block].
     */
    private fun block(case: String): Block = blocks().single { it.case == case }

    /**
     * Parses the fixture.
     * Why:    by hand, because `:domain:*` is pure Kotlin with no serialisation dependency
     *         (ARC-002) — the same reason `LoanGoldenTest` parses `loan.txt` itself.
     * Result: one [Block] per `===`-separated section, header prose dropped.
     * Input:  none. Output: the blocks.
     */
    private fun blocks(): List<Block> {
        val text =
            checkNotNull(javaClass.getResourceAsStream("/golden/investment.txt")) {
                "golden/investment.txt is missing"
            }.bufferedReader().readText()

        return text.split("\n===\n", "\n===\r\n")
            .drop(1) // the prose header, which explains where the numbers came from
            .map { section ->
                val lines = section.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
                Block(
                    case = header(lines, "case"),
                    expectBps = header(lines, "expect_bps").toInt(),
                    spanDays = header(lines, "span_days").toInt(),
                    flows =
                        lines.filterNot { it.startsWith("#") }.map { row ->
                            val (day, minor) = row.split("|")
                            CashFlow(day, Money(minor.toLong()))
                        },
                )
            }
    }

    /**
     * Reads one `# key=value` header.
     * Result: the value. Input: [lines] — the block's lines; [key]. Output: [String].
     */
    private fun header(
        lines: List<String>,
        key: String,
    ): String = lines.first { it.startsWith("# $key=") }.substringAfter("=").trim()

    /** One fixture case: its name, its expected rate, its span and its flows. */
    private data class Block(
        val case: String,
        val expectBps: Int,
        val spanDays: Int,
        val flows: List<CashFlow>,
    )

    private companion object {
        /** Asserted, so a block cannot quietly disappear. */
        const val EXPECTED_BLOCKS = 8
    }
}
