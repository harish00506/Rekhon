package com.aicfo.domain.engines.investment

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.RuleCitation

/**
 * The engine §11's AI-INV names (issues 6.3 and 6.4; §11, AI-ARC-003).
 *
 * Why:  `internal`, with [InvestmentEngineFactory] as the seam, for the reason every other engine
 *       here is: the contract is the interface, and a caller that can name the implementation is a
 *       caller that can come to depend on something the interface never promised.
 * What: delegates the arithmetic to [CashFlows], [Xirr] and [Allocation] and attaches provenance.
 * Result: the numbers `:data:repository` hands to `:feature:accounts`.
 * Changelog: 2026-08-24 — Created for issue 6.3 (§11).
 *   2026-08-28 — Added [allocation] for issue 6.4 (FR-INV-002); [ENGINE_VERSION] 1.0 → 1.1.
 *
 * **Evidence on one operation of the three.** A citation names the rulebook row that decided
 * something, and for [xirr] and [holding] no row decided anything: a money-weighted return has no
 * threshold to compare against, only arithmetic to get right. That is the argument
 * `DefaultLoanEngine` makes for amortisation. [allocation] is the other kind — it compares shares
 * against three rulebook rows and cannot report a breach without naming which one drew the line
 * (P-02), so it cites, exactly as `DefaultCardEngine` does for every alert it raises.
 */
internal class DefaultInvestmentEngine : InvestmentEngine {
    override fun xirr(input: CashFlowSeriesInput): Result<XirrRate, AppError> =
        when (val outcome = Xirr.solve(input.flows)) {
            is Xirr.Outcome.Solved ->
                Ok(
                    XirrRate(
                        rateBps = outcome.rateBps,
                        flowCount = outcome.flowCount,
                        spanDays = outcome.spanDays,
                        provenance = provenance(input.nowUtcMillis),
                    ),
                )

            is Xirr.Outcome.Unavailable -> Err(AppError.Validation(outcome.reason.field))
        }

    override fun holding(input: HoldingInput): Result<HoldingPerformance, AppError> {
        val units = CashFlows.netQuantity(input.lots)
        val value = CashFlows.currentValue(input.holding, units)
        val invested = CashFlows.invested(input.lots)
        val realised = CashFlows.realised(input.lots)
        val outcome = Xirr.solve(CashFlows.of(input.holding, input.lots, value))

        return Ok(
            HoldingPerformance(
                holdingId = input.holding.id,
                name = input.holding.name,
                accountId = input.holding.accountId,
                assetClass = input.holding.assetClass,
                netQuantity = units,
                currentValue = value,
                invested = invested,
                realised = realised,
                // A gain is only knowable when the value is. Substituting zero for an unpriced
                // holding would report the user's whole cost as a loss (P-03).
                gain = value?.let { realised + it - invested },
                xirrBps = (outcome as? Xirr.Outcome.Solved)?.rateBps,
                xirrUnavailable = (outcome as? Xirr.Outcome.Unavailable)?.reason,
                provenance = provenance(input.nowUtcMillis),
            ),
        )
    }

    override fun allocation(input: AllocationInput): Result<PortfolioAllocation, AppError> {
        val computed = Allocation.compute(input.positions, input.rules)

        return Ok(
            PortfolioAllocation(
                total = computed.total,
                slices = computed.slices,
                flags = computed.flags,
                valuedCount = computed.valuedCount,
                unvaluedCount = computed.unvaluedCount,
                unavailable = computed.unavailable,
                // All three rows, because the screen reports all three checks: a drill-down citing
                // only the rows that happened to fire would leave a clean portfolio unable to say
                // what it was clean *of*. Each individual flag still cites exactly one row — the
                // one that decided it.
                provenance =
                    provenance(
                        nowUtcMillis = input.nowUtcMillis,
                        evidence = InvestmentRules.CITATIONS,
                        // Coverage, not certainty: the share of positions this split could actually
                        // see. A portfolio with three unpriced holdings out of eleven is not wrong,
                        // it is partial, and P-02 requires the user be told which (AI-ARC-003).
                        confidenceBps = coverageBps(computed.valuedCount, computed.unvaluedCount),
                    ),
            ),
        )
    }

    override fun priceFreshness(input: PriceFreshnessInput): Result<PriceFreshness, AppError> {
        val (verdict, ageDays, refreshDue) = Freshness.of(input)

        return Ok(
            PriceFreshness(
                verdict = verdict,
                // Echoed only when there is one, so the type's own invariant holds: a date and an
                // age are both present or both absent.
                pricedOnIsoDate = input.pricedOnIsoDate.takeIf { verdict != PriceVerdict.NEVER_PRICED },
                ageDays = ageDays,
                refreshDue = refreshDue,
                citation = InvestmentRules.PRICE_STALE,
                // One row decided this, so one row is cited — the same rule the concentration flags
                // keep. Pointing "why am I being told this is old?" at the gold cap would be worse
                // than citing nothing.
                provenance =
                    provenance(
                        nowUtcMillis = input.nowUtcMillis,
                        evidence = listOf(InvestmentRules.PRICE_STALE),
                    ),
            ),
        )
    }

    /**
     * How much of the portfolio the split could see, in basis points.
     * Why:    an allocation computed over eight of eleven holdings is a different claim from one
     *         computed over all eleven, and `confidenceBps` is the field AI-ARC-003 reserves for
     *         saying so. Measured by position count rather than by value, necessarily: the value of
     *         the ones that were excluded is precisely what is unknown.
     * Result: 10 000 when every position was priced, 0 when none was, proportional between.
     * Input:  [valued] — positions counted; [unvalued] — positions excluded for having no price.
     * Output: basis points, or `null` when there were no positions at all — no coverage claim can
     *         be made about an empty portfolio, and 0% would read as a warning it has not earned.
     * Changelog: 2026-08-28 — Created for issue 6.4.
     */
    private fun coverageBps(
        valued: Int,
        unvalued: Int,
    ): Int? {
        val positions = valued + unvalued
        return if (positions == 0) null else valued * BPS_FULL / positions
    }

    /**
     * Stamps a result with what produced it.
     * Why:    AI-ARC-003 — a figure that cannot name its engine and version is a figure nobody can
     *         reproduce once the formula moves (AI-ARC-006).
     * Result: the provenance, carrying whatever evidence the caller's operation earned.
     * Input:  [nowUtcMillis] — supplied by the repository, which owns the clock (TIM-001);
     *         [evidence] — the rulebook rows the operation applied, empty for the two that apply
     *         none; [confidenceBps] — coverage, `null` for the operations that are exact.
     * Output: [EngineProvenance].
     * Changelog: 2026-08-24 — Created for issue 6.3.
     *   2026-08-28 — Took evidence and confidence as arguments for issue 6.4's allocation.
     */
    private fun provenance(
        nowUtcMillis: Long,
        evidence: List<RuleCitation> = emptyList(),
        confidenceBps: Int? = null,
    ): EngineProvenance =
        EngineProvenance(
            engineId = ENGINE_ID,
            engineVersion = ENGINE_VERSION,
            computedAtUtcMillis = nowUtcMillis,
            evidence = evidence,
            confidenceBps = confidenceBps,
        )

    private companion object {
        const val ENGINE_ID = "investment-xirr"

        /**
         * Bumped whenever the solve changes — which includes `Xirr`'s bracket, iteration count,
         * precision and day count, because each of those changes every historical answer
         * (AI-ARC-006). Issue 6.4 moved it to 1.1 by *adding* [allocation]: no existing answer
         * changed, but a result stored under 1.0 came from an engine that could not have produced
         * an allocation at all, and that has to remain tellable.
         */
        const val ENGINE_VERSION = "1.1"
    }
}

/**
 * The validation field name a refusal reports as.
 * Why:    `AppError.Validation` carries a dotted field the UI maps to a message, and putting the
 *         mapping here rather than at the one call site keeps the enum and its wire name together,
 *         where a new arm cannot be added without one.
 * Result: a stable identifier per reason.
 * Input:  none (receiver). Output: [String].
 * Changelog: 2026-08-24 — Created for issue 6.3.
 */
private val XirrUnavailable.field: String
    get() =
        when (this) {
            XirrUnavailable.TOO_FEW_FLOWS -> "flows.tooFew"
            XirrUnavailable.SAME_SIGN -> "flows.sameSign"
            XirrUnavailable.NOT_BRACKETED -> "flows.notBracketed"
        }
