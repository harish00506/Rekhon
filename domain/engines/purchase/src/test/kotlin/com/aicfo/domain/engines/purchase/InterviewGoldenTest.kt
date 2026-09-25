package com.aicfo.domain.engines.purchase

import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The golden-file gate for AI-PA-INT (issue 10.2; §21.5).
 *
 * Why:  the ladder and the score interact — which band a wish lands in decides which answers count,
 *       and therefore what it scores. Five fixed wishes are compared line for line with an
 *       **independent** oracle (`golden/interview_oracle.py`), which reads the rulebook and walks
 *       §13.3 itself.
 * What: a casual need; a small want the user already owns something for; a significant wish part
 *       way through; a major one barely started; and a phone on instalments, still wanted a month
 *       later.
 * Result: a change to a band, a delta or an outcome line fails naming the wish.
 * Changelog: 2026-09-26 — Created for issue 10.2.
 */
class InterviewGoldenTest {
    private val golden: List<String> by lazy {
        (
            javaClass.classLoader.getResource("golden/interview.txt")?.readText()
                ?: throw AssertionError("golden/interview.txt is missing")
        ).lines().filter { it.isNotBlank() && !it.startsWith("#") }
    }

    @Test
    fun `every fixed wish is assessed exactly as the oracle assesses it`() {
        val actual = WISHES.map(::line)

        assertEquals(golden, actual)
    }

    private fun line(wish: Wish): String {
        val assessment =
            when (val result = PurchaseInterviewEngineFactory.create().assess(wish.input)) {
                is Ok -> result.value
                is Err -> throw AssertionError("${result.error}")
            }
        return "wish ${wish.name} ${assessment.weight} ask=${assessment.questionsToAsk.size} " +
            "score=${assessment.wantScore} ${assessment.outcome} " +
            "cooloff=${if (assessment.coolingOffRequired) "yes" else "no"}"
    }

    private data class Wish(
        val name: String,
        val input: InterviewInput,
    )

    private companion object {
        val INCOME = Money(1_00_000_00L)

        fun wish(
            name: String,
            price: Long,
            answers: List<InterviewAnswer>,
            method: PaymentMethod = PaymentMethod.CASH,
            emi: Money? = null,
        ) = Wish(
            name,
            InterviewInput(
                price = Money(price),
                method = method,
                monthlyEmi = emi,
                monthlyIncome = INCOME,
                monthlySurplus = Money(28_000_00L),
                answers = answers,
            ),
        )

        val WISHES =
            listOf(
                wish("coffee_grinder", 400_00L, listOf(InterviewAnswer.NeedOrWant(isNeed = true))),
                wish(
                    "second_headphones",
                    1_500_00L,
                    listOf(
                        InterviewAnswer.NeedOrWant(isNeed = false),
                        InterviewAnswer.Uses(InterviewAnswer.UsesPerMonth.LOW),
                        InterviewAnswer.AlreadyOwns(ownsSimilar = true),
                    ),
                ),
                wish(
                    "standing_desk",
                    8_000_00L,
                    listOf(
                        InterviewAnswer.NeedOrWant(isNeed = true),
                        InterviewAnswer.Uses(InterviewAnswer.UsesPerMonth.HIGH),
                        InterviewAnswer.AlreadyOwns(ownsSimilar = false),
                        InterviewAnswer.WaitThirtyDays(somethingBreaks = true),
                    ),
                ),
                wish(
                    "camera_half_asked",
                    20_000_00L,
                    listOf(
                        InterviewAnswer.NeedOrWant(isNeed = false),
                        InterviewAnswer.Uses(InterviewAnswer.UsesPerMonth.MEDIUM),
                    ),
                ),
                wish(
                    "phone_on_emi",
                    45_000_00L,
                    listOf(
                        InterviewAnswer.NeedOrWant(isNeed = true),
                        InterviewAnswer.StillWanted(stillWanted = true),
                    ),
                    method = PaymentMethod.EMI,
                    emi = Money(4_000_00L),
                ),
            )
    }
}
