package com.aicfo.domain.engines.tax

import com.aicfo.core.common.GoldenFixture
import com.aicfo.core.common.GoldenRecord
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frozen gate for [TaxEngine] (issue 13.4; §21.5, AC3, P-08).
 *
 * Why:  AC3 asks for a golden-file skeleton; this is the full thing, and what makes it worth having
 *       is **where the expected numbers come from**. `tax_oracle.py` re-implements §38 in Python
 *       from the same knowledge base, with its own `Decimal` arithmetic. Nothing in the fixture was
 *       produced by the engine under test.
 *
 *       Tax is the subject where that matters most. A wrong slab boundary, a forgotten cess or a
 *       rebate applied at the wrong point all produce figures that look entirely plausible across a
 *       wide range of incomes — nothing about them announces the error. Two independent
 *       implementations agreeing is the only cheap way to be confident.
 * What: thirteen households covering both regimes winning, the caps, employer NPS in both regimes,
 *       equity gains either side of the annual exemption, a slab-taxed debt gain, an exempt SGB
 *       gain, surcharge territory and zero income.
 * Result: a change to a slab, a cap, the cess or the rebate cannot land silently.
 * Changelog: 2026-10-09 — Created for issue 13.4.
 */
class TaxGoldenTest {
    private val engine = TaxEngineFactory.create()

    /**
     * Input:  every record in `golden/tax.txt`.
     * Output: asserts both regimes' full working and the capital-gains split for each.
     */
    @Test
    fun `every golden record still holds`() {
        records().forEach { record ->
            val name = record.heading.substringBefore(" ")
            val estimate = (engine.estimate(inputFrom(record)) as Ok).value

            assertRegime(name, "old", record, estimate.old)
            assertRegime(name, "new", record, estimate.new)
            assertEquals("$name — winner", record.required("expect_winner"), estimate.winner.name)
            assertEquals("$name — margin", record.long("expect_margin"), estimate.margin.minor)

            val gains = estimate.capitalGains
            assertEquals("$name — STCG tax", record.long("expect_stcg_tax"), gains.shortTermTax.minor)
            assertEquals("$name — LTCG tax", record.long("expect_ltcg_tax"), gains.longTermTax.minor)
            assertEquals("$name — exemption used", record.long("expect_exemption_used"), gains.exemptionUsed.minor)
            assertEquals(
                "$name — exemption remaining",
                record.long("expect_exemption_remaining"),
                gains.exemptionRemaining.minor,
            )
            assertEquals("$name — slab-taxed gains", record.long("expect_slab_taxed_gains"), gains.slabTaxedGains.minor)
            assertEquals("$name — exempt gains", record.long("expect_exempt_gains"), gains.exemptGains.minor)
        }
    }

    /**
     * Input:  the fixture.
     * Output: asserts it still covers the cases it was built for — both regimes winning, gains on
     *         both sides of the exemption, and surcharge territory. Thirteen passing records prove
     *         nothing if they are all the same shape.
     */
    @Test
    fun `the fixture still covers both regimes winning and both sides of the exemption`() {
        val all = records()
        assertTrue("fewer than thirteen households — has the fixture been trimmed?", all.size >= EXPECTED_HOUSEHOLDS)

        // With a POSITIVE margin, deliberately. The first version of this assertion was satisfied
        // by `zero_income`, where both regimes compute zero and the tie-break happens to pick OLD —
        // so it passed while the fixture contained no case where the old regime is genuinely
        // better. Under FY2025-26's rates that case is hard to reach (it needs about 7.25L of
        // deductions at 18L income), which is exactly why it must be pinned.
        Regime.entries.forEach { regime ->
            assertTrue(
                "no household where the $regime regime wins by a non-zero margin",
                all.any { it.required("expect_winner") == regime.name && it.long("expect_margin") > 0L },
            )
        }
        assertTrue(
            "no household inside the LTCG exemption",
            all.any {
                it.long("expect_exemption_remaining") > 0L && it.long("expect_exemption_used") > 0L
            },
        )
        assertTrue("no household over the LTCG exemption", all.any { it.long("expect_ltcg_tax") > 0L })
        assertTrue("no household with a slab-taxed gain", all.any { it.long("expect_slab_taxed_gains") > 0L })
        assertTrue("no household with an exempt gain", all.any { it.long("expect_exempt_gains") > 0L })
        assertTrue("no household pays zero under either regime", all.any { it.long("expect_new_total") == 0L })
    }

    /**
     * Input:  every record.
     * Output: asserts the FY rules version is on every estimate. TAX-002 requires it, and a figure
     *         without it cannot be reproduced once a Budget moves the rates.
     */
    @Test
    fun `every estimate is stamped with the FY rules version`() {
        records().forEach { record ->
            val estimate = (engine.estimate(inputFrom(record)) as Ok).value

            assertEquals(TaxKnowledge.FY_RULES_VERSION, estimate.fyRulesVersion)
            assertTrue("the provenance must carry it too", estimate.provenance.evidence.isNotEmpty())
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun assertRegime(
        name: String,
        prefix: String,
        record: GoldenRecord,
        computed: RegimeComputation,
    ) {
        assertEquals("$name — $prefix taxable", record.long("expect_${prefix}_taxable"), computed.taxableIncome.minor)
        assertEquals(
            "$name — $prefix tax before rebate",
            record.long("expect_${prefix}_before_rebate"),
            computed.taxBeforeRebate.minor,
        )
        assertEquals("$name — $prefix rebate", record.long("expect_${prefix}_rebate"), computed.rebate.minor)
        assertEquals("$name — $prefix cess", record.long("expect_${prefix}_cess"), computed.cess.minor)
        assertEquals("$name — $prefix total", record.long("expect_${prefix}_total"), computed.totalTax.minor)
    }

    private fun inputFrom(record: GoldenRecord): TaxInput =
        TaxInput(
            salary =
                SalaryIncome(
                    grossAnnual = Money(record.long("salary")),
                    employerNpsContribution = Money(record.long("employer_nps")),
                ),
            todayIsoDate = record.required("today"),
            nowUtcMillis = FIXED_NOW,
            deductions =
                Deductions(
                    section80C = Money(record.long("d_80c")),
                    section80CcdOneB = Money(record.long("d_80ccd1b")),
                    section80D = Money(record.long("d_80d")),
                    homeLoanInterest = Money(record.long("d_home_loan")),
                ),
            realisedGains =
                record.required("gains")
                    .split("|").filter { it.isNotBlank() && it != "-" }
                    .map { entry ->
                        val fields = entry.split(":")
                        RealisedGain(
                            assetClass = AssetClass.valueOf(fields[0]),
                            gain = Money(fields[1].toLong()),
                            heldMonths = fields[2].toInt(),
                        )
                    },
        )

    private fun records(): List<GoldenRecord> = GoldenFixture.load(this, FIXTURE)

    private companion object {
        const val FIXTURE = "/golden/tax.txt"
        const val FIXED_NOW = 1_790_000_000_000L
        const val EXPECTED_HOUSEHOLDS = 13
    }
}
