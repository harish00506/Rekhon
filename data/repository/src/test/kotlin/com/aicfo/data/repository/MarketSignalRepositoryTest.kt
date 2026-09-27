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
import com.aicfo.core.database.entity.AccountEntity
import com.aicfo.core.database.entity.InvestmentHoldingEntity
import com.aicfo.core.database.entity.ProfileEntity
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.marketsignal.AssessmentOutcome
import com.aicfo.domain.engines.marketsignal.Capacity
import com.aicfo.domain.engines.marketsignal.MarketSignalEngineFactory
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The opportunity screen against a real database (issue 10.7; §30, ADR-0055).
 *
 * Why:  the scoring is proven in `:domain:engines:marketsignal`. What only this layer can get wrong
 *       is which history it hands over and which household it describes: a second close recorded on
 *       one day (which would weight that day twice in every average), another profile's series
 *       leaking in, a held instrument with no cached history quietly disappearing from the screen
 *       instead of saying it has none, or capacity gates read from the wrong engines.
 * What: the series, the one-row-per-day rule, the profile scope, the empty case and the gates.
 * Result: a screen that is about this household's own cached history.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MarketSignalRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var repository: MarketSignalRepository

    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = FakeClock(Instant.parse("2026-09-27T04:30:00Z").toEpochMilli(), ZoneId.of("Asia/Kolkata"))
    private val activeProfileId = MutableStateFlow(PROFILE)
    private val capacity = MutableStateFlow(Capacity())

    /** Input: none. Output: an in-memory database, one held instrument, and the repository. */
    @Before
    fun setUp() =
        runTest(dispatcher) {
            database =
                Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CfoDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            repository =
                CachedMarketSignalRepository(
                    database = database,
                    engine = MarketSignalEngineFactory.create(),
                    capacity = capacity,
                    clock = clock,
                    dispatchers = TestDispatchers(dispatcher),
                    activeProfileId = activeProfileId,
                    idGenerator = UuidIdGenerator(),
                )
            seedHolding()
        }

    /** Input: none. Output: closed. */
    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a held instrument with no cached history says so rather than disappearing`() =
        runTest(dispatcher) {
            // `first()` alone can catch the combine's opening emission, before Room has replayed
            // the holding written in setUp — so this waits for the list the screen would render.
            val view = repository.observeOpportunities().first { it.isNotEmpty() }.single()

            assertEquals("Nifty BeES", view.holdingLabel)
            assertEquals(AssessmentOutcome.NOT_ENOUGH_HISTORY, view.assessment.outcome)
        }

    @Test
    fun `a year of cached closes is scored`() =
        runTest(dispatcher) {
            record(300) { 10_000_00L }

            val view = repository.observeOpportunities().first().single()

            assertEquals(AssessmentOutcome.SCORED, view.assessment.outcome)
            assertEquals(
                "the price and volatility feeds are absent, so their weight is off the table",
                60,
                view.assessment.possibleScore,
            )
        }

    @Test
    fun `a second close for one day corrects it rather than counting it twice`() =
        runTest(dispatcher) {
            // Two points for one day would weight that day twice in every average the engine takes.
            record(300) { 10_000_00L }
            repository.recordClose(KEY, day(299), Money(9_000_00L)).expectOk()

            assertEquals(300, database.marketCloseDao().seriesFor(PROFILE, KEY).size)
            assertEquals(9_000_00L, database.marketCloseDao().seriesFor(PROFILE, KEY).last().closeMinor)
        }

    @Test
    fun `another profile's history is not this profile's`() =
        runTest(dispatcher) {
            record(300) { 10_000_00L }
            activeProfileId.value = "demo"

            assertTrue(repository.observeOpportunities().first().isEmpty())
        }

    @Test
    fun `the capacity gates come from the household, and close the suggestion`() =
        runTest(dispatcher) {
            record(300) { 10_000_00L }
            capacity.value = Capacity(idleCash = Money(1_00_000_00L), runwayMeetsTarget = true, crunchDaysAhead = 3)

            val plan = repository.observeOpportunities().first().single().assessment.tranches

            assertEquals(0, plan.suggested)
            assertEquals(listOf("AI-FCT"), plan.gates.filter { !it.passed }.map { it.ruleId })
        }

    @Test
    fun `nothing held means nothing to show`() =
        runTest(dispatcher) {
            database.investmentHoldingDao().softDelete(HOLDING, clock.nowUtcMillis())

            assertTrue(repository.observeOpportunities().first().isEmpty())
        }

    // --- fixtures ----------------------------------------------------------------------------------

    private suspend fun record(
        days: Int,
        price: (Int) -> Long,
    ) {
        (0 until days).forEach { index -> repository.recordClose(KEY, day(index), Money(price(index))).expectOk() }
    }

    private fun day(index: Int): String = START.plusDays(index.toLong()).toString()

    private suspend fun seedHolding() {
        database.profileDao().upsert(ProfileEntity(PROFILE, "Test", "Asia/Kolkata", "INR", NOW, NOW))
        database.accountDao().upsert(
            AccountEntity(
                id = ACCOUNT,
                profileId = PROFILE,
                name = "Zerodha",
                type = "INVESTMENT",
                institution = null,
                openingBalanceMinor = 0L,
                currentBalanceMinor = 0L,
                currencyCode = "INR",
                createdAtUtcMillis = NOW,
                updatedAtUtcMillis = NOW,
            ),
        )
        database.investmentHoldingDao().upsert(
            InvestmentHoldingEntity(
                id = HOLDING,
                profileId = PROFILE,
                accountId = ACCOUNT,
                name = "Nifty BeES",
                assetClass = "equity",
                priceKey = KEY,
                createdAtUtcMillis = NOW,
                updatedAtUtcMillis = NOW,
            ),
        )
    }

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val PROFILE = "local"
        const val ACCOUNT = "account:1"
        const val HOLDING = "holding:1"
        const val KEY = "NSE:NIFTYBEES"
        const val NOW = 1_790_000_000_000L

        /** The series ends on the profile's today, or every scenario would be refused as stale. */
        val TODAY: LocalDate = LocalDate.parse("2026-09-27")
        val START: LocalDate = TODAY.minusDays(299)
    }
}
