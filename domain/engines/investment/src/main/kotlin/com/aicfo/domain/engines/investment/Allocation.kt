package com.aicfo.domain.engines.investment

import com.aicfo.core.model.AssetClass
import com.aicfo.core.model.Money

/**
 * Turns a list of positions into a split, and the split into flags (issue 6.4; §11.2, MNY-002).
 *
 * Why:  kept out of [DefaultInvestmentEngine] for the reason [CashFlows] and [Xirr] are: the engine
 *       class's job is to assemble a result and stamp provenance on it, and mixing arithmetic into
 *       that is how a rounding decision ends up somewhere nobody looks for it. `internal` because
 *       every one of these steps is an implementation detail of
 *       [InvestmentEngine.allocation] — the contract is the interface.
 * What: the denominator, the per-class shares, and the three concentration checks.
 * Result: everything [PortfolioAllocation] carries except its provenance.
 * Changelog: 2026-08-28 — Created for issue 6.4.
 *
 * **No `Double`, anywhere** (MNY-001/MNY-002). Every share is integer basis points, and the
 * rounding is a *distribution* rather than a round-per-slice: shares are floored, then the leftover
 * basis points are handed out largest-remainder-first, so the slices sum to exactly 10 000. Rounding
 * each slice independently would leave a portfolio adding to 99.97%, which is the most visible
 * possible symptom of the least visible possible bug.
 */
internal object Allocation {
    /**
     * Everything the engine needs to build a result, computed in one pass over one denominator.
     *
     * Why:  the slices and the flags are returned together because they are measured against the
     *       same total. Two calls would let a caller pair a split with flags computed over a
     *       different portfolio, and nothing in the types would object.
     * Result: the value [DefaultInvestmentEngine] copies into [PortfolioAllocation].
     * Changelog: 2026-08-28 — Created for issue 6.4.
     *
     * @property total paise across the priced positions.
     * @property slices one per class holding something, largest first.
     * @property flags every breach found, class caps first.
     * @property valuedCount positions that carried a price.
     * @property unvaluedCount positions excluded for carrying none (P-03).
     * @property unavailable why there is nothing to split, or `null`.
     */
    data class Computed(
        val total: Money,
        val slices: List<AllocationSlice>,
        val flags: List<ConcentrationFlag>,
        val valuedCount: Int,
        val unvaluedCount: Int,
        val unavailable: AllocationUnavailable?,
    )

    /**
     * Splits the portfolio and judges it.
     * Why:    the single entry point, so the order of the steps — exclude, total, share, flag — is
     *         stated once and cannot be reassembled differently by a second caller.
     * Result: a [Computed]. When there is nothing to split it carries the reason and no slices, so
     *         the caller always has a result to render (P-02).
     * Input:  [positions] — every holding and un-held account balance, in any order; [rules] — the
     *         thresholds to apply.
     * Output: [Computed].
     * Changelog: 2026-08-28 — Created for issue 6.4.
     */
    fun compute(
        positions: List<PortfolioPosition>,
        rules: InvestmentRules,
    ): Computed {
        val priced = positions.filter { it.value != null }
        val unvalued = positions.size - priced.size
        val total = priced.fold(Money.ZERO) { running, position -> running + position.value!! }

        // Three different nothings, and only two sentences worth saying. An empty portfolio asks
        // the user to add a holding; one that is unpriced, or wholly exited and therefore worth
        // zero, asks them to price what they have. Dividing by the total in either case would
        // throw, and reporting 0% for every class would be a confident lie (P-03).
        val unavailable =
            when {
                positions.isEmpty() -> AllocationUnavailable.NO_POSITIONS
                total <= Money.ZERO -> AllocationUnavailable.NOTHING_PRICED
                else -> null
            }
        if (unavailable != null) {
            return Computed(Money.ZERO, emptyList(), emptyList(), priced.size, unvalued, unavailable)
        }

        val slices = slices(priced, total)
        return Computed(
            total = total,
            slices = slices,
            flags = flags(slices, priced, total, rules),
            valuedCount = priced.size,
            unvaluedCount = unvalued,
            unavailable = null,
        )
    }

    /**
     * Sums the priced positions by class and gives each its share.
     * Why:    classes holding nothing are dropped rather than carried at zero, so the legend has no
     *         empty rows and the bar has no invisible segments.
     * Result: the slices, largest value first, whose shares sum to exactly [BPS_FULL].
     * Input:  [priced] — positions known to carry a value; [total] — their sum, known positive.
     * Output: the slices.
     * Changelog: 2026-08-28 — Created for issue 6.4.
     */
    private fun slices(
        priced: List<PortfolioPosition>,
        total: Money,
    ): List<AllocationSlice> {
        val byClass =
            priced
                .groupBy { it.assetClass }
                .mapValues { (_, group) -> group.fold(Money.ZERO) { running, row -> running + row.value!! } }
                .filterValues { it > Money.ZERO }
                // Ordered before the shares are handed out so the remainder tie-break is stable
                // across runs: the same portfolio must give the same basis point to the same class
                // every time (P-08).
                .toList()
                .sortedWith(compareByDescending<Pair<AssetClass, Money>> { it.second }.thenBy { it.first.ordinal })

        return distribute(byClass, total)
    }

    /**
     * Hands out 10 000 basis points across the classes, losing none of them.
     * Why:    `value * 10000 / total` floors, so the floors sum to somewhere in
     *         `[BPS_FULL - classes, BPS_FULL]`. The shortfall is real basis points belonging to real
     *         money, and dropping it would show a portfolio that does not add up. Largest remainder
     *         is the standard apportionment: whoever was rounded down hardest is paid first.
     * Result: one [AllocationSlice] per entry, summing to exactly [BPS_FULL].
     * Input:  [byClass] — class totals, already ordered; [total] — the denominator, positive.
     * Output: the slices in the order given.
     * Changelog: 2026-08-28 — Created for issue 6.4.
     */
    private fun distribute(
        byClass: List<Pair<AssetClass, Money>>,
        total: Money,
    ): List<AllocationSlice> {
        val scaled = byClass.map { (_, value) -> Math.multiplyExact(value.minor, BPS_FULL.toLong()) }
        val floors = scaled.map { (it / total.minor).toInt() }
        val remainders = scaled.map { it % total.minor }

        // Indices of the classes rounded down hardest, worst first. The index tie-break keeps the
        // order the caller established, so two classes with identical remainders resolve the same
        // way on every run.
        val order = byClass.indices.sortedWith(compareByDescending<Int> { remainders[it] }.thenBy { it })
        val shortfall = BPS_FULL - floors.sum()
        val bonus = order.take(shortfall).toSet()

        return byClass.mapIndexed { index, (assetClass, value) ->
            AllocationSlice(
                assetClass = assetClass,
                value = value,
                shareBps = floors[index] + if (index in bonus) 1 else 0,
            )
        }
    }

    /**
     * Runs the three concentration checks over a split (issue 6.4; §11.2).
     * Why:    ordered class-cap, then single-class, then single-holding, because that is narrowest
     *         rule to broadest: "gold is over its own ceiling" is a more specific thing to be told
     *         than "something is over 70%", and a user reading top-down should meet the specific
     *         sentence first.
     * Result: every breach, or an empty list — which is the good case and not an absence of an
     *         answer.
     * Input:  [slices] — the split; [priced] — the positions behind it; [total] — the denominator;
     *         [rules] — the thresholds.
     * Output: the flags.
     * Changelog: 2026-08-28 — Created for issue 6.4.
     */
    private fun flags(
        slices: List<AllocationSlice>,
        priced: List<PortfolioPosition>,
        total: Money,
        rules: InvestmentRules,
    ): List<ConcentrationFlag> =
        classCapFlags(slices, rules) +
            singleClassFlags(slices, rules) +
            holdingFlags(priced, total, rules)

    /**
     * `RULE-GOLD-CAP` and `RULE-CRYPTO-CAP` — a class over its own ceiling.
     * Why:    driven by the configured map rather than by two `if`s naming gold and crypto, so a
     *         third cap is a rulebook row and nothing else.
     * Result: one flag per capped class that exceeded its cap, each citing **its own** row.
     * Input:  [slices]; [rules]. Output: the flags.
     * Changelog: 2026-08-28 — Created for issue 6.4.
     */
    private fun classCapFlags(
        slices: List<AllocationSlice>,
        rules: InvestmentRules,
    ): List<ConcentrationFlag> =
        slices.mapNotNull { slice ->
            val cap = rules.assetClassCaps[slice.assetClass] ?: return@mapNotNull null
            val thresholdBps = cap.capPct * BPS_PER_PERCENT
            // Strictly greater: the rulebook says "gold <= 10%", so a portfolio at exactly 10% is
            // inside the rule and has nothing to answer for.
            if (slice.shareBps <= thresholdBps) return@mapNotNull null
            ConcentrationFlag(
                kind = ConcentrationKind.ASSET_CLASS_CAP,
                assetClass = slice.assetClass,
                holdingId = null,
                name = "",
                measuredBps = slice.shareBps,
                thresholdBps = thresholdBps,
                value = slice.value,
                citation = cap.citation,
            )
        }

    /**
     * `RULE-CONC-15-70.single_class_pct` — one class that is most of the portfolio.
     * Result: at most one flag per class over the line. Input: [slices]; [rules]. Output: the flags.
     * Changelog: 2026-08-28 — Created for issue 6.4.
     */
    private fun singleClassFlags(
        slices: List<AllocationSlice>,
        rules: InvestmentRules,
    ): List<ConcentrationFlag> {
        val thresholdBps = rules.singleClassPct * BPS_PER_PERCENT
        return slices.filter { it.shareBps > thresholdBps }.map { slice ->
            ConcentrationFlag(
                kind = ConcentrationKind.SINGLE_CLASS,
                assetClass = slice.assetClass,
                holdingId = null,
                name = "",
                measuredBps = slice.shareBps,
                thresholdBps = thresholdBps,
                value = slice.value,
                citation = InvestmentRules.CONCENTRATION,
            )
        }
    }

    /**
     * `RULE-CONC-15-70.single_holding_pct` — one position that is too much of the portfolio.
     * Why:    a position's share is floored rather than remainder-distributed, unlike a slice's.
     *         Positions are not a partition being displayed as a whole, so nothing has to sum to
     *         10 000 here; flooring is the conservative choice, since it can only ever under-report
     *         a share and so can only ever fail to raise a flag at the very boundary.
     * Result: one flag per position over the line, largest first.
     * Input:  [priced]; [total]; [rules]. Output: the flags.
     * Changelog: 2026-08-28 — Created for issue 6.4.
     */
    private fun holdingFlags(
        priced: List<PortfolioPosition>,
        total: Money,
        rules: InvestmentRules,
    ): List<ConcentrationFlag> {
        val thresholdBps = rules.singleHoldingPct * BPS_PER_PERCENT
        return priced
            .map { it to (Math.multiplyExact(it.value!!.minor, BPS_FULL.toLong()) / total.minor).toInt() }
            .filter { (_, shareBps) -> shareBps > thresholdBps }
            .sortedWith(compareByDescending<Pair<PortfolioPosition, Int>> { it.second }.thenBy { it.first.name })
            .map { (position, shareBps) ->
                ConcentrationFlag(
                    kind = ConcentrationKind.SINGLE_HOLDING,
                    assetClass = position.assetClass,
                    holdingId = position.holdingId,
                    name = position.name,
                    measuredBps = shareBps,
                    thresholdBps = thresholdBps,
                    value = position.value!!,
                    citation = InvestmentRules.CONCENTRATION,
                )
            }
    }
}
