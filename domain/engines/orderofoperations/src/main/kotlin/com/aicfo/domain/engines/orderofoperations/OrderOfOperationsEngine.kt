package com.aicfo.domain.engines.orderofoperations

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import com.aicfo.domain.engines.goals.SurplusBasis
import java.time.LocalDate

/**
 * The Financial Order of Operations — "what should my next rupee do?" (issue 7.5; SRS §36, AI-FOO).
 *
 * Why:  every engine before this one answers its own question — how big the buffer should be, what a
 *       goal needs each month, how much of a card is used — and none of them answers the question a
 *       user actually has on payday. §36 calls AI-FOO "the connective tissue the earlier engines
 *       lacked": one deterministic ranking across the buffer, debt, the emergency fund, tax, goals
 *       and investing, with the rupee amount for each.
 * What: one method. Pours [OrderOfOperationsInput.monthlySurplus] down §36's eight stages in their
 *       fixed order, each stage taking the lesser of what it needs and what is left, and reports all
 *       eight — including the ones that were skipped, and why (§36: "every skipped stage shows why").
 * Result: an [OrderOfOperations] — a ranked, *advisory* list (P-07). The top action is what the Home
 *       dashboard leads with (FOO-002); every stage names the rule that placed it (P-02, AI-ARC-003).
 * Changelog: 2026-09-17 — Created for issue 7.5.
 *
 * **It moves nothing.** Every amount is a recommendation; the app never executes it (P-07).
 *
 * **The order is strict, and user reordering is not built here.** FOO-001 allows the user to reorder
 * and asks the app to show the cost of doing so; that needs an interest-cost projection per deviation
 * and is deferred (ADR-0037). The stages below are always ranked in §36's order.
 *
 * **Every threshold comes from the FOO rule set**, mirrored in [OrderOfOperationsRules] and held to
 * `ai/rules/financial-order-of-operations.json` by a drift test (FOO-003). The one exception is
 * `RULE-EMERG-FIRST`'s number, which arrives resolved as an input for the reason ADR-0035 gives.
 *
 * Pure Kotlin (ARC-002); the caller supplies the day and the instant, so no clock is read (TIM-001).
 */
interface OrderOfOperationsEngine {
    /**
     * Ranks the month's surplus across §36's eight stages.
     * Why:    a `Result` like every engine here, though a well-formed input cannot fail — `Err` is
     *         reserved for a sum that will not fit in a `Long`, which `Money` refuses to wrap.
     * What:   the stages in §36's order, each filled from what the ones above it left.
     * Result: `Ok(ranking)` with exactly eight stages, in order, for any input whose sums fit in a
     *         `Long`. A profile with no data still gets eight stages: the ranking is a finding.
     * Input:  [input] — the surplus, the emergency-fund position, the debts, the goals' monthly need,
     *   the day and the caller's instant.
     * Output: `Result<OrderOfOperations, AppError>`.
     */
    fun rank(input: OrderOfOperationsInput): Result<OrderOfOperations, AppError>
}

/**
 * Input to [OrderOfOperationsEngine.rank] (issue 7.5).
 * Changelog: 2026-09-17 — Created for issue 7.5.
 *
 * Every figure here arrives already resolved by the repository (ARC-005): the surplus from
 * `GoalWaterfallRepository` (so the two screens pour the same number), the emergency-fund position
 * from `EmergencyFundPlan`, and the debts from the accounts and their card and loan details.
 *
 * @property monthlySurplus what the month has spare, or **null when it cannot be known**. Null is not
 *   zero. May be negative; a negative surplus ranks exactly as a zero one and pours nothing.
 * @property surplusBasis where [monthlySurplus] came from — §36 asks for the forecast surplus, and
 *   issue 9.2 never built one, so this says which stand-in was used (ADR-0035).
 * @property monthlyEssentials `EmergencyFundPlan.monthlyEssentials` — sizes the starter buffer. Null
 *   when unknown, in which case the buffer is sized by its cap alone.
 * @property liquidFunds `EmergencyFundPlan.liquidFunds` — what is spendable today. A magnitude.
 * @property emergencyShortfall what the full emergency fund still needs, or **null when the fund
 *   cannot be sized** (`EmergencyStatus.UNKNOWN`). EMF reports a zero shortfall in that case, and
 *   passing that zero on would tell the user an unsized fund was complete.
 * @property emergencyTopUpMonthly `EmergencyFundPlan.topUpMonthly` — the pace AI-EMF recommends.
 * @property emergencyRunwayMonthsBps `EmergencyFundPlan.runwayMonthsBps` — basis points of a month
 *   (MNY-002). **Null means unknown, and unknown holds the gate**, as it does in 7.3.
 * @property emergencyGateMonths `RULE-EMERG-FIRST.min_runway_months`, resolved by the caller.
 * @property debts every debt with an outstanding balance the app knows of. Order does not matter.
 * @property goalsRequiredMonthly what every active goal needs this month, summed.
 * @property goalCount how many active goals there are. Zero makes Stage 5 not applicable, which is a
 *   different finding from goals that need nothing this month.
 * @property today the day to reckon from, already resolved in the profile's time zone.
 * @property nowUtcMillis the caller's instant, stamped onto the provenance, never read as a clock.
 * @property rules the FOO thresholds. Defaults to the mirrored file; tests pass their own.
 */
data class OrderOfOperationsInput(
    val monthlySurplus: Money?,
    val surplusBasis: SurplusBasis,
    val monthlyEssentials: Money? = null,
    val liquidFunds: Money = Money.ZERO,
    val emergencyShortfall: Money? = null,
    val emergencyTopUpMonthly: Money = Money.ZERO,
    val emergencyRunwayMonthsBps: Int? = null,
    val emergencyGateMonths: Int = DEFAULT_EMERGENCY_GATE_MONTHS,
    val debts: List<DebtPosition> = emptyList(),
    val goalsRequiredMonthly: Money = Money.ZERO,
    val goalCount: Int = 0,
    val today: LocalDate,
    val nowUtcMillis: Long = 0L,
    val rules: OrderOfOperationsRules = OrderOfOperationsRules(),
) {
    init {
        require(surplusBasis == SurplusBasis.NONE || monthlySurplus != null) {
            "A surplus basis of $surplusBasis claims a figure was resolved, but none was given"
        }
        require(surplusBasis != SurplusBasis.NONE || monthlySurplus == null) {
            "A surplus of $monthlySurplus was given with basis NONE: an amount with no source " +
                "cannot be shown to the user (P-02)"
        }
        require(monthlyEssentials == null || monthlyEssentials >= Money.ZERO) {
            "Essentials are a magnitude, were $monthlyEssentials"
        }
        require(liquidFunds >= Money.ZERO) { "Liquid funds are a magnitude, were $liquidFunds" }
        require(emergencyShortfall == null || emergencyShortfall >= Money.ZERO) {
            "A shortfall is a magnitude, was $emergencyShortfall"
        }
        require(emergencyTopUpMonthly >= Money.ZERO) {
            "A top-up is a magnitude, was $emergencyTopUpMonthly"
        }
        require(emergencyRunwayMonthsBps == null || emergencyRunwayMonthsBps >= 0) {
            "A runway is a magnitude in basis points of a month, was $emergencyRunwayMonthsBps"
        }
        require(emergencyGateMonths >= 0) {
            "RULE-EMERG-FIRST's minimum runway must not be negative, was $emergencyGateMonths"
        }
        require(goalsRequiredMonthly >= Money.ZERO) {
            "What the goals need is a magnitude, was $goalsRequiredMonthly"
        }
        require(goalCount >= 0) { "A goal count must not be negative, was $goalCount" }
        require(goalCount > 0 || goalsRequiredMonthly == Money.ZERO) {
            "With no goals, nothing can be required of them, but $goalsRequiredMonthly was"
        }
    }

    private companion object {
        /**
         * `RULE-EMERG-FIRST.min_runway_months`, as a default for tests only. Production passes
         * `QuickSetupRules.emergencyRunwayMonths` — see [OrderOfOperationsRules] on why.
         */
        const val DEFAULT_EMERGENCY_GATE_MONTHS = 3
    }
}

/**
 * One debt the waterfall may pay down (issue 7.5; §36 Stages 2, 6 and 7).
 * Changelog: 2026-09-17 — Created for issue 7.5.
 *
 * @property accountId the liability account this is.
 * @property name the user's label, carried through for the screen.
 * @property kind a card or a loan. Informal debts (`AccountType.PAYABLE`) carry no rate and are not
 *   debts this engine can place, so the repository does not send them.
 * @property outstanding what is owed now, as a **positive** magnitude — the account holds it
 *   negative, and the repository flips it.
 * @property aprBps the annual rate in basis points (MNY-002). **Null only for a card whose APR the
 *   user has not entered** — a loan always has one (schema: `loan.annual_rate_bps` is not null).
 */
data class DebtPosition(
    val accountId: String,
    val name: String,
    val kind: DebtKind,
    val outstanding: Money,
    val aprBps: Int?,
) {
    init {
        require(outstanding >= Money.ZERO) { "An outstanding balance is a magnitude, was $outstanding" }
        require(aprBps == null || aprBps >= 0) { "A rate must not be negative, was $aprBps" }
        require(aprBps != null || kind == DebtKind.CARD) {
            "Only a card may have an unknown rate — a loan's rate is required when it is saved"
        }
    }
}

/** Which kind of liability a [DebtPosition] is. Changelog: 2026-09-17 — Created for issue 7.5. */
enum class DebtKind {
    /** A credit card (`AccountType.CREDIT_CARD`). */
    CARD,

    /** A loan (`AccountType.LOAN`). */
    LOAN,
}

/**
 * What [OrderOfOperationsEngine.rank] decided (issue 7.5; AI-ARC-003).
 * Changelog: 2026-09-17 — Created for issue 7.5.
 *
 * @property monthlySurplus the figure poured, echoed so a screen can show its working.
 * @property surplusBasis where that figure came from.
 * @property stages all eight stages, **always in §36's order** — never filtered, because a skipped
 *   stage and its reason are part of the answer.
 * @property topAction the single thing the Home dashboard leads with (FOO-002): the first stage that
 *   asks for money, else the first that offers a choice, else null — "nothing to do this month".
 * @property unallocated what is left after every stage took its share. Positive only when the surplus
 *   exceeds every need, which is the app's cue that money is sitting idle.
 * @property provenance which engine, which version, when, and which rules.
 */
data class OrderOfOperations(
    val monthlySurplus: Money?,
    val surplusBasis: SurplusBasis,
    val stages: List<StageOutcome>,
    val topAction: StageOutcome?,
    val unallocated: Money,
    val provenance: EngineProvenance,
) {
    init {
        require(stages.map { it.stage } == FooStage.entries) {
            "A ranking reports all eight stages in §36's order, got ${stages.map { it.stage }}"
        }
        require(topAction == null || topAction in stages) { "The top action must be one of the stages" }
        require(provenance.evidence.isNotEmpty()) {
            "A ranking names the rules that shaped it (P-02, AI-ARC-006)"
        }
        require(unallocated >= Money.ZERO) { "The waterfall placed more than it had: $unallocated left" }
        // The invariant that makes a waterfall trustworthy, the one GoalWaterfall asserts too: it
        // neither creates nor loses a paise. A stage that quietly rounded would pass every test that
        // looked at one stage at a time.
        val distributable = maxOf(Money.ZERO, monthlySurplus ?: Money.ZERO)
        val placed = stages.fold(unallocated) { sum, stage -> sum + stage.amountMonthly }
        require(placed == distributable) {
            "The stages took $placed including what was left over, but $distributable was poured"
        }
    }
}

/**
 * One stage's verdict (issue 7.5; §36).
 * Changelog: 2026-09-17 — Created for issue 7.5.
 *
 * @property stage which of §36's eight stages this is.
 * @property status what the stage asks of the user — see [StageStatus].
 * @property need what the stage needs in full, or **null when that cannot be known** (a skipped
 *   stage, or an emergency fund that cannot be sized). A stock for the buffer and the debts, a
 *   monthly figure for the goals — the stage says which, through its [reason].
 * @property amountMonthly what this month's surplus can give it. Never more than [need].
 * @property reason why the stage came out as it did, as an enum the feature module words.
 * @property debts the debts this stage covers, highest rate first. Empty for every other stage.
 * @property comparisonBps a rate to show beside the debts: for Stage 6, the expected equity return a
 *   grey-zone payoff competes with. Null elsewhere.
 * @property citations the FOO stage and every rule that shaped this stage (P-02).
 */
data class StageOutcome(
    val stage: FooStage,
    val status: StageStatus,
    val need: Money?,
    val amountMonthly: Money,
    val reason: StageReason,
    val debts: List<DebtPosition> = emptyList(),
    val comparisonBps: Int? = null,
    val citations: List<RuleCitation>,
) {
    init {
        require(amountMonthly >= Money.ZERO) { "A stage cannot take a negative amount: $amountMonthly" }
        require(need == null || amountMonthly <= need) {
            "$stage took $amountMonthly but only needed $need — a waterfall fills a claim, never overfills it"
        }
        require(citations.isNotEmpty()) { "$stage must cite the rule that placed it (P-02)" }
    }
}

/**
 * §36's eight stages, in their fixed order (issue 7.5).
 * Changelog: 2026-09-17 — Created for issue 7.5.
 *
 * Declaration order **is** the ranking; `OrderOfOperationsRulesDriftTest` asserts it matches the
 * file's `stages` array exactly, so reordering either one fails the build.
 *
 * @property fileId the stage's `id` in `financial-order-of-operations.json`.
 */
enum class FooStage(val fileId: String) {
    /** Stage 0 — `min(₹50,000, 1 month essentials)`, instant access. */
    STARTER_BUFFER("STARTER_BUFFER"),

    /** Stage 1 — employer EPF captured, VPF headroom. Not assessable yet. */
    CAPTURE_EPF_VPF("CAPTURE_EPF_VPF"),

    /** Stage 2 — any APR at or above the fire threshold. */
    KILL_FIRE_DEBT("KILL_FIRE_DEBT"),

    /** Stage 3 — the full emergency fund, M months of essentials (§10). */
    FULL_EMERGENCY("FULL_EMERGENCY"),

    /** Stage 4 — 80C and NPS headroom, only if the old regime wins (§38). Not assessable yet. */
    TAX_ADVANTAGED("TAX_ADVANTAGED"),

    /** Stage 5 — the user's goals, bucketed by horizon (`RULE-HORIZON`). */
    GOAL_INVESTING("GOAL_INVESTING"),

    /** Stage 6 — debt in the grey band, where payoff and investing are a close call. */
    GREY_ZONE_DEBT("GREY_ZONE_DEBT"),

    /** Stage 7 — low-rate debt, deferred to the prepay-vs-invest simulator. */
    LOW_RATE_DEBT("LOW_RATE_DEBT"),
}

/**
 * What a stage asks of the user (issue 7.5).
 * An enum, not a sentence: the domain decides what is true, the feature module decides the words.
 * Changelog: 2026-09-17 — Created for issue 7.5.
 */
enum class StageStatus {
    /** The stage needs money. The first of these is the top action. */
    ACTION,

    /** The stage's need is already met. */
    SATISFIED,

    /** The stage has a need, but `RULE-EMERG-FIRST` holds it at zero until the buffer is built. */
    BLOCKED,

    /** The stage cannot be assessed with what the app knows yet. [StageReason] says what is missing. */
    SKIPPED,

    /** Nothing in this stage applies to this user — no such debt, no goals. */
    NOT_APPLICABLE,

    /**
     * A close call the user makes with the numbers shown (§36 Stage 6: "user chooses with the math
     * shown"). The amount is offered, not recommended over investing.
     */
    CHOICE,

    /** §36 Stage 7 hands the decision to the prepay-vs-invest simulator; no amount is proposed. */
    DEFER_TO_SIMULATOR,
}

/**
 * Why a stage came out as it did (issue 7.5; P-02).
 * Changelog: 2026-09-17 — Created for issue 7.5.
 */
enum class StageReason {
    /** Stage 0: liquid funds are below the starter buffer. */
    BUFFER_SHORT,

    /** Stage 0: essentials are unknown, so the buffer was sized by its cap alone. */
    BUFFER_SHORT_ESSENTIALS_UNKNOWN,

    /** Stage 0: liquid funds already cover the starter buffer. */
    BUFFER_HELD,

    /** Stage 1: the app holds no EPF contribution data to verify. */
    NO_EPF_DATA,

    /** Stage 2: at least one debt is at or above the fire threshold. */
    FIRE_DEBT_OUTSTANDING,

    /**
     * Stage 2: a card with a balance has no APR recorded, and was counted as fire debt — §36 names
     * credit cards as 36–42%. The screen says the rate was assumed.
     */
    FIRE_DEBT_CARD_RATE_UNKNOWN,

    /** Stage 2: no debt at or above the fire threshold. */
    NO_FIRE_DEBT,

    /** Stage 3: the fund cannot be sized because the essentials are unknown. */
    EMERGENCY_UNSIZED,

    /** Stage 3: runway is below `RULE-EMERG-FIRST`'s minimum, so every later stage waits. */
    EMERGENCY_BELOW_GATE,

    /** Stage 3: past the gate, still short of the full target; funded at AI-EMF's pace. */
    EMERGENCY_BUILDING,

    /** Stage 3: the full emergency fund is in place. */
    EMERGENCY_FUNDED,

    /** Stages 5–7: held at zero by `RULE-EMERG-FIRST`. */
    EMERGENCY_GATE,

    /** Stage 4: needs the §38 regime comparator (issue 13.4), which is not built. */
    NO_REGIME_COMPARATOR,

    /** Stage 5: the goals need money this month. */
    GOALS_NEED_FUNDING,

    /** Stage 5: the goals need nothing this month. */
    GOALS_ON_TRACK,

    /** Stage 5: the user has no active goals. */
    NO_GOALS,

    /** Stage 6: at least one debt is in the grey band. */
    GREY_DEBT_OUTSTANDING,

    /** Stage 6: no debt in the grey band. */
    NO_GREY_DEBT,

    /** Stage 7: low-rate debt exists; the simulator that decides it (issue 10.3) is not built. */
    LOW_RATE_DEBT_SIMULATOR_NOT_BUILT,

    /** Stage 7: no low-rate debt. */
    NO_LOW_RATE_DEBT,
}

/**
 * How the rest of the app gets an [OrderOfOperationsEngine] (ARC-003).
 *
 * Why:  the implementation is `internal`, so a Hilt module in `:app` cannot name it. This is the one
 *       seam it can, matching `GoalWaterfallEngineFactory`.
 * Result: the production engine. Input: none. Output: [OrderOfOperationsEngine].
 * Changelog: 2026-09-17 — Created for issue 7.5.
 */
object OrderOfOperationsEngineFactory {
    /** Result: the production engine. Input: none. Output: [OrderOfOperationsEngine]. */
    fun create(): OrderOfOperationsEngine = DefaultOrderOfOperationsEngine()
}
