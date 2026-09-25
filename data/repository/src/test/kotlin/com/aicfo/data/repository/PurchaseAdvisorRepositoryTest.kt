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
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import com.aicfo.domain.engines.emergencyfund.EmergencyFundPlan
import com.aicfo.domain.engines.emergencyfund.EmergencyStatus
import com.aicfo.domain.engines.emergencyfund.EssentialsBasis
import com.aicfo.domain.engines.nature.NatureBreakdown
import com.aicfo.domain.engines.purchase.GateId
import com.aicfo.domain.engines.purchase.PaymentMethod
import com.aicfo.domain.engines.purchase.PurchaseAdvisorEngineFactory
import com.aicfo.domain.engines.purchase.PurchaseRequest
import com.aicfo.domain.engines.purchase.Urgency
import com.aicfo.domain.engines.purchase.Verdict
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

/**
 * The Purchase Advisor against a real database (issue 10.1; §13.2, AI-ARC-006, ADR-0006).
 *
 * Why:  the gates are proven in `:domain:engines:purchase`. What only this layer can get wrong is
 *       the part §13.2 actually promises: that a verdict **survives** — that the card written today
 *       reads back in December as the card it was, gates, figures, citations and engine version
 *       included. A history that quietly lost its reasons would look exactly like a history that
 *       kept them, until someone asked why.
 * What: a stored card read back whole; the signals reaching the gates; the listing; the profile
 *       scope; and a source that fails leaving nothing behind.
 * Result: the advisor's memory is trustworthy.
 * Changelog: 2026-09-25 — Created for issue 10.1.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PurchaseAdvisorRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var repository: PurchaseAdvisorRepository

    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = FakeClock(Instant.parse("2026-09-25T04:30:00Z").toEpochMilli(), ZoneId.of("Asia/Kolkata"))
    private val activeProfileId = MutableStateFlow(PROFILE)
    private val emergency = MutableStateFlow(plan())

    /** Input: none. Output: an in-memory database and the advisor over it. */
    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CfoDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = advisor()
    }

    /** Input: none. Output: closed. */
    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a verdict is kept, and reads back as the card it was`() =
        runTest(dispatcher) {
            val given = repository.advise(request(price = 30_000_00L)).expectOk()

            val kept = repository.find(storedId()).expectOk()

            assertEquals("§13.2: the whole trace survives, not a summary of it", given, kept)
        }

    @Test
    fun `the kept card keeps every gate, its figures and the rules it cited`() =
        runTest(dispatcher) {
            repository.advise(request(price = 30_000_00L)).expectOk()

            val kept = repository.find(storedId()).expectOk()!!

            assertEquals(GateId.entries, kept.gates.map { it.gate })
            assertTrue("a gate lost its citations", kept.gates.all { it.citations.isNotEmpty() })
            val affordability = kept.gates.first { it.gate == GateId.AFFORDABILITY }
            assertEquals(Money(1_00_000_00L), affordability.figures.first { it.key == "liquidBefore" }.amount)
            assertEquals("AI-PA", kept.provenance.engineId)
            assertEquals("1.0", kept.provenance.engineVersion)
        }

    @Test
    fun `the gates judge the figures the engines below published`() =
        runTest(dispatcher) {
            // The emergency fund's own target is the floor the advisor will not cross silently, and
            // its liquid figure is the money it spends. Move them, and the verdict moves.
            val comfortable = repository.advise(request(price = 20_000_00L)).expectOk()
            assertEquals(Verdict.COMFORTABLE, comfortable.verdict)

            emergency.value = plan(liquid = 60_000_00L)

            assertEquals(Verdict.STRETCH, repository.advise(request(price = 20_000_00L)).expectOk().verdict)
        }

    @Test
    fun `the history lists what was asked, newest first`() =
        runTest(dispatcher) {
            repository.advise(request(item = "Headphones", price = 8_000_00L)).expectOk()
            clock.advanceBy(java.time.Duration.ofMinutes(5))
            repository.advise(request(item = "Fridge", price = 45_000_00L)).expectOk()

            val history = repository.observeRecent().first()

            assertEquals(listOf("Fridge", "Headphones"), history.map { it.item })
            assertEquals(listOf(Money(45_000_00L), Money(8_000_00L)), history.map { it.price })
            assertEquals("2026-09-25", history.first().decidedOnIsoDate)
        }

    @Test
    fun `another profile's verdicts are not this one's`() =
        runTest(dispatcher) {
            repository.advise(request(item = "Headphones")).expectOk()

            activeProfileId.value = "demo"

            assertTrue(repository.observeRecent().first().isEmpty())
            assertNull(
                "a demo card is invisible to the real profile and vice versa",
                repository.find(storedId()).expectOk(),
            )
        }

    @Test
    fun `a source that cannot be read leaves no half-written card`() =
        runTest(dispatcher) {
            // An I/O failure, deliberately not an IllegalStateException: §21.6 says a programmer
            // error crashes rather than becoming an Err, and `runCatchingToResult` rethrows those.
            val unreadable = sources().copy(emergency = flow { throw IOException("the fund is unreadable") })
            val broken = advisor(sources = unreadable)

            val result = broken.advise(request())

            assertTrue(result is Err)
            assertTrue("nothing was stored", repository.observeRecent().first().isEmpty())
        }

    // --- fixtures ---------------------------------------------------------------------------------

    private fun advisor(sources: PurchaseSources = sources()): PurchaseAdvisorRepository =
        StoredPurchaseAdvisorRepository(
            database = database,
            sources = sources,
            engine = PurchaseAdvisorEngineFactory.create(),
            clock = clock,
            dispatchers = TestDispatchers(dispatcher),
            activeProfileId = activeProfileId,
            idGenerator = UuidIdGenerator(),
        )

    private fun sources() =
        PurchaseSources(
            emergency = emergency,
            safeToSpend = flowOf(null),
            // Too little history to forecast: the cash-flow gate says nothing rather than guessing,
            // which is the ordinary state of a young profile.
            forecast = flowOf(Err(AppError.Validation("forecast.none"))),
            ledger =
                flowOf(
                    listOf(MonthlyLedger("2026-08", NatureBreakdown(needs = Money(40_000_00L)), Money(1_00_000_00L))),
                ),
            streams = flowOf(Err(AppError.Validation("streams.none"))),
            instalments = flowOf(emptyMap()),
            categories = flowOf(emptyList()),
            goals = flowOf(emptyList()),
            budgets = flowOf(emptyList()),
        )

    private fun request(
        item: String = "Headphones",
        price: Long = 8_000_00L,
    ) = PurchaseRequest(item, Money(price), PaymentMethod.CASH, Urgency.ROUTINE)

    /** Result: the id of the most recently stored card. */
    private suspend fun storedId(): String =
        database.purchaseTraceDao().allTraces(PROFILE).maxBy {
            it.createdAtUtcMillis
        }.id

    private fun plan(liquid: Long = 1_00_000_00L) =
        EmergencyFundPlan(
            monthlyEssentials = Money(42_000_00L),
            essentialsBasis = EssentialsBasis.OBSERVED_MEDIAN,
            incomeCvBps = null,
            multiplierMonths = 6,
            multiplierWasClamped = false,
            target = Money(50_000_00L),
            liquidFunds = Money(liquid),
            fundedRatioBps = 10_000,
            shortfall = Money.ZERO,
            runwayMonthsBps = 24_000,
            topUpMonthly = Money.ZERO,
            status = EmergencyStatus.FUNDED,
            liquidAccountNames = emptyList(),
            essentialCategoryNames = emptyList(),
            provenance = EngineProvenance("AI-EMF", "1.0", NOW, listOf(RuleCitation("RULE-EMF-MULT", "1.0"))),
        )

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val PROFILE = "local"
        const val NOW = 1_790_000_000_000L
    }
}
