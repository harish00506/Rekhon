package com.aicfo.domain.engines.card

import com.aicfo.core.common.Ok
import com.aicfo.core.model.CreditCard
import com.aicfo.core.model.Money
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.time.LocalDate

/**
 * The frozen billing-cycle gate for [CardEngine] (issue 6.1; §21.5, P-08).
 *
 * Why:  the acceptance criterion asks for "a golden-file test on a billing cycle", and the word
 *       *cycle* is the point. The boundary tests in `CardEngineTest` each check one decision in
 *       isolation; this walks one card day by day from its statement through its due date and out
 *       the other side, asserting the whole answer at each step. A rule that fires a day early or
 *       stops a day late is a diff here, where the same bug is invisible to a test that only ever
 *       asks about one day.
 * What: parses `golden/card.txt` and asserts every field of `status` and `alert` per record.
 * Result: a change to the dates, the ratios or the alert windows cannot land silently.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 *
 * **The fixture is parsed by this test, not by a library.** `:domain:*` has no serialisation
 * dependency by design (ARC-002), and the format is `# key=value` for exactly that reason.
 */
class CardGoldenTest {
    private val engine = CardEngineFactory.create()

    /**
     * Input:  every record in the fixture.
     * Output: asserts the full status and alert set for each, labelled so a failure names the case.
     */
    @Test
    fun `every golden record still holds`() {
        records().forEach { record ->
            val card =
                CreditCard(
                    accountId = "account:golden",
                    creditLimit = Money(record.long("limit")),
                    statementDay = record.int("statement_day"),
                    dueDay = record.int("due_day"),
                    lastStatement = record.longOrNull("last_statement")?.let(::Money),
                    lastStatementIsoDate = record.optional("last_statement")?.let { "2000-01-01" },
                    minimumDue = record.longOrNull("minimum_due")?.let(::Money),
                )
            val today = LocalDate.parse(record.required("today"))
            val outstanding = Money(record.long("outstanding"))
            val label = record.required("label")

            val status = (engine.status(CardStatusInput(card, today, outstanding)) as Ok).value
            assertWithMessage("%s — statement date", label)
                .that(status.cycle.statementDate.toString()).isEqualTo(record.required("expect_statement"))
            assertWithMessage("%s — due date", label)
                .that(status.cycle.dueDate.toString()).isEqualTo(record.required("expect_due"))
            assertWithMessage("%s — days until due", label)
                .that(status.cycle.daysUntilDue).isEqualTo(record.int("expect_days"))
            assertWithMessage("%s — live utilisation bps", label)
                .that(status.live.ratioBps).isEqualTo(record.bpsOrNull("expect_live_bps"))
            assertWithMessage("%s — statement utilisation bps", label)
                .that(status.statement.ratioBps).isEqualTo(record.bpsOrNull("expect_stmt_bps"))
            assertWithMessage("%s — unbilled paise", label)
                .that(status.unbilled.minor).isEqualTo(record.long("expect_unbilled"))
            assertWithMessage("%s — available paise", label)
                .that(status.available.minor).isEqualTo(record.long("expect_available"))

            val alerts = (engine.alert(CardAlertInput(card, today, outstanding)) as Ok).value
            assertWithMessage("%s — alerts", label)
                .that(alerts.map { it.kind.name }).isEqualTo(record.alerts())
        }
    }

    /**
     * Input:  the fixture.
     * Output: asserts it still covers every path the file claims to.
     *
     * Why:    a golden file silently shrinks. Somebody deletes a record while chasing a failure and
     *         the suite stays green with less coverage than its header advertises. This is the
     *         meta-test `BudgetGoldenTest` carries for the same reason.
     */
    @Test
    fun `the fixture still covers the paths it says it does`() {
        val labels = records().map { it.required("label") }

        assertThat(labels).hasSize(EXPECTED_RECORDS)
        assertThat(labels.filter { it.startsWith("cycle walk") }).hasSize(CYCLE_WALK_STEPS)
        listOf("day-31", "next month", "year rollover", "over the limit", "brand-new", "boundary")
            .forEach { path ->
                assertWithMessage("the fixture lost its '%s' record", path)
                    .that(labels.any { path in it }).isTrue()
            }
    }

    // --- fixture parsing --------------------------------------------------------------------

    /** One `# key=value` record. Result: a map. Input: the record's text. */
    private class Record(private val values: Map<String, String>) {
        fun required(key: String): String =
            values[key] ?: error("golden record is missing '$key': ${values["label"] ?: values}")

        fun optional(key: String): String? = values[key]

        fun int(key: String): Int = required(key).toInt()

        fun long(key: String): Long = required(key).toLong()

        fun longOrNull(key: String): Long? = values[key]?.toLong()

        /** `none` means the figure is absent, which is not the same as zero (P-03). */
        fun bpsOrNull(key: String): Int? = required(key).takeIf { it != "none" }?.toInt()

        fun alerts(): List<String> = required("expect_alerts").takeIf { it != "none" }?.split(",") ?: emptyList()
    }

    /**
     * Reads the fixture into records.
     * Result: one [Record] per `===`-separated block. Input: none. Output: the records; fails
     *         loudly if the resource is missing rather than passing against nothing.
     */
    private fun records(): List<Record> {
        val text =
            checkNotNull(javaClass.getResourceAsStream(FIXTURE)) {
                "golden fixture $FIXTURE is missing — this gate would otherwise pass vacuously"
            }.bufferedReader().readText()

        return text.split("\n===")
            .drop(1)
            .map { block ->
                Record(
                    block.lineSequence()
                        .map { it.trim() }
                        .filter { it.startsWith("# ") && "=" in it }
                        .associate { line ->
                            val body = line.removePrefix("# ")
                            body.substringBefore('=') to body.substringAfter('=')
                        },
                )
            }
    }

    private companion object {
        const val FIXTURE = "/golden/card.txt"

        /** Bump deliberately when a record is added, so a deletion cannot pass unnoticed. */
        const val EXPECTED_RECORDS = 15

        /** The day-by-day walk the acceptance criterion asks for. */
        const val CYCLE_WALK_STEPS = 5
    }
}
