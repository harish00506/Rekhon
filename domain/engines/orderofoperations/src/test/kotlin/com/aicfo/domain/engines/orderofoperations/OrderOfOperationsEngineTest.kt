package com.aicfo.domain.engines.orderofoperations

import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.goals.SurplusBasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Behaviour of [OrderOfOperationsEngine], one stage branch at a time (issue 7.5; §36, AI-FOO).
 *
 * Why:  the golden file fixes whole scenarios; this file pins each branch on its own so a failure
 *       names the rule that broke, not just the record that noticed.
 * What: every status and reason each stage can produce, the band edges, the gate, the invariants on
 *       the result and every input guard.
 * Result: a change to any stage's decision fails a test named after that decision.
 * Changelog: 2026-09-17 — Created for issue 7.5.
 *
 * Every amount is paise, written out literally. ₹50,000 is `5_000_000`.
 */
class OrderOfOperationsEngineTest {
    private val engine = OrderOfOperationsEngineFactory.create()

    // --- the shape of every answer ------------------------------------------------------------

    /** Input: a profile the app knows nothing about. Output: all eight stages, ranked, with reasons. */
    @Test
    fun `a cold start still ranks all eight stages and says why each is where it is`() {
        val result = rank(input(surplus = null))

        assertEquals(FooStage.entries, result.stages.map { it.stage })
        assertStage(result, FooStage.STARTER_BUFFER, StageStatus.ACTION, StageReason.BUFFER_SHORT_ESSENTIALS_UNKNOWN)
        assertStage(result, FooStage.CAPTURE_EPF_VPF, StageStatus.SKIPPED, StageReason.NO_EPF_DATA)
        assertStage(result, FooStage.KILL_FIRE_DEBT, StageStatus.NOT_APPLICABLE, StageReason.NO_FIRE_DEBT)
        assertStage(result, FooStage.FULL_EMERGENCY, StageStatus.ACTION, StageReason.EMERGENCY_UNSIZED)
        assertStage(result, FooStage.TAX_ADVANTAGED, StageStatus.SKIPPED, StageReason.NO_REGIME_COMPARATOR)
        assertStage(result, FooStage.GOAL_INVESTING, StageStatus.NOT_APPLICABLE, StageReason.NO_GOALS)
        assertStage(result, FooStage.GREY_ZONE_DEBT, StageStatus.NOT_APPLICABLE, StageReason.NO_GREY_DEBT)
        assertStage(result, FooStage.LOW_RATE_DEBT, StageStatus.NOT_APPLICABLE, StageReason.NO_LOW_RATE_DEBT)
        assertEquals(FooStage.STARTER_BUFFER, result.topAction?.stage)
        assertEquals(Money.ZERO, result.unallocated)
    }

    /** Input: an unknown surplus. Output: needs are still reported; nothing is poured. */
    @Test
    fun `an unknown surplus ranks by need and pours nothing`() {
        val result = rank(input(surplus = null))

        assertEquals(Money(5_000_000), stage(result, FooStage.STARTER_BUFFER).need)
        assertTrue(result.stages.all { it.amountMonthly == Money.ZERO })
        assertEquals(SurplusBasis.NONE, result.surplusBasis)
        assertNull(result.monthlySurplus)
    }

    /** Input: a negative surplus. Output: ranked exactly as a zero surplus would be; nothing poured. */
    @Test
    fun `a negative surplus ranks the same stages and pours nothing`() {
        val result = rank(input(surplus = Money(-1_500_000), essentials = Money(3_000_000)))

        assertStage(result, FooStage.STARTER_BUFFER, StageStatus.ACTION, StageReason.BUFFER_SHORT)
        assertEquals(Money(3_000_000), stage(result, FooStage.STARTER_BUFFER).need)
        assertTrue(result.stages.all { it.amountMonthly == Money.ZERO })
        assertEquals(Money.ZERO, result.unallocated)
        assertEquals(Money(-1_500_000), result.monthlySurplus)
    }

    // --- Stage 0: starter buffer --------------------------------------------------------------

    /** Input: essentials below the cap. Output: the buffer is one month of essentials, less liquid. */
    @Test
    fun `the starter buffer is one month of essentials when that is below the cap`() {
        val result = rank(input(essentials = Money(3_000_000), liquid = Money(1_000_000)))

        assertEquals(Money(2_000_000), stage(result, FooStage.STARTER_BUFFER).need)
        assertEquals(StageReason.BUFFER_SHORT, stage(result, FooStage.STARTER_BUFFER).reason)
    }

    /** Input: essentials above the cap. Output: the buffer stops at the ₹50,000 cap. */
    @Test
    fun `the starter buffer never asks for more than the cap`() {
        val result = rank(input(essentials = Money(8_000_000), liquid = Money.ZERO))

        assertEquals(Money(5_000_000), stage(result, FooStage.STARTER_BUFFER).need)
    }

    /** Input: liquid funds at the buffer exactly. Output: the stage is satisfied and takes nothing. */
    @Test
    fun `a buffer that is exactly met is satisfied`() {
        val result = rank(input(surplus = Money(900_000), essentials = Money(3_000_000), liquid = Money(3_000_000)))

        assertStage(result, FooStage.STARTER_BUFFER, StageStatus.SATISFIED, StageReason.BUFFER_HELD)
        assertEquals(Money.ZERO, stage(result, FooStage.STARTER_BUFFER).need)
        assertEquals(Money.ZERO, stage(result, FooStage.STARTER_BUFFER).amountMonthly)
    }

    /** Input: a surplus smaller than the buffer's need. Output: the buffer takes it all; nothing flows on. */
    @Test
    fun `strict order gives the whole of a small surplus to the first stage that needs it`() {
        val result =
            rank(
                input(
                    surplus = Money(400_000),
                    essentials = Money(3_000_000),
                    debts = listOf(card(outstanding = Money(900_000), aprBps = 3_600)),
                ),
            )

        assertEquals(Money(400_000), stage(result, FooStage.STARTER_BUFFER).amountMonthly)
        assertEquals(Money.ZERO, stage(result, FooStage.KILL_FIRE_DEBT).amountMonthly)
        assertEquals(StageStatus.ACTION, stage(result, FooStage.KILL_FIRE_DEBT).status)
        assertEquals(FooStage.STARTER_BUFFER, result.topAction?.stage)
    }

    // --- Stage 2: fire debt -------------------------------------------------------------------

    /** Input: two fire debts. Output: both counted, highest rate first, filled from what is left. */
    @Test
    fun `fire debt is every balance at or above the threshold, highest rate first`() {
        val loan = loan(id = "loan", outstanding = Money(1_000_000), aprBps = 1_400)
        val card = card(id = "card", outstanding = Money(4_000_000), aprBps = 3_600)
        val result = rank(input(surplus = Money(2_500_000), liquid = Money(5_000_000), debts = listOf(loan, card)))

        val fire = stage(result, FooStage.KILL_FIRE_DEBT)
        assertEquals(StageStatus.ACTION, fire.status)
        assertEquals(StageReason.FIRE_DEBT_OUTSTANDING, fire.reason)
        assertEquals(listOf("card", "loan"), fire.debts.map { it.accountId })
        assertEquals(Money(5_000_000), fire.need)
        assertEquals(Money(2_500_000), fire.amountMonthly)
        assertEquals(FooStage.KILL_FIRE_DEBT, result.topAction?.stage)
    }

    /** Input: rates either side of 13.5%. Output: 1 350 bps is fire debt; 1 349 bps is grey. */
    @Test
    fun `the fire threshold is inclusive at 1350 bps`() {
        val result =
            rank(
                input(
                    liquid = Money(5_000_000),
                    debts = listOf(loan(id = "at", aprBps = 1_350), loan(id = "below", aprBps = 1_349)),
                ),
            )

        assertEquals(listOf("at"), stage(result, FooStage.KILL_FIRE_DEBT).debts.map { it.accountId })
        assertEquals(listOf("below"), stage(result, FooStage.GREY_ZONE_DEBT).debts.map { it.accountId })
    }

    /** Input: a card with a balance and no APR. Output: counted as fire debt, flagged, ranked first. */
    @Test
    fun `a card with no rate entered is counted as fire debt and flagged`() {
        val unknown = card(id = "unknown", outstanding = Money(300_000), aprBps = null)
        val known = card(id = "known", outstanding = Money(200_000), aprBps = 4_200)
        val result = rank(input(liquid = Money(5_000_000), debts = listOf(known, unknown)))

        val fire = stage(result, FooStage.KILL_FIRE_DEBT)
        assertEquals(StageReason.FIRE_DEBT_CARD_RATE_UNKNOWN, fire.reason)
        assertEquals(listOf("unknown", "known"), fire.debts.map { it.accountId })
        assertEquals(Money(500_000), fire.need)
    }

    /** Input: debts that are fully paid. Output: ignored — a zero balance is not a debt to rank. */
    @Test
    fun `a debt with nothing outstanding is not ranked anywhere`() {
        val result =
            rank(
                input(
                    liquid = Money(5_000_000),
                    debts =
                        listOf(
                            card(id = "a", outstanding = Money.ZERO, aprBps = 3_600),
                            loan(id = "b", outstanding = Money.ZERO, aprBps = 1_100),
                            loan(id = "c", outstanding = Money.ZERO, aprBps = 800),
                        ),
                ),
            )

        assertEquals(StageStatus.NOT_APPLICABLE, stage(result, FooStage.KILL_FIRE_DEBT).status)
        assertEquals(StageStatus.NOT_APPLICABLE, stage(result, FooStage.GREY_ZONE_DEBT).status)
        assertEquals(StageStatus.NOT_APPLICABLE, stage(result, FooStage.LOW_RATE_DEBT).status)
    }

    /** Input: a gated profile with fire debt. Output: Stage 2 sits above the gate and is still funded. */
    @Test
    fun `the emergency gate never holds back fire debt`() {
        val result =
            rank(
                input(
                    surplus = Money(1_000_000),
                    liquid = Money(5_000_000),
                    shortfall = Money(9_000_000),
                    runwayBps = 10_000,
                    debts = listOf(card(outstanding = Money(600_000), aprBps = 3_600)),
                ),
            )

        assertEquals(StageStatus.ACTION, stage(result, FooStage.KILL_FIRE_DEBT).status)
        assertEquals(Money(600_000), stage(result, FooStage.KILL_FIRE_DEBT).amountMonthly)
        assertEquals(Money(400_000), stage(result, FooStage.FULL_EMERGENCY).amountMonthly)
    }

    // --- Stage 3: full emergency fund and the gate --------------------------------------------

    /**
     * Input:  runway below RULE-EMERG-FIRST's three months.
     * Output: Stage 3 may take up to its whole shortfall — nothing below it may be funded anyway —
     *         and every later stage with a need is blocked.
     */
    @Test
    fun `while the gate holds the emergency fund takes up to its whole shortfall and later stages wait`() {
        val result =
            rank(
                gatedWithEverythingBelow(surplus = Money(3_000_000), shortfall = Money(2_000_000)),
            )

        val emergency = stage(result, FooStage.FULL_EMERGENCY)
        assertEquals(StageReason.EMERGENCY_BELOW_GATE, emergency.reason)
        assertEquals(Money(2_000_000), emergency.amountMonthly)
        assertStage(result, FooStage.GOAL_INVESTING, StageStatus.BLOCKED, StageReason.EMERGENCY_GATE)
        assertStage(result, FooStage.GREY_ZONE_DEBT, StageStatus.BLOCKED, StageReason.EMERGENCY_GATE)
        assertStage(result, FooStage.LOW_RATE_DEBT, StageStatus.BLOCKED, StageReason.EMERGENCY_GATE)
        assertTrue(
            listOf(FooStage.GOAL_INVESTING, FooStage.GREY_ZONE_DEBT, FooStage.LOW_RATE_DEBT)
                .all { stage(result, it).amountMonthly == Money.ZERO },
        )
        assertEquals("the ₹10,000 the fund could not use waits, unallocated", Money(1_000_000), result.unallocated)
    }

    /** Input: an unknown runway. Output: treated as below the gate — the expensive side to be wrong on. */
    @Test
    fun `an unknown runway holds the gate`() {
        val result =
            rank(gatedWithEverythingBelow(surplus = Money(1_000_000), shortfall = Money(500_000), runway = null))

        assertEquals(StageReason.EMERGENCY_BELOW_GATE, stage(result, FooStage.FULL_EMERGENCY).reason)
        assertEquals(StageStatus.BLOCKED, stage(result, FooStage.GOAL_INVESTING).status)
    }

    /** Input: runway exactly at three months. Output: the gate is clear; the fund builds at EMF's pace. */
    @Test
    fun `at exactly the gate the fund builds at its pace and the money flows on`() {
        val result =
            rank(
                input(
                    surplus = Money(3_000_000),
                    liquid = Money(9_000_000),
                    shortfall = Money(6_000_000),
                    topUp = Money(1_000_000),
                    runwayBps = 30_000,
                    goalsRequired = Money(1_500_000),
                    goalCount = 2,
                ),
            )

        val emergency = stage(result, FooStage.FULL_EMERGENCY)
        assertEquals(StageReason.EMERGENCY_BUILDING, emergency.reason)
        assertEquals(Money(6_000_000), emergency.need)
        assertEquals(Money(1_000_000), emergency.amountMonthly)
        assertStage(result, FooStage.GOAL_INVESTING, StageStatus.ACTION, StageReason.GOALS_NEED_FUNDING)
        assertEquals(Money(1_500_000), stage(result, FooStage.GOAL_INVESTING).amountMonthly)
        assertEquals(Money(500_000), result.unallocated)
    }

    /** Input: a top-up larger than the shortfall. Output: the fund never takes more than it needs. */
    @Test
    fun `the fund never takes more than its shortfall even when its pace is higher`() {
        val result =
            rank(
                input(
                    surplus = Money(3_000_000),
                    liquid = Money(9_000_000),
                    shortfall = Money(200_000),
                    topUp = Money(1_000_000),
                    runwayBps = 40_000,
                ),
            )

        assertEquals(Money(200_000), stage(result, FooStage.FULL_EMERGENCY).amountMonthly)
    }

    /** Input: no shortfall. Output: the fund is satisfied and takes nothing. */
    @Test
    fun `a funded emergency fund is satisfied`() {
        val result =
            rank(input(surplus = Money(100_000), liquid = Money(9_000_000), shortfall = Money.ZERO, runwayBps = 60_000))

        assertStage(result, FooStage.FULL_EMERGENCY, StageStatus.SATISFIED, StageReason.EMERGENCY_FUNDED)
        assertEquals(Money.ZERO, stage(result, FooStage.FULL_EMERGENCY).amountMonthly)
    }

    /** Input: an unsized fund. Output: an action with no amount and no need — "tell us your essentials". */
    @Test
    fun `an emergency fund that cannot be sized asks for data rather than money`() {
        val result =
            rank(input(surplus = Money(100_000), liquid = Money(9_000_000), shortfall = null, runwayBps = null))

        val emergency = stage(result, FooStage.FULL_EMERGENCY)
        assertEquals(StageStatus.ACTION, emergency.status)
        assertEquals(StageReason.EMERGENCY_UNSIZED, emergency.reason)
        assertNull(emergency.need)
        assertEquals(Money.ZERO, emergency.amountMonthly)
    }

    // --- Stage 5: goals -----------------------------------------------------------------------

    /** Input: goals that need nothing this month. Output: satisfied, not "no goals". */
    @Test
    fun `goals that need nothing this month are on track`() {
        val result = rank(clear(surplus = Money(100_000)).copy(goalCount = 2))

        assertStage(result, FooStage.GOAL_INVESTING, StageStatus.SATISFIED, StageReason.GOALS_ON_TRACK)
    }

    /** Input: no goals while the gate holds. Output: not applicable wins over blocked — nothing to hold. */
    @Test
    fun `with no goals the stage is not applicable even while the gate holds`() {
        val result =
            rank(input(surplus = Money(100_000), liquid = Money(5_000_000), shortfall = Money(900_000), runwayBps = 0))

        assertStage(result, FooStage.GOAL_INVESTING, StageStatus.NOT_APPLICABLE, StageReason.NO_GOALS)
    }

    /** Input: goals needing more than is left. Output: they take what is left. */
    @Test
    fun `goals take only what the stages above them left`() {
        val result = rank(clear(surplus = Money(700_000)).withOneGoalNeeding(Money(1_000_000)))

        assertEquals(Money(700_000), stage(result, FooStage.GOAL_INVESTING).amountMonthly)
        assertEquals(Money(1_000_000), stage(result, FooStage.GOAL_INVESTING).need)
        assertTrue(OrderOfOperationsRules.HORIZON in stage(result, FooStage.GOAL_INVESTING).citations)
    }

    // --- Stage 6: grey zone -------------------------------------------------------------------

    /** Input: a 10% loan past the gate. Output: a choice, with the equity rate beside it. */
    @Test
    fun `grey-zone debt is offered as a choice with the equity return beside it`() {
        val carLoan = loan(outstanding = Money(700_000), aprBps = 1_000)
        val result = rank(clear(surplus = Money(900_000)).copy(debts = listOf(carLoan)))

        val grey = stage(result, FooStage.GREY_ZONE_DEBT)
        assertEquals(StageStatus.CHOICE, grey.status)
        assertEquals(StageReason.GREY_DEBT_OUTSTANDING, grey.reason)
        assertEquals(1_200, grey.comparisonBps)
        assertEquals(Money(700_000), grey.amountMonthly)
        assertEquals(Money(200_000), result.unallocated)
        assertEquals(
            "a choice is the top action only when nothing asks outright",
            FooStage.GREY_ZONE_DEBT,
            result.topAction?.stage,
        )
    }

    /** Input: rates either side of 10%. Output: 1 000 bps is grey; 999 bps is low-rate. */
    @Test
    fun `the grey floor is inclusive at 1000 bps`() {
        val debts = listOf(loan(id = "at", aprBps = 1_000), loan(id = "under", aprBps = 999))
        val result = rank(clear(surplus = Money.ZERO).copy(debts = debts))

        assertEquals(listOf("at"), stage(result, FooStage.GREY_ZONE_DEBT).debts.map { it.accountId })
        assertEquals(listOf("under"), stage(result, FooStage.LOW_RATE_DEBT).debts.map { it.accountId })
    }

    // --- Stage 7: low-rate debt ---------------------------------------------------------------

    /** Input: an 8.5% home loan. Output: deferred to the simulator, no amount, the rule cited. */
    @Test
    fun `low-rate debt defers to the simulator and proposes no amount`() {
        val home = loan(id = "home", outstanding = Money(250_000_000), aprBps = 850)
        val result = rank(clear(surplus = Money(900_000)).copy(debts = listOf(home)))

        val low = stage(result, FooStage.LOW_RATE_DEBT)
        assertEquals(StageStatus.DEFER_TO_SIMULATOR, low.status)
        assertEquals(StageReason.LOW_RATE_DEBT_SIMULATOR_NOT_BUILT, low.reason)
        assertEquals(Money(250_000_000), low.need)
        assertEquals(Money.ZERO, low.amountMonthly)
        assertTrue(OrderOfOperationsRules.PREPAY_VS_INVEST in low.citations)
        assertEquals(Money(900_000), result.unallocated)
        assertNull("deferring to a simulator is not an action", result.topAction)
    }

    // --- the result as a whole ----------------------------------------------------------------

    /** Input: the full picture. Output: every paise poured is accounted for exactly once. */
    @Test
    fun `stage amounts plus what is left equal what was poured`() {
        val result =
            rank(
                input(
                    surplus = Money(12_345_678),
                    essentials = Money(2_000_000),
                    liquid = Money(500_000),
                    shortfall = Money(4_000_000),
                    topUp = Money(700_000),
                    runwayBps = 35_000,
                    debts =
                        listOf(
                            card(outstanding = Money(1_234_567), aprBps = 3_600),
                            loan(outstanding = Money(2_000_000), aprBps = 1_100),
                        ),
                    goalsRequired = Money(3_333_333),
                    goalCount = 3,
                ),
            )

        val placed = result.stages.fold(result.unallocated) { sum, stage -> sum + stage.amountMonthly }
        assertEquals(Money(12_345_678), placed)
        assertEquals(Money(1_500_000), stage(result, FooStage.STARTER_BUFFER).amountMonthly)
        assertEquals(Money(1_234_567), stage(result, FooStage.KILL_FIRE_DEBT).amountMonthly)
        assertEquals(Money(700_000), stage(result, FooStage.FULL_EMERGENCY).amountMonthly)
        assertEquals(Money(3_333_333), stage(result, FooStage.GOAL_INVESTING).amountMonthly)
        assertEquals(Money(2_000_000), stage(result, FooStage.GREY_ZONE_DEBT).amountMonthly)
        assertEquals(Money(3_577_778), result.unallocated)
    }

    /** Input: a profile with nothing left to do. Output: no top action, the surplus left idle. */
    @Test
    fun `with nothing to do there is no top action and the surplus is idle`() {
        val result = rank(clear(surplus = Money(800_000)))

        assertNull(result.topAction)
        assertEquals(Money(800_000), result.unallocated)
    }

    /** Input: any ranking. Output: provenance names AI-FOO 1.0, the day, the gate and every stage. */
    @Test
    fun `the provenance names the engine, the day, the gate and every FOO stage`() {
        val result = rank(input(surplus = null, nowUtcMillis = 1_789_000_000_000L))

        assertEquals("AI-FOO", result.provenance.engineId)
        assertEquals("1.0", result.provenance.engineVersion)
        assertEquals(1_789_000_000_000L, result.provenance.computedAtUtcMillis)
        assertEquals("2026-09-17", result.provenance.inputWindow)
        assertTrue(OrderOfOperationsRules.EMERGENCY_FIRST in result.provenance.evidence)
        FooStage.entries.forEach { foo ->
            assertTrue("$foo is not cited", OrderOfOperationsRules.citationFor(foo) in result.provenance.evidence)
        }
        assertEquals(
            "a stage citation is FOO.<id> at the file's version",
            "FOO.KILL_FIRE_DEBT@1.0",
            OrderOfOperationsRules.citationFor(FooStage.KILL_FIRE_DEBT).let { "${it.ruleId}@${it.ruleVersion}" },
        )
    }

    /** Input: the same input twice. Output: equal results — no clock, no randomness (P-08). */
    @Test
    fun `the same input always gives the same ranking`() {
        val input = gatedWithEverythingBelow(surplus = Money(4_000_000), shortfall = Money(1_000_000))

        assertEquals(rank(input), rank(input))
    }

    /** Input: sums past `Long.MAX_VALUE`. Output: `Err`, not a wrapped number or a thrown exception. */
    @Test
    fun `an overflowing sum is an error, never a wrapped figure`() {
        val huge = Money(Long.MAX_VALUE)
        val debts =
            listOf(
                loan(id = "a", outstanding = huge, aprBps = 1_400),
                loan(id = "b", outstanding = huge, aprBps = 1_500),
            )
        val result = engine.rank(input(liquid = Money(5_000_000), debts = debts))

        assertTrue("expected Err, was $result", result is Err)
    }

    // --- input guards -------------------------------------------------------------------------

    /** Input: malformed inputs. Output: each is refused at construction. */
    @Test
    fun `malformed inputs are refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            OrderOfOperationsInput(monthlySurplus = Money(1), surplusBasis = SurplusBasis.NONE, today = TODAY)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrderOfOperationsInput(monthlySurplus = null, surplusBasis = SurplusBasis.OBSERVED_MEDIAN, today = TODAY)
        }
        assertThrows(IllegalArgumentException::class.java) { input(liquid = Money(-1)) }
        assertThrows(IllegalArgumentException::class.java) { input(shortfall = Money(-1)) }
        assertThrows(IllegalArgumentException::class.java) { input(topUp = Money(-1)) }
        assertThrows(IllegalArgumentException::class.java) { input(runwayBps = -1) }
        assertThrows(IllegalArgumentException::class.java) { input(essentials = Money(-1)) }
        assertThrows(IllegalArgumentException::class.java) { input(goalsRequired = Money(-1), goalCount = 1) }
        assertThrows(IllegalArgumentException::class.java) { input(goalsRequired = Money(1), goalCount = 0) }
        assertThrows(IllegalArgumentException::class.java) { input(goalCount = -1) }
        assertThrows(IllegalArgumentException::class.java) { input().copy(emergencyGateMonths = -1) }
    }

    /** Input: malformed debts. Output: refused — only a card may have an unknown rate. */
    @Test
    fun `malformed debts are refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            DebtPosition("l", "Loan", DebtKind.LOAN, Money(1), aprBps = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DebtPosition("c", "Card", DebtKind.CARD, Money(-1), aprBps = 100)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DebtPosition("c", "Card", DebtKind.CARD, Money(1), aprBps = -1)
        }
    }

    /** Input: inverted or negative thresholds. Output: refused — they would make a stage unreachable. */
    @Test
    fun `malformed rules are refused`() {
        assertThrows(IllegalArgumentException::class.java) { OrderOfOperationsRules(greyAprMinBps = 1_400) }
        assertThrows(IllegalArgumentException::class.java) { OrderOfOperationsRules(greyAprMinBps = -1) }
        assertThrows(IllegalArgumentException::class.java) { OrderOfOperationsRules(starterCap = Money(-1)) }
        assertThrows(IllegalArgumentException::class.java) { OrderOfOperationsRules(starterEssentialsMonths = -1) }
        assertThrows(IllegalArgumentException::class.java) { OrderOfOperationsRules(equityNominalBps = -1) }
    }

    /** Input: results that break the waterfall's own invariants. Output: refused at construction. */
    @Test
    fun `a result that loses a paise or reorders the stages cannot be built`() {
        val good = rank(clear(surplus = Money(800_000)))

        assertThrows(IllegalArgumentException::class.java) { good.copy(unallocated = Money(799_999)) }
        assertThrows(IllegalArgumentException::class.java) { good.copy(stages = good.stages.reversed()) }
        assertThrows(IllegalArgumentException::class.java) { good.copy(unallocated = Money(-1)) }
        assertThrows(IllegalArgumentException::class.java) {
            good.copy(topAction = good.stages.first().copy(reason = StageReason.NO_GOALS))
        }
        assertThrows(IllegalArgumentException::class.java) {
            good.copy(provenance = good.provenance.copy(evidence = emptyList()))
        }
        val stage = good.stages.first()
        assertThrows(IllegalArgumentException::class.java) { stage.copy(amountMonthly = Money(-1)) }
        assertThrows(IllegalArgumentException::class.java) { stage.copy(need = Money(1), amountMonthly = Money(2)) }
        assertThrows(IllegalArgumentException::class.java) { stage.copy(citations = emptyList()) }
    }

    // --- fixtures -----------------------------------------------------------------------------

    /**
     * Result: the ranking, unwrapped. Input: [input]. Output: the `Ok` value; fails the test on `Err`.
     */
    private fun rank(input: OrderOfOperationsInput): OrderOfOperations {
        val result = engine.rank(input)
        assertTrue("expected Ok, was $result", result is Ok)
        return (result as Ok).value
    }

    /** Result: one stage of a ranking. Input: [result]; [foo]. Output: that stage's outcome. */
    private fun stage(
        result: OrderOfOperations,
        foo: FooStage,
    ): StageOutcome = result.stages.single { it.stage == foo }

    /** Asserts one stage's status and reason together, naming the stage on failure. */
    private fun assertStage(
        result: OrderOfOperations,
        foo: FooStage,
        status: StageStatus,
        reason: StageReason,
    ) {
        val outcome = stage(result, foo)
        assertEquals("$foo status", status, outcome.status)
        assertEquals("$foo reason", reason, outcome.reason)
    }

    /**
     * An input with every field defaulted to "nothing known", so each test states only what it is
     * about. The surplus basis follows the surplus, so the pair can never disagree by accident.
     */
    @Suppress("LongParameterList") // A fixture: every argument is a field a test may need to set.
    private fun input(
        surplus: Money? = Money.ZERO,
        essentials: Money? = null,
        liquid: Money = Money.ZERO,
        shortfall: Money? = null,
        topUp: Money = Money.ZERO,
        runwayBps: Int? = null,
        debts: List<DebtPosition> = emptyList(),
        goalsRequired: Money = Money.ZERO,
        goalCount: Int = 0,
        nowUtcMillis: Long = 0L,
    ): OrderOfOperationsInput =
        OrderOfOperationsInput(
            monthlySurplus = surplus,
            surplusBasis = if (surplus == null) SurplusBasis.NONE else SurplusBasis.OBSERVED_MEDIAN,
            monthlyEssentials = essentials,
            liquidFunds = liquid,
            emergencyShortfall = shortfall,
            emergencyTopUpMonthly = topUp,
            emergencyRunwayMonthsBps = runwayBps,
            debts = debts,
            goalsRequiredMonthly = goalsRequired,
            goalCount = goalCount,
            today = TODAY,
            nowUtcMillis = nowUtcMillis,
        )

    /**
     * A profile past every gate with nothing owed: buffer held, fund complete, runway six months.
     * Tests add the one thing they are about.
     */
    private fun clear(surplus: Money): OrderOfOperationsInput =
        input(
            surplus = surplus,
            essentials = Money(3_000_000),
            liquid = Money(18_000_000),
            shortfall = Money.ZERO,
            runwayBps = 60_000,
        )

    /** Result: a copy of the receiver with one goal needing [required] this month. */
    private fun OrderOfOperationsInput.withOneGoalNeeding(required: Money): OrderOfOperationsInput =
        copy(goalsRequiredMonthly = required, goalCount = 1)

    /**
     * A gated profile — runway two months, below the three-month gate — with a need in every stage
     * below the fund, so a test can see all three held.
     */
    private fun gatedWithEverythingBelow(
        surplus: Money,
        shortfall: Money,
        runway: Int? = 20_000,
    ): OrderOfOperationsInput =
        input(
            surplus = surplus,
            essentials = Money(3_000_000),
            liquid = Money(6_000_000),
            shortfall = shortfall,
            topUp = Money(300_000),
            runwayBps = runway,
            debts =
                listOf(
                    loan(id = "grey", outstanding = Money(900_000), aprBps = 1_100),
                    loan(id = "home", outstanding = Money(900_000), aprBps = 850),
                ),
            goalsRequired = Money(500_000),
            goalCount = 1,
        )

    private fun card(
        id: String = "card",
        outstanding: Money = Money(100_000),
        aprBps: Int?,
    ) = DebtPosition(id, "Card $id", DebtKind.CARD, outstanding, aprBps)

    private fun loan(
        id: String = "loan",
        outstanding: Money = Money(100_000),
        aprBps: Int,
    ) = DebtPosition(id, "Loan $id", DebtKind.LOAN, outstanding, aprBps)

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 9, 17)
    }
}
