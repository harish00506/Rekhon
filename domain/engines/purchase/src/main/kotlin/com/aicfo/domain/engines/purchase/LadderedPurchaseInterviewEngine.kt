package com.aicfo.domain.engines.purchase

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money

/**
 * §13.3's interview, as written (issue 10.2; AI-PA-INT, P-02, P-07, P-08, ADR-0050).
 *
 * Why:  three steps, and the order is what makes the result defensible. **Weigh** the purchase
 *       against the money coming in, because that is what decides how much asking it has earned.
 *       **Ask only what is missing**, because re-asking is the nagging §13.3's honesty rule
 *       forbids. **Score the answers the band actually asked for**, so the number can always be
 *       read back as a list of things the user said.
 * What: validate → band → the questions still missing → the score and its evidence → the outcome.
 * Result: an [InterviewAssessment].
 * Changelog: 2026-09-26 — Created for issue 10.2.
 *
 * No clock and no I/O (P-08). `internal` per ARC-003.
 */
internal class LadderedPurchaseInterviewEngine : PurchaseInterviewEngine {
    override fun assess(input: InterviewInput): Result<InterviewAssessment, AppError> {
        validate(input)?.let { return Err(it) }
        val rules = input.rules
        val weight = weigh(input)
        val asked = ladder(weight, rules)
        // The band's questions, plus the re-interview's own: "do you still want it?" is never part
        // of a ladder (§13.3.2 offers it thirty days later), but it counts wherever it is answered.
        val countable = asked + InterviewQuestion.STILL_WANTED
        val counted = input.answers.filter { it.question in countable }
        val deltas =
            counted.map(::delta).map { (question, reason) -> ScoreDelta(question, rules.delta(reason), reason) }
        val score = rules.startScore + deltas.sumOf { it.points }
        return Ok(
            InterviewAssessment(
                weight = weight,
                questionsToAsk = asked.filterNot { question -> counted.any { it.question == question } },
                wantScore = score,
                outcome = outcome(score, rules),
                isComplete = counted.map { it.question }.containsAll(asked),
                deltas = deltas,
                coolingOffRequired = weight == PurchaseWeight.HEAVY,
                coolingOffHours = rules.heavyCoolOffHours,
                provenance = provenance(input),
            ),
        )
    }

    /**
     * What the purchase weighs (§13.3.1).
     * Why:    the price as a share of a month's income, because that is what makes a purchase heavy
     *         for **this** household rather than in the abstract. An instalment is heavy whatever
     *         it costs — borrowing is the weight. With no income recorded there is no share to
     *         take, and the honest fallback is to ask rather than to wave it through.
     * Result: the band. Input: [input]. Output: [PurchaseWeight].
     */
    private fun weigh(input: InterviewInput): PurchaseWeight {
        val rules = input.rules
        val borrowed = input.method == PaymentMethod.EMI && rules.emiIsAlwaysHeavy
        val income = input.monthlyIncome
        return if (borrowed || income <= Money.ZERO) {
            PurchaseWeight.HEAVY
        } else {
            val shareBps = (input.price.minor * BPS) / income.minor
            ORDER.firstOrNull { band -> rules.bandCeilingsBps[band]?.let { shareBps < it } == true }
                ?: PurchaseWeight.HEAVY
        }
    }

    /**
     * The band's questions, in ladder order.
     * Result: the first N of the bank. Input: [weight]; [rules]. Output: `List<InterviewQuestion>`.
     */
    private fun ladder(
        weight: PurchaseWeight,
        rules: InterviewRules,
    ): List<InterviewQuestion> = InterviewQuestion.entries.take(rules.questionsFor(weight))

    /**
     * What one answer is worth, by name.
     * Why:    the *name* is what the rulebook holds and what the screen explains, and [AnswerKeys]
     *         owns it — so a stored answer and a scored answer can never mean different things.
     * Result: the question and the delta's key. Input: [answer]. Output: a pair.
     */
    private fun delta(answer: InterviewAnswer): Pair<InterviewQuestion, String> =
        answer.question to AnswerKeys.of(answer)

    /** Result: §13.3.2's outcome for a score. Input: [score]; [rules]. Output: [InterviewOutcome]. */
    private fun outcome(
        score: Int,
        rules: InterviewRules,
    ): InterviewOutcome =
        when {
            score >= rules.keepMin -> InterviewOutcome.KEEP
            score >= rules.parkMin -> InterviewOutcome.PARK
            else -> InterviewOutcome.SUGGEST_REMOVE
        }

    /**
     * The inputs no interview can be run on.
     * Why:    two answers to one question would make the score depend on list order, which is the
     *         one thing a reproducible score cannot allow (P-08).
     * Result: the first refusal by field, or `null`. Input: [input]. Output: `AppError.Validation?`.
     */
    private fun validate(input: InterviewInput): AppError.Validation? {
        val questions = input.answers.map { it.question }
        return when {
            input.price < Money.ZERO -> AppError.Validation(FIELD_PRICE)
            input.monthlyIncome < Money.ZERO -> AppError.Validation(FIELD_INCOME)
            questions.size != questions.distinct().size -> AppError.Validation(FIELD_ANSWERS)
            else -> null
        }
    }

    /**
     * Provenance (AI-ARC-003): both rules. **No confidence** — a score is the sum of what the user
     * said, not an estimate of anything.
     * Result: the provenance. Input: [input]. Output: [EngineProvenance].
     */
    private fun provenance(input: InterviewInput) =
        EngineProvenance(
            engineId = ENGINE_ID,
            engineVersion = ENGINE_VERSION,
            computedAtUtcMillis = input.nowUtcMillis,
            evidence = listOf(InterviewRules.LADDER, InterviewRules.SCORE),
        )

    private companion object {
        const val ENGINE_ID = "AI-PA-INT"
        const val ENGINE_VERSION = "1.0"
        const val FIELD_PRICE = "interview.price"
        const val FIELD_INCOME = "interview.monthlyIncome"
        const val FIELD_ANSWERS = "interview.answers"
        const val BPS = 10_000L

        /** The bands with a ceiling, lightest first; anything past the last of them is heavy. */
        val ORDER =
            listOf(
                PurchaseWeight.CASUAL,
                PurchaseWeight.SMALL,
                PurchaseWeight.SIGNIFICANT,
                PurchaseWeight.MAJOR,
            )
    }
}
