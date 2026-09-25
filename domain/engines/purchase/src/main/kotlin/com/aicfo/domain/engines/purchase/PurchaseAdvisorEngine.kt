package com.aicfo.domain.engines.purchase

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import java.time.LocalDate

/**
 * AI-PA — can I afford this, and why (issue 10.1; SRS §13, FR-AI-003, P-02, P-07).
 *
 * Why:  this is the question the whole app exists to answer well. Anyone can say yes or no; what
 *       makes the answer worth trusting is the working behind it — which check objected, on which
 *       figures, citing which rule. §13 sets seven gates and takes the worst outcome across them,
 *       so a single sentence ("₹80,000 would take you below your emergency fund") can always be
 *       traced to the arithmetic that produced it.
 * What: the seven gates, the verdict, the before-and-after strip, and the alternatives. It decides
 *       nothing on the user's behalf and moves no money (P-07) — it advises, and the card is kept
 *       so the reasoning survives (AI-ARC-006).
 * Result: a [PurchaseVerdictCard].
 * Changelog: 2026-09-25 — Created for issue 10.1.
 *
 * Input:  [PurchaseInput]. Output: `Result<PurchaseVerdictCard, AppError>`; `Err` only for an
 *         impossible request (a negative price, a nameless item, an EMI with no instalment).
 *
 * **Pure** (ARC-002, P-08): no clock, no I/O, no other engine module. The caller supplies today's
 * date and the figures the engines below already published, so the same request always gives the
 * same verdict — which is what lets a card from March still be read in December.
 */
interface PurchaseAdvisorEngine {
    /** Judges one purchase. See the interface's doc. */
    fun advise(input: PurchaseInput): Result<PurchaseVerdictCard, AppError>
}

/**
 * Builds the one [PurchaseAdvisorEngine] (ARC-003 — the implementation stays `internal`).
 * Changelog: 2026-09-25 — Created for issue 10.1.
 */
object PurchaseAdvisorEngineFactory {
    /** Result: the §13 advisor. Input: none. Output: [PurchaseAdvisorEngine]. */
    fun create(): PurchaseAdvisorEngine = GatedPurchaseAdvisorEngine()
}

/**
 * How the purchase would be paid for (§13, FR-AI-003).
 * Changelog: 2026-09-25 — Created for issue 10.1.
 */
enum class PaymentMethod {
    /** Money leaves now. */
    CASH,

    /** Money leaves now, from a card that will be settled this cycle — the same cash question. */
    CARD,

    /** Money leaves monthly, and adds to the obligation ratio (RULE-EMI-40). */
    EMI,
}

/**
 * How much the purchase can wait — §13's "softened by urgency flag".
 * Changelog: 2026-09-25 — Created for issue 10.1.
 */
enum class Urgency {
    /** It can wait. The default, and the one that gets the plain answer. */
    ROUTINE,

    /** Wanted soon, but not a crisis. */
    SOON,

    /** A broken fridge, a medical bill. Softens the verdict by one step, never past a hard fail. */
    URGENT,
}

/** What one gate concluded. Changelog: 2026-09-25 — Created for issue 10.1. */
enum class GateOutcome {
    /** Nothing to say about this one. */
    PASS,

    /** Worth knowing before deciding. */
    WARN,

    /** This purchase breaks something. */
    FAIL,
}

/** §13.1's verdict. Changelog: 2026-09-25 — Created for issue 10.1. */
enum class Verdict {
    /** Every gate passed. */
    COMFORTABLE,

    /** Possible, with something to accept. */
    STRETCH,

    /** Something is broken by it; the card says what, and when to revisit. */
    NOT_NOW,
}

/** §13.1's seven checks, in the order they are run. Changelog: 2026-09-25 — Created for issue 10.1. */
enum class GateId {
    /** Can the money absorb it without breaching the emergency floor? */
    AFFORDABILITY,

    /** Does the ninety-day forecast dip under its buffer because of it? */
    CASH_FLOW,

    /** Would EMIs and rent pass 40% of income, or 50% (RULE-EMI-40)? */
    OBLIGATIONS,

    /** How much later do the goals arrive? */
    GOAL_IMPACT,

    /** Is there room left in this category's budget this month? */
    BUDGET_FIT,

    /** What the money would have become if invested instead (RULE-PA-OPPCOST). */
    OPPORTUNITY_COST,

    /** Is there a month ahead where this costs less? */
    TIMING,
}

/**
 * One figure a gate judged, named so the screen can label it and the guardrail can verify it.
 *
 * Why:  a gate states numbers, and P-02 means the user sees them. They are carried as typed values
 *       with a key rather than as a formatted sentence, because the engine writes no English
 *       (§21.6 keeps user-visible words in `strings.xml`) and because AI-GRD checks a figure
 *       against the value, not against a string the engine chose.
 * Input:  [key] — stable, the screen's lookup; exactly one of [amount], [count], [bps] or [text].
 * Output: an immutable value.
 * Changelog: 2026-09-25 — Created for issue 10.1.
 */
data class GateFigure(
    val key: String,
    val amount: Money? = null,
    val count: Int? = null,
    val bps: Int? = null,
    val text: String? = null,
)

/**
 * What one gate concluded, and on what.
 * Input:  [gate]; [outcome]; [figures] — everything it judged; [citations] — the rules behind it,
 *         never empty (P-02). Output: an immutable value.
 * Changelog: 2026-09-25 — Created for issue 10.1.
 */
data class GateResult(
    val gate: GateId,
    val outcome: GateOutcome,
    val figures: List<GateFigure> = emptyList(),
    val citations: List<RuleCitation> = emptyList(),
) {
    init {
        require(citations.isNotEmpty()) { "a gate that cites no rule cannot answer 'why am I seeing this?' (P-02)" }
    }
}

/**
 * What the purchase moves — §13.2's impact strip.
 * Input:  [liquidBefore] / [liquidAfter]; [runwayMonthsBeforeTenths] / [runwayMonthsAfterTenths] —
 *         months of essentials covered, in tenths so a Long stays a Long (MNY-001: no floats near
 *         money, and a runway is money divided by money); [goalDelayDays].
 * Output: an immutable value.
 * Changelog: 2026-09-25 — Created for issue 10.1.
 */
data class ImpactStrip(
    val liquidBefore: Money,
    val liquidAfter: Money,
    val runwayMonthsBeforeTenths: Int,
    val runwayMonthsAfterTenths: Int,
    val goalDelayDays: Int,
)

/**
 * §13.2's alternatives — what would make the answer different.
 * Input:  [comfortablePrice] — the highest price at which every gate would pass, `null` when even
 *         nothing would not help; [comfortableFrom] — when saving at the current rate covers this
 *         price above the emergency floor, `null` when it already does; [coolOffSuggested] —
 *         RULE-COOL-OFF's 24-hour pause for a purchase big against annual income.
 * Output: an immutable value.
 * Changelog: 2026-09-25 — Created for issue 10.1.
 */
data class Alternatives(
    val comfortablePrice: Money? = null,
    val comfortableFrom: LocalDate? = null,
    val coolOffSuggested: Boolean = false,
)

/**
 * What the user asked about.
 * Input:  [item] — their own words for it, never blank; [price] — MNY-001 paise; [method];
 *         [urgency]; [monthlyEmi] — required for [PaymentMethod.EMI] and ignored otherwise;
 *         [categoryId] — the budget this would come out of, when known.
 * Output: an immutable value.
 * Changelog: 2026-09-25 — Created for issue 10.1.
 */
data class PurchaseRequest(
    val item: String,
    val price: Money,
    val method: PaymentMethod = PaymentMethod.CASH,
    val urgency: Urgency = Urgency.ROUTINE,
    val monthlyEmi: Money? = null,
    val categoryId: String? = null,
)

/**
 * The figures the engines below AI-PA have already published (AI-ARC-001).
 *
 * Why:  small typed signals rather than the other engines' result types, for the reason ADR-0046
 *       gives for AI-ORCH: an L5 engine that imported AI-FCT, AI-EMF and AI-GOAL could start
 *       re-deriving what they own, and then two parts of the app would disagree about the same
 *       number. The repository maps each one across.
 * Input:  [liquidFunds]; [emergencyFloor] — AI-EMF's target, the line the advisor will not cross
 *         silently; [monthlyEssentials] — for the runway; [safeToSpend] — AI-STS's figure for this
 *         month; [forecastLowest] — AI-FCT's lowest projected day, `null` with too little history;
 *         [forecastBuffer]; [forecastCrunchDays] — how many days are *already* under it;
 *         [monthlyIncome]; [monthlyObligations] — EMIs and rent today; [goalContributionsMonthly] —
 *         what goes to goals each month; [categoryRemaining] — what is left in this category's
 *         budget, `null` when none is set; [cheaperMonth] — AI-SEAS's nearest cheaper month.
 * Output: an immutable value.
 * Changelog: 2026-09-25 — Created for issue 10.1.
 */
data class PurchaseSignals(
    val liquidFunds: Money,
    val emergencyFloor: Money,
    val monthlyEssentials: Money,
    val safeToSpend: Money,
    val forecastLowest: Money?,
    val forecastBuffer: Money,
    val forecastCrunchDays: Int,
    val monthlyIncome: Money,
    val monthlyObligations: Money,
    val goalContributionsMonthly: Money,
    val categoryRemaining: Money?,
    val cheaperMonth: CheaperMonth? = null,
)

/**
 * A month ahead where this would cost less (§13.1 step 7).
 * Input:  [month] — ISO `yyyy-MM`; [saving] — what the season would knock off.
 * Output: an immutable value.
 * Changelog: 2026-09-25 — Created for issue 10.1.
 */
data class CheaperMonth(
    val month: String,
    val saving: Money,
)

/**
 * What AI-PA reads.
 * Input:  [request]; [signals]; [today] — the profile's date (TIM-001 resolves it before the engine
 *         sees it); [nowUtcMillis] — stamped on provenance; [rules].
 * Output: an immutable value.
 * Changelog: 2026-09-25 — Created for issue 10.1.
 */
data class PurchaseInput(
    val request: PurchaseRequest,
    val signals: PurchaseSignals,
    val today: LocalDate,
    val nowUtcMillis: Long,
    val rules: PurchaseRules = PurchaseRules(),
)

/**
 * The reasoning card (§13.2) — the whole answer, kept so it can be read again later (AI-ARC-006).
 * Input:  [request]; [verdict]; [gates] — in §13.1's order; [impact]; [alternatives];
 *         [hardFail] — whether a gate failed outright, which is what urgency may not soften;
 *         [provenance].
 * Output: an immutable value.
 * Changelog: 2026-09-25 — Created for issue 10.1.
 */
data class PurchaseVerdictCard(
    val request: PurchaseRequest,
    val verdict: Verdict,
    val gates: List<GateResult>,
    val impact: ImpactStrip,
    val alternatives: Alternatives,
    val hardFail: Boolean,
    val provenance: EngineProvenance,
)
