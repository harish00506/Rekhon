package com.aicfo.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.Ok
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.TransactionEntity
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.recurring.Cadence
import com.aicfo.domain.engines.recurring.RecurringEngineFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tests for [RecurringRepository] — which rows are looked at, and what a decision does
 * (issue 3.7; FR-TXN-006).
 *
 * Why:  the detector's arithmetic is proven in its own module. What is proven here is everything the
 *       engine cannot see: **which rows it is handed**, and **whether a merchant the user has
 *       already answered ever comes back**. Both have failures that produce a plausible wrong
 *       screen rather than an error — a standing transfer to savings proposed as a bill, a
 *       rejection that reappears next time the list is opened — and neither would show up anywhere
 *       but in front of the user.
 * What: the query's four exclusions, the window, the two writes, and the exclusion that makes a
 *       decision stick.
 * Result: the storage half of FR-TXN-006 is proven against a real SQL engine.
 * Changelog: 2026-08-05 — Created for issue 3.7.
 *
 * Unencrypted in-memory Room, deliberately — the same reasoning as the suites beside it: what is
 * under test is the SQL and the ordering, not SQLCipher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RecurringRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var repository: RecurringRepository
    private val clock = FakeClock(initialMillis = TODAY_MILLIS, initialZone = ZoneId.of("Asia/Kolkata"))
    private val activeProfileId = MutableStateFlow(PROFILE)

    /** Input: none. Output: a fresh in-memory database and the repository over it. */
    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                CfoDatabase::class.java,
            ).allowMainThreadQueries().build()
        repository =
            RepositoryFactory.recurring(
                database = database,
                engine = RecurringEngineFactory.create(),
                clock = clock,
                // `Dispatchers.Unconfined`, not an `UnconfinedTestDispatcher`, and this is
                // load-bearing: `observeSuggestions` combines two Room flows, and `combine` yields
                // between emissions. A test dispatcher created here carries its own scheduler,
                // which `runTest` rejects the moment that yield crosses back into the test body
                // ("Detected use of different schedulers"). The real unconfined dispatcher has no
                // scheduler to disagree about, and the flows are hot Room queries either way.
                dispatchers = TestDispatchers(Dispatchers.Unconfined),
                activeProfileId = activeProfileId,
            )
    }

    /** Input: none. Output: closes the database between tests. */
    @After
    fun tearDown() {
        database.close()
    }

    // --- what reaches the detector ---------------------------------------------------------------

    @Test
    fun `a repeating merchant becomes a suggestion with its occurrence dates`() =
        runTest {
            insertRent()

            val series = repository.observeSuggestions().first().single()

            assertEquals("Landlord", series.merchant)
            assertEquals(Cadence.MONTHLY, series.cadence)
            assertEquals(Money(-25_000_00L), series.medianAmount)
            assertEquals(
                "the dates the card shows as evidence (P-02)",
                listOf("2026-06-03", "2026-07-03", "2026-08-03"),
                series.occurrences.map { it.bookedOn },
            )
        }

    @Test
    fun `a soft-deleted row is not evidence of anything`() =
        runTest {
            insertRent()
            database.transactionDao().softDelete("t:rent-aug", clock.nowUtcMillis())

            // Two survive, which is still a series — but the deleted one must not be in it.
            val series = repository.observeSuggestions().first().single()

            assertEquals(listOf("t:rent-jun", "t:rent-jul"), series.occurrences.map { it.transactionId })
        }

    @Test
    fun `a transfer leg is never proposed as a bill`() =
        runTest {
            // FR-TXN-003: money moving between the user's own accounts is not spending. A standing
            // transfer to savings is the most regular thing in a ledger and the least like a bill.
            listOf("2026-06-01", "2026-07-01", "2026-08-01").forEachIndexed { index, day ->
                insertTransaction("t:sweep-$index", "Savings sweep", -10_000_00L, day, transferId = "x:$index")
            }

            assertTrue(repository.observeSuggestions().first().isEmpty())
        }

    @Test
    fun `a scheduled row is not a pattern - only money that has actually moved counts`() =
        runTest {
            // FR-TXN-010. Detecting a series in payments the user has merely *planned* would build a
            // rule out of the app's own predictions.
            insertTransaction("t:gym-1", "Gym", -1_500_00L, "2026-06-01")
            insertTransaction("t:gym-2", "Gym", -1_500_00L, "2026-07-01")
            insertTransaction("t:gym-future", "Gym", -1_500_00L, TODAY.plusMonths(1).toString())

            val series = repository.observeSuggestions().first().single()

            assertEquals(2, series.occurrences.size)
            assertTrue("t:gym-future" !in series.occurrences.map { it.transactionId })
        }

    @Test
    fun `a row with no merchant is never proposed`() =
        runTest {
            listOf("2026-06-01", "2026-07-01").forEachIndexed { index, day ->
                insertTransaction("t:blank-$index", merchant = "  ", amountMinor = -250_00L, bookedOn = day)
            }

            assertTrue(repository.observeSuggestions().first().isEmpty())
        }

    @Test
    fun `rows outside the lookback window are not read`() =
        runTest {
            val tooOld = TODAY.minusDays(RecurringRepository.LOOKBACK_DAYS + 40)
            insertTransaction("t:old-1", "Insurance", -18_400_00L, tooOld.toString())
            insertTransaction("t:old-2", "Insurance", -18_400_00L, tooOld.plusYears(1).toString())

            assertTrue(
                "the window is a cost bound, and a partial series is not a series",
                repository.observeSuggestions().first().isEmpty(),
            )
        }

    @Test
    fun `another profile's transactions are never proposed to this one`() =
        runTest {
            insertRent(profileId = "p:other")

            assertTrue(repository.observeSuggestions().first().isEmpty())
        }

    // --- decisions feed back as data (the acceptance criterion) ---------------------------------

    @Test
    fun `confirming writes a confirmed rule and the suggestion goes`() =
        runTest {
            insertRent()
            val series = repository.observeSuggestions().first().single()

            assertTrue(repository.confirm(series) is Ok)

            val rule = database.recurringRuleDao().observeForProfile(PROFILE).first().single()
            assertEquals("Landlord", rule.name)
            assertEquals(Money(-25_000_00L).minor, rule.amountMinor)
            assertEquals("monthly", rule.cadence)
            assertEquals("2026-09-03", rule.nextDueIsoDate)
            assertEquals("FR-TXN-009: it came from the detector", "detected", rule.source)
            assertTrue(rule.isConfirmed)
            assertNull("a confirmation is not a dismissal", rule.dismissedAtUtcMillis)
            assertTrue("the same merchant is never proposed twice", repository.observeSuggestions().first().isEmpty())
        }

    @Test
    fun `dismissing records the rejection and the suggestion does not come back`() =
        runTest {
            insertRent()
            val series = repository.observeSuggestions().first().single()

            assertTrue(repository.dismiss(series) is Ok)

            // The rule leaves every ordinary read...
            assertTrue(database.recurringRuleDao().observeForProfile(PROFILE).first().isEmpty())
            // ...but the decision is still on record, which is what stops the proposal returning.
            assertEquals(listOf("landlord"), database.recurringRuleDao().observeDecidedNames(PROFILE).first())
            assertTrue(repository.observeSuggestions().first().isEmpty())
        }

    @Test
    fun `a dismissed merchant stays dismissed after more of the same charges arrive`() =
        runTest {
            // The regression this issue exists to avoid: the user says "not recurring", pays the
            // same bill again, and the app asks a second time. Proven by re-reading, because
            // `observeSuggestions` re-runs the detector on every ledger change.
            insertRent()
            repository.dismiss(repository.observeSuggestions().first().single())

            insertTransaction("t:rent-sep", "Landlord", -25_000_00L, "2026-09-03")

            assertTrue(repository.observeSuggestions().first().isEmpty())
        }

    @Test
    fun `a deleted rule does not resurrect its proposal`() =
        runTest {
            insertRent()
            repository.confirm(repository.observeSuggestions().first().single())
            val ruleId = database.recurringRuleDao().observeForProfile(PROFILE).first().single().id

            database.recurringRuleDao().softDelete(ruleId, clock.nowUtcMillis())

            assertTrue(
                "a tombstone still means the user has seen this merchant",
                repository.observeSuggestions().first().isEmpty(),
            )
        }

    @Test
    fun `a decision under one profile does not silence the other`() =
        runTest {
            insertRent()
            insertRent(profileId = OTHER_PROFILE)
            repository.dismiss(repository.observeSuggestions().first().single())

            activeProfileId.value = OTHER_PROFILE

            assertEquals("Landlord", repository.observeSuggestions().first().single().merchant)
        }

    @Test
    fun `the proposal follows the active profile`() =
        runTest {
            insertRent(profileId = OTHER_PROFILE)

            assertTrue(repository.observeSuggestions().first().isEmpty())
            activeProfileId.value = OTHER_PROFILE
            assertEquals(1, repository.observeSuggestions().first().size)
        }

    // --- fixtures --------------------------------------------------------------------------------

    /** Result: three monthly rent charges. Input: [profileId]. Output: none (suspends). */
    private suspend fun insertRent(profileId: String = PROFILE) {
        listOf("2026-06-03", "2026-07-03", "2026-08-03").forEachIndexed { index, day ->
            insertRow(
                id = "$profileId:t:rent-${MONTHS[index]}".takeIf { profileId != PROFILE } ?: "t:rent-${MONTHS[index]}",
                merchant = "Landlord",
                amountMinor = -25_000_00L,
                bookedOn = day,
                transferId = null,
                profileId = profileId,
            )
        }
    }

    /** Result: one transaction row. Input: the fields the detector reads. Output: none (suspends). */
    private suspend fun insertTransaction(
        id: String,
        merchant: String,
        amountMinor: Long,
        bookedOn: String,
        transferId: String? = null,
    ) = insertRow(id, merchant, amountMinor, bookedOn, transferId, PROFILE)

    /** Result: one row under any profile. Input: the fields the detector reads. Output: none. */
    @Suppress("LongParameterList") // A row fixture: the count is the table's, not a design choice.
    private suspend fun insertRow(
        id: String,
        merchant: String,
        amountMinor: Long,
        bookedOn: String,
        transferId: String?,
        profileId: String,
    ) {
        database.transactionDao().upsert(
            TransactionEntity(
                id = id,
                profileId = profileId,
                accountId = "a:bank",
                amountMinor = amountMinor,
                currencyCode = "INR",
                occurredAtUtcMillis = clock.nowUtcMillis(),
                bookedOnIsoDate = bookedOn,
                merchant = merchant,
                source = "manual",
                type = if (transferId == null) "expense" else "transfer_out",
                transferId = transferId,
                createdAtUtcMillis = clock.nowUtcMillis(),
                updatedAtUtcMillis = clock.nowUtcMillis(),
            ),
        )
    }

    private companion object {
        const val PROFILE = "p:test"
        const val OTHER_PROFILE = "p:demo"

        /** The profile-zone day every fixture is written relative to. */
        val TODAY: LocalDate = LocalDate.parse("2026-08-05")

        /** 2026-08-05T12:00 in Asia/Kolkata, so the profile day is unambiguous (TIM-001). */
        val TODAY_MILLIS: Long = Instant.parse("2026-08-05T06:30:00Z").toEpochMilli()

        val MONTHS = listOf("jun", "jul", "aug")
    }
}
