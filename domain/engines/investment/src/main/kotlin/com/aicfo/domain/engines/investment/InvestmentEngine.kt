package com.aicfo.domain.engines.investment

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.AssetClass
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.InvestmentHolding
import com.aicfo.core.model.InvestmentLot
import com.aicfo.core.model.Money
import com.aicfo.core.model.Quantity
import com.aicfo.core.model.RuleCitation

/**
 * What a holding earned, and what it is worth (issue 6.3; §11 AI-INV, P-03, P-08).
 *
 * Why:  an investment account's balance answers "how much is in there" and nothing else. The
 *       question a person actually has is "did this do well", and the honest answer to that is
 *       money-weighted: a SIP that put ₹5,000 in every month for a year has most of its money
 *       invested for far less than a year, so a naive cost-to-value ratio understates the return by
 *       a factor of roughly two. §11.2 names XIRR for exactly this reason, and §11.1 draws the line
 *       this engine sits on — it *measures*, and never recommends a security (P-07).
 *
 *       **Two operations rather than one**, for the reason the card and loan engines give: the
 *       accounts list wants one holding's headline figures, while issue 6.4's portfolio XIRR will
 *       want a rate over cash flows that have already been pooled across holdings. Pooling is the
 *       caller's business; solving the rate is this engine's, so the rate is exposed on its own.
 * What: [xirr] over an arbitrary dated series, and [holding] over one holding's lots and price.
 * Result: a rate in basis points, and a holding's value, cost, gain and return.
 * Changelog: 2026-08-24 — Created for issue 6.3 (§11).
 *
 * **Numbers only (P-03).** Nothing here produces a word. The reason a figure is unavailable is an
 * enum, not a sentence, so `:feature:*` can render it in the user's language and the numeric
 * guardrail has an engine result to verify prose against (AI-ARC-004).
 *
 * **Reads no clock.** A holding's terminal cash flow is dated by the day its price was observed
 * (`InvestmentHolding.pricedOnIsoDate`), never by today — so the same holding gives the same answer
 * tomorrow, which is what P-08 requires and what "today" would quietly break.
 */
interface InvestmentEngine {
    /**
     * Solves the money-weighted return over a dated cash-flow series.
     * Why:    exposed separately from [holding] so issue 6.4 can pool flows across holdings and
     *         reuse the identical solver rather than growing a second one that rounds differently.
     * Result: [Ok] with the annualised rate, or [Err] naming which of the three refusals applied.
     * Input:  [input] — the flows and a provenance timestamp.
     * Output: `Result<XirrRate, AppError>`.
     * Changelog: 2026-08-24 — Created for issue 6.3.
     */
    fun xirr(input: CashFlowSeriesInput): Result<XirrRate, AppError>

    /**
     * Values one holding and measures what it returned.
     * Why:    the accounts list and the holdings screen both want the same five figures, and
     *         deriving them at two call sites is how two screens come to disagree about one rupee.
     * Result: [Ok] with the performance. Unlike [xirr] this does **not** fail when a rate cannot be
     *         found: an unpriced or freshly-created holding is the ordinary state of a new position,
     *         so the reason lands in [HoldingPerformance.xirrUnavailable] and every other figure is
     *         still computed.
     * Input:  [input] — the holding, its lots, and a provenance timestamp.
     * Output: `Result<HoldingPerformance, AppError>`.
     * Changelog: 2026-08-24 — Created for issue 6.3.
     */
    fun holding(input: HoldingInput): Result<HoldingPerformance, AppError>

    /**
     * Splits a portfolio by asset class and flags what has grown too large (issue 6.4; FR-INV-002).
     * Why:    a holding seen alone tells the user nothing about shape. §11.2 asks for "actual
     *         allocation across equity / debt / gold / real-estate / cash / crypto" and for
     *         concentration flags, and both are the same arithmetic over the same denominator — so
     *         computing them in one pass is what stops a slice and a flag disagreeing about what
     *         the portfolio totals.
     * Result: [Ok] with the split, the flags and the coverage. Like [holding] and unlike [xirr] this
     *         does **not** fail when there is nothing to split: an empty or wholly unpriced
     *         portfolio is the ordinary state of a new account, so the reason lands in
     *         [PortfolioAllocation.unavailable] and the caller still has a result to render.
     * Input:  [input] — the positions, the thresholds to apply, and a provenance timestamp.
     * Output: `Result<PortfolioAllocation, AppError>`.
     * Changelog: 2026-08-28 — Created for issue 6.4 (§11, FR-INV-002).
     */
    fun allocation(input: AllocationInput): Result<PortfolioAllocation, AppError>
}

/**
 * One dated movement of cash, signed (issue 6.3; §11).
 *
 * Why:  the engine's own currency, distinct from [InvestmentLot]: a lot stores a magnitude and a
 *       kind because a row must not be able to disagree with itself, while a *flow* is the signed
 *       thing the arithmetic needs. Converting once, in [CashFlows], keeps the sign convention in
 *       one place.
 * What: a day and an amount, negative when money left the user.
 * Result: the atom [InvestmentEngine.xirr] runs over.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 *
 * @property onIsoDate ISO `yyyy-MM-dd` day the money moved (TIM-002).
 * @property amount paise, signed: negative is money out, positive is money in (MNY-001).
 */
data class CashFlow(
    val onIsoDate: String,
    val amount: Money,
)

/**
 * A solved money-weighted return (issue 6.3; §11, MNY-002).
 *
 * Why:  the rate alone would be a black-box verdict. [flowCount] and [spanDays] are the inputs the
 *       drill-down shows beside it, which is what P-02 asks for: "16.1% over 888 days from 4 cash
 *       flows" is checkable, "16.1%" is not.
 * What: the rate and the two facts about the series that produced it.
 * Result: the number the UI renders and the guardrail verifies prose against.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 *
 * @property rateBps annualised rate in integer basis points; 1000 is 10%, negative is a loss.
 * @property flowCount how many flows were solved over, **after** same-day coalescing.
 * @property spanDays days from the first flow to the last, ACT.
 * @property provenance which engine and version produced this (AI-ARC-003).
 */
data class XirrRate(
    val rateBps: Int,
    val flowCount: Int,
    val spanDays: Int,
    val provenance: EngineProvenance,
) {
    init {
        require(flowCount >= 2) { "A rate is solved over at least two flows, was $flowCount" }
        require(spanDays > 0) { "A rate over zero days is undefined, was $spanDays" }
    }
}

/**
 * Why a holding has no money-weighted return yet (issue 6.3; §11, P-02).
 *
 * Why:  each of these is an ordinary state of a real holding, not a fault: a position bought last
 *       week and never priced has nothing to annualise. Modelling them as an enum rather than an
 *       error keeps [InvestmentEngine.holding] returning every *other* figure, and gives the UI
 *       something to explain rather than an empty cell (P-02).
 * What: the three conditions under which the solver has no answer.
 * Result: the reason the screen shows in place of a percentage.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 */
enum class XirrUnavailable {
    /**
     * Fewer than two distinct days. A single purchase is not yet a return, and neither is a
     * handful of them made on one morning: same-day flows are summed before counting, so this one
     * reason covers both. There is deliberately no separate "zero span" arm — after coalescing,
     * two distinct days always span at least one, so such an arm could never fire and would sit in
     * the enum looking like a case somebody had thought about.
     */
    TOO_FEW_FLOWS,

    /**
     * Every flow points the same way — purchases with nothing valued or sold. The ordinary state of
     * a holding whose price has not been entered, which is why it is not an error.
     */
    SAME_SIGN,

    /**
     * The rate lies outside the solver's bracket — in practice a total loss, whose only root is
     * a growth factor of zero. Reported rather than clamped: −100% and "cannot say" are different
     * claims, and only one of them is true.
     */
    NOT_BRACKETED,
}

/**
 * What one holding is worth and what it returned (issue 6.3; §11, P-02, P-03).
 *
 * Why:  the five figures a person needs about a position, computed once so the accounts list and
 *       the holdings screen cannot disagree. [invested] and [realised] are shown beside [gain]
 *       because a gain with no inputs is a verdict, and P-02 forbids those.
 * What: the derived position, value, cost, gain and rate, plus provenance.
 * Result: everything `:feature:accounts` renders for a holding.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 *
 * **Nothing here is stored.** Every field is a pure function of the holding's price and its lots,
 * which is the argument ADR-0007 makes for balances and ADR-0026 for amortisation schedules.
 *
 * @property holdingId the holding these figures describe.
 * @property name the user's label for it, carried through so the screen has one source for it
 *   rather than joining these figures back to the holding row they came from.
 * @property accountId the account it sits in — the key the accounts list groups by.
 * @property assetClass carried through for issue 6.4's allocation, which needs it beside the value.
 * @property netQuantity units still held: bought minus sold. May be zero after a full exit.
 * @property currentValue [netQuantity] x the unit price, or `null` when the holding has never been
 *   priced **and** still holds units — absent, never zero (P-03).
 * @property invested paise put in: the sum of the purchases.
 * @property realised paise taken out: sales plus income.
 * @property gain `realised + currentValue − invested`, or `null` when [currentValue] is.
 * @property xirrBps the annualised money-weighted return, or `null` when unavailable.
 * @property xirrUnavailable why there is no rate, or `null` when there is one.
 * @property provenance which engine and version produced this (AI-ARC-003).
 */
data class HoldingPerformance(
    val holdingId: String,
    val name: String,
    val accountId: String,
    val assetClass: AssetClass,
    val netQuantity: Quantity,
    val currentValue: Money?,
    val invested: Money,
    val realised: Money,
    val gain: Money?,
    val xirrBps: Int?,
    val xirrUnavailable: XirrUnavailable?,
    val provenance: EngineProvenance,
) {
    init {
        require((xirrBps == null) != (xirrUnavailable == null)) {
            "A holding has a rate or a reason it has none, never both and never neither — an empty " +
                "cell with no explanation is the black-box verdict P-02 forbids"
        }
        require((currentValue == null) == (gain == null)) {
            "A gain is only knowable when the value is: both are absent together"
        }
    }
}

/**
 * The flows to solve a rate over (issue 6.3).
 *
 * @property flows the dated, signed movements. Order does not matter — the engine sorts and
 *   coalesces same-day flows itself.
 * @property nowUtcMillis stamped into provenance only; the engine never reads it as a date
 *   (TIM-001).
 */
data class CashFlowSeriesInput(
    val flows: List<CashFlow>,
    val nowUtcMillis: Long = 0L,
)

/**
 * One holding and everything it is made of (issue 6.3).
 *
 * Why:  no `asOf` date, deliberately. The terminal flow is dated by the day the price was observed,
 *       so the answer is a function of stored facts alone and cannot change because the clock
 *       moved (P-08). A holding with no price has no terminal flow at all, which the engine reports
 *       as [XirrUnavailable.SAME_SIGN] rather than inventing one at today's date.
 *
 * @property holding the instrument, its class and its last observed unit price.
 * @property lots its dated cash movements, in any order.
 * @property nowUtcMillis stamped into provenance only (TIM-001).
 */
data class HoldingInput(
    val holding: InvestmentHolding,
    val lots: List<InvestmentLot>,
    val nowUtcMillis: Long = 0L,
)

/**
 * One thing the portfolio is made of (issue 6.4; §11.2, P-03).
 *
 * Why:  the engine is pure and has never seen an account row or a holding row, but allocation has
 *       to span both: a broker account contributes one position per holding, while a gold account
 *       the user tracks as a single balance contributes one position for itself. Flattening the two
 *       into one type in the repository — rather than teaching the engine about accounts — is what
 *       keeps the arithmetic testable from a literal list (P-08) and keeps DAOs out of `:domain:*`
 *       (ARC-005).
 * What: a value, the class it belongs to, and enough identity to name it in a flag.
 * Result: the atom [InvestmentEngine.allocation] runs over.
 * Changelog: 2026-08-28 — Created for issue 6.4.
 *
 * @property holdingId the holding this came from, or `null` when the position **is** an account
 *   counted whole. A flag against a position with no holding id points at the account instead.
 * @property accountId the account it sits in, carried so a drill-down can name where to look.
 * @property name the user's label — the holding's name, or the account's when there is no holding.
 * @property assetClass which slice it lands in. The caller has already resolved this from the
 *   holding's stored class or [AssetClass.defaultFor]; a position whose class could not be resolved
 *   is not a position and must never be constructed, because filing a debt under `OTHER` would
 *   understate every other class's share.
 * @property value paise this position is worth, or `null` when it has never been priced — absent,
 *   never zero (P-03). An unpriced position is counted in [PortfolioAllocation.unvaluedCount] and
 *   excluded from every share.
 */
data class PortfolioPosition(
    val holdingId: String?,
    val accountId: String,
    val name: String,
    val assetClass: AssetClass,
    val value: Money?,
) {
    init {
        require(accountId.isNotBlank()) { "A position must name the account it sits in" }
        require(name.isNotBlank()) { "A position the user cannot see the name of cannot be explained (P-02)" }
        // A negative position would make the shares of every other class exceed 100% while still
        // summing to 10 000 bps, so the arithmetic would look right and read as nonsense.
        require(value == null || value >= Money.ZERO) {
            "A position's value is a magnitude and cannot be negative, was ${value?.minor}: a " +
                "liability has no asset class and belongs outside the denominator entirely"
        }
    }
}

/**
 * One asset class's share of the portfolio (issue 6.4; §11.2, MNY-002).
 *
 * Why:  the value and the share travel together because the screen shows both and P-02 makes that
 *       mandatory: "gold 12%" is a verdict, "gold ₹1,20,000 of ₹10,00,000, 12%" is checkable.
 * What: a class, what it is worth, and what fraction of the whole that is.
 * Result: one legend row and one bar segment.
 * Changelog: 2026-08-28 — Created for issue 6.4.
 *
 * @property assetClass the slice.
 * @property value paise held in this class, summed over its priced positions.
 * @property shareBps its share in integer basis points; 1200 is 12%. Every slice's share sums to
 *   exactly 10 000 — the remainder is distributed, never dropped.
 */
data class AllocationSlice(
    val assetClass: AssetClass,
    val value: Money,
    val shareBps: Int,
)

/**
 * Which kind of concentration a flag is about (issue 6.4; §11.2, P-02).
 *
 * Why:  three flags can name the same class and the same amount while meaning different things —
 *       "gold is over its own 10% ceiling" is a different sentence from "gold is over 70% of
 *       everything you own", and the screen has to be able to tell them apart to say either. An
 *       enum rather than a message keeps the words in `:feature:*` (P-03).
 * What: the three breaches this engine can report.
 * Result: what the UI switches on to choose a sentence.
 * Changelog: 2026-08-28 — Created for issue 6.4.
 */
enum class ConcentrationKind {
    /** A class exceeded its own ceiling — `RULE-GOLD-CAP` or `RULE-CRYPTO-CAP`. */
    ASSET_CLASS_CAP,

    /** One position exceeded `RULE-CONC-15-70.single_holding_pct`. */
    SINGLE_HOLDING,

    /** One class exceeded `RULE-CONC-15-70.single_class_pct`. */
    SINGLE_CLASS,
}

/**
 * Something that has grown larger than a rulebook row allows (issue 6.4; §11.2, P-02, P-07).
 *
 * Why:  an observation, not an instruction. §11.1 binds this module to "analyses, educates, and
 *       flags — it does not recommend", so this type carries no action and no target: it names what
 *       was measured, the line it crossed, and the row that drew the line, and stops there (P-07).
 *       [measuredBps] and [thresholdBps] both travel because a flag that shows only one of them
 *       cannot be checked by the person reading it.
 * What: what breached, by how much, and on whose authority.
 * Result: one card on the allocation screen.
 * Changelog: 2026-08-28 — Created for issue 6.4.
 *
 * @property kind which of the three breaches this is.
 * @property assetClass the class concerned; `null` only for a [ConcentrationKind.SINGLE_HOLDING]
 *   flag, where the position rather than the class is the subject.
 * @property holdingId the position concerned, for [ConcentrationKind.SINGLE_HOLDING]; `null`
 *   otherwise, and also null when the flagged position is an account counted whole.
 * @property name what to call the subject on screen — the holding's or account's name for a
 *   single-holding flag, and empty for a class flag, whose label comes from [assetClass].
 * @property measuredBps the share actually observed, in basis points.
 * @property thresholdBps the share the rule allows, in basis points.
 * @property value paise the subject is worth, so the card can show the amount beside the share.
 * @property citation the one row that decided this. Exactly one: pointing "why am I seeing this?"
 *   at a rule that had no part in the decision is worse than citing nothing.
 */
data class ConcentrationFlag(
    val kind: ConcentrationKind,
    val assetClass: AssetClass?,
    val holdingId: String?,
    val name: String,
    val measuredBps: Int,
    val thresholdBps: Int,
    val value: Money,
    val citation: RuleCitation,
) {
    init {
        require(measuredBps > thresholdBps) {
            "A flag reports a breach, so the measured share ($measuredBps bps) must exceed the " +
                "threshold ($thresholdBps bps) — a flag at or under the line is advice the user " +
                "cannot act on and did not earn"
        }
        require(kind == ConcentrationKind.SINGLE_HOLDING || assetClass != null) {
            "A class-level flag must name the class it is about"
        }
    }
}

/**
 * Why a portfolio has no allocation to show (issue 6.4; §11.2, P-03).
 *
 * Why:  both of these are ordinary states of a real account, not faults, so they follow
 *       [XirrUnavailable]'s precedent: a reason on a successful result rather than an error, which
 *       leaves the UI something to explain instead of an empty screen (P-02). The distinction
 *       matters because the two need opposite prompts — one asks the user to add a holding, the
 *       other to price the ones they have.
 * What: the two conditions under which there is nothing to split.
 * Result: the sentence the screen shows in place of a chart.
 * Changelog: 2026-08-28 — Created for issue 6.4.
 */
enum class AllocationUnavailable {
    /** No investable account holds anything — there is nothing to allocate. */
    NO_POSITIONS,

    /**
     * Positions exist but they are collectively worth nothing, so no share can be computed.
     *
     * Either none of them carries a price — absent, never zero (P-03) — or every one of them has
     * been fully exited and is genuinely worth ₹0. The two are one arm rather than two because the
     * denominator is zero in both, and the prompt the user needs is the same: price what you hold.
     */
    NOTHING_PRICED,
}

/**
 * How a portfolio is spread, and what about that is worth mentioning (issue 6.4; FR-INV-002).
 *
 * Why:  the slices and the flags are one result rather than two calls, because they are computed
 *       over one denominator and separating them is how a screen comes to show a 12% slice beside a
 *       flag that measured 13%. [valuedCount] and [unvaluedCount] ship with them for the same
 *       reason: a split that silently excluded a third of the portfolio would be a confident wrong
 *       answer, and P-02 requires the user be told what the figure was computed from.
 * What: the total, the split, the breaches, and how much of the portfolio could be seen.
 * Result: what `:feature:accounts` renders on the allocation screen.
 * Changelog: 2026-08-28 — Created for issue 6.4.
 *
 * @property total paise across every **priced** position — the denominator every share is over.
 * @property slices one per class that holds something, largest first. Classes holding nothing are
 *   absent rather than present at zero, so the legend has no empty rows.
 * @property flags every breach found, class caps first then concentration. Empty is the good case.
 * @property valuedCount how many positions carried a price and were counted.
 * @property unvaluedCount how many were excluded for having none (P-03).
 * @property unavailable why there is nothing to show, or `null` when there is.
 * @property provenance which engine and version produced this, and the rows it applied
 *   (AI-ARC-003).
 */
data class PortfolioAllocation(
    val total: Money,
    val slices: List<AllocationSlice>,
    val flags: List<ConcentrationFlag>,
    val valuedCount: Int,
    val unvaluedCount: Int,
    val unavailable: AllocationUnavailable?,
    val provenance: EngineProvenance,
) {
    init {
        require(provenance.evidence.isNotEmpty()) {
            "An allocation that cites no rule is a black-box verdict, which violates P-02"
        }
        require((unavailable == null) == slices.isNotEmpty()) {
            "There are slices or there is a reason there are none, never both and never neither"
        }
        // The remainder is distributed rather than dropped, so this is an equality and not a
        // tolerance. A rounding change that lost a basis point would show the user a pie that does
        // not add to 100% — the most visible possible symptom of the least visible possible bug.
        require(slices.isEmpty() || slices.sumOf { it.shareBps } == BPS_FULL) {
            "Shares must account for the whole portfolio, summed to " +
                "${slices.sumOf { it.shareBps }} bps instead of $BPS_FULL"
        }
    }
}

/**
 * The portfolio to split, and the thresholds to judge it by (issue 6.4).
 *
 * Why:  [rules] is a parameter rather than something the engine reads, so a test can move a
 *       threshold and assert the engine moves with it — and so the runtime rules loader, when it
 *       lands, changes the argument and not this engine (ADR-0017).
 *
 * @property positions every holding and un-held account balance in the portfolio, in any order.
 *   The caller has already excluded archived accounts, accounts the user opted out of net worth,
 *   and everything with no asset class.
 * @property nowUtcMillis stamped into provenance only; the engine never reads it as a date
 *   (TIM-001).
 * @property rules the thresholds to apply, defaulting to the shipped rulebook values.
 */
data class AllocationInput(
    val positions: List<PortfolioPosition>,
    val nowUtcMillis: Long = 0L,
    val rules: InvestmentRules = InvestmentRules(),
)

/**
 * Hands out the engine without exposing the implementation (issue 6.3; ARC-003).
 *
 * Why:  the same seam every other engine here uses. The implementation is `internal`, so Hilt
 *       cannot name it in a `@Binds`; `:app` calls this from a `@Provides` instead.
 * Result: an [InvestmentEngine]. Input: none. Output: the engine.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 */
object InvestmentEngineFactory {
    /** Input: none. Output: a ready [InvestmentEngine]. */
    fun create(): InvestmentEngine = DefaultInvestmentEngine()
}
