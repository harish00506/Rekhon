package com.aicfo.domain.engines.goals

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import java.time.LocalDate

/**
 * Shares one month's surplus between goals that are all asking for it (issue 7.3; SRS §15.1,
 * FR-GOAL-003, FR-GOAL-005, `RULE-EMERG-FIRST`).
 *
 * Why:  [GoalEngine] answers each goal as though it were the only claim on the month, because that
 *       is the honest answer to "what would this one take". Add three goals and the sum of those
 *       honest answers can quietly exceed everything the user earns. **Nothing in the app noticed.**
 *       §15.1 is the check that does:
 *
 *       ```
 *       feasibility: Σ requiredMonthly(all active goals) ≤ P50 forecast surplus
 *         if infeasible → gap analysis + 3 levers per goal (date / amount / contribution)
 *       ```
 *
 *       and beside it, the priority waterfall: emergency fund first, then goals in the user's own
 *       order, until the money runs out.
 * What: one method. Pours [GoalWaterfallInput.monthlySurplus] down the list in order, filling each
 *       claim as far as it goes; reports what each goal got, what it still needs, and the three
 *       levers that would close the gap.
 * Result: a [GoalWaterfall] — a *suggested* plan, with every rupee attributable to the rule that
 *       placed it (P-02, AI-ARC-003).
 * Changelog: 2026-09-03 — Created for issue 7.3.
 *
 * **It allocates nothing and moves nothing** (P-07). Every figure here is a recommendation the user
 * may drag into a different order, ignore, or disagree with; the app never executes it.
 *
 * **The surplus arrives resolved, and where it comes from is not this engine's business** (ARC-005).
 * That matters more than usual here, because §15.1 asks for the *P50 forecast* surplus and
 * `:domain:engines:forecast` is still a stub — issue 9.2 was never built. `GoalWaterfallRepository`
 * substitutes the P50 of *observed* surplus and says so through [SurplusBasis]; ADR-0035 records
 * why, and what has to change when the forecast lands. Keeping the substitution on the far side of
 * this interface is what makes it a one-line change then instead of a rewrite.
 *
 * Pure Kotlin (ARC-002); the caller supplies the day, so no wall clock is read here (TIM-001).
 */
interface GoalWaterfallEngine {
    /**
     * Allocates the month's surplus across [GoalWaterfallInput.goals].
     *
     * Why:    a `Result` matching every other engine here, though a well-formed input cannot fail —
     *         the `Err` branch is reserved for arithmetic that will not fit in a `Long`, which
     *         `Money` raises rather than wrapping (MNY-001).
     * What:   the emergency fund first when `RULE-EMERG-FIRST` holds, then each goal in the order
     *         given, each taking the lesser of what it needs and what is left.
     * Result: `Ok(waterfall)` for any input whose terms fit in a `Long`, with one line per goal in
     *         the order given. An empty goal list yields an empty plan, not an error.
     * Input:  [input] — the goals in priority order, the surplus and where it came from, the
     *   emergency-fund position, the day, and the caller's instant.
     * Output: `Result<GoalWaterfall, AppError>`.
     */
    fun allocate(input: GoalWaterfallInput): Result<GoalWaterfall, AppError>
}

/**
 * Input to [GoalWaterfallEngine.allocate] (issue 7.3).
 *
 * Changelog: 2026-09-03 — Created for issue 7.3.
 *
 * @property goals the projections to fund, **already in the order the user wants them funded**.
 *   Ordering is a stored preference (`goal.sort_order`, schema 21), so it is settled before the
 *   engine sees the list — an engine that re-sorted its own input would be overruling the one part
 *   of this calculation the user controls.
 * @property monthlySurplus what the month has spare for goals, or **null when it cannot be known**.
 *   Null is not zero: zero means "this month has no room", which is a real and different answer.
 *   May be negative — a profile spending more than it earns has a negative surplus, and reporting it
 *   is more useful than clamping it out of sight.
 * @property surplusBasis where [monthlySurplus] came from, carried so the screen can say it in words
 *   rather than presenting a figure with no provenance (P-02).
 * @property emergencyTopUpMonthly `EmergencyFundPlan.topUpMonthly` — what the emergency fund needs
 *   each month to reach its target. Claimed before any goal while the gate below holds.
 * @property emergencyRunwayMonthsBps `EmergencyFundPlan.runwayMonthsBps` — how many months the user
 *   could live on what they hold liquid, in basis points of a month (MNY-002; 15 000 = 1.5 months).
 *   **Null means unknown**, and unknown is treated as "the gate holds": with no evidence the buffer
 *   exists, funding a holiday ahead of it is the mistake that costs the most.
 * @property emergencyGateMonths `RULE-EMERG-FIRST.min_runway_months`, resolved by the caller. See
 *   [GoalWaterfall] on why the *number* lives outside this module while the *citation* lives in it.
 * @property today the day to reckon from, already resolved in the profile's time zone.
 * @property nowUtcMillis the caller's instant, stamped onto the provenance and **never read as a
 *   clock** — the shape every input in this codebase uses.
 */
data class GoalWaterfallInput(
    val goals: List<GoalProjection>,
    val monthlySurplus: Money?,
    val surplusBasis: SurplusBasis,
    val emergencyTopUpMonthly: Money = Money.ZERO,
    val emergencyRunwayMonthsBps: Int? = null,
    val emergencyGateMonths: Int = DEFAULT_EMERGENCY_GATE_MONTHS,
    val today: LocalDate,
    val nowUtcMillis: Long = 0L,
) {
    init {
        require(emergencyTopUpMonthly >= Money.ZERO) {
            "An emergency-fund top-up is a magnitude and must not be negative, was " +
                "$emergencyTopUpMonthly"
        }
        require(emergencyRunwayMonthsBps == null || emergencyRunwayMonthsBps >= 0) {
            "A runway is a magnitude in basis points of a month and must not be negative, was " +
                "$emergencyRunwayMonthsBps"
        }
        require(emergencyGateMonths >= 0) {
            "RULE-EMERG-FIRST's minimum runway is a count of months and must not be negative, was " +
                "$emergencyGateMonths"
        }
        require(surplusBasis == SurplusBasis.NONE || monthlySurplus != null) {
            "A surplus basis of $surplusBasis claims a figure was resolved, but none was given: " +
                "the two must agree, or the screen would name a source for a number that is absent"
        }
        require(surplusBasis != SurplusBasis.NONE || monthlySurplus == null) {
            "A surplus of $monthlySurplus was given with basis NONE: an amount with no source " +
                "cannot be shown to the user (P-02)"
        }
    }

    private companion object {
        /**
         * `RULE-EMERG-FIRST.min_runway_months`, as a default only.
         *
         * The row is mirrored **once** in this repository, by `QuickSetupRules`, and
         * `GoalWaterfallRepository` passes that mirror's value in. This default exists so a test can
         * construct an input without reaching across three modules for a constant; production never
         * relies on it. See [GoalWaterfall] and ADR-0035 on why a second mirror was avoided.
         */
        const val DEFAULT_EMERGENCY_GATE_MONTHS = 3
    }
}

/**
 * What [GoalWaterfallEngine.allocate] decided (issue 7.3; AI-ARC-003).
 *
 * Changelog: 2026-09-03 — Created for issue 7.3.
 *
 * **This engine holds a citation for `RULE-EMERG-FIRST` but not a copy of its number, and the
 * distinction is load-bearing.** ADR-0017's second trigger says that when a *second* engine needs to
 * mirror a rule row already mirrored elsewhere, the right answer is to stop mirroring and build the
 * runtime rulebook loader. `QuickSetupRules.emergencyRunwayMonths` is already that first mirror. So
 * the threshold reaches this engine as [GoalWaterfallInput.emergencyGateMonths], resolved by the
 * repository from the mirror that exists, and what lives here is only the row's *version*, for
 * provenance. A citation is not a mirror: nothing here would drift if the number changed, because
 * nothing here holds the number. ADR-0035 records the reasoning.
 *
 * @property monthlySurplus the figure allocated from, echoed so the card can show its working.
 * @property surplusBasis where that figure came from.
 * @property totalRequiredMonthly what every goal needs, summed — §15.1's left-hand side.
 * @property totalAllocated what the goals actually got. Equals [totalRequiredMonthly] when feasible.
 * @property gapMonthly `totalRequiredMonthly - totalAllocated`, the size of the problem in one
 *   number. Zero when feasible — the gap, not a boolean, is what makes the verdict act on (P-02).
 * @property feasibility the one verdict a screen leads with.
 * @property emergencyFirstApplied whether `RULE-EMERG-FIRST` fired — the runway was below
 *   `min_runway_months`, or unknown — and so held every goal at zero.
 * @property emergencyAllocated what the emergency fund took off the top before any goal was
 *   considered. Zero unless [emergencyFirstApplied].
 * @property lines one allocation per input goal, in the same order.
 * @property unallocated what is left after every claim is met. Positive only when the month has more
 *   surplus than the goals and the buffer can absorb — which is the app's cue that the money is
 *   idle, not that the plan is finished.
 * @property provenance which engine, which version, when, and which rules — the citation is required
 *   by the `init` below, so a plan that cannot name the rules that shaped it cannot be constructed.
 */
data class GoalWaterfall(
    val monthlySurplus: Money?,
    val surplusBasis: SurplusBasis,
    val totalRequiredMonthly: Money,
    val totalAllocated: Money,
    val gapMonthly: Money,
    val feasibility: Feasibility,
    val emergencyFirstApplied: Boolean,
    val emergencyAllocated: Money,
    val lines: List<GoalAllocation>,
    val unallocated: Money,
    val provenance: EngineProvenance,
) {
    init {
        require(provenance.evidence.isNotEmpty()) {
            "A waterfall names the rules that shaped it (P-02, AI-ARC-006)"
        }
        // The invariant that makes this engine trustworthy: it neither creates nor loses a paise.
        // `SafeToSpend` requires the same of its own lines, for the same reason — an allocation
        // that quietly rounded away a rupee would pass every test that looked at one goal at a time.
        val distributable = maxOf(Money.ZERO, monthlySurplus ?: Money.ZERO)
        val placed = emergencyAllocated + totalAllocated + unallocated
        require(placed == distributable) {
            "The waterfall must place exactly what it was given: $emergencyAllocated to the " +
                "emergency fund + $totalAllocated to goals + $unallocated left over = $placed, " +
                "but the distributable surplus was $distributable"
        }
        require(unallocated >= Money.ZERO) {
            "The waterfall allocated more than it had — $unallocated is left over"
        }
    }
}

/**
 * What one goal got, and what would close the rest (issue 7.3; §15.1, FR-GOAL-003).
 *
 * Changelog: 2026-09-03 — Created for issue 7.3.
 *
 * @property goalId the [GoalProjection.goalId] this allocates for.
 * @property name the user's label, carried through.
 * @property requiredMonthly what the goal needs each month, from [GoalEngine].
 * @property allocatedMonthly what the surplus could actually give it. Never more than
 *   [requiredMonthly] — a waterfall fills a claim, it does not overfill it.
 * @property shortfallMonthly `requiredMonthly - allocatedMonthly`, floored at zero.
 * @property fullyFunded whether the surplus covers this goal outright.
 * @property blockedByEmergencyFund whether this goal got nothing because `RULE-EMERG-FIRST` fired,
 *   as opposed to because the money ran out above it. The two look identical on a card — both are
 *   ₹0 — and they call for completely different actions, so the screen has to be able to tell them
 *   apart.
 * @property levers the three ways out, or **null when the goal is fully funded** — offering to fix
 *   something that is not broken is noise.
 */
data class GoalAllocation(
    val goalId: String,
    val name: String,
    val requiredMonthly: Money,
    val allocatedMonthly: Money,
    val shortfallMonthly: Money,
    val fullyFunded: Boolean,
    val blockedByEmergencyFund: Boolean,
    val levers: GoalLevers?,
)

/**
 * FR-GOAL-003's three levers: "infeasible plans show the gap and three levers (extend date, reduce
 * target, increase contribution)" (issue 7.3; §15.1).
 *
 * Why:  a gap with no lever is a complaint. The requirement names exactly three ways out because
 *       they are the three variables in the equation — time, size, and rate — and the user gets to
 *       pick which one moves (P-07).
 * What: each lever expressed as the *smallest change that works*, not a direction to travel in.
 * Result: three concrete numbers a screen can put in a sentence.
 * Changelog: 2026-09-03 — Created for issue 7.3.
 *
 * All three are computed at the goal's **allocated** rate, not at zero and not at what the user
 * planned: they answer "given what this plan can actually spare for you, what would have to give?"
 *
 * @property extendByMonths how many further whole months the target date would need to move for the
 *   allocated contribution to reach the target. **Null when the allocation is zero** — no amount of
 *   time gets there at ₹0 a month, and a very large number would read as one that might — and null
 *   again when the honest answer is past [DefaultGoalWaterfallEngine]'s hundred-year horizon.
 * @property reduceTargetTo what the target would have to shrink to for the allocated contribution to
 *   reach it by the existing date. **Null when no whole month remains**, where there is nothing left
 *   to contribute over and the only lever is paying the balance now.
 * @property increaseContributionBy how much more the goal needs each month to be fully funded —
 *   always equal to [GoalAllocation.shortfallMonthly], stated here so all three levers read from one
 *   place.
 */
data class GoalLevers(
    val extendByMonths: Int?,
    val reduceTargetTo: Money?,
    val increaseContributionBy: Money,
)

/**
 * §15.1's verdict on whether the plan holds together (issue 7.3).
 *
 * An enum, not a sentence: the domain decides what is true, `:feature:goals` decides how to say it.
 *
 * Changelog: 2026-09-03 — Created for issue 7.3.
 */
enum class Feasibility {
    /** Every goal's required monthly is met — §15.1's `Σ requiredMonthly ≤ surplus` holds. */
    FEASIBLE,

    /**
     * The claims exceed the surplus, by `gapMonthly`.
     *
     * A zero surplus lands here, not in [UNKNOWN]: "this month has no room" is a finding, and the
     * levers on each line are exactly what the user needs to see about it.
     */
    INFEASIBLE,

    /**
     * The surplus could not be resolved, so feasibility is not a question that has an answer yet.
     *
     * **Distinct from a zero surplus on purpose.** Treating unknown as zero would tell a user with
     * one month of history that every goal they own is impossible — the same class of mistake as a
     * zero emergency-fund target congratulating someone with nothing saved (issue 7.2).
     */
    UNKNOWN,
}

/**
 * Where the month's surplus figure came from (issue 7.3; §15.1, ADR-0035).
 *
 * Why:  §15.1 asks for the **P50 forecast** surplus, and this app has no forecast — issue 9.2's
 *       `:domain:engines:forecast` is still a placeholder. Rather than quietly substituting
 *       something else and calling it the same thing, the substitution is named on the result and
 *       shown to the user (P-02).
 * What: the three sources, best first.
 * Result: a screen can say "the middle of your last six months" instead of presenting a number from
 *       nowhere — and when 9.2 lands, a fourth value appears here and the old ones keep meaning what
 *       they meant, so stored assessments stay readable (AI-ARC-006).
 * Changelog: 2026-09-03 — Created for issue 7.3.
 */
enum class SurplusBasis {
    /**
     * The median of what the closed months actually had spare: income less needs less wants.
     *
     * A genuine P50 — of observed surplus rather than forecast surplus, which is the whole of the
     * substitution. The median rather than the mean for the reason issue 7.2 found: one replaced
     * fridge moves a mean and moves a median by nothing.
     */
    OBSERVED_MEDIAN,

    /**
     * The INVEST envelope the user set at onboarding — what they *said* they would save.
     *
     * The fallback while there is too little history to take a median from, and only that: it is a
     * figure the user typed once and never revisits.
     */
    DECLARED_ENVELOPE,

    /** Neither was available. The surplus is `null` and feasibility is [Feasibility.UNKNOWN]. */
    NONE,
}

/**
 * How the rest of the app gets a [GoalWaterfallEngine] (ARC-003).
 *
 * Why:  the implementation is `internal`, so a Hilt module in `:app` cannot name it. This is the one
 *       seam it can, matching [GoalEngineFactory].
 * Result: the production engine. Input: none. Output: [GoalWaterfallEngine].
 * Changelog: 2026-09-03 — Created for issue 7.3.
 */
object GoalWaterfallEngineFactory {
    /** Result: the production engine. Input: none. Output: [GoalWaterfallEngine]. */
    fun create(): GoalWaterfallEngine = DefaultGoalWaterfallEngine()
}
