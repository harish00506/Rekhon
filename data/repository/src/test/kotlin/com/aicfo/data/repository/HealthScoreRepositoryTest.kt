package com.aicfo.data.repository

import app.cash.turbine.test
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.model.Category
import com.aicfo.core.model.CategoryNature
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import com.aicfo.domain.engines.budget.BudgetStatus
import com.aicfo.domain.engines.card.BillingCycle
import com.aicfo.domain.engines.card.CardStatus
import com.aicfo.domain.engines.card.Utilisation
import com.aicfo.domain.engines.card.UtilisationBasis
import com.aicfo.domain.engines.emergencyfund.EmergencyFundPlan
import com.aicfo.domain.engines.emergencyfund.EmergencyStatus
import com.aicfo.domain.engines.emergencyfund.EssentialsBasis
import com.aicfo.domain.engines.goals.GoalProjection
import com.aicfo.domain.engines.goals.GoalStatus
import com.aicfo.domain.engines.goals.Horizon
import com.aicfo.domain.engines.healthscore.HealthScore
import com.aicfo.domain.engines.healthscore.HealthScoreEngineFactory
import com.aicfo.domain.engines.healthscore.MonthFlow
import com.aicfo.domain.engines.healthscore.ObligationInput
import com.aicfo.domain.engines.healthscore.Pillar
import com.aicfo.domain.engines.healthscore.RunwayInput
import com.aicfo.domain.engines.healthscore.ShareInput
import com.aicfo.domain.engines.healthscore.UtilisationInput
import com.aicfo.domain.engines.loan.AmortisationRow
import com.aicfo.domain.engines.nature.NatureBreakdown
import com.aicfo.domain.engines.stream.StreamBasis
import com.aicfo.domain.engines.stream.StreamClass
import com.aicfo.domain.engines.stream.StreamProfile
import com.aicfo.domain.engines.stream.StreamVerdict
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * AI-FHS fed from the owning repositories (issue 9.4; §14, ADR-0045).
 *
 * Why:  the engine is proven on literal signals. What only this layer can get wrong is **what goes
 *       in**: an EMI counted once as a loan and again as a fixed stream, a card with no statement
 *       scored as empty, an unbudgeted category counted as kept, a goal with no target counted as
 *       behind, liability payments counted as savings. Each produces a score that looks fine.
 * What: [HealthSignals]'s six mappings, one decision per test; then the composed repository end to
 *       end over plain flows, including re-emission when a source moves.
 * Result: the score on the dashboard is built from the right figures.
 * Changelog: 2026-09-19 — Created for issue 9.4.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HealthScoreRepositoryTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Test
    fun `runway is the emergency fund's, against its own M, and absent while essentials are unknown`() {
        assertEquals(RunwayInput(36_000, 6), HealthSignals.runway(plan(runwayBps = 36_000, m = 6)))
        assertNull(HealthSignals.runway(plan(runwayBps = null, m = 6)))
    }

    @Test
    fun `obligations are the fixed streams plus each loan's instalment, over the median income`() {
        val signal =
            HealthSignals.obligations(
                months =
                    listOf(
                        month("2026-06", 90_000_00L),
                        month("2026-07", 1_00_000_00L),
                        month("2026-08", 95_000_00L),
                    ),
                streams =
                    profile(
                        stream("rent", StreamClass.FIXED, 28_000_00L),
                        stream("dining", StreamClass.VARIABLE, 6_000_00L),
                    ),
                instalments = listOf(instalment(12_000_00L)),
                liabilityCategories = emptySet(),
            )

        assertEquals(ObligationInput(Money(40_000_00L), Money(95_000_00L), 3), signal)
    }

    @Test
    fun `a fixed liability stream is dropped when loans exist, because it is those loans' instalments`() {
        val months = listOf(month("2026-08", 1_00_000_00L))
        val streams =
            profile(stream("emi", StreamClass.FIXED, 12_000_00L), stream("rent", StreamClass.FIXED, 28_000_00L))

        assertEquals(
            Money(40_000_00L),
            HealthSignals.obligations(months, streams, listOf(instalment(12_000_00L)), setOf("emi"))!!.obligations,
        )
        assertEquals(
            "with no loan account, the EMI stream is the only record of the EMI",
            Money(40_000_00L),
            HealthSignals.obligations(months, streams, emptyList(), setOf("emi"))!!.obligations,
        )
    }

    @Test
    fun `nothing fixed and no loan means obligations are unknown, not zero`() {
        // Found on the device: the demo has ₹28,000 of rent, but AI-CLS cannot call a stream FIXED
        // until three closed months (§8.2). Scoring that as "0% of income, full marks" claims
        // something the data does not support — the signal is absent instead (ADR-0045).
        val months = listOf(month("2026-08", 1_00_000_00L))

        assertNull(
            HealthSignals.obligations(
                months,
                profile(stream("dining", StreamClass.VARIABLE, 6_000_00L)),
                emptyList(),
                emptySet(),
            ),
        )
        assertNull(HealthSignals.obligations(months, null, emptyList(), emptySet()))
        assertEquals(
            Money.ZERO,
            HealthSignals.obligations(months, profile(), listOf(instalment(0L)), emptySet())!!.obligations,
        )
    }

    @Test
    fun `obligations need a month of income, and count only the months that had some`() {
        val rent = profile(stream("rent", StreamClass.FIXED, 20_000_00L))
        assertNull(HealthSignals.obligations(listOf(month("2026-08", 0L)), rent, emptyList(), emptySet()))
        val signal =
            HealthSignals.obligations(
                listOf(month("2026-07", 0L), month("2026-08", 50_000_00L)),
                rent,
                emptyList(),
                emptySet(),
            )
        assertEquals(ObligationInput(Money(20_000_00L), Money(50_000_00L), 1), signal)
    }

    @Test
    fun `utilisation is the statement balance over the limit, summed, leaving out cards with no statement`() {
        val signal =
            HealthSignals.cards(
                listOf(
                    card(limit = 1_00_000_00L, statement = 20_000_00L, live = 55_000_00L),
                    card(limit = 50_000_00L, statement = 10_000_00L, live = 10_000_00L),
                    card(limit = 2_00_000_00L, statement = null, live = 1_00_000_00L),
                ),
            )

        assertEquals(UtilisationInput(Money(30_000_00L), Money(1_50_000_00L)), signal)
        assertNull(HealthSignals.cards(listOf(card(limit = 1_00_000_00L, statement = null, live = 1L))))
    }

    @Test
    fun `saved is income less needs, wants and liability payments, and investing counts as kept`() {
        val months =
            listOf(
                MonthlyLedger(
                    "2026-08",
                    NatureBreakdown(
                        needs = Money(40_000_00L),
                        wants = Money(15_000_00L),
                        invested = Money(10_000_00L),
                        assets = Money(2_000_00L),
                        liabilities = Money(12_000_00L),
                    ),
                    Money(95_000_00L),
                ),
            )

        assertEquals(
            listOf(MonthFlow("2026-08", Money(95_000_00L), Money(28_000_00L))),
            HealthSignals.savings(months).months,
        )
    }

    @Test
    fun `budgets count only the categories that have one, and an overspent one is not kept`() {
        val rows =
            listOf(
                budget(id = "a", remaining = 1L),
                budget(id = "b", remaining = -1L),
                budget(id = null, remaining = -99L),
            )

        assertEquals(ShareInput(1, 2), HealthSignals.budgets(rows))
        assertNull(HealthSignals.budgets(listOf(budget(id = null, remaining = 0L))))
    }

    @Test
    fun `goals without a target are not counted, and funded and on-track ones are good`() {
        val goals =
            listOf(
                goal(GoalStatus.ON_TRACK),
                goal(GoalStatus.OVER_FUNDED),
                goal(GoalStatus.BEHIND),
                goal(GoalStatus.PAST_DUE),
                goal(GoalStatus.NO_TARGET),
            )

        assertEquals(ShareInput(2, 4), HealthSignals.goals(goals))
        assertNull(HealthSignals.goals(listOf(goal(GoalStatus.NO_TARGET))))
    }

    @Test
    fun `the composed repository scores the sources and re-scores when one moves`() =
        runTest(dispatcher) {
            val emergency = MutableStateFlow(plan(runwayBps = 36_000, m = 6))
            val ledger = MutableStateFlow(listOf(month("2026-08", 1_00_000_00L, needs = 70_000_00L)))
            val repository = repository(emergency, ledger)

            repository.observeHealthScore().test {
                val first = awaitItem().expectOk()
                assertEquals(6_000, first.pillars.single { it.pillar == Pillar.LIQUIDITY }.points)
                assertEquals("2026-08..2026-08", first.provenance.inputWindow)

                emergency.value = plan(runwayBps = 60_000, m = 6)
                assertEquals(10_000, awaitItem().expectOk().pillars.single { it.pillar == Pillar.LIQUIDITY }.points)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a stream engine failure leaves the debt pillar unscored rather than failing the score`() =
        runTest(dispatcher) {
            val repository =
                repository(
                    MutableStateFlow(plan(runwayBps = null, m = 6)),
                    MutableStateFlow(listOf(month("2026-08", 1_00_000_00L))),
                    streams = MutableStateFlow(Err(AppError.Validation("stream.x"))),
                )

            repository.observeHealthScore().test {
                val score = awaitItem().expectOk()
                // Nothing measured is not zero obligations: the pillar is "—" and its weight moves.
                assertNull(score.pillars.single { it.pillar == Pillar.DEBT }.points)
                assertEquals(0, score.pillars.single { it.pillar == Pillar.DEBT }.effectiveWeightBps)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- fixtures ---------------------------------------------------------------------------------

    private fun repository(
        emergency: MutableStateFlow<EmergencyFundPlan>,
        ledger: MutableStateFlow<List<MonthlyLedger>>,
        streams: MutableStateFlow<Result<StreamProfile, AppError>> = MutableStateFlow(Ok(profile())),
    ): HealthScoreRepository =
        ComposedHealthScoreRepository(
            sources =
                HealthSources(
                    emergency = emergency,
                    ledger = ledger,
                    streams = streams,
                    instalments = MutableStateFlow(emptyMap()),
                    cards = MutableStateFlow(emptyMap()),
                    budgets = MutableStateFlow(emptyList()),
                    goals = MutableStateFlow(emptyList()),
                    categories = MutableStateFlow(listOf(Category("emi", "EMI", CategoryNature.LIABILITY))),
                ),
            engine = HealthScoreEngineFactory.create(),
            clock = FakeClock(initialMillis = NOW),
            dispatchers = TestDispatchers(dispatcher),
        )

    private fun month(
        key: String,
        income: Long,
        needs: Long = 0L,
    ) = MonthlyLedger(key, NatureBreakdown(needs = Money(needs)), Money(income))

    private fun profile(vararg streams: StreamVerdict) =
        StreamProfile(streams.toList(), Money.ZERO, Money.ZERO, Money.ZERO, PROVENANCE)

    private fun stream(
        key: String,
        streamClass: StreamClass,
        typical: Long,
    ) = StreamVerdict(key, streamClass, StreamBasis.SCORED, null, Money(typical), PROVENANCE)

    private fun instalment(amount: Long): AmortisationRow {
        val principal = amount - 1_000_00L
        return AmortisationRow(
            1,
            "2026-10-05",
            Money(amount),
            Money(principal),
            Money(1_000_00L),
            Money(OPENING),
            Money(OPENING - principal),
        )
    }

    private fun card(
        limit: Long,
        statement: Long?,
        live: Long,
    ) = CardStatus(
        accountId = "card",
        creditLimit = Money(limit),
        live = Utilisation(UtilisationBasis.LIVE, Money(live), null),
        statement = Utilisation(UtilisationBasis.STATEMENT, statement?.let(::Money), null),
        unbilled = Money.ZERO,
        available = Money(limit),
        cycle =
            BillingCycle(
                LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-09-20"),
                LocalDate.parse("2026-10-01"),
                1,
            ),
        minimumDue = null,
        provenance = PROVENANCE,
    )

    private fun budget(
        id: String?,
        remaining: Long,
    ) = CategoryBudget(
        id = id,
        category = Category("c-$id", "C", CategoryNature.WANT),
        status =
            BudgetStatus(
                Money(100L),
                Money.ZERO,
                Money(100L - remaining),
                Money(remaining),
                Money.ZERO,
                null,
                PROVENANCE,
            ),
        rolloverEnabled = false,
        source = "manual",
    )

    private fun goal(status: GoalStatus) =
        GoalProjection(
            goalId = status.name,
            name = status.name,
            target = Money(1_000L),
            targetDateIso = "2027-01-01",
            saved = Money.ZERO,
            remaining = Money(1_000L),
            monthsRemaining = 4,
            requiredMonthly = Money(250L),
            plannedMonthly = Money(250L),
            shortfallMonthly = Money.ZERO,
            etaIsoDate = null,
            onTrack = status == GoalStatus.ON_TRACK,
            horizon = Horizon.SHORT,
            status = status,
        )

    private fun plan(
        runwayBps: Int?,
        m: Int,
    ) = EmergencyFundPlan(
        monthlyEssentials = Money(50_000_00L),
        essentialsBasis = EssentialsBasis.OBSERVED_MEDIAN,
        incomeCvBps = null,
        multiplierMonths = m,
        multiplierWasClamped = false,
        target = Money(3_00_000_00L),
        liquidFunds = Money(1_80_000_00L),
        fundedRatioBps = 6_000,
        shortfall = Money(1_20_000_00L),
        runwayMonthsBps = runwayBps,
        topUpMonthly = Money.ZERO,
        status = EmergencyStatus.BUILDING,
        liquidAccountNames = emptyList(),
        essentialCategoryNames = emptyList(),
        provenance = PROVENANCE,
    )

    private fun Result<HealthScore, AppError>.expectOk(): HealthScore =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val NOW = 1_789_800_000_000L
        const val OPENING = 10_00_000_00L
        val PROVENANCE = EngineProvenance("test", "1.0", 0L, listOf(RuleCitation("RULE-TEST", "1.0")))
    }
}
