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
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.purchase.InterviewAnswer
import com.aicfo.domain.engines.purchase.InterviewOutcome
import com.aicfo.domain.engines.purchase.InterviewQuestion
import com.aicfo.domain.engines.purchase.PaymentMethod
import com.aicfo.domain.engines.purchase.PurchaseInterviewEngineFactory
import com.aicfo.domain.engines.purchase.PurchaseWeight
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneId

/**
 * The buy list against a real database (issue 10.2; §13.3, ADR-0050).
 *
 * Why:  the interview is proven in `:domain:engines:purchase`. What only this layer can get wrong is
 *       the memory: an answer that does not survive, a second answer to the same question quietly
 *       counted twice, a band that goes stale when income changes, or — worst — a wish the app
 *       removes by itself. §13.3 is explicit that nothing is auto-deleted, and that is a promise
 *       about storage, not about wording.
 * What: adding, answering, changing your mind, the score surviving a reload, the band following
 *       income, status changes, the profile scope, and the advisor re-evaluation.
 * Result: a list that remembers exactly what the user said, and nothing more.
 * Changelog: 2026-09-26 — Created for issue 10.2.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BuyListRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var repository: BuyListRepository

    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = FakeClock(Instant.parse("2026-09-26T04:30:00Z").toEpochMilli(), ZoneId.of("Asia/Kolkata"))
    private val activeProfileId = MutableStateFlow(PROFILE)
    private val income = MutableStateFlow(Money(1_00_000_00L))
    private val advisor = RecordingAdvisor()

    /** Input: none. Output: an in-memory database and the buy list over it. */
    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CfoDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository =
            StoredBuyListRepository(
                database = database,
                engine = PurchaseInterviewEngineFactory.create(),
                advisor = advisor,
                monthlyIncome = income,
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
    fun `a new wish is parked, not judged`() =
        runTest(dispatcher) {
            repository.add("Standing desk", Money(8_000_00L)).expectOk()

            val entry = repository.observeList().first().single()

            assertEquals("Standing desk", entry.name)
            assertEquals(BuyListStatus.PARKED, entry.status)
            assertEquals(InterviewOutcome.PARK, entry.assessment.outcome)
            assertEquals(
                "a wish nobody has been asked about is neither kept nor condemned",
                50,
                entry.assessment.wantScore,
            )
        }

    @Test
    fun `an answer is remembered, and the score moves with it`() =
        runTest(dispatcher) {
            val id = repository.add("Standing desk", Money(8_000_00L)).expectOk()

            val after = repository.answer(id, InterviewAnswer.NeedOrWant(isNeed = true)).expectOk()

            assertEquals(65, after.assessment.wantScore)
            assertEquals(65, repository.observeList().first().single().assessment.wantScore)
            assertFalse(InterviewQuestion.NEED_OR_WANT in after.assessment.questionsToAsk)
        }

    @Test
    fun `changing your mind replaces an answer rather than counting both`() =
        runTest(dispatcher) {
            val id = repository.add("Standing desk", Money(8_000_00L)).expectOk()

            repository.answer(id, InterviewAnswer.NeedOrWant(isNeed = true)).expectOk()
            val changed = repository.answer(id, InterviewAnswer.NeedOrWant(isNeed = false)).expectOk()

            assertEquals("the want, not the need plus the want", 45, changed.assessment.wantScore)
            assertEquals(1, database.buyListDao().answersFor(PROFILE, id).size)
        }

    @Test
    fun `the answers survive a reload, with the points they were worth`() =
        runTest(dispatcher) {
            val id = repository.add("Standing desk", Money(8_000_00L)).expectOk()
            repository.answer(id, InterviewAnswer.NeedOrWant(isNeed = true)).expectOk()
            repository.answer(id, InterviewAnswer.Uses(InterviewAnswer.UsesPerMonth.HIGH)).expectOk()

            val stored = database.buyListDao().answersFor(PROFILE, id)

            assertEquals(listOf("need", "uses_high"), stored.map { it.answerKey })
            assertEquals(listOf(15, 15), stored.map { it.points })
            assertEquals(80, repository.observeList().first().single().assessment.wantScore)
        }

    @Test
    fun `the band follows income, so a raise asks fewer questions`() =
        runTest(dispatcher) {
            repository.add("Standing desk", Money(8_000_00L)).expectOk()
            assertEquals(PurchaseWeight.SIGNIFICANT, repository.observeList().first().single().assessment.weight)

            income.value = Money(10_00_000_00L)

            assertEquals(PurchaseWeight.SMALL, repository.observeList().first().single().assessment.weight)
        }

    @Test
    fun `a wish that scores badly is suggested for removal, never removed`() =
        runTest(dispatcher) {
            // §13.3: "one tap removes, one tap overrides — never auto-deleted".
            val id = repository.add("Second headphones", Money(8_000_00L)).expectOk()
            repository.answer(id, InterviewAnswer.NeedOrWant(isNeed = false)).expectOk()
            repository.answer(id, InterviewAnswer.Uses(InterviewAnswer.UsesPerMonth.LOW)).expectOk()
            repository.answer(id, InterviewAnswer.AlreadyOwns(ownsSimilar = true)).expectOk()
            repository.answer(id, InterviewAnswer.WaitThirtyDays(somethingBreaks = false)).expectOk()

            val entry = repository.observeList().first().single()

            assertEquals(InterviewOutcome.SUGGEST_REMOVE, entry.assessment.outcome)
            assertEquals("still on the list until the user says otherwise", BuyListStatus.PARKED, entry.status)
            assertTrue("the suggestion carries the answers behind it", entry.assessment.deltas.size == 4)
        }

    @Test
    fun `a removed wish leaves the list only when the user removes it`() =
        runTest(dispatcher) {
            val id = repository.add("Second headphones", Money(8_000_00L)).expectOk()

            repository.setStatus(id, BuyListStatus.REMOVED).expectOk()

            assertTrue(repository.observeList().first().isEmpty())
            assertEquals("removed, not destroyed", "removed", database.buyListDao().findItem(PROFILE, id)?.status)
        }

    @Test
    fun `asking the advisor about a wish passes the wish's own figures`() =
        runTest(dispatcher) {
            val id =
                repository.add(
                    "Phone",
                    Money(45_000_00L),
                    method = PaymentMethod.EMI,
                    monthlyEmi = Money(4_000_00L),
                ).expectOk()

            repository.advise(id).expectOk()

            val asked = advisor.asked.single()
            assertEquals("Phone", asked.item)
            assertEquals(Money(45_000_00L), asked.price)
            assertEquals(PaymentMethod.EMI, asked.method)
            assertEquals(Money(4_000_00L), asked.monthlyEmi)
        }

    @Test
    fun `another profile's wishes are not this one's`() =
        runTest(dispatcher) {
            repository.add("Standing desk", Money(8_000_00L)).expectOk()

            activeProfileId.value = "demo"

            assertTrue(repository.observeList().first().isEmpty())
        }

    @Test
    fun `answering a wish that is not there is refused, not crashed`() =
        runTest(dispatcher) {
            val result = repository.answer("wish:missing", InterviewAnswer.NeedOrWant(isNeed = true))

            assertEquals(AppError.Validation("buylist.item"), (result as Err).error)
        }

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val PROFILE = "local"
    }
}

/**
 * A stand-in for the Purchase Advisor (issue 10.2).
 *
 * Why:  10.1's advisor is proven in its own tests; what matters here is *what the buy list asks it*
 *       — a wish's own price, method and instalment, not something reconstructed.
 * Result: the requests, and a card to hand back.
 * Changelog: 2026-09-26 — Created for issue 10.2.
 */
private class RecordingAdvisor : PurchaseAdvisorRepository {
    /** Every request the buy list made, in order. */
    val asked: MutableList<com.aicfo.domain.engines.purchase.PurchaseRequest> = mutableListOf()

    override suspend fun advise(
        request: com.aicfo.domain.engines.purchase.PurchaseRequest,
    ): Result<com.aicfo.domain.engines.purchase.PurchaseVerdictCard, AppError> {
        asked += request
        return Ok(card(request))
    }

    override fun observeRecent(limit: Int): kotlinx.coroutines.flow.Flow<List<KeptVerdict>> =
        kotlinx.coroutines.flow.flowOf(emptyList())

    override suspend fun find(id: String): Result<com.aicfo.domain.engines.purchase.PurchaseVerdictCard?, AppError> =
        Ok(null)

    private fun card(request: com.aicfo.domain.engines.purchase.PurchaseRequest) =
        com.aicfo.domain.engines.purchase.PurchaseVerdictCard(
            request = request,
            verdict = com.aicfo.domain.engines.purchase.Verdict.STRETCH,
            gates = emptyList(),
            impact =
                com.aicfo.domain.engines.purchase.ImpactStrip(
                    liquidBefore = Money.ZERO,
                    liquidAfter = Money.ZERO,
                    runwayMonthsBeforeTenths = 0,
                    runwayMonthsAfterTenths = 0,
                    goalDelayDays = 0,
                ),
            alternatives = com.aicfo.domain.engines.purchase.Alternatives(),
            hardFail = false,
            provenance = com.aicfo.core.model.EngineProvenance("AI-PA", "1.0", 0L),
        )
}
