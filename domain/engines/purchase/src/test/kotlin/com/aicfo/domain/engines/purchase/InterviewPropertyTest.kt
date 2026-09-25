package com.aicfo.domain.engines.purchase

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * AI-PA-INT's promises, over many generated wishes (issue 10.2; §21.5, §13.3, P-08).
 *
 * Why:  the interview's two promises are about every wish, not the handful in the example tests.
 *       **It never asks twice** — the honesty rule — and **the score is only ever the answers**, so
 *       a removal suggestion can always be read back as a list of things the user said. A third
 *       matters as much: borrowing is always heavy, whatever the price.
 * What: 300 seeded wishes per property.
 * Result: a broken promise names its case.
 * Changelog: 2026-09-26 — Created for issue 10.2.
 */
class InterviewPropertyTest {
    private val engine = PurchaseInterviewEngineFactory.create()
    private val rules = InterviewRules()

    @Test
    fun `a question already answered is never asked again`() {
        repeat(CASES) { case ->
            val random = Random(case)
            val answers = answers(random)

            val asked = assess(random, answers).questionsToAsk

            assertTrue("case $case", asked.none { question -> answers.any { it.question == question } })
        }
    }

    @Test
    fun `exactly the band's unanswered questions are asked`() {
        repeat(CASES) { case ->
            val random = Random(case)
            val answers = answers(random)
            val assessment = assess(random, answers)

            val inBand = InterviewQuestion.entries.take(rules.questionsFor(assessment.weight))
            val answeredInBand = inBand.count { question -> answers.any { it.question == question } }
            assertEquals("case $case", inBand.size - answeredInBand, assessment.questionsToAsk.size)
        }
    }

    @Test
    fun `the score is the starting score plus exactly the answers that counted`() {
        repeat(CASES) { case ->
            val random = Random(case)
            val assessment = assess(random, answers(random))

            assertEquals("case $case", rules.startScore + assessment.deltas.sumOf { it.points }, assessment.wantScore)
        }
    }

    @Test
    fun `the outcome follows the score's band, always`() {
        repeat(CASES) { case ->
            val random = Random(case)
            val assessment = assess(random, answers(random))

            val expected =
                when {
                    assessment.wantScore >= rules.keepMin -> InterviewOutcome.KEEP
                    assessment.wantScore >= rules.parkMin -> InterviewOutcome.PARK
                    else -> InterviewOutcome.SUGGEST_REMOVE
                }
            assertEquals("case $case at ${assessment.wantScore}", expected, assessment.outcome)
        }
    }

    @Test
    fun `borrowing is heavy whatever it costs, and so is a wish with no income to judge it against`() {
        repeat(CASES) { case ->
            val random = Random(case)
            val price = Money(random.nextLong(1L, 5_00_000_00L))

            val borrowed =
                engine.assess(
                    InterviewInput(price, PaymentMethod.EMI, Money(1_000_00L), Money(1_00_000_00L)),
                ).expectOk()
            val unknownIncome = engine.assess(InterviewInput(price, monthlyIncome = Money.ZERO)).expectOk()

            assertEquals("case $case", PurchaseWeight.HEAVY, borrowed.weight)
            assertEquals("case $case", PurchaseWeight.HEAVY, unknownIncome.weight)
        }
    }

    @Test
    fun `the same wish is assessed the same way twice`() {
        repeat(CASES) { case ->
            val random = Random(case)
            val answers = answers(random)
            val first = assess(Random(case), answers)
            val second = assess(Random(case), answers)

            assertEquals("case $case", first, second)
        }
    }

    /** Result: a wish somewhere on the ladder. Input: [random]. */
    private fun assess(
        random: Random,
        answers: List<InterviewAnswer>,
    ): InterviewAssessment =
        engine.assess(
            InterviewInput(
                price = Money(random.nextLong(1L, 5_00_000_00L)),
                monthlyIncome = Money(random.nextLong(20_000_00L, 5_00_000_00L)),
                monthlySurplus = Money(random.nextLong(0L, 50_000_00L)),
                answers = answers,
                nowUtcMillis = 0L,
            ),
        ).expectOk()

    /** Result: a random subset of the bank, at most one answer per question. Input: [random]. */
    private fun answers(random: Random): List<InterviewAnswer> =
        buildList {
            if (random.nextBoolean()) add(InterviewAnswer.NeedOrWant(random.nextBoolean()))
            if (random.nextBoolean()) {
                add(InterviewAnswer.Uses(InterviewAnswer.UsesPerMonth.entries[random.nextInt(3)]))
            }
            if (random.nextBoolean()) add(InterviewAnswer.AlreadyOwns(random.nextBoolean()))
            if (random.nextBoolean()) add(InterviewAnswer.WaitThirtyDays(random.nextBoolean()))
            if (random.nextBoolean()) add(InterviewAnswer.GoalDelay(random.nextBoolean()))
            if (random.nextBoolean()) add(InterviewAnswer.TimingFlexible(random.nextBoolean()))
            if (random.nextBoolean()) add(InterviewAnswer.StillWanted(random.nextBoolean()))
        }

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val CASES = 300
    }
}
