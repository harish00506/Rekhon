package com.aicfo.domain.engines.investment

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.model.AssetClass
import com.aicfo.core.model.InvestmentHolding
import com.aicfo.core.model.InvestmentLot
import com.aicfo.core.model.LotKind
import com.aicfo.core.model.Money
import com.aicfo.core.model.Quantity
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Pins the engine's boundaries and the shape of what it returns (issue 6.3; §11, P-02, P-03).
 *
 * Why:  the golden file proves the arithmetic and the property test proves the invariants; this
 *       proves the *edges*, which are where a money engine actually hurts people. The dangerous
 *       ones are not the failures — those are loud — but the two places a plausible wrong answer
 *       could be produced silently: substituting zero for a price nobody entered, which reports a
 *       user's entire cost as a loss, and letting a rate and a reason-there-is-no-rate both be
 *       absent, which puts an empty cell on screen with nothing to explain it (P-02).
 * What: the three refusals, provenance, the derived position and value, the rounding of
 *       units x price, and the both-or-neither invariants.
 * Result: the contract `:data:repository` and `:feature:accounts` are allowed to rely on.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 */
class InvestmentEngineTest {
    private val engine = InvestmentEngineFactory.create()

    /**
     * A priced holding — the base every case deviates from by one field via `copy`.
     * Result: 'Parag Parikh Flexi Cap', equity, last priced at ₹78.43 per unit on 2027-01-01.
     * Input:  none. Output: [InvestmentHolding].
     */
    private fun holding() =
        InvestmentHolding(
            id = "holding:ppfas",
            accountId = "account:zerodha",
            name = "Parag Parikh Flexi Cap",
            assetClass = AssetClass.EQUITY,
            unitPrice = Money(7_843),
            pricedOnIsoDate = "2027-01-01",
        )

    /**
     * One lot, defaulted to a purchase.
     * Result: the lot. Input: the fields that vary. Output: [InvestmentLot].
     */
    private fun lot(
        id: String,
        kind: LotKind = LotKind.BUY,
        day: String = "2026-01-01",
        units: Long = 100,
        minor: Long = 750_000,
    ) = InvestmentLot(
        id = id,
        holdingId = "holding:ppfas",
        kind = kind,
        transactedOnIsoDate = day,
        quantity = Quantity(units * Quantity.SCALE),
        amount = Money(minor),
    )

    /** Input: no flows, then one. Output: asserts both refuse for the same reason. */
    @Test
    fun `a series with fewer than two days has no rate`() {
        for (flows in listOf(emptyList(), listOf(CashFlow("2026-01-01", Money(-1000))))) {
            val solved = engine.xirr(CashFlowSeriesInput(flows))

            assertThat(solved).isEqualTo(Err(AppError.Validation("flows.tooFew")))
        }
    }

    /**
     * Input: three flows that all fall on one day.
     * Output: asserts they coalesce to one and refuse as too few — not as a zero span.
     */
    @Test
    fun `flows that all fall on one day coalesce to one and refuse`() {
        val sameDay =
            listOf(
                CashFlow("2026-01-01", Money(-1000)),
                CashFlow("2026-01-01", Money(-2000)),
                CashFlow("2026-01-01", Money(5000)),
            )

        assertThat(engine.xirr(CashFlowSeriesInput(sameDay)))
            .isEqualTo(Err(AppError.Validation("flows.tooFew")))
    }

    /**
     * Input: purchases with nothing valued or sold.
     * Output: asserts `sameSign` — the ordinary state of an unpriced holding, not a fault.
     */
    @Test
    fun `a series of purchases alone has no rate to find`() {
        val onlyOut =
            listOf(
                CashFlow("2026-01-01", Money(-1000)),
                CashFlow("2026-06-01", Money(-2000)),
            )

        assertThat(engine.xirr(CashFlowSeriesInput(onlyOut)))
            .isEqualTo(Err(AppError.Validation("flows.sameSign")))
    }

    /**
     * Input: a near-total loss — ₹1,00,000 in, one paise back a year later.
     * Output: asserts `notBracketed` rather than a clamped −10000 bps.
     *
     * "The return was −100%" and "the return is beyond what this engine will report" are different
     * claims, and only the second one is true here. Clamping would print the first.
     */
    @Test
    fun `a loss beyond the solver's floor is reported, never clamped`() {
        val wipeout =
            listOf(
                CashFlow("2026-01-01", Money(-10_000_000)),
                CashFlow("2027-01-01", Money(1)),
            )

        assertThat(engine.xirr(CashFlowSeriesInput(wipeout)))
            .isEqualTo(Err(AppError.Validation("flows.notBracketed")))
    }

    /** Input: a solvable series. Output: asserts the drill-down facts P-02 needs travel with it. */
    @Test
    fun `a solved rate carries the flow count and span it was solved over`() {
        val solved =
            engine.xirr(
                CashFlowSeriesInput(
                    listOf(
                        CashFlow("2026-01-01", Money(-3_000_000)),
                        CashFlow("2026-01-01", Money(-7_000_000)),
                        CashFlow("2027-01-01", Money(11_000_000)),
                    ),
                    nowUtcMillis = 1_767_312_000_000L,
                ),
            )

        val rate = (solved as Ok).value
        assertThat(rate.rateBps).isEqualTo(1_000)
        assertWithMessage("the two same-day purchases count as one flow").that(rate.flowCount).isEqualTo(2)
        assertThat(rate.spanDays).isEqualTo(365)
        assertThat(rate.provenance.engineId).isEqualTo("investment-xirr")
        // 1.1 since issue 6.4, which added `allocation`. The rate itself did not move — the golden
        // file, computed independently of this engine, still agrees to the basis point — but a
        // result stored under 1.0 came from an engine that could not have produced an allocation at
        // all, and AI-ARC-006 needs that to stay tellable.
        assertThat(rate.provenance.engineVersion).isEqualTo("1.1")
        assertThat(rate.provenance.computedAtUtcMillis).isEqualTo(1_767_312_000_000L)
    }

    /**
     * Input: a solved rate.
     * Output: asserts it cites no rulebook row.
     *
     * The mirror of `CardEngineTest`'s evidence assertion, and the same argument the loan engine
     * makes: no row decided this, so claiming one would be a false citation (AI-ARC-006).
     */
    @Test
    fun `a return cites no rule, because no rule decided it`() {
        val solved =
            engine.xirr(
                CashFlowSeriesInput(
                    listOf(
                        CashFlow("2026-01-01", Money(-10_000_000)),
                        CashFlow("2027-01-01", Money(11_000_000)),
                    ),
                ),
            )

        assertThat((solved as Ok).value.provenance.evidence).isEmpty()
    }

    /** Input: a holding with two buys, a sale and a dividend. Output: asserts every derived figure. */
    @Test
    fun `a holding reports its position, value, cost and gain`() {
        val lots =
            listOf(
                lot("l1", units = 100, minor = 750_000),
                lot("l2", day = "2026-07-01", units = 50, minor = 400_000),
                lot("l3", kind = LotKind.SELL, day = "2026-10-01", units = 30, minor = 250_000),
                lot("l4", kind = LotKind.INCOME, day = "2026-11-01", units = 0, minor = 12_000),
            )

        val performance = (engine.holding(HoldingInput(holding(), lots)) as Ok).value

        assertWithMessage("120 units held: 100 + 50 bought, 30 sold")
            .that(performance.netQuantity)
            .isEqualTo(Quantity(120 * Quantity.SCALE))
        assertWithMessage("120 units x ₹78.43").that(performance.currentValue).isEqualTo(Money(941_160))
        assertWithMessage("only the purchases").that(performance.invested).isEqualTo(Money(1_150_000))
        assertWithMessage("the sale and the dividend").that(performance.realised).isEqualTo(Money(262_000))
        assertWithMessage("realised + value − invested").that(performance.gain).isEqualTo(Money(53_160))
        assertThat(performance.assetClass).isEqualTo(AssetClass.EQUITY)
        assertThat(performance.accountId).isEqualTo("account:zerodha")
    }

    /**
     * Input: a dividend lot carrying no units.
     * Output: asserts it moves cash without moving the position.
     */
    @Test
    fun `income is a return, not a discount on what was paid`() {
        val lots =
            listOf(
                lot("l1", units = 100, minor = 750_000),
                lot("l2", kind = LotKind.INCOME, units = 0, minor = 12_000),
            )

        val performance = (engine.holding(HoldingInput(holding(), lots)) as Ok).value

        assertWithMessage("a dividend does not enlarge the position")
            .that(performance.netQuantity)
            .isEqualTo(Quantity(100 * Quantity.SCALE))
        assertWithMessage("nor does it reduce what was paid")
            .that(performance.invested)
            .isEqualTo(Money(750_000))
        assertThat(performance.realised).isEqualTo(Money(12_000))
    }

    /**
     * Input: a holding that still holds units and has never been priced.
     * Output: asserts value and gain are both absent, and the reason for the missing rate is given.
     *
     * The most consequential case in this file. Substituting zero for the price would report the
     * user's entire cost as a loss — a number that is wrong, alarming, and perfectly plausible.
     */
    @Test
    fun `an unpriced holding reports no value and no gain, never zero`() {
        val unpriced = holding().copy(unitPrice = null, pricedOnIsoDate = null)

        // Two purchase dates, so the series is long enough to be solvable and fails for the reason
        // under test - with one lot it would refuse as TOO_FEW_FLOWS and prove nothing about signs.
        val lots = listOf(lot("l1"), lot("l2", day = "2026-07-01", units = 50, minor = 400_000))

        val performance = (engine.holding(HoldingInput(unpriced, lots)) as Ok).value

        assertThat(performance.currentValue).isNull()
        assertThat(performance.gain).isNull()
        assertWithMessage("still the cost, which is knowable").that(performance.invested).isEqualTo(Money(1_150_000))
        assertThat(performance.xirrBps).isNull()
        assertWithMessage("purchases with nothing valued or sold all point one way")
            .that(performance.xirrUnavailable)
            .isEqualTo(XirrUnavailable.SAME_SIGN)
    }

    /**
     * Input: a holding sold in full, never priced.
     * Output: asserts the value is exactly zero and the gain is therefore knowable.
     *
     * The one case where an absent price costs nothing: no units are held, so the position is worth
     * zero as a matter of arithmetic rather than of valuation.
     */
    @Test
    fun `a fully exited holding is worth zero without needing a price`() {
        val unpriced = holding().copy(unitPrice = null, pricedOnIsoDate = null)
        val lots =
            listOf(
                lot("l1", units = 100, minor = 750_000),
                lot("l2", kind = LotKind.SELL, day = "2027-01-01", units = 100, minor = 900_000),
            )

        val performance = (engine.holding(HoldingInput(unpriced, lots)) as Ok).value

        assertThat(performance.netQuantity).isEqualTo(Quantity.ZERO)
        assertThat(performance.currentValue).isEqualTo(Money.ZERO)
        assertWithMessage("₹9,000 back on ₹7,500 in").that(performance.gain).isEqualTo(Money(150_000))
        assertWithMessage("a completed round trip still has a return").that(performance.xirrBps).isEqualTo(2_000)
    }

    /**
     * Input: a holding with no lots at all.
     * Output: asserts every figure is a defensible zero or absent, and nothing throws.
     */
    @Test
    fun `a holding with no lots yet is empty rather than broken`() {
        val performance = (engine.holding(HoldingInput(holding(), emptyList())) as Ok).value

        assertThat(performance.netQuantity).isEqualTo(Quantity.ZERO)
        assertWithMessage("no units held, so worth zero").that(performance.currentValue).isEqualTo(Money.ZERO)
        assertThat(performance.invested).isEqualTo(Money.ZERO)
        assertThat(performance.realised).isEqualTo(Money.ZERO)
        assertThat(performance.gain).isEqualTo(Money.ZERO)
        assertThat(performance.xirrUnavailable).isEqualTo(XirrUnavailable.TOO_FEW_FLOWS)
    }

    /**
     * Input: positions whose value lands exactly halfway between two paise.
     * Output: asserts banker's rounding, in both directions.
     *
     * Half-up would bias every holding's value upward by half a paise on average — invisible per
     * row and systematic across a portfolio, which is the shape of error MNY-001 exists to stop.
     */
    @Test
    fun `a value landing on half a paise rounds to even`() {
        val perPaise = holding().copy(unitPrice = Money(1))

        val oneAndAHalf = valueOf(perPaise, unitsNano = 1_500_000_000)
        val twoAndAHalf = valueOf(perPaise, unitsNano = 2_500_000_000)

        assertWithMessage("1.5 paise rounds to 2, the even neighbour").that(oneAndAHalf).isEqualTo(Money(2))
        assertWithMessage("2.5 paise rounds to 2, the even neighbour").that(twoAndAHalf).isEqualTo(Money(2))
    }

    /** Input: a solvable holding. Output: asserts it never carries both a rate and a reason. */
    @Test
    fun `a holding carries a rate or a reason, never both`() {
        val lots =
            listOf(
                lot("l1", units = 100, minor = 750_000),
                lot("l2", kind = LotKind.SELL, day = "2026-12-01", units = 10, minor = 90_000),
            )

        val performance = (engine.holding(HoldingInput(holding(), lots)) as Ok).value

        assertThat(performance.xirrBps).isNotNull()
        assertThat(performance.xirrUnavailable).isNull()
    }

    /** Input: identical input twice. Output: asserts byte-identical results (P-08). */
    @Test
    fun `the same holding gives the same answer every time`() {
        val lots = listOf(lot("l1"), lot("l2", kind = LotKind.SELL, day = "2026-12-01", units = 10, minor = 90_000))
        val input = HoldingInput(holding(), lots, nowUtcMillis = 1L)

        assertThat(engine.holding(input)).isEqualTo(engine.holding(input))
    }

    /**
     * Values a position of [unitsNano] nano-units.
     * Result: the computed value. Input: [subject]; [unitsNano]. Output: [Money]?.
     */
    private fun valueOf(
        subject: InvestmentHolding,
        unitsNano: Long,
    ): Money? {
        val lots = listOf(lot("l1", units = 0, minor = 1).copy(quantity = Quantity(unitsNano)))
        return (engine.holding(HoldingInput(subject, lots)) as Ok).value.currentValue
    }
}
