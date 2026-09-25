package com.aicfo.domain.engines.purchase

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money

/**
 * AI-PA-INT — how much to ask before a wish becomes a purchase (issue 10.2; SRS §13.3).
 *
 * Why:  a buy list only works if adding to it is cheaper than buying. So the interview's depth
 *       scales with what the purchase weighs against the money coming in: a ₹200 wish that asked
 *       seven questions would teach the user to bypass the list, and a ₹3,00,000 one that asked
 *       none would never have earned its place. The questions also have to be **new** — re-asking
 *       what the user already answered is the nagging §13.3's honesty rule forbids.
 * What: the band, the questions still missing, the score those answers add up to, and what the app
 *       should suggest doing with the wish.
 * Result: an [InterviewAssessment]. It suggests; the user always confirms (P-07).
 * Changelog: 2026-09-26 — Created for issue 10.2.
 *
 * Input:  [InterviewInput]. Output: `Result<InterviewAssessment, AppError>`; `Err` only for an
 *         impossible input (a negative price or income, or two answers to one question).
 *
 * **Pure** (ARC-002, P-08): arithmetic on the caller's figures, no clock and no I/O.
 */
interface PurchaseInterviewEngine {
    /** Assesses one wish and its answers so far. See the interface's doc. */
    fun assess(input: InterviewInput): Result<InterviewAssessment, AppError>
}

/**
 * Builds the one [PurchaseInterviewEngine] (ARC-003 — the implementation stays `internal`).
 * Changelog: 2026-09-26 — Created for issue 10.2.
 */
object PurchaseInterviewEngineFactory {
    /** Result: the §13.3 interview engine. Input: none. Output: [PurchaseInterviewEngine]. */
    fun create(): PurchaseInterviewEngine = LadderedPurchaseInterviewEngine()
}

/**
 * §13.3.1's bands — what a purchase weighs against the money coming in.
 * Changelog: 2026-09-26 — Created for issue 10.2.
 */
enum class PurchaseWeight {
    /** Under 0.5% of a month's income. No friction worth the name. */
    CASUAL,

    /** 0.5–2%. Worth a moment's thought. */
    SMALL,

    /** 2–10%. Worth knowing what it costs elsewhere. */
    SIGNIFICANT,

    /** 10–25%. Worth the whole picture. */
    MAJOR,

    /** Above 25%, bought on instalments, or with no income recorded to judge against. */
    HEAVY,
}

/**
 * The bank of questions, in the order the ladder asks them (§13.3.1).
 *
 * Why:  a fixed order, because the ladder is "the first N of these" — so a band's questions are
 *       predictable, and a user who moves up a band is asked what is new rather than everything
 *       again.
 * Changelog: 2026-09-26 — Created for issue 10.2.
 */
enum class InterviewQuestion {
    /** "Need or want?" — the one question even a casual wish earns. */
    NEED_OR_WANT,

    /** "How often will you use it?" */
    USES_PER_MONTH,

    /** "Do you already own something that does this?" — §13.3.1's duplicate check. */
    ALREADY_OWN_SIMILAR,

    /** "What breaks if you wait 30 days?" */
    WAIT_THIRTY_DAYS,

    /** Which goal it delays, and whether that is acceptable — the delay is computed and shown. */
    GOAL_DELAY_ACCEPTABLE,

    /** What it costs to own: accessories, subscriptions, maintenance. */
    TOTAL_COST_OF_OWNERSHIP,

    /** Whether it could wait for a sale season. */
    TIMING_FLEXIBLE,

    /** Heavy purchases only: the user has seen the advisor's gates and the cooling-off. */
    COOLING_OFF_ACKNOWLEDGED,

    /**
     * "Do you still want it?", asked only at a re-interview thirty days later.
     *
     * **Last on purpose**: the ladder is "the first N of this bank", so a band never asks this —
     * it is offered by the re-interview instead, and it is scored in every band (§13.3.2's organic
     * promotion).
     */
    STILL_WANTED,
}

/**
 * One answer the user gave.
 *
 * Why:  a sealed hierarchy rather than free text, because §13.3.2 scores answers and a score built
 *       on prose could not be explained or reproduced. Each answer names its question, so the
 *       engine can tell what is still missing.
 * Changelog: 2026-09-26 — Created for issue 10.2.
 */
sealed interface InterviewAnswer {
    /** Which question this answers. */
    val question: InterviewQuestion

    /** Need or want. */
    data class NeedOrWant(val isNeed: Boolean) : InterviewAnswer {
        override val question = InterviewQuestion.NEED_OR_WANT
    }

    /** How often it would be used, in bands rather than a number nobody can estimate honestly. */
    data class Uses(val band: UsesPerMonth) : InterviewAnswer {
        override val question = InterviewQuestion.USES_PER_MONTH
    }

    /** Whether something owned already does this (§13.3.1's duplicate check). */
    data class AlreadyOwns(val ownsSimilar: Boolean) : InterviewAnswer {
        override val question = InterviewQuestion.ALREADY_OWN_SIMILAR
    }

    /** Whether anything breaks if it waits a month. */
    data class WaitThirtyDays(val somethingBreaks: Boolean) : InterviewAnswer {
        override val question = InterviewQuestion.WAIT_THIRTY_DAYS
    }

    /** Whether the goal delay the advisor computed is acceptable. */
    data class GoalDelay(val accepted: Boolean) : InterviewAnswer {
        override val question = InterviewQuestion.GOAL_DELAY_ACCEPTABLE
    }

    /** What owning it adds each month, in paise (MNY-001). */
    data class TotalCostOfOwnership(val monthlyExtra: Money) : InterviewAnswer {
        override val question = InterviewQuestion.TOTAL_COST_OF_OWNERSHIP
    }

    /** Whether it could wait for a sale. */
    data class TimingFlexible(val canWait: Boolean) : InterviewAnswer {
        override val question = InterviewQuestion.TIMING_FLEXIBLE
    }

    /** The heavy purchase's cooling-off was seen and accepted. */
    data class CoolingOff(val acknowledged: Boolean) : InterviewAnswer {
        override val question = InterviewQuestion.COOLING_OFF_ACKNOWLEDGED
    }

    /** Asked only at a re-interview: is it still wanted a month later (§13.3.2's organic promotion)? */
    data class StillWanted(val stillWanted: Boolean) : InterviewAnswer {
        override val question = InterviewQuestion.STILL_WANTED
    }

    /** How often a thing would be used, in bands. */
    enum class UsesPerMonth {
        /** Ten times a month or more. */
        HIGH,

        /** A few times a month. */
        MEDIUM,

        /** Rarely. */
        LOW,
    }
}

/** What the app should do with a wish (§13.3.2). Changelog: 2026-09-26 — Created for issue 10.2. */
enum class InterviewOutcome {
    /** 70 or more: keep it and watch for a good moment to buy. */
    KEEP,

    /** 40–69: it stays on the list and is asked about again in thirty days. */
    PARK,

    /** Under 40: the app suggests removing it, and shows the answers that say so. */
    SUGGEST_REMOVE,
}

/**
 * One answer's contribution to the score — the evidence behind a suggestion (§13.3.2, P-02).
 * Input:  [question]; [points] — signed; [reason] — the stable key of the delta that fired, which
 *         the screen turns into words. Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.2.
 */
data class ScoreDelta(
    val question: InterviewQuestion,
    val points: Int,
    val reason: String,
)

/**
 * What AI-PA-INT reads.
 * Input:  [price]; [method] and [monthlyEmi] — an instalment is always heavy (§13.3.1);
 *         [monthlyIncome] — what the weight is measured against; [monthlySurplus] — for an
 *         instalment's weight; [answers] — what the user has already said, at most one per question;
 *         [nowUtcMillis]; [rules].
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.2.
 */
data class InterviewInput(
    val price: Money,
    val method: PaymentMethod = PaymentMethod.CASH,
    val monthlyEmi: Money? = null,
    val monthlyIncome: Money = Money.ZERO,
    val monthlySurplus: Money = Money.ZERO,
    val answers: List<InterviewAnswer> = emptyList(),
    val nowUtcMillis: Long = 0L,
    val rules: InterviewRules = InterviewRules(),
)

/**
 * What the interview concluded so far (§13.3.2).
 * Input:  [weight]; [questionsToAsk] — **only what is still missing**, in ladder order;
 *         [wantScore]; [outcome]; [isComplete] — every question the band asks has an answer;
 *         [deltas] — the evidence; [coolingOffRequired] and [coolingOffHours] — §13.3.1's 24 hours
 *         for a heavy purchase; [provenance].
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.2.
 */
data class InterviewAssessment(
    val weight: PurchaseWeight,
    val questionsToAsk: List<InterviewQuestion>,
    val wantScore: Int,
    val outcome: InterviewOutcome,
    val isComplete: Boolean,
    val deltas: List<ScoreDelta>,
    val coolingOffRequired: Boolean,
    val coolingOffHours: Int,
    val provenance: EngineProvenance,
)
