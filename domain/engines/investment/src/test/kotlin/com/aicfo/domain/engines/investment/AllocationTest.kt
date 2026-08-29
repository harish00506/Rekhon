package com.aicfo.domain.engines.investment

import com.aicfo.core.common.Ok
import com.aicfo.core.model.AssetClass
import com.aicfo.core.model.Money
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins the allocation's boundaries and the shape of what it returns (issue 6.4; §11.2, P-02, P-03).
 *
 * Why:  the golden file proves the arithmetic and the property test proves the invariants; this
 *       proves the *edges*. Two of them are worth more than the rest. A threshold engine that
 *       flags at the boundary rather than past it accuses a user who has done nothing wrong, and
 *       one that treats an unpriced holding as ₹0 reports a portfolio the user does not have — and
 *       neither failure looks wrong in a screenshot, which is what makes them expensive.
 * What: the cap boundaries, the exclusion of unpriced positions, coverage, evidence, the two
 *       unavailable reasons, and the invariants the value types refuse to be built without.
 * Result: the contract `:data:repository` and `:feature:accounts` are allowed to rely on.
 * Changelog: 2026-08-28 — Created for issue 6.4.
 *
 * **Every threshold here is read from [InvestmentRules], never written as a literal**, so moving a
 * rulebook number moves these tests with it — which is the whole point of injecting the rules.
 */
class AllocationTest {
    private val engine = InvestmentEngineFactory.create()
    private val rules = InvestmentRules()

    // --- the class caps (RULE-GOLD-CAP, RULE-CRYPTO-CAP) ----------------------------------------

    @Test
    fun `a class sitting exactly on its cap is not flagged`() {
        val cap = rules.assetClassCaps.getValue(AssetClass.GOLD).capPct
        val result = split(gold = cap.toLong(), equity = (WHOLE - cap).toLong())

        assertWithMessage("the rulebook says gold <= 10%%, so 10%% is inside the rule")
            .that(result.flags.filter { it.kind == ConcentrationKind.ASSET_CLASS_CAP }).isEmpty()
    }

    @Test
    fun `a class one basis point past its cap is flagged, and cites that cap's own row`() {
        // 1001 bps against a 1000 bps ceiling: the smallest breach the type can express.
        val result = splitBps(AssetClass.GOLD to 1001, AssetClass.EQUITY to 8999)
        val flag = result.flags.single { it.kind == ConcentrationKind.ASSET_CLASS_CAP }

        assertThat(flag.assetClass).isEqualTo(AssetClass.GOLD)
        assertThat(flag.measuredBps).isEqualTo(1001)
        assertThat(flag.thresholdBps).isEqualTo(rules.assetClassCaps.getValue(AssetClass.GOLD).capPct * BPS_PER_PERCENT)
        assertWithMessage("a gold breach must be answerable by the gold row, not by whichever fired last")
            .that(flag.citation).isEqualTo(InvestmentRules.GOLD_CAP)
    }

    @Test
    fun `crypto is capped tighter than gold, and cites its own row`() {
        val goldCap = rules.assetClassCaps.getValue(AssetClass.GOLD).capPct
        val cryptoCap = rules.assetClassCaps.getValue(AssetClass.CRYPTO).capPct
        assertWithMessage("the rulebook bounds crypto below gold; this test means nothing otherwise")
            .that(cryptoCap).isLessThan(goldCap)

        // A share legal for gold and illegal for crypto — the one place the two rows must not be
        // interchangeable.
        val result = splitBps(AssetClass.CRYPTO to goldCap * BPS_PER_PERCENT, AssetClass.EQUITY to 9000)
        val flag = result.flags.single { it.kind == ConcentrationKind.ASSET_CLASS_CAP }

        assertThat(flag.citation).isEqualTo(InvestmentRules.CRYPTO_CAP)
    }

    @Test
    fun `an uncapped class is never flagged for a cap, however large it grows`() {
        val result = splitBps(AssetClass.DEBT to 9999, AssetClass.GOLD to 1)

        assertWithMessage("the rulebook caps gold and crypto; it does not cap debt")
            .that(result.flags.filter { it.kind == ConcentrationKind.ASSET_CLASS_CAP }).isEmpty()
    }

    // --- concentration (RULE-CONC-15-70) --------------------------------------------------------

    @Test
    fun `a class sitting exactly on the single-class ceiling is not flagged`() {
        val ceiling = rules.singleClassPct * BPS_PER_PERCENT
        val result = splitBps(AssetClass.EQUITY to ceiling, AssetClass.DEBT to BPS_FULL - ceiling)

        assertThat(result.flags.filter { it.kind == ConcentrationKind.SINGLE_CLASS }).isEmpty()
    }

    @Test
    fun `a class one basis point past the single-class ceiling is flagged`() {
        val ceiling = rules.singleClassPct * BPS_PER_PERCENT
        val result = splitBps(AssetClass.EQUITY to ceiling + 1, AssetClass.DEBT to BPS_FULL - ceiling - 1)
        val flag = result.flags.single { it.kind == ConcentrationKind.SINGLE_CLASS }

        assertThat(flag.measuredBps).isEqualTo(ceiling + 1)
        assertThat(flag.citation).isEqualTo(InvestmentRules.CONCENTRATION)
    }

    @Test
    fun `only the single holding past its ceiling is flagged, and it is flagged by name`() {
        // Seven positions rather than two, because with two the remainder is 85% and *both* would
        // be over the line — a portfolio cannot contain exactly one holding above 15% unless the
        // rest are numerous enough to stay below it. Six fillers is the minimum that works.
        val ceiling = rules.singleHoldingPct * BPS_PER_PERCENT
        val fillers = listOf(1_416L, 1_416L, 1_416L, 1_417L, 1_417L, 1_417L)
        val result =
            allocate(
                position("Nifty Index", AssetClass.EQUITY, (ceiling + 1).toLong()),
                *fillers.mapIndexed { index, paise -> position("Filler $index", AssetClass.EQUITY, paise) }
                    .toTypedArray(),
            )
        val flags = result.flags.filter { it.kind == ConcentrationKind.SINGLE_HOLDING }

        assertWithMessage("only the position past the line, not the six sitting under it")
            .that(flags).hasSize(1)
        assertThat(flags.single().name).isEqualTo("Nifty Index")
        assertThat(flags.single().measuredBps).isEqualTo(ceiling + 1)
        assertWithMessage("the card has to name the holding the user should go and look at")
            .that(flags.single().holdingId).isEqualTo("id-Nifty Index")
    }

    @Test
    fun `a holding sitting exactly on its ceiling is not flagged`() {
        val ceiling = rules.singleHoldingPct * BPS_PER_PERCENT
        val fillers = listOf(1_417L, 1_417L, 1_417L, 1_417L, 1_416L, 1_416L)
        val result =
            allocate(
                position("Nifty Index", AssetClass.EQUITY, ceiling.toLong()),
                *fillers.mapIndexed { index, paise -> position("Filler $index", AssetClass.EQUITY, paise) }
                    .toTypedArray(),
            )

        assertWithMessage("the rulebook says a single holding <= 15%%, so 15%% has nothing to answer for")
            .that(result.flags.filter { it.kind == ConcentrationKind.SINGLE_HOLDING }).isEmpty()
    }

    // --- unpriced positions (P-03) --------------------------------------------------------------

    @Test
    fun `an unpriced position is excluded from the denominator rather than counted as zero`() {
        val result =
            allocate(
                position("Nifty Index", AssetClass.EQUITY, 100_000L),
                position("SGB 2030", AssetClass.GOLD, null),
            )

        assertWithMessage("counting the unpriced gold as ₹0 would put equity at 100%% of a portfolio it is not")
            .that(result.total).isEqualTo(Money(100_000L))
        assertWithMessage("a class nobody priced is absent from the split, not present at 0%%")
            .that(result.slices.map { it.assetClass }).containsExactly(AssetClass.EQUITY)
        assertThat(result.valuedCount).isEqualTo(1)
        assertThat(result.unvaluedCount).isEqualTo(1)
    }

    @Test
    fun `an unpriced position cannot trigger the cap of the class it would have joined`() {
        // Were the gold position counted at ₹0 it would be 0% and legal; were it counted at its
        // real value it might not be. Neither is knowable, so neither is claimed.
        val result =
            allocate(
                position("Nifty Index", AssetClass.EQUITY, 100_000L),
                position("SGB 2030", AssetClass.GOLD, null),
            )

        assertThat(result.flags.none { it.assetClass == AssetClass.GOLD }).isTrue()
    }

    @Test
    fun `coverage reports how much of the portfolio the split could see`() {
        val result =
            allocate(
                position("Nifty Index", AssetClass.EQUITY, 100_000L),
                position("Flexi Cap", AssetClass.EQUITY, 100_000L),
                position("Mid Cap", AssetClass.EQUITY, 100_000L),
                position("SGB 2030", AssetClass.GOLD, null),
            )

        assertWithMessage("three of four positions priced is 75%% coverage")
            .that(result.provenance.confidenceBps).isEqualTo(7_500)
    }

    @Test
    fun `a fully priced portfolio claims complete coverage`() {
        assertThat(split(equity = 100L).provenance.confidenceBps).isEqualTo(BPS_FULL)
    }

    // --- nothing to split -----------------------------------------------------------------------

    @Test
    fun `an empty portfolio reports why rather than an empty split`() {
        val result = allocate()

        assertThat(result.unavailable).isEqualTo(AllocationUnavailable.NO_POSITIONS)
        assertThat(result.slices).isEmpty()
        assertWithMessage("no coverage claim can be made about a portfolio with no positions")
            .that(result.provenance.confidenceBps).isNull()
    }

    @Test
    fun `a wholly unpriced portfolio asks to be priced rather than reporting zeroes`() {
        val result = allocate(position("SGB 2030", AssetClass.GOLD, null))

        assertThat(result.unavailable).isEqualTo(AllocationUnavailable.NOTHING_PRICED)
        assertThat(result.provenance.confidenceBps).isEqualTo(0)
    }

    @Test
    fun `a wholly exited portfolio is worth zero and has no split to show`() {
        val result = allocate(position("Nifty Index", AssetClass.EQUITY, 0L))

        assertWithMessage("a fully sold holding is priced and worth ₹0 — dividing by it would throw")
            .that(result.unavailable).isEqualTo(AllocationUnavailable.NOTHING_PRICED)
        assertThat(result.valuedCount).isEqualTo(1)
    }

    // --- provenance (AI-ARC-003, P-02) ----------------------------------------------------------

    @Test
    fun `every allocation cites all three rules, even when none of them fired`() {
        val result = split(equity = 100L)

        assertWithMessage("a clean portfolio must still be able to say what it was found clean of")
            .that(result.provenance.evidence).isEqualTo(InvestmentRules.CITATIONS)
        assertThat(result.provenance.engineVersion).isEqualTo("1.1")
    }

    @Test
    fun `the engine reads the timestamp it is given and never a clock`() {
        assertThat(allocate(position("A", AssetClass.EQUITY, 1L), nowUtcMillis = 42L).provenance.computedAtUtcMillis)
            .isEqualTo(42L)
    }

    // --- invariants the value types refuse to be built without ----------------------------------

    @Test
    fun `a position cannot carry a negative value`() {
        val thrown =
            assertThrows(IllegalArgumentException::class.java) {
                position("Loan", AssetClass.EQUITY, -1L)
            }

        assertThat(thrown).hasMessageThat().contains("liability has no asset class")
    }

    @Test
    fun `a flag at or under the line cannot be constructed`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConcentrationFlag(
                kind = ConcentrationKind.SINGLE_CLASS,
                assetClass = AssetClass.EQUITY,
                holdingId = null,
                name = "",
                measuredBps = 1_000,
                thresholdBps = 1_000,
                value = Money(1L),
                citation = InvestmentRules.CONCENTRATION,
            )
        }
    }

    @Test
    fun `a threshold outside one to ninety-nine percent is refused`() {
        assertThrows(IllegalArgumentException::class.java) { InvestmentRules(singleHoldingPct = 0) }
        assertThrows(IllegalArgumentException::class.java) { InvestmentRules(singleClassPct = WHOLE) }
        assertThrows(IllegalArgumentException::class.java) { AssetClassCap(0, InvestmentRules.GOLD_CAP) }
    }

    @Test
    fun `a class ceiling below the holding ceiling is refused as incoherent`() {
        val thrown =
            assertThrows(IllegalArgumentException::class.java) {
                InvestmentRules(singleHoldingPct = 50, singleClassPct = 40)
            }

        assertThat(thrown).hasMessageThat().contains("a class contains its holdings")
    }

    // --- helpers --------------------------------------------------------------------------------

    /** Result: a position. Input: [name], [assetClass], [value] paise or `null` for unpriced. */
    private fun position(
        name: String,
        assetClass: AssetClass,
        value: Long?,
    ) = PortfolioPosition(
        holdingId = "id-$name",
        accountId = "acc-1",
        name = name,
        assetClass = assetClass,
        value = value?.let { Money(it) },
    )

    /** Result: the allocation over [positions]. Input: the positions and a provenance stamp. */
    private fun allocate(
        vararg positions: PortfolioPosition,
        nowUtcMillis: Long = 1L,
    ): PortfolioAllocation = (engine.allocation(AllocationInput(positions.toList(), nowUtcMillis)) as Ok).value

    /**
     * Result: an allocation of one position per named class, each worth the paise given.
     * Input: [equity] and [gold] in paise. Output: the allocation.
     */
    private fun split(
        equity: Long = 0L,
        gold: Long = 0L,
    ): PortfolioAllocation =
        allocate(
            *listOfNotNull(
                equity.takeIf { it > 0 }?.let { position("Equity", AssetClass.EQUITY, it) },
                gold.takeIf { it > 0 }?.let { position("Gold", AssetClass.GOLD, it) },
            ).toTypedArray(),
        )

    /**
     * Builds a portfolio whose shares are exactly the basis points asked for.
     * Why:    the pairs must sum to [BPS_FULL], so one paise per basis point makes the share and
     *         the amount the same number and the test's intent readable at a glance.
     * Result: the allocation. Input: [shares] — class to basis points. Output: the allocation.
     */
    private fun splitBps(vararg shares: Pair<AssetClass, Int>): PortfolioAllocation {
        require(shares.sumOf { it.second } == BPS_FULL) { "a portfolio is 10 000 bps of itself" }
        return allocate(*shares.map { (cls, bps) -> position(cls.storedValue, cls, bps.toLong()) }.toTypedArray())
    }

    private companion object {
        /** 100% as a whole number of percent — the ceiling every threshold must stay under. */
        const val WHOLE = 100
    }
}
