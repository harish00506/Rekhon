package com.aicfo.domain.engines.purchase

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI-PA-INT, one question at a time (issue 10.2; SRS §13.3, P-02, P-07).
 *
 * Why:  the interview's whole justification is proportion. Ask a ₹200 wish seven questions and the
 *       user stops using the list; ask a ₹3,00,000 one none and the list never helped. So the
 *       ladder is tested band by band, and so is the promise that **only the missing questions are
 *       asked** — a list that re-asked what it already knew would be the same nagging by another
 *       route (§13.3's honesty rule).
 * What: the five weight bands and their question counts; asking only what is unanswered; the score
 *       and the three outcomes; the evidence behind a removal suggestion; the cooling-off for heavy
 *       purchases; the refusals; provenance; the rules seam.
 * Result: a regression in any of them names itself.
 * Changelog: 2026-09-26 — Created for issue 10.2.
 */
class PurchaseInterviewEngineTest {
    private val engine = PurchaseInterviewEngineFactory.create()

    // --- the ladder (§13.3.1) ---------------------------------------------------------------------

    @Test
    fun `a casual wish is barely asked anything`() {
        // ₹400 against ₹1,00,000 a month is 0.4% — under the casual line.
        val assessment = assess(price = 400_00L)

        assertEquals(PurchaseWeight.CASUAL, assessment.weight)
        assertEquals(1, assessment.questionsToAsk.size)
        assertEquals(InterviewQuestion.NEED_OR_WANT, assessment.questionsToAsk.single())
    }

    @Test
    fun `the ladder climbs with what the purchase weighs`() {
        assertEquals(PurchaseWeight.CASUAL, assess(price = 400_00L).weight)
        assertEquals(PurchaseWeight.SMALL, assess(price = 1_500_00L).weight)
        assertEquals(PurchaseWeight.SIGNIFICANT, assess(price = 8_000_00L).weight)
        assertEquals(PurchaseWeight.MAJOR, assess(price = 20_000_00L).weight)
        assertEquals(PurchaseWeight.HEAVY, assess(price = 40_000_00L).weight)
    }

    @Test
    fun `each band asks the number of questions the ladder allows`() {
        assertEquals(1, assess(price = 400_00L).questionsToAsk.size)
        assertEquals(3, assess(price = 1_500_00L).questionsToAsk.size)
        assertEquals(5, assess(price = 8_000_00L).questionsToAsk.size)
        assertEquals(7, assess(price = 20_000_00L).questionsToAsk.size)
        assertEquals(8, assess(price = 40_000_00L).questionsToAsk.size)
    }

    @Test
    fun `an instalment is heavy whatever it costs`() {
        // §13.3.1's "or any EMI (heavy)": borrowing for it is what makes it heavy, not the price.
        val assessment = assess(price = 1_500_00L, method = PaymentMethod.EMI, monthlyEmi = Money(500_00L))

        assertEquals(PurchaseWeight.HEAVY, assessment.weight)
        assertTrue(assessment.coolingOffRequired)
    }

    @Test
    fun `only a heavy purchase has to wait a day before it can be bought`() {
        assertFalse(assess(price = 20_000_00L).coolingOffRequired)
        assertTrue(assess(price = 40_000_00L).coolingOffRequired)
        assertEquals(24, assess(price = 40_000_00L).coolingOffHours)
    }

    // --- asking only what is missing (§13.3's honesty rule) ----------------------------------------

    @Test
    fun `a question already answered is not asked again`() {
        val answered = assess(price = 8_000_00L, answers = listOf(need(), uses(InterviewAnswer.UsesPerMonth.HIGH)))

        assertFalse(InterviewQuestion.NEED_OR_WANT in answered.questionsToAsk)
        assertFalse(InterviewQuestion.USES_PER_MONTH in answered.questionsToAsk)
        assertEquals(3, answered.questionsToAsk.size)
    }

    @Test
    fun `an interview is complete when the band's questions are all answered`() {
        assertFalse(assess(price = 1_500_00L, answers = listOf(need())).isComplete)

        val complete = assess(price = 1_500_00L, answers = smallBandAnswers())

        assertTrue(complete.isComplete)
        assertTrue(complete.questionsToAsk.isEmpty())
    }

    @Test
    fun `an answer the band never asked for is not counted`() {
        // The ladder is a promise in both directions: a casual wish is scored on its one answer, so
        // volunteering six more cannot push it up the list.
        val casual = assess(price = 400_00L, answers = listOf(need(), uses(InterviewAnswer.UsesPerMonth.HIGH)))

        assertEquals(1, casual.deltas.size)
        assertEquals(InterviewQuestion.NEED_OR_WANT, casual.deltas.single().question)
    }

    // --- the score and the outcomes (§13.3.2) ------------------------------------------------------

    @Test
    fun `an unanswered wish sits in the middle, parked`() {
        val assessment = assess(price = 8_000_00L)

        assertEquals(50, assessment.wantScore)
        assertEquals(InterviewOutcome.PARK, assessment.outcome)
    }

    @Test
    fun `a needed thing used often is kept`() {
        val assessment =
            assess(
                price = 8_000_00L,
                answers = listOf(need(), uses(InterviewAnswer.UsesPerMonth.HIGH), ownsNothing(), waitingBreaks(true)),
            )

        assertEquals(50 + 15 + 15 + 5 + 10, assessment.wantScore)
        assertEquals(InterviewOutcome.KEEP, assessment.outcome)
    }

    @Test
    fun `a want the user rarely uses and already owns is suggested for removal`() {
        val assessment =
            assess(
                price = 8_000_00L,
                answers = listOf(want(), uses(InterviewAnswer.UsesPerMonth.LOW), ownsSimilar(), waitingBreaks(false)),
            )

        assertEquals(50 - 5 - 10 - 10 - 10, assessment.wantScore)
        assertEquals(InterviewOutcome.SUGGEST_REMOVE, assessment.outcome)
    }

    @Test
    fun `a removal suggestion carries the user's own answers as its evidence`() {
        // §13.3.2: "with the user's own answers as evidence". The card says *why*, in the words the
        // user chose, or the suggestion is just the app overruling them.
        val assessment =
            assess(
                price = 8_000_00L,
                answers = listOf(want(), uses(InterviewAnswer.UsesPerMonth.LOW), ownsSimilar()),
            )

        assertEquals(
            listOf(
                InterviewQuestion.NEED_OR_WANT,
                InterviewQuestion.USES_PER_MONTH,
                InterviewQuestion.ALREADY_OWN_SIMILAR,
            ),
            assessment.deltas.map { it.question },
        )
        assertEquals(listOf(-5, -10, -10), assessment.deltas.map { it.points })
    }

    @Test
    fun `no single answer can decide an outcome on its own`() {
        // A score that could be settled by one tap would make the rest of the interview theatre.
        val bestSingle = assess(price = 8_000_00L, answers = listOf(need())).wantScore
        val worstSingle = assess(price = 8_000_00L, answers = listOf(ownsSimilar())).wantScore

        assertTrue("one answer reached KEEP", bestSingle < KEEP_MIN)
        assertTrue("one answer reached SUGGEST_REMOVE", worstSingle >= PARK_MIN)
    }

    @Test
    fun `still wanting it a month later earns the wish points back`() {
        val parked = assess(price = 8_000_00L, answers = listOf(want(), uses(InterviewAnswer.UsesPerMonth.MEDIUM)))
        val persisted =
            assess(
                price = 8_000_00L,
                answers = listOf(want(), uses(InterviewAnswer.UsesPerMonth.MEDIUM), stillWanted()),
            )

        assertEquals(parked.wantScore + 10, persisted.wantScore)
    }

    // --- refusals and provenance -------------------------------------------------------------------

    @Test
    fun `impossible inputs are refused by field`() {
        assertEquals(AppError.Validation("interview.price"), error(input(price = Money(-1L))))
        assertEquals(AppError.Validation("interview.monthlyIncome"), error(input(monthlyIncome = Money(-1L))))
        assertEquals(
            AppError.Validation("interview.answers"),
            error(input(answers = listOf(need(), want()))),
        )
    }

    @Test
    fun `with no income recorded every wish is treated as heavy`() {
        // Weight is the price against income; with no income there is no share to take, and the
        // honest fallback is to ask rather than to wave it through.
        val assessment = assess(price = 400_00L, monthlyIncome = Money.ZERO)

        assertEquals(PurchaseWeight.HEAVY, assessment.weight)
    }

    @Test
    fun `provenance names the engine and both rules`() {
        val provenance = assess(price = 8_000_00L).provenance

        assertEquals("AI-PA-INT", provenance.engineId)
        assertEquals("1.0", provenance.engineVersion)
        assertEquals(NOW_MILLIS, provenance.computedAtUtcMillis)
        assertEquals(listOf(InterviewRules.LADDER, InterviewRules.SCORE), provenance.evidence)
    }

    @Test
    fun `the ladder and the scores are the rulebook's, not the engine's`() {
        val gentle = InterviewRules(questionsPerBand = mapOf(PurchaseWeight.SIGNIFICANT to 2))
        assertEquals(2, assessWith(8_000_00L, gentle).questionsToAsk.size)

        val strict = InterviewRules(keepMin = 40)
        assertEquals(InterviewOutcome.KEEP, assessWith(8_000_00L, strict).outcome)
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private fun need() = InterviewAnswer.NeedOrWant(isNeed = true)

    private fun want() = InterviewAnswer.NeedOrWant(isNeed = false)

    private fun uses(band: InterviewAnswer.UsesPerMonth) = InterviewAnswer.Uses(band)

    private fun ownsSimilar() = InterviewAnswer.AlreadyOwns(ownsSimilar = true)

    private fun ownsNothing() = InterviewAnswer.AlreadyOwns(ownsSimilar = false)

    private fun waitingBreaks(breaks: Boolean) = InterviewAnswer.WaitThirtyDays(somethingBreaks = breaks)

    private fun stillWanted() = InterviewAnswer.StillWanted(stillWanted = true)

    /** Every answer the small band asks for, so an interview can be finished. */
    private fun smallBandAnswers() = listOf(need(), uses(InterviewAnswer.UsesPerMonth.MEDIUM), ownsNothing())

    private fun input(
        price: Money = Money(8_000_00L),
        monthlyIncome: Money = Money(1_00_000_00L),
        method: PaymentMethod = PaymentMethod.CASH,
        monthlyEmi: Money? = null,
        answers: List<InterviewAnswer> = emptyList(),
    ) = InterviewInput(
        price = price,
        method = method,
        monthlyEmi = monthlyEmi,
        monthlyIncome = monthlyIncome,
        monthlySurplus = Money(28_000_00L),
        answers = answers,
        nowUtcMillis = NOW_MILLIS,
    )

    private fun assess(
        price: Long,
        answers: List<InterviewAnswer> = emptyList(),
        method: PaymentMethod = PaymentMethod.CASH,
        monthlyEmi: Money? = null,
        monthlyIncome: Money = Money(1_00_000_00L),
    ): InterviewAssessment = engine.assess(input(Money(price), monthlyIncome, method, monthlyEmi, answers)).expectOk()

    /** The rules seam: the same wish under a rulebook a reviewer has changed. */
    private fun assessWith(
        price: Long,
        rules: InterviewRules,
    ): InterviewAssessment = engine.assess(input(price = Money(price)).copy(rules = rules)).expectOk()

    private fun error(input: InterviewInput): AppError =
        when (val result = engine.assess(input)) {
            is Ok -> throw AssertionError("expected Err, got ${result.value.outcome}")
            is Err -> result.error
        }

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val NOW_MILLIS = 1_790_000_000_000L
        const val KEEP_MIN = 70
        const val PARK_MIN = 40
    }
}
