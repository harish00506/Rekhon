package com.aicfo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [NumericGuardrail] — the AI-ARC-004 gate issue 4.5's notification passes through.
 *
 * Why:  a guardrail that passes everything is worse than no guardrail, because the team stops
 *       looking. Most of these tests are therefore *refusals*: the cases that must not get through.
 * What: verified figures, fabricated figures, arithmetic the engine did not do (GRD-003), the
 *       fail-closed default, and the boundary of what the extractor sees.
 * Result: the gate provably rejects, not merely provably accepts.
 * Changelog: 2026-08-13 — Created for issue 4.5 (FR-BUD-004).
 */
class NumericGuardrailTest {
    private val budget = Money(1_000_000)
    private val spent = Money(1_010_000)

    /** Input: text quoting exactly what the engine produced. Output: asserts it passes. */
    @Test
    fun `text quoting only engine figures passes`() {
        val text = "You have spent ₹10,100.00 of your ₹10,000.00 Groceries budget — 101% of the plan."

        assertEquals(
            GuardrailResult.Pass,
            NumericGuardrail.verify(text, listOf(spent, budget), listOf(101)),
        )
    }

    /** Input: text with no figures at all. Output: asserts it passes — there is nothing to fabricate. */
    @Test
    fun `text with no figures passes`() {
        assertEquals(GuardrailResult.Pass, NumericGuardrail.verify("Your Groceries budget needs a look."))
    }

    /**
     * Input:  a plausible amount the engine never produced.
     * Output: asserts refusal, and that the offending span is named. This is the whole point: the
     *         number is well-formed, correctly grouped, and completely invented.
     */
    @Test
    fun `a fabricated amount is refused and named`() {
        val result = NumericGuardrail.verify("You have spent ₹12,345.67 this month.", listOf(spent, budget))

        assertEquals(GuardrailResult.Unverifiable(listOf("₹12,345.67")), result)
    }

    /**
     * Input:  a total the engine did not compute (GRD-003).
     * Output: asserts refusal. ₹10,000 × 12 really is ₹1,20,000, and that is exactly why this test
     *         exists — a figure being *arithmetically true* is not the standard. Composition of
     *         numbers is the engines' job (P-03), so a correct sum the engine never returned is as
     *         unverifiable as a wrong one.
     */
    @Test
    fun `arithmetic the engine did not perform is refused even when it is correct`() {
        val result =
            NumericGuardrail.verify(
                "At ₹10,000.00 a month that is ₹1,20,000.00 a year.",
                listOf(budget),
            )

        assertEquals(GuardrailResult.Unverifiable(listOf("₹1,20,000.00")), result)
    }

    /**
     * Input:  a percentage the engine did not produce.
     * Output: asserts refusal. 80 and 100 are the bands the app may quote; 85 is a number someone
     *         wrote into a string.
     */
    @Test
    fun `a percentage outside the allowed set is refused`() {
        val result = NumericGuardrail.verify("You are 85% through your budget.", allowedPercents = listOf(80, 100))

        assertEquals(GuardrailResult.Unverifiable(listOf("85%")), result)
    }

    /**
     * Input:  correct figures, but no allowed values passed.
     * Output: asserts refusal. Fail-closed is the design: a caller that forgets to declare its
     *         values gets silence, not a pass. The alternative fails open on exactly the code path
     *         nobody remembered to finish.
     */
    @Test
    fun `an empty allowed set verifies nothing`() {
        val result = NumericGuardrail.verify("You have spent ₹10,100.00.")

        assertTrue(result is GuardrailResult.Unverifiable)
    }

    /**
     * Input:  the same amount with a space after the rupee sign.
     * Output: asserts it passes. A translator's spacing is not a fabrication, and a gate that
     *         rejected it would be one teams learn to route around.
     */
    @Test
    fun `whitespace inside a figure does not make it unverifiable`() {
        assertEquals(GuardrailResult.Pass, NumericGuardrail.verify("Spent: ₹ 10,000.00", listOf(budget)))
        assertEquals(GuardrailResult.Pass, NumericGuardrail.verify("Used 80 %", allowedPercents = listOf(80)))
    }

    /**
     * Input:  the right amount rendered without its paise, and with Western grouping.
     * Output: asserts both are refused. The allowlist is the formatter's exact output (GRD-002), not
     *         anything that rounds or reads close to it — "₹10,000" for ₹10,000.00 is harmless here
     *         and would not be for an amount whose paise mattered, and the gate cannot tell those
     *         apart. `₹10,000.00` written Western-style is also the tell that some other code path
     *         formatted it, which is itself worth failing on (MNY-001, §5).
     */
    @Test
    fun `a rendering the formatter would not produce is refused`() {
        assertTrue(NumericGuardrail.verify("₹10,000", listOf(budget)) is GuardrailResult.Unverifiable)
        assertTrue(NumericGuardrail.verify("₹100,000.00", listOf(Money(10_000_000))) is GuardrailResult.Unverifiable)
    }

    /**
     * Input:  a lakh-worded amount.
     * Output: asserts refusal, and this is a *documented narrowing* rather than a bug. GRD-002
     *         permits lakh/crore wording as a display transform in general; this verifier's
     *         allowlist is exactly one transform — [MoneyFormatter.format] — because nothing on the
     *         4.5 path emits lakh wording. Widening the allowlist is issue 9.7's, and doing it here
     *         would mean writing a renderer with no caller and trusting it untested.
     */
    @Test
    fun `lakh wording is outside this verifier's allowlist, by design`() {
        val result = NumericGuardrail.verify("You have spent ₹1.2 lakh.", listOf(Money(12_000_000)))

        assertTrue(result is GuardrailResult.Unverifiable)
    }

    /**
     * Input:  text mixing one good figure and two bad ones.
     * Output: asserts every failure is reported, in order — a caller diagnosing this needs the whole
     *         list, not the first thing that broke.
     */
    @Test
    fun `every unverifiable span is reported`() {
        val result =
            NumericGuardrail.verify(
                "₹10,000.00 budgeted, ₹9,999.99 spent, 73% used.",
                listOf(budget),
                listOf(80),
            )

        assertEquals(GuardrailResult.Unverifiable(listOf("₹9,999.99", "73%")), result)
    }

    /**
     * Input:  a negative amount that the engine did produce.
     * Output: asserts it passes. Refunds and negative remainders render with a leading minus, and a
     *         gate that could not see the sign would accept `₹500.00` for a `-₹500.00` result.
     */
    @Test
    fun `a negative amount is verified against its own sign`() {
        val overdrawn = Money(-50_000)

        assertEquals(GuardrailResult.Pass, NumericGuardrail.verify("Remaining: -₹500.00", listOf(overdrawn)))
        assertTrue(NumericGuardrail.verify("Remaining: ₹500.00", listOf(overdrawn)) is GuardrailResult.Unverifiable)
    }
}
