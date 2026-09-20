package com.aicfo.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.common.UuidIdGenerator
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.CategoryEntity
import com.aicfo.core.database.entity.ProfileEntity
import com.aicfo.core.model.Category
import com.aicfo.core.model.CategoryNature
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import com.aicfo.domain.engines.budget.BudgetStatus
import com.aicfo.domain.engines.emergencyfund.EmergencyFundPlan
import com.aicfo.domain.engines.emergencyfund.EmergencyStatus
import com.aicfo.domain.engines.emergencyfund.EssentialsBasis
import com.aicfo.domain.engines.goals.GoalProjection
import com.aicfo.domain.engines.goals.GoalStatus
import com.aicfo.domain.engines.goals.Horizon
import com.aicfo.domain.engines.healthscore.HealthScore
import com.aicfo.domain.engines.insight.InsightEngineFactory
import com.aicfo.domain.engines.insight.InsightType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant

/**
 * AI-ORCH's pipeline and its persisted feed (issue 9.5; §7.2, AI-ARC-005, RULE-INS-DEDUP).
 *
 * Why:  the engine is proven on literal signals. What only this layer can get wrong is the part
 *       that makes a feed a feed: that a recomputation **updates** a card rather than adding a
 *       second, that a dismissal survives the next recomputation, that a card whose fact has been
 *       corrected goes away, and that a card the user put away comes back when its window ends.
 *       Each of those failures is invisible in a single read and obvious after a week of use.
 * What: the signal mappings; refresh's upsert, its pruning and its dismissal-preserving update; the
 *       verdicts and their suppression; the dashboard's few; the profile scope.
 * Result: the feed on the dashboard is the one the orchestrator meant.
 * Changelog: 2026-09-20 — Created for issue 9.5.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class InsightRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var repository: InsightRepository

    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = FakeClock(initialMillis = Instant.parse("2026-09-20T06:00:00Z").toEpochMilli())
    private val activeProfileId = MutableStateFlow(PROFILE)
    private val forecast =
        MutableStateFlow<Result<com.aicfo.domain.engines.forecast.CashFlowForecast, AppError>>(Err(NO_FORECAST))
    private val health = MutableStateFlow<Result<HealthScore, AppError>>(Err(NO_FORECAST))
    private val emergency = MutableStateFlow(plan(shortfall = 0L))
    private val budgets = MutableStateFlow<List<CategoryBudget>>(emptyList())
    private val goals = MutableStateFlow<List<GoalProjection>>(emptyList())

    /** Input: none. Output: an in-memory database and the real orchestrator over it. */
    @Before
    fun setUp() =
        runTest(dispatcher) {
            database =
                Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CfoDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            database.profileDao().upsert(ProfileEntity(PROFILE, "Test", "UTC", "INR", NOW, NOW))
            database.categoryDao().upsertAll(
                listOf(CategoryEntity(DINING, PROFILE, "Dining", null, "want", isSystem = true, NOW, NOW)),
            )
            repository =
                RoomInsightRepository(
                    database = database,
                    sources = InsightSources(forecast, health, emergency, budgets, goals),
                    engine = InsightEngineFactory.create(),
                    clock = clock,
                    dispatchers = TestDispatchers(dispatcher),
                    activeProfileId = activeProfileId,
                    idGenerator = UuidIdGenerator(),
                )
        }

    /** Input: none. Output: closed. */
    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a profile with nothing wrong has an empty feed`() =
        runTest(dispatcher) {
            assertEquals(0, repository.refresh().expectOk())
            assertTrue(repository.observeFeed().first().isEmpty())
        }

    @Test
    fun `an overspent budget becomes a card that carries the budget engine's own figure and rules`() =
        runTest(dispatcher) {
            budgets.value = listOf(budget(overspentBy = 1_250_00L))

            assertEquals(1, repository.refresh().expectOk())

            val card = repository.observeFeed().first().single()
            assertEquals(InsightType.BUDGET_OVERSPENT, card.insight.type)
            assertEquals("BUDGET_OVERSPENT|$DINING|2026-09", card.insight.fingerprint)
            assertEquals(Money(1_250_00L), card.insight.amount)
            assertEquals("Dining", card.insight.subjectLabel)
            assertEquals("budget-planner", card.insight.sourceEngineId)
            assertEquals(listOf(RuleCitation("RULE-BUD-ALERT", "1.0")), card.insight.citations)
            assertEquals(InsightStatus.ACTIVE, card.status)
        }

    @Test
    fun `recomputing updates the card in place rather than raising a second one`() =
        runTest(dispatcher) {
            budgets.value = listOf(budget(overspentBy = 1_250_00L))
            repository.refresh().expectOk()
            val first = repository.observeFeed().first().single()

            budgets.value = listOf(budget(overspentBy = 2_000_00L))
            repository.refresh().expectOk()

            val card = repository.observeFeed().first().single()
            assertEquals("the same fingerprint is the same row", first.id, card.id)
            assertEquals(Money(2_000_00L), card.insight.amount)
        }

    @Test
    fun `a card whose fact has been corrected goes away`() =
        runTest(dispatcher) {
            budgets.value = listOf(budget(overspentBy = 1_250_00L))
            repository.refresh().expectOk()

            budgets.value = listOf(budget(overspentBy = 0L))
            assertEquals(0, repository.refresh().expectOk())

            assertTrue(repository.observeFeed().first().isEmpty())
        }

    @Test
    fun `a dismissed card is hidden, survives the next recomputation, and returns when its window ends`() =
        runTest(dispatcher) {
            budgets.value = listOf(budget(overspentBy = 1_250_00L))
            repository.refresh().expectOk()
            val card = repository.observeFeed().first().single()

            repository.dismiss(card.id).expectOk()
            assertTrue("a dismissed card leaves the feed", repository.observeFeed().first().isEmpty())

            repository.refresh().expectOk()
            assertTrue("and a recomputation does not undo the dismissal", repository.observeFeed().first().isEmpty())

            clock.advanceBy(Duration.ofDays(8))
            val returned = repository.observeFeed().first().single()
            assertEquals("the same row, not a new one", card.id, returned.id)
            assertEquals(InsightStatus.DISMISSED, returned.status)
        }

    @Test
    fun `snoozing and acting record their own verdicts, with the same window`() =
        runTest(dispatcher) {
            budgets.value =
                listOf(budget(overspentBy = 1_250_00L), budget(categoryId = FUEL, name = "Fuel", overspentBy = 900_00L))
            repository.refresh().expectOk()
            val (first, second) = repository.observeFeed().first()

            repository.snooze(first.id).expectOk()
            repository.act(second.id).expectOk()

            clock.advanceBy(Duration.ofDays(8))
            val statuses = repository.observeFeed().first().associate { it.insight.subject to it.status }
            // Dining is over by more, so it ranks first and is the one snoozed.
            assertEquals(mapOf(DINING to InsightStatus.SNOOZED, FUEL to InsightStatus.ACTED), statuses)
        }

    @Test
    fun `a verdict on a card that is already gone is not an error`() =
        runTest(dispatcher) {
            assertEquals(Unit, repository.dismiss("insight:missing").expectOk())
        }

    @Test
    fun `the emergency fund's shortfall and the goals' shortfalls become their own cards, ranked by size`() =
        runTest(dispatcher) {
            emergency.value = plan(shortfall = 1_20_000_00L)
            goals.value =
                listOf(goal("g1", "Laptop", GoalStatus.BEHIND, 2_500_00L), goal("g2", "Trip", GoalStatus.ON_TRACK, 0L))

            assertEquals(2, repository.refresh().expectOk())

            assertEquals(
                listOf(InsightType.EMERGENCY_FUND_SHORT, InsightType.GOAL_BEHIND),
                repository.observeFeed().first().map { it.insight.type },
            )
        }

    @Test
    fun `the dashboard takes only the rulebook's few`() =
        runTest(dispatcher) {
            emergency.value = plan(shortfall = 1_20_000_00L)
            budgets.value =
                listOf(
                    budget(overspentBy = 1_250_00L),
                    budget(categoryId = FUEL, name = "Fuel", overspentBy = 900_00L),
                    budget(categoryId = "c3", name = "Three", overspentBy = 800_00L),
                )
            repository.refresh().expectOk()

            assertEquals(4, repository.observeFeed().first().size)
            assertEquals(3, repository.observeDashboard().first().size)
        }

    @Test
    fun `another profile's feed is never read`() =
        runTest(dispatcher) {
            budgets.value = listOf(budget(overspentBy = 1_250_00L))
            repository.refresh().expectOk()

            activeProfileId.value = "demo"

            assertTrue(repository.observeFeed().first().isEmpty())
        }

    @Test
    fun `a failed engine upstream costs its cards, not the feed`() =
        runTest(dispatcher) {
            budgets.value = listOf(budget(overspentBy = 1_250_00L))
            forecast.value = Err(AppError.Validation("forecast.spend"))
            health.value = Err(AppError.Validation("health.runway"))

            assertEquals(1, repository.refresh().expectOk())
            assertEquals(InsightType.BUDGET_OVERSPENT, repository.observeFeed().first().single().insight.type)
        }

    // --- signal mappings --------------------------------------------------------------------------

    @Test
    fun `only overspent budgets with a plan become signals`() {
        val rows =
            listOf(
                budget(overspentBy = 1_250_00L),
                budget(categoryId = FUEL, name = "Fuel", overspentBy = 0L),
                budget(categoryId = "c9", name = "Nine", overspentBy = 500_00L, planned = null),
            )

        val signals = InsightSignals.budgets(rows, "2026-09")

        assertEquals(listOf(DINING), signals.map { it.categoryId })
        assertEquals(Money(1_250_00L), signals.single().overspentBy)
    }

    @Test
    fun `only goals behind or past due, and short of their plan, become signals`() {
        val goals =
            listOf(
                goal("g1", "Behind", GoalStatus.BEHIND, 2_500_00L),
                goal("g2", "Past due", GoalStatus.PAST_DUE, 1_000_00L),
                goal("g3", "On track", GoalStatus.ON_TRACK, 900_00L),
                goal("g4", "No target", GoalStatus.NO_TARGET, 900_00L),
                goal("g5", "Behind by nothing", GoalStatus.BEHIND, 0L),
            )

        assertEquals(listOf("g1", "g2"), InsightSignals.goals(goals).map { it.goalId })
    }

    @Test
    fun `the emergency fund's own figures are passed through untouched`() {
        val signal = InsightSignals.emergency(plan(shortfall = 1_20_000_00L))

        assertEquals(Money(1_20_000_00L), signal.shortfall)
        assertEquals(Money(6_500_00L), signal.topUpMonthly)
        assertEquals("AI-EMF", signal.provenance.engineId)
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private fun budget(
        categoryId: String = DINING,
        name: String = "Dining",
        overspentBy: Long,
        planned: String? = "b-$categoryId",
    ) = CategoryBudget(
        id = planned,
        category = Category(categoryId, name, CategoryNature.WANT),
        status =
            BudgetStatus(
                budgeted = Money(10_000_00L),
                carriedOver = Money.ZERO,
                spent = Money(10_000_00L + overspentBy),
                remaining = Money(-overspentBy),
                safePaceToDate = Money(5_000_00L),
                projectedEndOfMonth = null,
                provenance =
                    EngineProvenance(
                        "budget-planner",
                        "1.0",
                        NOW,
                        listOf(RuleCitation("RULE-BUD-ALERT", "1.0")),
                        confidenceBps = 10_000,
                    ),
            ),
        rolloverEnabled = false,
        source = "manual",
    )

    private fun goal(
        id: String,
        name: String,
        status: GoalStatus,
        shortfall: Long,
    ) = GoalProjection(
        goalId = id,
        name = name,
        target = Money(1_00_000_00L),
        targetDateIso = "2027-06-01",
        saved = Money(10_000_00L),
        remaining = Money(90_000_00L),
        monthsRemaining = 9,
        requiredMonthly = Money(10_000_00L),
        plannedMonthly = Money(10_000_00L - shortfall),
        shortfallMonthly = Money(shortfall),
        etaIsoDate = null,
        onTrack = status == GoalStatus.ON_TRACK,
        horizon = Horizon.SHORT,
        status = status,
    )

    private fun plan(shortfall: Long) =
        EmergencyFundPlan(
            monthlyEssentials = Money(50_000_00L),
            essentialsBasis = EssentialsBasis.OBSERVED_MEDIAN,
            incomeCvBps = null,
            multiplierMonths = 6,
            multiplierWasClamped = false,
            target = Money(3_00_000_00L),
            liquidFunds = Money(1_80_000_00L),
            fundedRatioBps = 6_000,
            shortfall = Money(shortfall),
            runwayMonthsBps = 36_000,
            topUpMonthly = Money(6_500_00L),
            status = EmergencyStatus.BUILDING,
            liquidAccountNames = emptyList(),
            essentialCategoryNames = emptyList(),
            provenance =
                EngineProvenance(
                    "AI-EMF",
                    "1.0",
                    NOW,
                    listOf(RuleCitation("RULE-EMF-COACH", "1.0")),
                    confidenceBps = 10_000,
                ),
        )

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val PROFILE = "local"
        const val NOW = 1_789_800_000_000L
        const val DINING = "local:category:dining"
        const val FUEL = "local:category:fuel"
        val NO_FORECAST = AppError.Validation("forecast.none")
    }
}
