package com.aicfo.domain.engines.purchase

import com.aicfo.core.model.Money

/**
 * The stable name of an answer, in both directions (issue 10.2; §13.3.2).
 *
 * Why:  an answer is stored by **name** — `need`, `uses_high`, `owns_similar` — because that name is
 *       also the rulebook's key for what the answer is worth, and the screen's key for the words it
 *       shows. One mapping, owned by the engine that defines the names, so a stored answer and a
 *       scored answer can never mean different things.
 * What: an answer to its key, and a key back to an answer.
 * Result: `interview_answer` rows are readable by anything that knows the engine.
 * Changelog: 2026-09-26 — Created for issue 10.2.
 */
object AnswerKeys {
    /** Result: the stored key for an answer. Input: [answer]. Output: [String]. */
    fun of(answer: InterviewAnswer): String =
        when (answer) {
            is InterviewAnswer.NeedOrWant -> either(answer.isNeed, NEED, WANT)
            is InterviewAnswer.Uses -> usesKey(answer.band)
            is InterviewAnswer.AlreadyOwns -> either(answer.ownsSimilar, OWNS_SIMILAR, OWNS_NOTHING)
            is InterviewAnswer.WaitThirtyDays -> either(answer.somethingBreaks, WAITING_BREAKS, WAITING_FINE)
            is InterviewAnswer.GoalDelay -> either(answer.accepted, GOAL_DELAY_OK, GOAL_DELAY_NO)
            is InterviewAnswer.StillWanted -> either(answer.stillWanted, STILL_WANTED, WANT)
            is InterviewAnswer.TotalCostOfOwnership -> OWNING_COST
            is InterviewAnswer.TimingFlexible -> either(answer.canWait, TIMING_FLEXIBLE, TIMING_FIXED)
            is InterviewAnswer.CoolingOff -> COOLING_OFF
        }

    /** Result: [yes] or [no], by [flag]. Keeps the mapping above one branch per answer type. */
    private fun either(
        flag: Boolean,
        yes: String,
        no: String,
    ): String = if (flag) yes else no

    /** Result: the key for a use band. Input: [band]. */
    private fun usesKey(band: InterviewAnswer.UsesPerMonth): String =
        when (band) {
            InterviewAnswer.UsesPerMonth.HIGH -> USES_HIGH
            InterviewAnswer.UsesPerMonth.MEDIUM -> USES_MEDIUM
            InterviewAnswer.UsesPerMonth.LOW -> USES_LOW
        }

    /**
     * Result: the answer a stored row holds, or `null` for a key this build no longer knows —
     * which is how an old row from a future rulebook is ignored rather than mis-scored.
     * Input:  [question]; [key]; [amount] — carried only by the owning-cost answer. Output: an answer.
     */
    fun toAnswer(
        question: InterviewQuestion,
        key: String,
        amount: Money?,
    ): InterviewAnswer? =
        when (question) {
            InterviewQuestion.NEED_OR_WANT -> InterviewAnswer.NeedOrWant(isNeed = key == NEED)
            InterviewQuestion.USES_PER_MONTH -> uses(key)
            InterviewQuestion.ALREADY_OWN_SIMILAR -> InterviewAnswer.AlreadyOwns(ownsSimilar = key == OWNS_SIMILAR)
            InterviewQuestion.WAIT_THIRTY_DAYS ->
                InterviewAnswer.WaitThirtyDays(somethingBreaks = key == WAITING_BREAKS)
            InterviewQuestion.GOAL_DELAY_ACCEPTABLE -> InterviewAnswer.GoalDelay(accepted = key == GOAL_DELAY_OK)
            InterviewQuestion.TOTAL_COST_OF_OWNERSHIP ->
                InterviewAnswer.TotalCostOfOwnership(monthlyExtra = amount ?: Money.ZERO)
            InterviewQuestion.TIMING_FLEXIBLE -> InterviewAnswer.TimingFlexible(canWait = key == TIMING_FLEXIBLE)
            InterviewQuestion.COOLING_OFF_ACKNOWLEDGED -> InterviewAnswer.CoolingOff(acknowledged = key == COOLING_OFF)
            InterviewQuestion.STILL_WANTED -> InterviewAnswer.StillWanted(stillWanted = key == STILL_WANTED)
        }

    /** Result: the use band a key names, or `null`. Input: [key]. */
    private fun uses(key: String): InterviewAnswer? =
        when (key) {
            USES_HIGH -> InterviewAnswer.Uses(InterviewAnswer.UsesPerMonth.HIGH)
            USES_MEDIUM -> InterviewAnswer.Uses(InterviewAnswer.UsesPerMonth.MEDIUM)
            USES_LOW -> InterviewAnswer.Uses(InterviewAnswer.UsesPerMonth.LOW)
            else -> null
        }

    /** A need, worth the most a single answer can be (RULE-PAI-SCORE). */
    const val NEED = "need"

    /** A want. */
    const val WANT = "want"

    /** Ten uses a month or more. */
    const val USES_HIGH = "uses_high"

    /** A few uses a month. */
    const val USES_MEDIUM = "uses_medium"

    /** Rarely used. */
    const val USES_LOW = "uses_low"

    /** Something owned already does this (§13.3.1's duplicate check). */
    const val OWNS_SIMILAR = "owns_similar"

    /** Nothing owned does this. */
    const val OWNS_NOTHING = "owns_nothing_similar"

    /** Something breaks if it waits a month. */
    const val WAITING_BREAKS = "waiting_breaks_something"

    /** Nothing breaks if it waits. */
    const val WAITING_FINE = "waiting_breaks_nothing"

    /** The goal delay is acceptable. */
    const val GOAL_DELAY_OK = "goal_delay_accepted"

    /** The goal delay is not acceptable. */
    const val GOAL_DELAY_NO = "goal_delay_refused"

    /** Still wanted thirty days later (§13.3.2's organic promotion). */
    const val STILL_WANTED = "still_wanted_after_30_days"

    /** What owning it costs each month — recorded, not scored. */
    const val OWNING_COST = "owning_cost"

    /** It could wait for a sale — recorded, not scored. */
    const val TIMING_FLEXIBLE = "timing_flexible"

    /** It cannot wait for a sale — recorded, not scored. */
    const val TIMING_FIXED = "timing_fixed"

    /** The cooling-off was acknowledged — recorded, not scored. */
    const val COOLING_OFF = "cooling_off_acknowledged"
}
