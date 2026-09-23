package com.aicfo.domain.engines.guardrail

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * AI-GRD's two promises, over many generated replies (issue 9.7; §21.5, AI-ARC-004, P-08).
 *
 * Why:  the gate can fail in two directions and they cost differently. **A false refusal** silences
 *       a correct message — annoying, and the kind of thing a team routes around, which is worse.
 *       **A false pass** puts an invented rupee figure in front of someone who may act on it. Both
 *       are promises about every reply, not about the handful in the example tests, so both are
 *       stated as properties: anything the app itself formatted always survives, and anything no
 *       engine produced never does.
 * What: 300 seeded cases per property — random amounts, random evidence, random attempt counts.
 * Result: a broken promise names its case.
 * Changelog: 2026-09-23 — Created for issue 9.7.
 */
class GuardrailPropertyTest {
    private val engine = GuardrailEngineFactory.create()

    @Test
    fun `an amount the app itself formatted always verifies`() {
        repeat(CASES) { case ->
            val random = Random(case)
            val money = Money(random.nextLong(-9_99_99_999_99L, 9_99_99_999_99L))
            val text = "The figure is ${MoneyFormatter.format(money)} today."

            val verdict = verify(GuardrailInput(text, GuardrailEvidence(amounts = listOf(money))))

            assertTrue("case $case: ${MoneyFormatter.format(money)} was refused", verdict is GuardrailVerdict.Pass)
        }
    }

    @Test
    fun `an amount no engine produced never verifies`() {
        repeat(CASES) { case ->
            val random = Random(case + CASES)
            val produced = Money(random.nextLong(1L, 9_99_99_999_99L))
            // Far enough away that no rounding, lakh/crore wording or paise spelling of the real
            // figure could render as this one.
            val invented = Money(produced.minor + random.nextLong(1_00_00_00_000L, 5_00_00_00_000L))
            val text = "You have ${MoneyFormatter.format(invented)} left."

            val verdict = verify(GuardrailInput(text, GuardrailEvidence(amounts = listOf(produced))))

            assertTrue("case $case: an invented figure passed", verdict !is GuardrailVerdict.Pass)
        }
    }

    @Test
    fun `text with no figure in it is never refused`() {
        repeat(CASES) { case ->
            val random = Random(case)
            val words = List(random.nextInt(1, 12)) { WORDS[random.nextInt(WORDS.size)] }.joinToString(" ")

            assertTrue("case $case: '$words'", verify(GuardrailInput(words)) is GuardrailVerdict.Pass)
        }
    }

    @Test
    fun `the ladder depends only on whether anything failed and how many attempts are spent`() {
        repeat(CASES) { case ->
            val random = Random(case)
            val attemptsMade = random.nextInt(0, 5)
            val rules = GuardrailRules(maxAttempts = random.nextInt(0, 4))
            val truthful = random.nextBoolean()
            val money = Money(random.nextLong(1L, 10_00_000_00L))
            val text =
                if (truthful) {
                    MoneyFormatter.format(
                        money,
                    )
                } else {
                    MoneyFormatter.format(Money(money.minor + 7_77_777L))
                }

            val verdict =
                verify(GuardrailInput(text, GuardrailEvidence(amounts = listOf(money)), attemptsMade, rules = rules))

            val expected =
                when {
                    truthful -> "PASS"
                    rules.maxAttempts - attemptsMade > 0 -> "REGENERATE"
                    else -> "REFUSE"
                }
            assertEquals("case $case", expected, name(verdict))
        }
    }

    @Test
    fun `every claim is classified exactly once, in reading order`() {
        repeat(CASES) { case ->
            val random = Random(case)
            val first = Money(random.nextLong(1L, 10_00_000_00L))
            val second = Money(random.nextLong(1L, 10_00_000_00L))
            val text = "spent ${MoneyFormatter.format(
                first,
            )} of ${MoneyFormatter.format(second)} on ${random.nextInt(1, 999)} days"

            val verdict = verify(GuardrailInput(text, GuardrailEvidence(amounts = listOf(first))))

            val claims = verdict.verified + unverifiable(verdict)
            assertEquals("case $case: a claim was read twice", claims.size, claims.map { it.index }.distinct().size)
            unverifiable(verdict).zipWithNext { a, b -> assertTrue("case $case: out of order", a.index < b.index) }
        }
    }

    @Test
    fun `the same reply is judged the same way twice`() {
        repeat(CASES) { case ->
            val random = Random(case)
            val money = Money(random.nextLong(1L, 10_00_000_00L))
            val input =
                GuardrailInput(
                    "about ${MoneyFormatter.format(money)} and ${random.nextInt(1, 99)} things",
                    GuardrailEvidence(amounts = listOf(money)),
                )

            assertEquals("case $case", verify(input), verify(input))
        }
    }

    private fun verify(input: GuardrailInput): GuardrailVerdict = engine.verify(input).expectOk()

    private fun unverifiable(verdict: GuardrailVerdict): List<GuardrailClaim> =
        when (verdict) {
            is GuardrailVerdict.Pass -> emptyList()
            is GuardrailVerdict.Regenerate -> verdict.unverifiable
            is GuardrailVerdict.Refuse -> verdict.unverifiable
        }

    private fun name(verdict: GuardrailVerdict): String =
        when (verdict) {
            is GuardrailVerdict.Pass -> "PASS"
            is GuardrailVerdict.Regenerate -> "REGENERATE"
            is GuardrailVerdict.Refuse -> "REFUSE"
        }

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val CASES = 300
        val WORDS = listOf("you", "are", "on", "track", "this", "month", "nothing", "needs", "attention", "today")
    }
}
