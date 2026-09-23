package com.aicfo.domain.engines.guardrail

import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

/**
 * The golden-file gate for AI-GRD (issue 9.7; §21.5).
 *
 * Why:  the gate's worth is that it decides the **same** way every time, and its parts interact:
 *       what the extractor reads decides what the allowlist is asked, and what fails decides where
 *       the ladder lands. Four fixed replies are compared line for line with an **independent**
 *       oracle (`golden/guardrail_oracle.py`), which reads the rulebook and builds the permitted
 *       renderings from Python's own formatting rather than from the app's `MoneyFormatter` — so a
 *       mistake would have to be made twice, in two languages, to go unnoticed.
 * What: a reply that is wholly true; one that rounds and abbreviates but invents nothing; one that
 *       does arithmetic (GRD-003); and one whose attempts are spent.
 * Result: a change to a pattern, a transform or the ladder fails naming the line.
 * Changelog: 2026-09-23 — Created for issue 9.7.
 */
class GuardrailGoldenTest {
    private val golden: List<String> by lazy {
        (
            javaClass.classLoader.getResource("golden/guardrail.txt")?.readText()
                ?: throw AssertionError("golden/guardrail.txt is missing")
        ).lines().filter { it.isNotBlank() && !it.startsWith("#") }
    }

    @Test
    fun `every fixed reply is judged exactly as the oracle judges it`() {
        val actual = SCENARIOS.flatMap(::lines)

        assertEquals(golden, actual)
    }

    /** Result: one line per claim and one for the verdict. Input: [scenario]. Output: the lines. */
    private fun lines(scenario: Scenario): List<String> {
        val input =
            GuardrailInput(
                candidateText = scenario.text,
                evidence = scenario.evidence,
                attemptsMade = scenario.attemptsMade,
            )
        val verdict =
            when (val result = GuardrailEngineFactory.create().verify(input)) {
                is Ok -> result.value
                is Err -> throw AssertionError("${result.error}")
            }
        val claims =
            (verdict.verified + unverifiable(verdict)).sortedBy { it.index }.map {
                "claim ${scenario.name} ${normalise(it.span)} ${it.kind} " +
                    if (it in verdict.verified) "VERIFIED" else "UNVERIFIABLE"
            }
        return claims + "verdict ${scenario.name} ${name(verdict)} ${attemptsLeft(verdict)}"
    }

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

    private fun attemptsLeft(verdict: GuardrailVerdict): Int =
        if (verdict is GuardrailVerdict.Regenerate) verdict.attemptsLeft else 0

    private fun normalise(span: String): String = span.filterNot { it.isWhitespace() }.lowercase(Locale.ROOT)

    /** One fixed reply: what was written, what the engines produced, and how often it was rewritten. */
    private data class Scenario(
        val name: String,
        val text: String,
        val evidence: GuardrailEvidence,
        val attemptsMade: Int = 0,
    )

    private companion object {
        val SCENARIOS =
            listOf(
                Scenario(
                    name = "all_true",
                    text = "Groceries is at 80% of budget: you have spent ₹8,000.00 of ₹10,000.00 across 42 payments.",
                    evidence =
                        GuardrailEvidence(
                            amounts = listOf(Money(8_000_00L), Money(10_000_00L)),
                            percents = listOf(80),
                            counts = listOf(42),
                            names = listOf("Groceries"),
                        ),
                ),
                Scenario(
                    name = "rounded_and_abbreviated",
                    text = "Your fund holds about ₹1,23,457, which is ₹1.5 lakh short of the target by 2027-03-31.",
                    evidence =
                        GuardrailEvidence(
                            amounts = listOf(Money(1_23_456_78L), Money(1_50_000_00L)),
                            dates = listOf(java.time.LocalDate.parse("2027-03-31")),
                        ),
                ),
                Scenario(
                    name = "model_did_arithmetic",
                    text = "At ₹500.00 a month for 12 months that is ₹6,000.00 a year.",
                    evidence = GuardrailEvidence(amounts = listOf(Money(500_00L)), counts = listOf(12)),
                ),
                Scenario(
                    name = "attempts_spent",
                    text = "You saved ₹1,000.00 in March 2027 and 15% more in April 2027.",
                    evidence =
                        GuardrailEvidence(
                            amounts = listOf(Money(1_000_00L)),
                            percents = listOf(15),
                            dates = listOf(java.time.LocalDate.parse("2027-03-01")),
                            quantities = emptyList<BigDecimal>(),
                        ),
                    attemptsMade = 2,
                ),
            )
    }
}
