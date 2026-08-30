package com.aicfo.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.FakeIdGenerator
import com.aicfo.core.common.Ok
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.goals.GoalEngineFactory
import com.aicfo.domain.engines.goals.GoalStatus
import com.aicfo.domain.engines.goals.Horizon
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
 * The data half of goals — issue 7.1 (SRS §15, ARC-005).
 *
 * Why:  `GoalEngineTest`, the property test and the golden file already prove the arithmetic against
 *       fixtures, so repeating it here would assert nothing new. **What is unproven above SQLite is
 *       what this class owns**, and each part fails while still returning something plausible:
 *
 *       - the **round trip**. Storing a target in rupees instead of paise, or a date as a
 *         timestamp, produces a goal card that renders perfectly and is wrong by a factor of a
 *         hundred (MNY-001, TIM-002).
 *       - the **projection**. The engine is run on the way out; run it on stale rows, or with the
 *         wrong day, and every required monthly is quietly wrong.
 *       - the **profile scope**. A goal leaking between the real and demo profiles would put
 *         somebody else's target in the Safe-to-Spend subtraction (ADR-0006).
 *       - the **soft delete**. A tombstoned goal that still projects keeps subtracting from
 *         Safe-to-Spend for ever, and there is no foreign key to catch it.
 *       - the **total**, which is the number `SafeToSpendRepository` will subtract. If it
 *         disagreed with the list the user is looking at, both would be defensible and one would be
 *         wrong.
 * What: the round trip, the projection, both scopes, the delete, and the total.
 * Result: the first goal figures in the app are proven against a real SQL engine.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 *
 * Unencrypted in-memory Room and the **real** engine rather than a stub, the reasoning
 * `InvestmentRepositoryTest` gives: the claim is that a required monthly reaches the screen, and a
 * stub could not make it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GoalRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var goals: GoalRepository

    private val clock = FakeClock(initialMillis = Instant.parse("2026-08-30T06:00:00Z").toEpochMilli())
    private val ids = FakeIdGenerator()
    private val dispatcher = UnconfinedTestDispatcher()
    private val activeProfileId = MutableStateFlow(REAL_PROFILE)

    /** Input: none. Output: a fresh in-memory database and the repository. */
    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                CfoDatabase::class.java,
            ).allowMainThreadQueries().build()
        goals =
            RepositoryFactory.goals(
                database,
                GoalEngineFactory.create(),
                clock,
                ids,
                TestDispatchers(dispatcher),
                activeProfileId,
            )
    }

    /** Input: none. Output: closes the database between tests. */
    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a goal round-trips and comes back projected`() =
        runTest(dispatcher) {
            // 20 months to the date, ₹4,00,000 still to find: ₹20,000 a month against a ₹15,000 plan.
            goals.save(draft()).expectOk()

            val stored = goals.observeGoals().first().single()

            assertEquals("Kerala trip", stored.name)
            assertEquals("MNY-001: paise survive the round trip", Money(50_000_00), stored.target)
            assertEquals(Money(10_000_00), stored.saved)
            assertEquals(Money(40_000_00), stored.remaining)
            assertEquals(20, stored.monthsRemaining)
            assertEquals(Money(2_000_00), stored.requiredMonthly)
            assertEquals(Money(500_00), stored.shortfallMonthly)
            assertEquals(GoalStatus.BEHIND, stored.status)
            assertEquals(Horizon.SHORT, stored.horizon)
        }

    @Test
    fun `editing a goal keeps its id and its created stamp`() =
        runTest(dispatcher) {
            val id = goals.save(draft()).expectOk()
            val createdAt = database.goalDao().find(id)!!.createdAtUtcMillis
            clock.advanceBy(Duration.ofDays(1))

            goals.save(draft(saved = Money(20_000_00)), id = id).expectOk()

            val row = database.goalDao().find(id)!!
            assertEquals("editing must not mint a second goal", 1, database.goalDao().forProfile(REAL_PROFILE).size)
            assertEquals("'when did I start this?' stays answerable", createdAt, row.createdAtUtcMillis)
            assertTrue("but the update stamp moves", row.updatedAtUtcMillis > createdAt)
        }

    @Test
    fun `a blank name is refused with a key the UI maps, not a message`() =
        runTest(dispatcher) {
            val result = goals.save(draft(name = "   "))

            assertEquals(AppError.Validation("goal.name"), (result as Err).error)
            assertTrue("nothing was written", database.goalDao().forProfile(REAL_PROFILE).isEmpty())
        }

    @Test
    fun `a date that will not parse is refused rather than stored to fail later`() =
        runTest(dispatcher) {
            // Storing it would put a row in the table that no read can ever project — a goal the
            // user created, can see in a backup, and will never see on the screen.
            val result = goals.save(draft(targetDateIso = "30-04-2028"))

            assertEquals(AppError.Validation("goal.targetDate"), (result as Err).error)
            assertTrue("nothing was written", database.goalDao().forProfile(REAL_PROFILE).isEmpty())
        }

    @Test
    fun `a deleted goal stops projecting and stops being subtracted`() =
        runTest(dispatcher) {
            val id = goals.save(draft()).expectOk()

            goals.delete(id).expectOk()

            assertTrue("gone from the screen", goals.observeGoals().first().isEmpty())
            assertEquals(
                "and gone from what Safe-to-Spend subtracts",
                Money.ZERO,
                goals.requiredMonthlyTotal().expectOk(),
            )
            assertTrue(
                "but only tombstoned - §21.4 keeps the row so an earlier export still reconciles",
                database.goalDao().find(id) == null,
            )
        }

    @Test
    fun `goals belong to their profile and do not leak into the demo one`() =
        runTest(dispatcher) {
            goals.save(draft()).expectOk()

            activeProfileId.value = DEMO_PROFILE

            assertTrue("the demo profile sees none of it", goals.observeGoals().first().isEmpty())
            assertEquals(Money.ZERO, goals.requiredMonthlyTotal().expectOk())
        }

    @Test
    fun `the total is what the list adds up to, so the two can never disagree`() =
        runTest(dispatcher) {
            // The figure SafeToSpendRepository subtracts. If it were computed separately from the
            // list, both would be defensible and one would be wrong.
            goals.save(draft(name = "Kerala trip")).expectOk()
            goals.save(draft(name = "New laptop", target = Money(1_20_000), saved = Money.ZERO)).expectOk()

            val listed = goals.observeGoals().first()
            val total = goals.requiredMonthlyTotal().expectOk()

            assertEquals(2, listed.size)
            assertEquals(listed.fold(Money.ZERO) { running, g -> running + g.requiredMonthly }, total)
        }

    @Test
    fun `no goals subtracts nothing, leaving Safe-to-Spend exactly as it was`() =
        runTest(dispatcher) {
            assertEquals(Money.ZERO, goals.requiredMonthlyTotal().expectOk())
        }

    @Test
    fun `goals are listed soonest first, because the list is a queue`() =
        runTest(dispatcher) {
            goals.save(draft(name = "Later", targetDateIso = "2030-01-31")).expectOk()
            goals.save(draft(name = "Sooner", targetDateIso = "2027-01-31")).expectOk()

            assertEquals(listOf("Sooner", "Later"), goals.observeGoals().first().map { it.name })
        }

    @Test
    fun `a row whose stored date is corrupt is dropped, not fatal to the whole screen`() =
        runTest(dispatcher) {
            // Only reachable from a hand-edited database or a future migration bug. Losing one goal
            // beats a screen that cannot render at all (P-04).
            goals.save(draft(name = "Good")).expectOk()
            database.goalDao().upsert(
                database.goalDao().find(goals.save(draft(name = "Bad")).expectOk())!!
                    .copy(targetDateIso = "not-a-date"),
            )

            assertEquals(listOf("Good"), goals.observeGoals().first().map { it.name })
        }

    /** A draft goal with one field varied. */
    private fun draft(
        name: String = "Kerala trip",
        target: Money = Money(50_000_00),
        targetDateIso: String = "2028-04-30",
        saved: Money = Money(10_000_00),
        plannedMonthly: Money = Money(1_500_00),
    ) = GoalDraft(
        name = name,
        target = target,
        targetDateIso = targetDateIso,
        saved = saved,
        plannedMonthly = plannedMonthly,
    )

    private companion object {
        const val REAL_PROFILE = "profile:real"
        const val DEMO_PROFILE = "profile:demo"
    }
}

/**
 * Unwraps an `Ok`, failing the test with the error otherwise.
 * Result: the value. Input: the receiver. Output: [T].
 *
 * File-private, as in every other repository suite here — a shared helper would be one more thing
 * to import for four lines.
 */
private fun <T> com.aicfo.core.common.Result<T, AppError>.expectOk(): T =
    when (this) {
        is Ok -> value
        is Err -> error("expected Ok, was $error")
    }
