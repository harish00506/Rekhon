package com.aicfo.domain.engines.emergencyfund

import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The golden-file gate for the emergency-fund engine (issue 7.2; §21.5, and the acceptance criteria).
 *
 * Why:  §21.5 asks engines for "golden-file tests (fixed input snapshot → expected result)", and
 *       this issue's acceptance criteria name one explicitly. What makes it worth having next to
 *       [EmergencyFundEngineTest] is a **different assertion**: every record fixes the status, the
 *       multiplier and the cv as well as the money, so a figure that came out right from the wrong
 *       branch still fails.
 *
 *       That is not hypothetical. A top-up of `₹0.00` is the correct answer for a fully funded fund,
 *       for one in surplus, **and** for one whose essentials are unknown — three different things a
 *       screen has to say differently. A null cv is correct both for "too few months to measure"
 *       and for "three months of no income at all". Only the verdict beside the figure tells those
 *       apart.
 * What: runs every record in `golden/emergencyfund.txt` and asserts all six outputs.
 * Result: a change to the formula fails the build naming the record that caught it.
 * Changelog: 2026-09-02 — Created for issue 7.2.
 *
 * **A gate nobody has watched go red is not a gate.** Before this was trusted, records were
 * deliberately mis-stated — once with a wrong amount and once with a *right* amount beside a wrong
 * status — and this test was confirmed to fail both times. The second is the one that matters, and
 * it is the check `runMigrationsAndValidate` turned out not to be making in issue 7.1.
 */
class EmergencyFundGoldenTest {
    private val engine = EmergencyFundEngineFactory.create()
    private val records: List<GoldenFund> by lazy { loadRecords() }

    /**
     * Input:  the golden file.
     * Output: asserts it was found and still exercises every verdict, every volatility band and
     *         every essentials basis. Without this the assertions below would score nothing
     *         perfectly if the resource ever went missing — which is precisely how a coverage gate
     *         in this repo once passed at 0%.
     */
    @Test
    fun `the golden file is loaded and exercises every verdict`() {
        assertTrue("fewer records than the file is documented to hold", records.size >= MIN_RECORDS)
        assertEquals(
            "the golden file no longer exercises every RULE-EMF-COACH status",
            EmergencyStatus.entries.map { it.name }.toSet(),
            records.map { it.status }.toSet(),
        )
        assertEquals(
            "the golden file no longer exercises every essentials basis",
            EssentialsBasis.entries.map { it.name }.toSet(),
            records.map { it.basis.name }.toSet(),
        )
        assertEquals(
            "the golden file no longer exercises every RULE-EMF-MULT bump",
            setOf(BASE_MONTHS, BASE_MONTHS + MID_BUMP, BASE_MONTHS + HIGH_BUMP),
            records.map { it.months }.toSet(),
        )
        assertTrue("no record has an unmeasurable cv — the null branch is untested", records.any { it.cv == null })
        assertTrue("no record has an unknown runway — the null branch is untested", records.any { it.runway == null })
    }

    /** Input: every record. Output: fails naming each fund whose money came out wrong. */
    @Test
    fun `every fund computes to its expected target and top-up`() {
        val wrong =
            records.mapNotNull { record ->
                val actual = assess(record)
                val got = "${actual.target.minor}/${actual.topUpMonthly.minor}"
                val want = "${record.target}/${record.topUp}"
                if (got == want) null else "${record.label}: expected $want, got $got"
            }

        assertEquals("$wrong", 0, wrong.size)
    }

    /**
     * Input:  every record.
     * Output: fails naming each fund whose verdict, multiplier or volatility reading came out wrong.
     *
     * The assertion this file exists for: the amounts alone cannot distinguish funded from surplus,
     * nor an unmeasured income from a steady one.
     */
    @Test
    fun `every fund computes to its expected cv, multiplier and status`() {
        val wrong =
            records.mapNotNull { record ->
                val actual = assess(record)
                val got = "${actual.incomeCvBps}/${actual.multiplierMonths}/${actual.status}"
                val want = "${record.cv}/${record.months}/${record.status}"
                if (got == want) null else "${record.label}: expected $want, got $got"
            }

        assertEquals("$wrong", 0, wrong.size)
    }

    /** Input: every record. Output: fails naming each fund whose two ratios came out wrong. */
    @Test
    fun `every fund computes to its expected runway and funded ratio`() {
        val wrong =
            records.mapNotNull { record ->
                val actual = assess(record)
                val got = "${actual.runwayMonthsBps}/${actual.fundedRatioBps}"
                val want = "${record.runway}/${record.funded}"
                if (got == want) null else "${record.label}: expected $want, got $got"
            }

        assertEquals("$wrong", 0, wrong.size)
    }

    /**
     * Input:  every record.
     * Output: asserts the shortfall and the top-up never disagree, and that `isFunded` never
     *         disagrees with either.
     *
     * Three fields that can contradict each other are a bug waiting to be shipped; the card shows
     * all three, so they have to mean the same thing.
     */
    @Test
    fun `the shortfall, the top-up and isFunded never disagree`() {
        records.forEach { record ->
            val actual = assess(record)
            assertEquals(
                "${record.label}: a top-up of ${actual.topUpMonthly} beside a shortfall of ${actual.shortfall}",
                actual.shortfall == Money.ZERO,
                actual.topUpMonthly == Money.ZERO,
            )
            // Not the same claim as the line above: UNKNOWN has no shortfall and is not funded
            // either, which is exactly the case a screen must not render as a celebration.
            assertEquals(
                "${record.label}: isFunded=${actual.isFunded} beside a status of ${actual.status}",
                actual.status == EmergencyStatus.FUNDED || actual.status == EmergencyStatus.SURPLUS,
                actual.isFunded,
            )
        }
    }

    /** Result: the assessment for one record. Input: [record]. Output: [EmergencyFundPlan]. */
    private fun assess(record: GoldenFund): EmergencyFundPlan {
        val plan =
            engine.assess(
                EmergencyFundInput(
                    monthlyEssentials = record.essentials?.let { Money(it) },
                    essentialsBasis = record.basis,
                    monthlyIncomes = record.incomes.map { Money(it) },
                    liquidFunds = Money(record.liquid),
                    today = TODAY,
                ),
            )
        return (plan as Ok).value
    }

    /**
     * Reads `golden/emergencyfund.txt`.
     * Why:    a resource rather than a Kotlin list, so a reviewer reads the cases without reading
     *         the test, and so adding one costs no code.
     * Result: the records. Input: none. Output: the parsed list; fails if the resource is missing.
     */
    private fun loadRecords(): List<GoldenFund> {
        val text =
            requireNotNull(javaClass.getResourceAsStream(GOLDEN_PATH)) {
                "missing golden resource $GOLDEN_PATH"
            }.bufferedReader().readText()
        return text.split("===")
            .map { block -> block.lines().filter { it.trimStart().startsWith("#") } }
            .filter { it.isNotEmpty() }
            .map { lines -> GoldenFund.from(lines.associate(::keyValue)) }
    }

    /** Result: one `# key=value` line split. Input: [line]. Output: the pair. */
    private fun keyValue(line: String): Pair<String, String> {
        val body = line.trimStart().removePrefix("#").trim()
        val key = body.substringBefore('=').trim()
        return key to body.substringAfter('=').trim()
    }

    /** One record from the golden file. */
    private data class GoldenFund(
        val label: String,
        val essentials: Long?,
        val basis: EssentialsBasis,
        val incomes: List<Long>,
        val liquid: Long,
        val cv: Int?,
        val months: Int,
        val target: Long,
        val topUp: Long,
        val runway: Int?,
        val funded: Int,
        val status: String,
    ) {
        companion object {
            /**
             * Result: a record. Input: [fields] — the record's key/value pairs.
             * Output: [GoldenFund]; throws naming the missing key rather than defaulting an
             *   expectation, so a typo cannot turn an assertion into a tautology.
             */
            fun from(fields: Map<String, String>): GoldenFund =
                GoldenFund(
                    label = fields.getValue("label"),
                    essentials = fields.getValue("essentials").takeIf { it != NONE }?.toLong(),
                    basis = EssentialsBasis.valueOf(fields.getValue("basis")),
                    incomes =
                        fields.getValue("incomes")
                            .takeIf { it != NONE }
                            ?.split(",")
                            ?.map { it.trim().toLong() }
                            .orEmpty(),
                    liquid = fields.getValue("liquid").toLong(),
                    cv = fields.getValue("expect_cv").takeIf { it != NONE }?.toInt(),
                    months = fields.getValue("expect_months").toInt(),
                    target = fields.getValue("expect_target").toLong(),
                    topUp = fields.getValue("expect_topup").toLong(),
                    runway = fields.getValue("expect_runway").takeIf { it != NONE }?.toInt(),
                    funded = fields.getValue("expect_funded").toInt(),
                    status = fields.getValue("expect_status"),
                )

            /** The file's spelling of an absent value, in five different fields. */
            const val NONE = "none"
        }
    }

    private companion object {
        const val GOLDEN_PATH = "/golden/emergencyfund.txt"

        /** The day every record is reckoned from. One instant, so the file is byte-reproducible. */
        val TODAY: LocalDate = LocalDate.parse("2026-09-02")

        /** The file is documented to cover every status, every band, and the edges between. */
        const val MIN_RECORDS = 16

        /** `RULE-EMF-MULT.base_months`, restated so the coverage assertion reads as arithmetic. */
        const val BASE_MONTHS = 6

        /** `RULE-EMF-MULT.cv_mid_bump`. */
        const val MID_BUMP = 1

        /** `RULE-EMF-MULT.cv_high_bump`. */
        const val HIGH_BUMP = 3
    }
}
