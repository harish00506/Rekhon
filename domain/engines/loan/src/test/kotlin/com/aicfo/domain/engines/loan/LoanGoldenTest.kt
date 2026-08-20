package com.aicfo.domain.engines.loan

import com.aicfo.core.common.Ok
import com.aicfo.core.model.Loan
import com.aicfo.core.model.Money
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The frozen schedule gate for [LoanEngine] (issue 6.2; §21.5, P-08).
 *
 * Why:  the acceptance criterion asks for "a golden-file test vs a known schedule", and *known*
 *       is the operative word — the expectations in `golden/loan.txt` came from an independent
 *       50-digit decimal implementation of the same formula, not from capturing this engine's
 *       output. A golden file regenerated from the code it guards asserts only that the code has
 *       not changed, which is a different and much weaker claim than asserting it is right.
 *
 *       **Every instalment is asserted, not a sample.** The two invariants this issue exists to
 *       hold are properties of the schedule as a whole: a fixture checking rows 1, 120 and 240
 *       would pass while the 237 rows between them drifted.
 * What: parses `golden/loan.txt` and asserts the EMI, the basis, the row count, the total interest,
 *       and every field of every row.
 * Result: a change to the formula, the rounding or the remainder handling cannot land silently.
 * Changelog: 2026-08-19 — Created for issue 6.2.
 *
 * **The fixture is parsed by this test, not by a library.** `:domain:*` has no serialisation
 * dependency by design (ARC-002), which is why the format is `# key=value` and pipe-separated rows.
 */
class LoanGoldenTest {
    private val engine = LoanEngineFactory.create()

    /**
     * Input:  every block in the fixture.
     * Output: asserts the whole schedule for each, labelled so a failure names the loan and the
     *         instalment rather than only the paise that differed.
     */
    @Test
    fun `every golden schedule still holds`() {
        val blocks = blocks()
        assertWithMessage("the fixture must not have been emptied").that(blocks).isNotEmpty()

        blocks.forEach { block ->
            val label = block.header("label")
            val schedule = (engine.schedule(LoanTermsInput(block.loan())) as Ok).value

            assertWithMessage("%s — EMI", label)
                .that(schedule.emi.amount.minor).isEqualTo(block.long("expect_emi"))
            assertWithMessage("%s — EMI basis", label)
                .that(schedule.emi.basis.name).isEqualTo(block.header("expect_basis"))
            assertWithMessage("%s — instalment count", label)
                .that(schedule.rows).hasSize(block.int("expect_rows"))
            assertWithMessage("%s — total interest", label)
                .that(schedule.totalInterest.minor).isEqualTo(block.long("expect_interest"))

            block.rows.zip(schedule.rows).forEach { (expected, actual) ->
                assertWithMessage("%s — instalment %s", label, expected.number)
                    .that(actual.render()).isEqualTo(expected.line)
            }
        }
    }

    /**
     * Input:  the canonical home loan, read back one instalment at a time.
     * Output: asserts [LoanEngine.instalment] agrees with the same row inside the full schedule —
     *         the two walks are separate entry points and a divergence between them would show the
     *         list a different split from the schedule table for the same month.
     */
    @Test
    fun `reading one instalment agrees with the whole schedule`() {
        val block = blocks().first()
        val loan = block.loan()
        val schedule = (engine.schedule(LoanTermsInput(loan)) as Ok).value

        listOf(1, 2, 120, schedule.rows.size).forEach { number ->
            val single = (engine.instalment(LoanInstalmentInput(loan, number)) as Ok).value
            assertWithMessage("instalment %s read alone", number)
                .that(single).isEqualTo(schedule.rows[number - 1])
        }
    }

    /**
     * Renders a row the way the fixture writes one.
     * Why:    comparing one string rather than six fields makes a failure show the whole instalment
     *         side by side, which is how a rounding drift is actually diagnosed.
     * Result: `number|due|amount|principal|interest|closing`.
     * Input:  the receiver. Output: [String].
     */
    private fun AmortisationRow.render(): String =
        listOf(number, dueIsoDate, amount.minor, principal.minor, interest.minor, closingBalance.minor)
            .joinToString("|")

    /**
     * One loan and its expected schedule, as read from the fixture.
     * Input:  [headers] — the `# key=value` lines; [rows] — the instalment lines.
     */
    private class Block(val headers: Map<String, String>, val rows: List<Row>) {
        /** Result: the header's value. Input: [key]. Output: [String]. */
        fun header(key: String): String = requireNotNull(headers[key]) { "golden/loan.txt: missing '$key'" }

        /** Result: the header as paise or a count. Input: [key]. Output: [Long]. */
        fun long(key: String): Long = header(key).toLong()

        /** Result: the header as a count. Input: [key]. Output: [Int]. */
        fun int(key: String): Int = header(key).toInt()

        /**
         * Result: the [Loan] this block describes, run through the real constructor so the fixture
         *         cannot describe a loan the app would refuse.
         * Input:  none. Output: [Loan].
         */
        fun loan(): Loan =
            Loan(
                accountId = "account:golden",
                principal = Money(long("principal")),
                annualRateBps = int("rate_bps"),
                tenureMonths = int("tenure"),
                firstEmiIsoDate = header("first_emi"),
                emiOverride = header("emi_override").takeIf { it != "-" }?.toLong()?.let(::Money),
            )
    }

    /** One expected instalment. Input: [number] — for the failure message; [line] — the raw row. */
    private class Row(val number: String, val line: String)

    /**
     * Reads the fixture.
     * Why:    the prose header is documentation for a human and must not reach the parser, so
     *         everything before the first `# label=` is skipped by starting at the block separator
     *         logic rather than by counting lines — a header someone extends must not break this.
     * Result: one [Block] per `===`-separated section that has a `# label=`.
     * Input:  none. Output: the blocks.
     */
    private fun blocks(): List<Block> =
        checkNotNull(javaClass.getResourceAsStream("/golden/loan.txt")) { "golden/loan.txt is missing" }
            .bufferedReader()
            .readText()
            .split("\n===\n")
            .mapNotNull { section ->
                val lines = section.lines().map { it.trim() }.filter { it.isNotEmpty() }
                val headers =
                    lines.filter { it.startsWith("# ") && "=" in it }
                        .associate { line ->
                            val body = line.removePrefix("# ")
                            body.substringBefore('=') to body.substringAfter('=')
                        }
                if ("label" !in headers) {
                    null
                } else {
                    // `startsWith(digit)`, not merely "contains a pipe": the prose header above the
                    // first block documents the row format on a line that also contains pipes, and
                    // it shares a section with block one. An instalment line always opens with its
                    // number.
                    val rows =
                        lines.filter { it.firstOrNull()?.isDigit() == true && "|" in it }
                            .map { Row(it.substringBefore('|'), it) }
                    Block(headers, rows)
                }
            }

    /**
     * Input:  the fixture's own shape.
     * Output: asserts it still holds the five loans it was written with, so a block deleted in a
     *         merge shows up as a failure rather than as a smaller, still-green test run.
     */
    @Test
    fun `the fixture still holds every loan it was written with`() {
        assertThat(blocks()).hasSize(EXPECTED_BLOCKS)
    }

    private companion object {
        const val EXPECTED_BLOCKS = 5
    }
}
