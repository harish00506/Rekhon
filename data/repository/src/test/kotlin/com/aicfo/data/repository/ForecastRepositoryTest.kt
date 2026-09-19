package com.aicfo.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.common.UuidIdGenerator
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.AccountEntity
import com.aicfo.core.database.entity.CategoryEntity
import com.aicfo.core.database.entity.ProfileEntity
import com.aicfo.core.database.entity.RecurringRuleEntity
import com.aicfo.core.database.entity.TransactionEntity
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.forecast.CashFlowForecast
import com.aicfo.domain.engines.forecast.ForecastEngineFactory
import com.aicfo.domain.engines.forecast.ItemSource
import com.aicfo.domain.engines.seasonality.SeasonalityEngineFactory
import com.aicfo.domain.engines.seasonality.SeasonalityRules
import com.aicfo.domain.engines.stream.StreamEngineFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.time.YearMonth

/**
 * AI-FCT fed from the real ledger (issue 9.2; §9.1, §9.2).
 *
 * Why:  the engine is proven on literal inputs. What only this layer can get wrong is **what goes
 *       in**: which accounts are liquid, which rows are everyday spending and which are already
 *       scheduled, which rules count, where the ledger begins. Every one of those mistakes produces a
 *       forecast that looks fine and is wrong — a rent counted twice, a card purchase deducted from
 *       the bank, a salary that never arrives.
 * What: each case isolates one join decision on a steady ₹100-a-day ledger, so the daily base shows
 *       exactly what was counted: the opening balance; confirmed rules as commitments; a FIXED stream
 *       projected and taken out of everyday spend; a recurring merchant taken out; future-dated rows;
 *       transfers; the ledger's start; the profile scope.
 * Result: the forecast on the dashboard is built from the right rows.
 * Changelog: 2026-09-19 — Created for issue 9.2.
 *            2026-09-19 — Issue 9.3: AI-SEAS joined — closed-month category history in, the
 *            October lift out as its own term; a young ledger with no season adds nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ForecastRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var repository: ForecastRepository

    private val dispatcher = UnconfinedTestDispatcher()
    private val dispatchers = TestDispatchers(dispatcher)
    private val clock = FakeClock(initialMillis = Instant.parse("2026-09-19T06:00:00Z").toEpochMilli())
    private val activeProfileId = MutableStateFlow(PROFILE)

    /** Input: none. Output: an in-memory database and the real repository stack over it. */
    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CfoDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val accounts = RepositoryFactory.accounts(database, clock, UuidIdGenerator(), dispatchers, activeProfileId)
        val streams =
            RepositoryFactory.streams(
                database,
                StreamEngineFactory.create(),
                clock,
                dispatchers,
                activeProfileId,
            )
        repository =
            RepositoryFactory.forecast(
                database = database,
                accounts = accounts,
                streams = streams,
                engine = ForecastEngineFactory.create(),
                seasonality = SeasonalityEngineFactory.create(),
                clock = clock,
                dispatchers = dispatchers,
                activeProfileId = activeProfileId,
            )
    }

    /** Input: none. Output: closed. */
    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `the opening balance is the liquid accounts' — bank and cash, not a card`() =
        runTest(dispatcher) {
            seed()
            steadySpend(days = 30)
            expense("card-buy", CARD, "2026-09-10", -9_000_00L, category = null)

            // Bank opens at 50 000 and spends 100 a day for 30 days; cash holds 2 000; the card is out.
            assertEquals(Money(50_000_00L - 30 * 100_00L + 2_000_00L), latest().openingBalance)
        }

    @Test
    fun `everyday spend is the liquid accounts' outflows, from the day the ledger starts`() =
        runTest(dispatcher) {
            seed()
            steadySpend(days = 30)
            expense("card-buy", CARD, "2026-09-10", -9_000_00L, category = null)

            val forecast = latest()

            assertEquals("a card purchase is not money leaving the bank", Money(100_00L), forecast.dailyBase)
            assertEquals("the ledger began 30 days ago", 30, forecast.historyDays)
        }

    @Test
    fun `confirmed recurring rules are scheduled, income and outflow alike, and unconfirmed ones are not`() =
        runTest(dispatcher) {
            seed()
            steadySpend(days = 30)
            database.recurringRuleDao().upsertAll(
                listOf(
                    rule("salary", "Employer", 60_000_00L, "2026-10-01", confirmed = true),
                    rule("landlord", "Landlord", -20_000_00L, "2026-10-05", confirmed = true),
                    rule("guess", "Gym", -1_500_00L, "2026-10-02", confirmed = false),
                ),
            )

            val forecast = latest()

            assertEquals(setOf("Employer", "Landlord"), forecast.scheduled.map { it.label }.toSet())
            assertTrue(forecast.scheduled.all { it.source == ItemSource.RECURRING_RULE })
            assertEquals(Money(3 * 60_000_00L), forecast.scheduledIncome)
            assertEquals(Money(3 * 20_000_00L), forecast.scheduledOutflow)
        }

    @Test
    fun `a recurring merchant's past payments are scheduled, not everyday spend`() =
        runTest(dispatcher) {
            seed()
            steadySpend(days = 90)
            listOf("2026-07-05", "2026-08-05", "2026-09-05").forEach {
                expense("rent-$it", BANK, it, -20_000_00L, category = null, merchant = "Landlord")
            }
            database.recurringRuleDao().upsertAll(
                listOf(rule("landlord", "Landlord", -20_000_00L, "2026-10-05", confirmed = true)),
            )

            assertEquals(Money(100_00L), latest().dailyBase)
        }

    @Test
    fun `a FIXED stream is projected on its day and taken out of everyday spend`() =
        runTest(dispatcher) {
            seed()
            steadySpend(days = 90)
            listOf("2026-06-07", "2026-07-07", "2026-08-07").forEach {
                expense("netflix-$it", BANK, it, -649_00L, category = SUBSCRIPTIONS)
            }

            val forecast = latest()

            val projected = forecast.scheduled.filter { it.source == ItemSource.FIXED_STREAM }
            assertEquals(
                listOf("2026-10-07", "2026-11-07", "2026-12-07").map(LocalDate::parse),
                projected.map { it.date },
            )
            assertTrue(projected.all { it.amount == Money(-649_00L) && it.label == "Subscriptions" })
            assertEquals(Money(100_00L), forecast.dailyBase)
        }

    @Test
    fun `a future-dated bank payment is scheduled but a future card purchase is not`() =
        runTest(dispatcher) {
            seed()
            steadySpend(days = 30)
            expense("laptop", BANK, "2026-10-10", -45_000_00L, category = null, merchant = "Laptop")
            expense("card-later", CARD, "2026-10-11", -3_000_00L, category = null, merchant = "Card thing")

            val future = latest().scheduled.filter { it.source == ItemSource.FUTURE_DATED }

            assertEquals(listOf("Laptop"), future.map { it.label })
            assertEquals(Money(-45_000_00L), future.single().amount)
        }

    @Test
    fun `a transfer between bank and cash moves no money, but paying the card from the bank does`() =
        runTest(dispatcher) {
            seed()
            steadySpend(days = 30)
            transfer("atm", from = BANK, to = CASH, date = "2026-09-10", amount = 5_000_00L)

            assertEquals(Money(100_00L), latest().dailyBase)

            transfer("card-bill", from = BANK, to = CARD, date = "2026-09-11", amount = 30_000_00L)
            assertTrue("a card bill leaves the liquid balance", latest().predictedSpend > Money(90 * 100_00L))
        }

    @Test
    fun `a Shopping category's festival season reaches the forecast as its own term`() =
        runTest(dispatcher) {
            seed()
            steadySpend(days = 90)
            // Six months of Shopping, uneven in size and day so AI-CLS scores it VARIABLE and it
            // stays everyday spend: the history AI-SEAS reads, and half the lookback's weight.
            listOf(
                "2026-03-04" to 2_500_00L,
                "2026-04-18" to 3_600_00L,
                "2026-05-09" to 2_200_00L,
                "2026-06-27" to 4_100_00L,
                "2026-07-13" to 2_700_00L,
                "2026-08-22" to 3_900_00L,
                "2026-09-06" to 2_400_00L,
            ).forEach { (date, paise) -> expense("shop-$date", BANK, date, -paise, category = SHOPPING) }

            val forecast = latest()
            val october = forecast.seasonalMonths.single { it.factor.month == YearMonth.of(2026, 10) }

            assertEquals(listOf("diwali"), october.factor.rising)
            assertTrue("Diwali lifts October's everyday spend", october.adjustment > Money.ZERO)
            assertTrue(SeasonalityRules.INDEX in forecast.provenance.evidence)
            assertEquals(forecast.seasonalMonths.sumOf { it.adjustment.minor }, forecast.seasonalAdjustment.minor)
        }

    @Test
    fun `a ledger with no category the calendar knows has no seasonal term`() =
        runTest(dispatcher) {
            seed()
            steadySpend(days = 90)

            val forecast = latest()

            assertEquals(Money.ZERO, forecast.seasonalAdjustment)
            assertTrue(forecast.seasonalMonths.isEmpty())
            assertTrue(SeasonalityRules.INDEX !in forecast.provenance.evidence)
        }

    @Test
    fun `another profile's ledger is never read`() =
        runTest(dispatcher) {
            seed()
            steadySpend(days = 30)
            expense("theirs", "demo:bank", "2026-09-10", -99_999_00L, category = null, profileId = "demo")

            assertEquals(Money(100_00L), latest().dailyBase)
        }

    // --- fixtures ---------------------------------------------------------------------------------

    private suspend fun latest(): CashFlowForecast {
        var forecast: CashFlowForecast? = null
        repository.observeForecast().test {
            forecast = awaitItem().expectOk()
            cancelAndIgnoreRemainingEvents()
        }
        return forecast!!
    }

    private suspend fun seed() {
        listOf(
            PROFILE,
            "demo",
        ).forEach { database.profileDao().upsert(ProfileEntity(it, "Test", "UTC", "INR", NOW, NOW)) }
        listOf(
            account(BANK, "bank", 50_000_00L),
            account(CASH, "cash", 2_000_00L),
            account(CARD, "credit_card", 0L),
            account("demo:bank", "bank", 0L, profileId = "demo"),
        ).forEach { database.accountDao().upsert(it) }
        database.categoryDao().upsertAll(
            listOf(
                CategoryEntity(SUBSCRIPTIONS, PROFILE, "Subscriptions", null, "want", isSystem = true, NOW, NOW),
                CategoryEntity(SHOPPING, PROFILE, "Shopping", null, "want", isSystem = true, NOW, NOW),
            ),
        )
    }

    /** One ₹100 bank expense a day for [days] days, ending yesterday. */
    private suspend fun steadySpend(days: Int) {
        (1..days).forEach { k ->
            expense(
                "daily-$k",
                BANK,
                LocalDate.parse("2026-09-19").minusDays(k.toLong()).toString(),
                -100_00L,
                category = null,
            )
        }
    }

    @Suppress("LongParameterList") // a fixture builder: each argument is one column a test varies
    private suspend fun expense(
        id: String,
        account: String,
        date: String,
        amountMinor: Long,
        category: String?,
        merchant: String? = null,
        profileId: String = PROFILE,
    ) = database.transactionDao().upsert(
        TransactionEntity(
            id = id,
            profileId = profileId,
            accountId = account,
            amountMinor = amountMinor,
            currencyCode = "INR",
            occurredAtUtcMillis = NOW,
            bookedOnIsoDate = date,
            categoryId = category,
            merchant = merchant,
            source = "manual",
            type = "expense",
            createdAtUtcMillis = NOW,
            updatedAtUtcMillis = NOW,
        ),
    )

    private suspend fun transfer(
        id: String,
        from: String,
        to: String,
        date: String,
        amount: Long,
    ) {
        listOf("$id-out" to (from to -amount), "$id-in" to (to to amount)).forEach { (legId, leg) ->
            database.transactionDao().upsert(
                TransactionEntity(
                    id = legId,
                    profileId = PROFILE,
                    accountId = leg.first,
                    amountMinor = leg.second,
                    currencyCode = "INR",
                    occurredAtUtcMillis = NOW,
                    bookedOnIsoDate = date,
                    source = "manual",
                    type = "transfer",
                    transferId = id,
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                ),
            )
        }
    }

    private fun account(
        id: String,
        type: String,
        opening: Long,
        profileId: String = PROFILE,
    ) = AccountEntity(
        id = id,
        profileId = profileId,
        name = id,
        type = type,
        openingBalanceMinor = opening,
        currentBalanceMinor = opening,
        currencyCode = "INR",
        createdAtUtcMillis = NOW,
        updatedAtUtcMillis = NOW,
    )

    private fun rule(
        id: String,
        name: String,
        amountMinor: Long,
        nextDue: String,
        confirmed: Boolean,
    ) = RecurringRuleEntity(
        id = id,
        profileId = PROFILE,
        name = name,
        amountMinor = amountMinor,
        cadence = "monthly",
        nextDueIsoDate = nextDue,
        source = "detected",
        isConfirmed = confirmed,
        createdAtUtcMillis = NOW,
        updatedAtUtcMillis = NOW,
    )

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val PROFILE = "local"
        const val NOW = 1_789_800_000_000L
        const val BANK = "local:bank"
        const val CASH = "local:cash"
        const val CARD = "local:card"
        const val SUBSCRIPTIONS = "local:category:subscriptions"
        const val SHOPPING = "local:category:shopping"
    }
}
