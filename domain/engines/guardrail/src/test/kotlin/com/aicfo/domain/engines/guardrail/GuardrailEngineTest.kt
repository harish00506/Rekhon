package com.aicfo.domain.engines.guardrail

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * AI-GRD, one claim at a time (issue 9.7; AI-ARC-004, P-03, `ai/chat/guardrail.md`).
 *
 * Why:  this gate is the difference between a CFO and a plausible liar. Every test here is a
 *       sentence a model could write: some are the truth in a reader's words, and must survive, and
 *       some are inventions that sound exactly as confident, and must not. The two failure
 *       directions cost differently — a false refusal loses a correct message, a false pass puts a
 *       fabricated rupee figure on someone's phone — so both are pinned.
 * What: the transforms that are allowed (money rendering, display rounding, lakh/crore, paise, bps
 *       as a percentage, engine dates); the ones that are not, arithmetic above all (GRD-003); the
 *       PASS → REGENERATE → REFUSE ladder; the refusals; provenance; the rules seam.
 * Result: a regression in any of them names itself.
 * Changelog: 2026-09-23 — Created for issue 9.7.
 */
class GuardrailEngineTest {
    private val engine = GuardrailEngineFactory.create()

    // --- the easy directions ------------------------------------------------------------------

    @Test
    fun `text with no figures has nothing to fabricate, and passes`() {
        assertTrue(verdict("You are on track this month.") is GuardrailVerdict.Pass)
    }

    @Test
    fun `an amount the engine produced, written the way the app writes it, passes`() {
        val pass = verdict("You have spent ₹1,23,456.78 this month.", rupees(1_23_456_78L))

        assertTrue(pass is GuardrailVerdict.Pass)
        assertEquals(listOf("₹1,23,456.78"), (pass as GuardrailVerdict.Pass).verified.map { it.span })
    }

    @Test
    fun `an amount no engine produced is not verifiable, however plausible`() {
        val verdict = verdict("You have spent ₹1,23,456.78 this month.", rupees(9_999_00L))

        assertEquals(listOf("₹1,23,456.78"), unverifiable(verdict))
    }

    @Test
    fun `whitespace inside a figure is a reader's habit, not a different number`() {
        assertTrue(verdict("about ₹ 1,000.00", rupees(1_000_00L)) is GuardrailVerdict.Pass)
    }

    @Test
    fun `a negative amount keeps its sign`() {
        assertTrue(verdict("-₹500.00 this week", rupees(-500_00L)) is GuardrailVerdict.Pass)
    }

    // --- the approved display transforms (GRD-002) ---------------------------------------------

    @Test
    fun `the same amount rounded for display is the same amount`() {
        val money = rupees(1_23_456_78L)

        assertTrue("to the rupee", verdict("about ₹1,23,457", money) is GuardrailVerdict.Pass)
        assertTrue("to ten paise", verdict("about ₹1,23,456.8", money) is GuardrailVerdict.Pass)
    }

    @Test
    fun `a whole amount may drop its paise`() {
        assertTrue(verdict("₹5,000", rupees(5_000_00L)) is GuardrailVerdict.Pass)
    }

    @Test
    fun `truncating is not rounding`() {
        // ₹1,23,456.78 to the rupee is ₹1,23,457. "₹1,23,456" is a different, smaller number, and
        // the difference is exactly the kind a reader cannot see and an engine never produced.
        assertEquals(
            listOf("₹1,23,456"),
            unverifiable(verdict("about ₹1,23,456", rupees(1_23_456_78L))),
        )
    }

    @Test
    fun `lakh and crore are Indian for the same figure`() {
        assertTrue(verdict("₹1.5 lakh saved", rupees(1_50_000_00L)) is GuardrailVerdict.Pass)
        assertTrue(
            verdict("₹1.50 lakh saved", rupees(1_50_000_00L)) is GuardrailVerdict.Pass,
        )
        assertTrue(verdict("₹2.5 crore", rupees(2_50_00_000_00L)) is GuardrailVerdict.Pass)
        assertEquals(
            listOf("₹2 lakh"),
            unverifiable(verdict("₹2 lakh saved", rupees(1_50_000_00L))),
        )
    }

    @Test
    fun `minor units are the same amount spelled in paise`() {
        assertTrue(verdict("15000000 paise", rupees(1_50_000_00L)) is GuardrailVerdict.Pass)
    }

    @Test
    fun `a percentage passes as itself, and as the basis points the engine published`() {
        assertTrue(verdict("35% of your limit", percents(35)) is GuardrailVerdict.Pass)
        assertTrue(verdict("35.5% of your limit", bps(3_550)) is GuardrailVerdict.Pass)
        assertTrue(verdict("36% of your limit", bps(3_550)) is GuardrailVerdict.Pass)
        assertEquals(listOf("40%"), unverifiable(verdict("40% of your limit", percents(35))))
    }

    @Test
    fun `a date the engine produced may be written four ways`() {
        val goal = dates("2027-03-31")

        listOf("2027-03-31", "31 Mar 2027", "March 2027", "2027").forEach { written ->
            assertTrue("$written is the same day", verdict("by $written", goal) is GuardrailVerdict.Pass)
        }
    }

    @Test
    fun `a date no engine produced is a promise nobody made`() {
        val goal = dates("2027-03-31")

        assertEquals(listOf("April 2027"), unverifiable(verdict("by April 2027", goal)))
    }

    @Test
    fun `counts, scores and quantities are checked like everything else`() {
        assertTrue(verdict("3 categories", counts(3)) is GuardrailVerdict.Pass)
        assertTrue(
            "a health score is an integer like any other",
            verdict("scored 742", counts(742)) is GuardrailVerdict.Pass,
        )
        assertTrue(
            verdict("3.5 months of runway", quantities("3.5")) is GuardrailVerdict.Pass,
        )
        assertTrue(
            "rounded for display",
            verdict("4 months of runway", quantities("3.5")) is GuardrailVerdict.Pass,
        )
        assertEquals(listOf("12"), unverifiable(verdict("12 categories", counts(3))))
    }

    @Test
    fun `a four-digit figure is checked, which the old subset could not do`() {
        // The limitation `NumericGuardrail` wrote down: a bare number of four digits or more was
        // never read, because years were indistinguishable from counts. Dates are extracted first
        // now, so the rest of the digits can be judged.
        assertEquals(listOf("4382"), unverifiable(verdict("4382 transactions", counts(7))))
    }

    @Test
    fun `a name the caller supplied may contain digits without becoming a claim`() {
        val parking = rupees(1_200_00L).copy(names = listOf("Zone 3 parking"))

        assertTrue(verdict("Zone 3 parking is at ₹1,200.00", parking) is GuardrailVerdict.Pass)
    }

    @Test
    fun `a rendering this app would never produce is not a rendering of anything`() {
        // Western grouping. The allowlist is the formatter's own output, and the formatter groups
        // the Indian way — so this is not "the same figure written differently", it is a figure the
        // app cannot account for (GRD-002).
        assertEquals(
            listOf("₹123,456.78"),
            unverifiable(verdict("₹123,456.78 spent", rupees(1_23_456_78L))),
        )
    }

    @Test
    fun `with no evidence at all, nothing verifies`() {
        // The fail-closed direction: a caller that forgets to pass its values gets a refusal, never
        // a pass.
        assertEquals(listOf("₹10.00", "5"), unverifiable(verdict("₹10.00 over 5 days")))
    }

    @Test
    fun `the digits inside an amount or a percentage are not offered up as counts`() {
        // Without the reading order, the "1" of ₹1,240.00 and the "40" of 40% would come back as
        // counts, and a caller would have to allow them to get its own message through — a rubber
        // stamp for exactly the digits the check exists to police.
        val verdict =
            verdict("₹1,240.00 is 40% of it", rupees(1_240_00L).copy(percents = listOf(40)))

        assertTrue(verdict is GuardrailVerdict.Pass)
    }

    @Test
    fun `striking out a name or an amount neither hides a real claim nor welds two together`() {
        val evidence =
            rupees(1_200_00L).copy(counts = listOf(7), names = listOf("Zone 3 parking"))

        // The count after the name is still read...
        assertTrue(verdict("Zone 3 parking: 7 visits at ₹1,200.00", evidence) is GuardrailVerdict.Pass)
        // ...and the digits either side of a struck-out span are never joined into a new number.
        assertEquals(
            listOf("9"),
            unverifiable(verdict("1 ₹1,200.00 9", rupees(1_200_00L).copy(counts = listOf(1)))),
        )
    }

    // --- what the model may not do (GRD-003) ---------------------------------------------------

    @Test
    fun `the model may not do arithmetic, even correct arithmetic`() {
        val verdict =
            verdict("₹500.00 × 12 = ₹6,000.00 a year", rupees(500_00L).copy(counts = listOf(12)))

        assertEquals("the product is a number no engine published", listOf("₹6,000.00"), unverifiable(verdict))
    }

    @Test
    fun `every unverifiable figure is reported, in the order it was written`() {
        val verdict =
            verdict(
                "You spent ₹900.00 on 4 days, which is 80% of ₹1,000.00.",
                rupees(1_000_00L).copy(counts = listOf(4)),
            )

        assertEquals(listOf("₹900.00", "80%"), unverifiable(verdict))
    }

    // --- the ladder (GRD-005, RULE-GRD-LADDER) -------------------------------------------------

    @Test
    fun `an unverifiable figure is sent back to be written again, while there are attempts left`() {
        val first = verdict("₹900.00", GuardrailEvidence(), attemptsMade = 0)
        val second = verdict("₹900.00", GuardrailEvidence(), attemptsMade = 1)

        assertEquals(2, (first as GuardrailVerdict.Regenerate).attemptsLeft)
        assertEquals(1, (second as GuardrailVerdict.Regenerate).attemptsLeft)
    }

    @Test
    fun `when the attempts run out the reply is refused, and only verified figures survive`() {
        val refused =
            verdict(
                "You spent ₹900.00 of your ₹1,000.00 budget.",
                rupees(1_000_00L),
                attemptsMade = 2,
            )

        assertTrue(refused is GuardrailVerdict.Refuse)
        refused as GuardrailVerdict.Refuse
        assertEquals(listOf("₹900.00"), refused.unverifiable.map { it.span })
        assertEquals(
            "GRD-005: the fallback may repeat these and nothing else",
            listOf(
                "₹1,000.00",
            ),
            refused.verified.map {
                it.span
            },
        )
    }

    @Test
    fun `a verifiable reply passes however many attempts it took`() {
        assertTrue(
            verdict(
                "₹1,000.00",
                rupees(1_000_00L),
                attemptsMade = 2,
            ) is GuardrailVerdict.Pass,
        )
    }

    // --- refusals and provenance ----------------------------------------------------------------

    @Test
    fun `impossible inputs are refused by field`() {
        assertEquals(
            AppError.Validation("guardrail.attemptsMade"),
            error(input("x", GuardrailEvidence(), attemptsMade = -1)),
        )
    }

    @Test
    fun `provenance names the engine and both rules`() {
        val provenance = verdict("nothing to check").provenance

        assertEquals("AI-GRD", provenance.engineId)
        assertEquals("1.0", provenance.engineVersion)
        assertEquals(listOf(GuardrailRules.LADDER, GuardrailRules.TRANSFORMS), provenance.evidence)
        assertEquals(NOW_MILLIS, provenance.computedAtUtcMillis)
    }

    // --- the rules are the rulebook's, not the engine's ------------------------------------------

    @Test
    fun `the transforms are an allowlist a reviewer can narrow`() {
        val amount = rupees(1_50_000_00L)
        val strict = GuardrailRules(allowLakhCroreWords = false, allowRoundedDisplay = false, allowMinorUnits = false)

        assertEquals(listOf("₹1.5 lakh"), unverifiable(verdict("₹1.5 lakh", amount, rules = strict)))
        assertEquals(listOf("15000000"), unverifiable(verdict("15000000 paise", amount, rules = strict)))
        assertEquals(listOf("₹1,50,001"), unverifiable(verdict("₹1,50,001", amount, rules = strict)))
        assertTrue(
            "the plain rendering always verifies",
            verdict("₹1,50,000.00", amount, rules = strict) is GuardrailVerdict.Pass,
        )
    }

    @Test
    fun `an attempt limit of zero refuses immediately`() {
        val verdict = verdict("₹900.00", GuardrailEvidence(), rules = GuardrailRules(maxAttempts = 0))

        assertTrue(verdict is GuardrailVerdict.Refuse)
    }

    @Test
    fun `basis points and bare years can each be switched off`() {
        assertEquals(
            listOf("35.5%"),
            unverifiable(
                verdict(
                    "35.5%",
                    bps(3_550),
                    rules = GuardrailRules(allowBpsAsPercent = false),
                ),
            ),
        )
        assertEquals(
            listOf("2027"),
            unverifiable(
                verdict(
                    "by 2027",
                    dates("2027-03-31"),
                    rules = GuardrailRules(allowYearAlone = false),
                ),
            ),
        )
    }

    // --- fixtures ---------------------------------------------------------------------------------

    /** One evidence list per kind, so a call site stays on one line. Result: the evidence. */
    private fun rupees(vararg minor: Long) = GuardrailEvidence(amounts = minor.map(::Money))

    private fun percents(vararg percents: Int) = GuardrailEvidence(percents = percents.toList())

    private fun bps(vararg bps: Int) = GuardrailEvidence(percentsBps = bps.toList())

    private fun counts(vararg counts: Int) = GuardrailEvidence(counts = counts.toList())

    private fun quantities(vararg quantities: String) = GuardrailEvidence(quantities = quantities.map(::BigDecimal))

    private fun dates(vararg iso: String) = GuardrailEvidence(dates = iso.map(LocalDate::parse))

    private fun input(
        text: String,
        evidence: GuardrailEvidence,
        attemptsMade: Int = 0,
        rules: GuardrailRules = GuardrailRules(),
    ) = GuardrailInput(text, evidence, attemptsMade, NOW_MILLIS, rules)

    private fun verdict(
        text: String,
        evidence: GuardrailEvidence = GuardrailEvidence(),
        attemptsMade: Int = 0,
        rules: GuardrailRules = GuardrailRules(),
    ): GuardrailVerdict = engine.verify(input(text, evidence, attemptsMade, rules)).expectOk()

    private fun unverifiable(verdict: GuardrailVerdict): List<String> =
        when (verdict) {
            is GuardrailVerdict.Pass -> throw AssertionError("expected a refusal, the text passed")
            is GuardrailVerdict.Regenerate -> verdict.unverifiable.map { it.span }
            is GuardrailVerdict.Refuse -> verdict.unverifiable.map { it.span }
        }

    private fun error(input: GuardrailInput): AppError =
        when (val result = engine.verify(input)) {
            is Ok -> throw AssertionError("expected Err, got ${result.value}")
            is Err -> result.error
        }

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val NOW_MILLIS = 1_790_000_000_000L
    }
}
