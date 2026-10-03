package com.aicfo.domain.engines.insurance

import com.aicfo.core.common.GoldenFixture
import com.aicfo.core.common.GoldenRecord
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frozen gate for [ProtectionEngine] (issue 13.3; §21.5, AC2, P-08).
 *
 * Why:  AC2 asks for a golden-file skeleton; this is the full thing, and what makes it worth more
 *       than a skeleton is **where the expected numbers come from**. `protection_oracle.py`
 *       re-implements §39.1 in Python, reading the same `rules-kb.json` rows, with Python's own
 *       `Decimal` arithmetic. Nothing in the fixture was produced by the engine under test, so a
 *       disagreement means one of the two is wrong and neither can be tuned to match the other.
 *       That matters more here than anywhere else in this app: a cover gap is the largest number it
 *       will ever show a person, and thirty years of compounding turns a small arithmetic slip into
 *       a lakh.
 * What: eight households covering both arms of §39.1's `max`, the no-dependents answer, the
 *       over-covered floor, a flagged endowment and ULIP beside a term plan that must not be
 *       flagged, and a zero-cover policy.
 * Result: a change to a multiple, a band, a rounding mode or the comparison's conventions cannot
 *         land silently.
 * Changelog: 2026-10-03 — Created for issue 13.3.
 */
class ProtectionGoldenTest {
    private val engine = ProtectionEngineFactory.create()

    /**
     * Input:  every record in `golden/protection.txt`.
     * Output: asserts the whole assessment for each, labelled so a failure names the household.
     */
    @Test
    fun `every golden record still holds`() {
        records().forEach { record ->
            val name = record.heading.substringBefore(" ")
            val assessment = (engine.assess(inputFrom(record)) as Ok).value

            if (record.optional("expect_term") == "none") {
                assertNull("$name — term cover must not be assessed without dependents", assessment.term)
            } else {
                val term = checkNotNull(assessment.term) { "$name — term must be assessed" }
                assertEquals("$name — term needed", record.long("expect_term_needed"), term.needed.minor)
                assertEquals("$name — term existing", record.long("expect_term_existing"), term.existing.minor)
                assertEquals("$name — term gap", record.long("expect_term_gap"), term.gap.minor)
                assertEquals("$name — term basis", record.required("expect_term_basis"), term.basis.name)
            }

            assertEquals("$name — health needed", record.long("expect_health_needed"), assessment.health.needed.minor)
            assertEquals(
                "$name — health existing",
                record.long("expect_health_existing"),
                assessment.health.existing.minor,
            )
            assertEquals("$name — health gap", record.long("expect_health_gap"), assessment.health.gap.minor)
            assertEquals("$name — flagged", record.required("expect_flagged"), renderFlagged(assessment))
        }
    }

    /**
     * Input:  the fixture.
     * Output: asserts it still covers the cases it was built for. Eight passing records prove
     *         nothing if they are the same household eight times — this is what stops the file
     *         being trimmed to the easy ones.
     */
    @Test
    fun `the fixture still covers both arms, the no-dependents answer and a flagged policy`() {
        val all = records()
        assertTrue("fewer than eight households — has the fixture been trimmed?", all.size >= EXPECTED_HOUSEHOLDS)

        CoverBasis.entries.filter { it != CoverBasis.HEALTH_FLOOR }.forEach { basis ->
            assertTrue(
                "no golden household is assessed on the $basis arm",
                all.any { it.optional("expect_term_basis") == basis.name },
            )
        }
        assertTrue("no household without dependents", all.any { it.optional("expect_term") == "none" })
        assertTrue("no household is over-covered", all.any { it.longOrNull("expect_term_gap") == 0L })
        assertTrue("no household has a flagged policy", all.any { it.required("expect_flagged") != "-" })
        assertTrue(
            "no household holds a term policy alongside a flagged one — the false positive that " +
                "matters most would go untested",
            all.any { it.required("expect_flagged") != "-" && "TERM" in it.required("policies") },
        )
    }

    // --- building the input ------------------------------------------------------------------------

    /** Result: the engine input a record describes. Input: [record]. Output: [ProtectionInput]. */
    private fun inputFrom(record: GoldenRecord): ProtectionInput =
        ProtectionInput(
            household =
                Household(
                    ageYears = record.int("age"),
                    annualIncome = Money(record.long("income")),
                    outstandingLiabilities = Money(record.long("liabilities")),
                    hasDependents = record.boolean("dependents"),
                    isSingleIncome = record.boolean("single_income"),
                    isMetro = record.boolean("metro"),
                ),
            todayIsoDate = TODAY,
            nowUtcMillis = FIXED_NOW,
            policies =
                record.required("policies")
                    .split("|").filter { it.isNotBlank() && it != "-" }
                    .map { entry ->
                        // Indexed rather than destructured: detekt caps a destructuring at three,
                        // and a policy is four fields.
                        val fields = entry.split(":")
                        Policy(
                            id = fields[0],
                            label = fields[0],
                            kind = PolicyKind.valueOf(fields[1]),
                            cover = Money(fields[2].toLong()),
                            annualPremium = Money(fields[3].toLong()),
                        )
                    },
        )

    /** Result: the flagged policies as the oracle renders them, or `-`. Input: [assessment]. */
    private fun renderFlagged(assessment: ProtectionAssessment): String =
        assessment.investmentLinked
            .joinToString("|") {
                "${it.policyId}:${it.premiumPerLakhPaise}:${it.termEquivalentPremium.minor}:" +
                    "${it.annualDifference.minor}:${it.investedValue.minor}:" +
                    "${it.policyValueLow.minor}:${it.policyValueHigh.minor}"
            }
            .ifEmpty { "-" }

    private fun records(): List<GoldenRecord> = GoldenFixture.load(this, FIXTURE)

    private companion object {
        const val FIXTURE = "/golden/protection.txt"

        /** The engine reads no dates today; carried so the input is complete and the gate is honest. */
        const val TODAY = "2026-10-03"

        /** Provenance only. */
        const val FIXED_NOW = 1_790_000_000_000L

        /** The households the oracle ships with; a smaller fixture means cases were dropped. */
        const val EXPECTED_HOUSEHOLDS = 8
    }
}
