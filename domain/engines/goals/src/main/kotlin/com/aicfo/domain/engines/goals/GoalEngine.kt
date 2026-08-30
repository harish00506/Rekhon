package com.aicfo.domain.engines.goals

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import java.time.LocalDate

/**
 * Answers "what would I have to put aside each month to get there?" (issue 7.1; SRS §10, §15, §36,
 * AI-GOAL).
 *
 * Why:  a goal without a number is a wish. The app already knows what the user owns and what they
 *       spend; §15 asks it to turn a target and a date into the one figure that decides whether the
 *       goal happens — the monthly contribution — and to say plainly whether the user's own plan
 *       clears it.
 *
 *       **The subtraction is trivial; the honesty is not.** A target date in the past still needs an
 *       answer, and "infinity" is not one. A goal already funded must report zero, not a negative
 *       instalment. And a required monthly that quietly assumed investment growth would be a number
 *       this app invented — see [GoalRules] on why no rate is assumed.
 * What: one method. For each goal: what is left, how many monthly contributions fit before the date,
 *       the largest of those contributions, the date the user's own plan actually reaches the
 *       target, and which funding bucket `RULE-HORIZON` puts the horizon in.
 * Result: a projection per goal, plus the provenance and rule citation every engine result carries
 *       (AI-ARC-003). The figures are exact paise (MNY-001) — `Money.split` distributes the odd
 *       paise rather than dropping them, so twelve instalments of a ₹1,00,000.01 goal still sum to
 *       ₹1,00,000.01.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 *
 * **It decides nothing about which goals exist or what "saved" means.** Both arrive resolved:
 * storage is `GoalRepository`'s question (ARC-005), the same division of labour `SafeToSpendEngine`
 * and `BudgetEngine` already keep to. That is what makes the whole calculation provable on the JVM.
 *
 * **It advises, it does not instruct** (P-07). Nothing here moves money or schedules a transfer; the
 * required monthly is a figure the user may act on, ignore, or argue with.
 *
 * Pure Kotlin (ARC-002); the caller supplies the day, so no wall clock is read here (TIM-001).
 */
interface GoalEngine {
    /**
     * Projects every goal in [input].
     *
     * Why:    a `Result` matching every other engine in this codebase, though a well-formed input
     *         cannot fail — the `Err` branch is reserved for arithmetic that will not fit in a
     *         `Long`, which `Money` raises rather than wrapping (MNY-001). An engine that let that
     *         escape would crash a screen instead of degrading it (§21.6: no exceptions across layer
     *         boundaries).
     * What:   applies the same arithmetic to each goal independently — no goal's numbers depend on
     *         another's, which is 7.3's job (feasibility against a shared surplus), not this one's.
     * Result: `Ok(plan)` for any input whose terms fit in a `Long`, with one projection per goal in
     *         the order given. An empty goal list yields an empty plan, not an error: a user who has
     *         set no goals has nothing wrong with them.
     * Input:  [input] — the goals, the day to reckon from, the caller's instant, and the thresholds.
     * Output: `Result<GoalPlan, AppError>`.
     */
    fun plan(input: GoalPlanInput): Result<GoalPlan, AppError>
}

/**
 * One goal, as the caller resolved it (issue 7.1).
 *
 * Why:    a value the engine can be handed without touching storage, so every case below — past due,
 *         over-funded, no target — is reachable in a unit test.
 * Result: the input to one projection.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 *
 * @property id the goal's identity, echoed onto the projection so a caller can match them up
 *   without depending on list order.
 * @property name the user's own label. Carried through untouched; the engine never reads it.
 * @property target what the goal needs in total, in paise (MNY-001). Zero is legitimate and means
 *   "no target set yet" — reported as [GoalStatus.NO_TARGET] rather than refused, because a
 *   half-filled goal is an ordinary state for something the user just created.
 * @property targetDate the day the money is needed (TIM-002). May be in the past; that is
 *   [GoalStatus.PAST_DUE], not an error.
 * @property saved what has been put aside so far. May exceed [target] — that is over-funded, and the
 *   engine reports zero required rather than a negative instalment.
 * @property plannedMonthly what the user says they will contribute each month. Zero means "no plan
 *   yet", and the projection's ETA is then `null` rather than a date infinitely far away.
 */
data class GoalSpec(
    val id: String,
    val name: String,
    val target: Money,
    val targetDate: LocalDate,
    val saved: Money,
    val plannedMonthly: Money = Money.ZERO,
) {
    init {
        require(target >= Money.ZERO) {
            "A goal's target is a magnitude and must not be negative, was $target: a negative " +
                "target would make `remaining` negative and the required monthly a refund"
        }
        require(saved >= Money.ZERO) {
            "A goal's saved amount is a magnitude and must not be negative, was $saved"
        }
        require(plannedMonthly >= Money.ZERO) {
            "A planned monthly contribution is a magnitude and must not be negative, was " +
                "$plannedMonthly: paying *out* of a goal each month is a withdrawal, which this " +
                "engine does not model"
        }
    }
}

/**
 * Input to [GoalEngine.plan] (issue 7.1).
 *
 * Changelog: 2026-08-30 — Created for issue 7.1.
 *
 * @property goals the goals to project, in the order the caller wants them back. May be empty.
 * @property today the day to reckon from, **already resolved in the profile's time zone** by the
 *   caller. A `LocalDate` rather than an instant because every question here is a calendar one, and
 *   because TIM-001 forbids this module reading a clock of its own.
 * @property nowUtcMillis the caller's instant, stamped onto the provenance and **never read as a
 *   clock** — the same shape `SafeToSpendInput` and `CardCycleInput` use.
 * @property rules the thresholds to apply. Injected so a test can move a band and assert the engine
 *   moves with it, which is also the seam the runtime rules loader will use.
 */
data class GoalPlanInput(
    val goals: List<GoalSpec>,
    val today: LocalDate,
    val nowUtcMillis: Long = 0L,
    val rules: GoalRules = GoalRules(),
)

/**
 * What [GoalEngine.plan] decided (issue 7.1; AI-ARC-003).
 *
 * Changelog: 2026-08-30 — Created for issue 7.1.
 *
 * @property goals one projection per input goal, in the same order.
 * @property provenance which engine, which version, when, and **which rule** — the citation is
 *   required by the `init` below, so a projection that cannot name the rule that shaped it cannot be
 *   constructed at all (P-02, AI-ARC-006).
 */
data class GoalPlan(
    val goals: List<GoalProjection>,
    val provenance: EngineProvenance,
) {
    init {
        require(provenance.evidence.isNotEmpty()) {
            "A goal projection names the rule that shaped it (P-02, AI-ARC-006)"
        }
    }

    /**
     * What the goals need each month, in total.
     *
     * Why:    Safe-to-Spend subtracts the contributions the month has not yet made
     *         (`RULE-STS.include_goal_contributions`), and until this engine existed it substituted
     *         the user's whole quick-setup INVEST envelope as a stand-in (ADR-0021). This is the
     *         real term, summed here rather than in the repository so there is one definition of it.
     * Result: the sum of every goal's required monthly. Zero when there are no goals, which leaves
     *         Safe-to-Spend exactly as it was.
     * Input:  none. Output: [Money].
     */
    val totalRequiredMonthly: Money
        get() = goals.fold(Money.ZERO) { running, goal -> running + goal.requiredMonthly }
}

/**
 * One goal's numbers (issue 7.1; §15).
 *
 * Field names deliberately mirror `ai/skills/tool-registry.json`'s `get_goals` contract
 * (`{name, target_minor, current_minor, eta_date, on_track}`), which the chat layer will read in
 * Epic 10 and which `ai/chat/guardrail.md` GRD-004 already points at for ETAs.
 *
 * Changelog: 2026-08-30 — Created for issue 7.1.
 *
 * @property goalId the [GoalSpec.id] this projects.
 * @property name the user's label, carried through.
 * @property target what the goal needs in total.
 * @property saved what is set aside now — `current_minor` in the tool contract.
 * @property remaining `target - saved`, floored at zero. Never negative: over-funding is a state,
 *   not a debt the goal owes back.
 * @property monthsRemaining whole months from today to the target date, floored at zero.
 * @property requiredMonthly the **largest** of the instalments [remaining] splits into. `Money.split`
 *   hands the odd paise to the earliest parts, so quoting the largest is the figure that actually
 *   gets there — quoting the smallest would leave the goal a few paise short.
 * @property plannedMonthly what the user said they would contribute, carried through so the card can
 *   show both halves of the comparison.
 * @property shortfallMonthly `requiredMonthly - plannedMonthly`, floored at zero. Shown instead of a
 *   bare "behind" so the user sees the size of the gap (P-02).
 * @property etaIsoDate the day the user's own plan reaches the target (TIM-002), or **null** when
 *   `plannedMonthly` is zero — at no contribution there is no date, and inventing a far-future one
 *   would be a number this app made up.
 * @property onTrack whether the plan clears the requirement. An exact comparison, not a band — see
 *   [GoalRules].
 * @property horizon which funding bucket `RULE-HORIZON` puts the remaining time in.
 * @property status the single verdict a screen leads with.
 */
data class GoalProjection(
    val goalId: String,
    val name: String,
    val target: Money,
    val saved: Money,
    val remaining: Money,
    val monthsRemaining: Int,
    val requiredMonthly: Money,
    val plannedMonthly: Money,
    val shortfallMonthly: Money,
    val etaIsoDate: String?,
    val onTrack: Boolean,
    val horizon: Horizon,
    val status: GoalStatus,
)

/**
 * The verdict a goal card leads with (issue 7.1).
 *
 * An enum, not a sentence: the domain decides *what is true*, a feature module decides how to say it
 * in the user's language. Declaration order is severity order, worst last, so a list of goals can be
 * sorted by attention needed without a second table.
 *
 * Changelog: 2026-08-30 — Created for issue 7.1.
 */
enum class GoalStatus {
    /** Already funded: `saved >= target`. Required monthly is zero, not negative. */
    OVER_FUNDED,

    /** The plan clears the requirement. */
    ON_TRACK,

    /** No target amount set yet. Ordinary for a goal the user just created; not an error. */
    NO_TARGET,

    /** The plan falls short of the requirement, by `shortfallMonthly`. */
    BEHIND,

    /** The target date has passed and money is still owed. The whole remainder is required now. */
    PAST_DUE,
}

/**
 * How the rest of the app gets a [GoalEngine] (ARC-003).
 *
 * Why:  the implementation is `internal`, so a Hilt module in `:app` cannot name it. This is the one
 *       seam it can, matching `SafeToSpendEngineFactory` and `NetWorthEngineFactory`.
 * Result: the production engine. Input: none. Output: [GoalEngine].
 * Changelog: 2026-08-30 — Created for issue 7.1.
 */
object GoalEngineFactory {
    /** Result: the production engine. Input: none. Output: [GoalEngine]. */
    fun create(): GoalEngine = DefaultGoalEngine()
}
