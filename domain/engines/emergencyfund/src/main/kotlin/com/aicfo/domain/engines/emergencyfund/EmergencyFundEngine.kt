package com.aicfo.domain.engines.emergencyfund

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import java.time.LocalDate

/**
 * Answers "how long could I live on what I have, and how long should I be able to?" (issue 7.2;
 * SRS §10.1, §15, §36, AI-EMF).
 *
 * Why:  §10.1 calls the runway "the headline metric", and until this engine existed the app had no
 *       way to compute it. `RULE-RUNWAY-M` has named `AI-EMF` as its `multiplier_source` since the
 *       rulebook was written, `RULE-EMERG-FIRST` gates the goal waterfall on a runway nothing
 *       produced, and `QuickSetupEngine` shipped a **stand-in** — a flat three-month target sized
 *       off whatever the user typed at onboarding. This is the engine all three were waiting for.
 *
 *       **The division is trivial; deciding what to divide is not.** A fund is only as good as the
 *       spending it has to cover, so the essentials figure is a median of what the user actually
 *       spent rather than what they once declared — and when there is no history, saying so is
 *       better than sizing a target off nothing.
 * What: one method. The multiplier M from the base months and the income's volatility, the target
 *       it implies, the runway the user's liquid funds actually buy, the shortfall, the monthly
 *       top-up that closes it, and which of `RULE-EMF-COACH`'s bands to say it in.
 * Result: an assessment, plus the provenance and rule citations every engine result carries
 *       (AI-ARC-003). Every figure is exact paise (MNY-001) and every ratio integer basis points
 *       (MNY-002) — there is no `Double` anywhere in this module.
 * Changelog: 2026-09-02 — Created for issue 7.2.
 *
 * **It decides nothing about what counts as essential or what counts as liquid.** Both arrive
 * resolved: which categories are `NEED` is `AI-NATURE`'s question and which accounts are spendable
 * is `EmergencyFundRepository`'s (ARC-005) — the same division of labour `GoalEngine` and
 * `SafeToSpendEngine` keep to, and what makes the whole calculation provable on the JVM.
 *
 * **It advises, it does not instruct** (P-07). Nothing here moves money; the top-up is a figure the
 * user may act on, ignore, or argue with, and §10.1's coach language is framing, not an order.
 *
 * Pure Kotlin (ARC-002); the caller supplies the day, so no wall clock is read here (TIM-001).
 */
interface EmergencyFundEngine {
    /**
     * Assesses the emergency fund described by [input].
     *
     * Why:    a `Result` matching every other engine in this codebase, though a well-formed input
     *         cannot fail — the `Err` branch is reserved for arithmetic that will not fit in a
     *         `Long`, which `Money` raises rather than wrapping (MNY-001). An engine that let that
     *         escape would crash a screen instead of degrading it (§21.6: no exceptions across
     *         layer boundaries).
     * What:   the six figures of §10.1, in the order they depend on each other.
     * Result: `Ok(plan)` for any input whose terms fit in a `Long`. **An input with no essentials
     *         is not an error**: it yields [EmergencyStatus.UNKNOWN], because a user the app has
     *         never watched spend has an unanswerable question, not a broken one.
     * Input:  [input] — the essentials, the income history, the liquid funds, the day, the caller's
     *         instant, and the thresholds.
     * Output: `Result<EmergencyFundPlan, AppError>`.
     */
    fun assess(input: EmergencyFundInput): Result<EmergencyFundPlan, AppError>
}

/**
 * Input to [EmergencyFundEngine.assess] (issue 7.2).
 *
 * Changelog: 2026-09-02 — Created for issue 7.2.
 *
 * @property monthlyEssentials what a month of essential living costs, in paise (MNY-001), as the
 *   caller resolved it. **Null means "not known yet"** — a fresh install with no ledger and no
 *   declared envelope — and is reported rather than replaced by a guess (P-03).
 * @property essentialsBasis where [monthlyEssentials] came from, carried onto the result so the
 *   screen can say whether the target rests on observed spending or on a figure typed at
 *   onboarding. §10.1 requires every number to link to its evidence.
 * @property monthlyIncomes total income per **whole closed month**, oldest first, in paise. The
 *   volatility term is measured from these; the live month is excluded by the caller because a
 *   half-elapsed month is not a month's income and would read as a collapse every time.
 * @property liquidFunds what could actually be spent today — savings and cash, per ADR-0034. Never
 *   negative: an overdrawn account is not negative liquidity, it is a liability, and the caller
 *   drops it rather than netting it off.
 * @property liquidAccountNames the accounts that made up [liquidFunds], for the evidence §10.1
 *   requires. Names only; no balances, so nothing here is a per-account amount to leak.
 * @property essentialCategoryNames the categories that counted as essential, for the same reason.
 * @property today the day to reckon from, **already resolved in the profile's time zone** by the
 *   caller (TIM-001) — this module reads no clock of its own.
 * @property nowUtcMillis the caller's instant, stamped onto the provenance and **never read as a
 *   clock** — the shape `GoalPlanInput` and `SafeToSpendInput` already use.
 * @property rules the thresholds to apply. Injected so a test can move a band and assert the engine
 *   moves with it, which is also the seam the runtime rules loader will use.
 */
data class EmergencyFundInput(
    val monthlyEssentials: Money?,
    val essentialsBasis: EssentialsBasis,
    val monthlyIncomes: List<Money> = emptyList(),
    val liquidFunds: Money = Money.ZERO,
    val liquidAccountNames: List<String> = emptyList(),
    val essentialCategoryNames: List<String> = emptyList(),
    val today: LocalDate,
    val nowUtcMillis: Long = 0L,
    val rules: EmergencyFundRules = EmergencyFundRules(),
) {
    init {
        require(monthlyEssentials == null || monthlyEssentials >= Money.ZERO) {
            "Monthly essentials are a magnitude and must not be negative, was $monthlyEssentials: a " +
                "negative cost of living would make the target a refund"
        }
        require(liquidFunds >= Money.ZERO) {
            "Liquid funds are a magnitude and must not be negative, was $liquidFunds: an overdrawn " +
                "account is a liability, and netting it against savings would understate a runway " +
                "the user could genuinely spend"
        }
        require(monthlyIncomes.all { it >= Money.ZERO }) {
            "A month's income is a magnitude; a negative month is a classification bug upstream, not " +
                "a month the user paid their employer"
        }
        // The basis and the figure are two halves of one fact. Letting them disagree would let a
        // screen say "based on your last six months" beside a target built from nothing at all.
        require((monthlyEssentials == null) == (essentialsBasis == EssentialsBasis.NONE)) {
            "essentialsBasis ($essentialsBasis) must be NONE exactly when monthlyEssentials is null"
        }
    }
}

/**
 * Where the essentials figure came from (issue 7.2; §10.1).
 *
 * Why:  §10.1 requires every number to link to its evidence, and "₹40,000 a month" means something
 *       different depending on whether the app watched it happen or the user typed it once at
 *       onboarding. An enum rather than a sentence, so a feature module owns the wording.
 * Changelog: 2026-09-02 — Created for issue 7.2.
 */
enum class EssentialsBasis {
    /** The median of the `NEED` spend in the closed months the lookback covers. The best answer. */
    OBSERVED_MEDIAN,

    /** The quick-setup needs envelope, used when there is not enough history to take a median. */
    DECLARED_ENVELOPE,

    /** Neither was available. The target is unknown and is reported as such, never as zero. */
    NONE,
}

/**
 * What [EmergencyFundEngine.assess] decided (issue 7.2; AI-ARC-003, §10.1).
 *
 * Changelog: 2026-09-02 — Created for issue 7.2.
 *
 * @property monthlyEssentials the figure the target was sized from, echoed back; null when unknown.
 * @property essentialsBasis where that figure came from.
 * @property incomeCvBps the coefficient of variation of monthly income, in basis points (MNY-002) —
 *   3 000 is a cv of 0.30. **Null when fewer than `min_months_observed` months were supplied**, or
 *   when the mean was not positive; the volatility bump is then zero rather than the maximum,
 *   because no history is not evidence of a lumpy income.
 * @property multiplierMonths the personal multiplier M, **after** `RULE-RUNWAY-M`'s clamp.
 * @property multiplierWasClamped whether the clamp actually changed the answer — the test of
 *   whether `RULE-RUNWAY-M` *fired*, and the reason it is cited in [provenance] only sometimes.
 * @property target `monthlyEssentials × multiplierMonths`. `Money.ZERO` when the essentials are
 *   unknown, which is why [status] and not this field is what a screen leads with.
 * @property liquidFunds what is spendable today, echoed back.
 * @property fundedRatioBps how much of [target] is funded, in basis points; 10 000 is fully funded
 *   and the figure is **not** capped there, so an over-funded fund reads above 100%. Zero when the
 *   target is unknown.
 * @property shortfall `target − liquidFunds`, floored at zero. Never negative: a surplus is a state,
 *   not a debt the fund owes back.
 * @property runwayMonthsBps months of essentials the liquid funds buy, in basis points of a month —
 *   **15 000 is 1.5 months**. In bps rather than a decimal because MNY-002 admits no floating point,
 *   and a whole-number month count would round a 29-day cushion up to "1 month" or down to "0".
 *   Null when the essentials are unknown or zero, where the division has no meaning.
 * @property topUpMonthly what closing [shortfall] over [multiplierMonths] costs each month — the
 *   **largest** of the instalments `Money.split` produces, for the reason
 *   `GoalProjection.requiredMonthly` gives: the odd paise go to the earliest parts, so quoting the
 *   smallest would leave the fund short. Zero when there is no shortfall.
 * @property status the single verdict a screen leads with, from `RULE-EMF-COACH`.
 * @property liquidAccountNames which accounts counted as liquid (§10.1's evidence requirement).
 * @property essentialCategoryNames which categories counted as essential (the same requirement).
 * @property provenance which engine, which version, when, and **which rules** — the citations are
 *   required by the `init` below, so an assessment that cannot name the rules that shaped it cannot
 *   be constructed at all (P-02, AI-ARC-006).
 */
data class EmergencyFundPlan(
    val monthlyEssentials: Money?,
    val essentialsBasis: EssentialsBasis,
    val incomeCvBps: Int?,
    val multiplierMonths: Int,
    val multiplierWasClamped: Boolean,
    val target: Money,
    val liquidFunds: Money,
    val fundedRatioBps: Int,
    val shortfall: Money,
    val runwayMonthsBps: Int?,
    val topUpMonthly: Money,
    val status: EmergencyStatus,
    val liquidAccountNames: List<String>,
    val essentialCategoryNames: List<String>,
    val provenance: EngineProvenance,
) {
    init {
        require(provenance.evidence.isNotEmpty()) {
            "An emergency-fund assessment names the rules that shaped it (P-02, AI-ARC-006)"
        }
    }

    /**
     * Whether the fund covers the target.
     *
     * Why:    two of the five statuses mean "yes" and three mean "no", and a screen that wants the
     *         boolean should not have to know which is which — nor re-derive it from the shortfall
     *         and get the zero-shortfall-with-unknown-target case wrong.
     * Result: true only when a target is actually known and met. **[EmergencyStatus.UNKNOWN] is
     *         false**: an unanswered question is not a funded fund.
     * Input:  none. Output: [Boolean].
     */
    val isFunded: Boolean
        get() = status == EmergencyStatus.FUNDED || status == EmergencyStatus.SURPLUS
}

/**
 * The verdict an emergency-fund card leads with (`RULE-EMF-COACH`, §10.1).
 *
 * An enum, not a sentence: the domain decides *what is true*, a feature module decides how to say it
 * in the user's language — the same split `GoalStatus` and `SafeToSpendComponent` keep. Declaration
 * order is severity order, worst last, so a caller can sort by attention needed without a second
 * table.
 *
 * Changelog: 2026-09-02 — Created for issue 7.2.
 */
enum class EmergencyStatus {
    /**
     * Runway exceeds the target by more than `surplus_above_target_months`. §10.1 suggests
     * deploying the excess — as a suggestion, never an instruction (P-07).
     */
    SURPLUS,

    /** Liquid funds cover the target. §10.1's "celebrate, suggest redirecting surplus to goals". */
    FUNDED,

    /** Under the target but above `urgent_below_months`. §10.1's "build a plan". */
    BUILDING,

    /**
     * The essentials are not known, so no target can be sized.
     *
     * Placed here rather than at either end for the reason `GoalStatus.NO_TARGET` sits between
     * `ON_TRACK` and `BEHIND`: it needs attention, but it is not the emergency [URGENT] is.
     */
    UNKNOWN,

    /** Under `urgent_below_months` of runway. §10.1's urgent framing; pausing goals is suggested. */
    URGENT,
}

/**
 * How the rest of the app gets an [EmergencyFundEngine] (ARC-003).
 *
 * Why:  the implementation is `internal`, so a Hilt module in `:app` cannot name it. This is the one
 *       seam it can, matching `GoalEngineFactory` and `SafeToSpendEngineFactory`.
 * Result: the production engine. Input: none. Output: [EmergencyFundEngine].
 * Changelog: 2026-09-02 — Created for issue 7.2.
 */
object EmergencyFundEngineFactory {
    /** Result: the production engine. Input: none. Output: [EmergencyFundEngine]. */
    fun create(): EmergencyFundEngine = DefaultEmergencyFundEngine()
}
