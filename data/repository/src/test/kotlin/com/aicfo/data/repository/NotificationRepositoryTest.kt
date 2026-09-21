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
import com.aicfo.domain.engines.notification.NotificationCandidate
import com.aicfo.domain.engines.notification.NotificationKind
import com.aicfo.domain.engines.notification.NotificationOutcome
import com.aicfo.domain.engines.notification.NotificationPlan
import com.aicfo.domain.engines.notification.NotificationPolicyEngineFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The notification gate with its memory (issue 9.6; §17.2 NTF-001/002, TIM-001).
 *
 * Why:  the engine is proven on literal histories. What only this layer can get wrong is where the
 *       history comes from and what "now" means: a send recorded and then forgotten, so the cap never
 *       fills; a message sent twice across two runs; quiet hours measured in UTC instead of the
 *       profile's zone, which in India is five and a half hours out — a 22:00 rule that fires at
 *       03:30.
 * What: the ration across two runs; never twice across runs; a held message recorded with its
 *       release time; quiet hours in the profile's zone; one row per key; the profile scope.
 * Result: the gate every worker asks is the one the rulebook describes.
 * Changelog: 2026-09-20 — Created for issue 9.6.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NotificationRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var repository: NotificationRepository

    private val dispatcher = UnconfinedTestDispatcher()

    // 10:00 in Kolkata on 20 September — 04:30 UTC.
    private val clock = FakeClock(Instant.parse("2026-09-20T04:30:00Z").toEpochMilli(), ZoneId.of("Asia/Kolkata"))
    private val activeProfileId = MutableStateFlow(PROFILE)

    /** Input: none. Output: an in-memory database and the real gate over it. */
    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CfoDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository =
            RoomNotificationRepository(
                database = database,
                engine = NotificationPolicyEngineFactory.create(),
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
    fun `the day's ration is remembered across runs`() =
        runTest(dispatcher) {
            assertEquals(listOf("a"), decide(budget("a")).deliverable.map { it.key })
            assertEquals(listOf("b"), decide(budget("b")).deliverable.map { it.key })

            assertEquals(NotificationOutcome.FOLD_INTO_DIGEST, decide(budget("c")).decisions.single().outcome)
        }

    @Test
    fun `a message is never sent twice, however many runs offer it`() =
        runTest(dispatcher) {
            decide(budget("a"))
            clock.advanceBy(Duration.ofDays(10))

            assertEquals(NotificationOutcome.ALREADY_SENT, decide(budget("a")).decisions.single().outcome)
        }

    @Test
    fun `a folded message is offered again, and goes when there is room`() =
        runTest(dispatcher) {
            decide(budget("a"))
            decide(budget("b"))
            assertEquals(NotificationOutcome.FOLD_INTO_DIGEST, decide(budget("c")).decisions.single().outcome)

            clock.advanceBy(Duration.ofDays(1))

            assertEquals(NotificationOutcome.DELIVER, decide(budget("c")).decisions.single().outcome)
        }

    @Test
    fun `quiet hours are the profile's own hours, not UTC's`() =
        runTest(dispatcher) {
            // 17:00 UTC is 22:30 in Kolkata: quiet, although it is only early evening in UTC.
            clock.advanceBy(Duration.ofMinutes(12 * 60 + 30))

            val decision = decide(budget("late")).decisions.single()
            assertEquals(NotificationOutcome.WAIT_FOR_QUIET_HOURS, decision.outcome)

            val row = database.notificationLogDao().find(PROFILE, "late")!!
            assertEquals(
                "the release is 08:00 Kolkata, recorded as UTC",
                Instant.parse("2026-09-21T02:30:00Z").toEpochMilli(),
                row.deliverAfterUtcMillis,
            )
            assertNull("held is not sent, so it costs nothing from the ration", row.sentAtUtcMillis)
        }

    @Test
    fun `one row per key, updated as the decision changes`() =
        runTest(dispatcher) {
            decide(budget("a"))
            decide(budget("b"))
            decide(budget("c"))
            val folded = database.notificationLogDao().find(PROFILE, "c")!!

            clock.advanceBy(Duration.ofDays(1))
            decide(budget("c"))

            val delivered = database.notificationLogDao().find(PROFILE, "c")!!
            assertEquals(folded.id, delivered.id)
            assertEquals("deliver", delivered.outcome)
            assertEquals(folded.createdAtUtcMillis, delivered.createdAtUtcMillis)
        }

    @Test
    fun `a critical event is delivered whatever has already gone out`() =
        runTest(dispatcher) {
            decide(budget("a"))
            decide(budget("b"))

            assertEquals(
                NotificationOutcome.DELIVER,
                decide(NotificationCandidate("bill", NotificationKind.CRITICAL_MONEY)).decisions.single().outcome,
            )
        }

    @Test
    fun `another profile's sends do not count against this one`() =
        runTest(dispatcher) {
            decide(budget("a"))
            decide(budget("b"))

            activeProfileId.value = "demo"

            assertEquals(NotificationOutcome.DELIVER, decide(budget("c")).decisions.single().outcome)
        }

    private suspend fun decide(vararg candidates: NotificationCandidate): NotificationPlan =
        repository.decide(candidates.toList()).expectOk()

    private fun budget(key: String) = NotificationCandidate(key, NotificationKind.BUDGET_DISCIPLINE)

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val PROFILE = "local"
    }
}
