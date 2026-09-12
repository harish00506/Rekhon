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
import com.aicfo.core.database.entity.AccountEntity
import com.aicfo.core.database.entity.RecurringRuleEntity
import com.aicfo.core.database.entity.TransactionEntity
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.classification.ClassificationEngineFactory
import com.aicfo.domain.engines.goals.GoalEngineFactory
import com.aicfo.domain.engines.nature.NatureEngineFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
import java.time.Instant

/**
 * Linked contributions, end to end over real SQL — issue 7.4 (§15, FR-GOAL-002, FR-GOAL-004).
 *
 * Why:  the engine tests prove the arithmetic of a split that is *handed* to them. **What is
 *       unproven above SQLite is where that split comes from**, and every part of it fails while
 *       still returning a perfectly plausible number:
 *
 *       - the **sum**. `GoalDao.observeEvidenced` is one `UNION ALL` over two tables with three
 *         date bounds and a `NOT EXISTS`. A wrong sign, a wrong bound or a missing exclusion all
 *         produce a figure, and only an assertion against known paise says which.
 *       - the **reversal**. The acceptance criterion is that unlinking reverses progress *exactly*
 *         and keeps the provenance. Those pull in opposite directions — a hard delete would do the
 *         first and destroy the second.
 *       - the **dedupe**. A transaction inside a dedicated account that is also linked by hand must
 *         count once. Counting it twice doubles a goal's progress silently.
 *       - the **profile scope**. Evidence leaking between the real and demo profiles would put
 *         somebody else's movements into a goal (ADR-0006).
 *       - the **anchor**. `RULE-PAY-FIRST` needs a day the app must not invent (P-03).
 * What: the sum in both directions, the dedupe, reversal and revival, profile scoping, the picker,
 *       and the anchor.
 * Result: the first evidence-based progress figures in the app, proven against a real SQL engine.
 * Changelog: 2026-09-06 — Created for issue 7.4.
 *
 * Unencrypted in-memory Room and the **real** engine, as `GoalRepositoryTest` does: the claim is
 * that a linked movement reaches the goal card, and a stub could not make it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GoalContributionRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var goals: GoalRepository
    private lateinit var links: GoalContributionRepository

    private val clock = FakeClock(initialMillis = Instant.parse("2026-08-30T06:00:00Z").toEpochMilli())
    private val ids = FakeIdGenerator()
    private val dispatcher = UnconfinedTestDispatcher()
    private val activeProfileId = MutableStateFlow(REAL_PROFILE)

    /** Input: none. Output: a fresh in-memory database, both repositories, and one account. */
    @Before
    fun setUp() =
        runTest(dispatcher) {
            database =
                Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    CfoDatabase::class.java,
                ).allowMainThreadQueries().build()
            val dispatchers = TestDispatchers(dispatcher)
            goals =
                RepositoryFactory.goals(
                    database,
                    GoalEngineFactory.create(),
                    clock,
                    ids,
                    dispatchers,
                    activeProfileId,
                )
            links =
                RepositoryFactory.goalContributions(
                    database = database,
                    transactions =
                        RepositoryFactory.transactions(
                            database, clock, ids, dispatchers, activeProfileId,
                            ClassificationEngineFactory.create(), NatureEngineFactory.create(),
                        ),
                    accounts = RepositoryFactory.accounts(database, clock, ids, dispatchers, activeProfileId),
                    clock = clock,
                    ids = ids,
                    dispatchers = dispatchers,
                    activeProfileId = activeProfileId,
                )
            insertAccount(SAVINGS, REAL_PROFILE)
        }

    /** Input: none. Output: closes the database between tests. */
    @After
    fun tearDown() {
        database.close()
    }

    // --- the sum -------------------------------------------------------------------------------

    @Test
    fun `linking a transaction raises progress by exactly its amount, and unlinking reverses it`() =
        runTest(dispatcher) {
            val goalId = goals.save(draft()).expectOk()
            // Stored negative — an outflow from the spending account into the SIP. The magnitude is
            // what funds the goal; the direction is what the user asserted by linking it.
            insertTransaction("t1", minor = -5_000_00)

            links.link(goalId, "t1").expectOk()
            val linked = goals.observeGoals().first().single()

            assertEquals("MNY-001: the paise arrive exactly", Money(15_000_00), linked.saved)
            assertEquals(Money(5_000_00), linked.savedEvidenced)
            assertEquals("what the user typed is untouched", Money(10_000_00), linked.savedDeclared)

            links.unlink(goalId, "t1").expectOk()
            val reversed = goals.observeGoals().first().single()

            assertEquals("unlinking reverses it to the paise", Money(10_000_00), reversed.saved)
            assertEquals(Money.ZERO, reversed.savedEvidenced)
        }

    @Test
    fun `unlinking keeps the row, so when it was linked survives the reversal`() =
        runTest(dispatcher) {
            val goalId = goals.save(draft()).expectOk()
            insertTransaction("t1", minor = -5_000_00)
            links.link(goalId, "t1").expectOk()
            val linkedAt = database.goalContributionDao().findIncludingDeleted(goalId, "t1")!!.createdAtUtcMillis

            links.unlink(goalId, "t1").expectOk()

            val row = database.goalContributionDao().findIncludingDeleted(goalId, "t1")
            assertTrue("the link must survive as a tombstone (DB-002)", row != null)
            assertEquals("and remember when it was made", linkedAt, row!!.createdAtUtcMillis)
            assertTrue("while no longer counting", row.deletedAtUtcMillis != null)
        }

    @Test
    fun `re-linking revives the row rather than minting a second`() =
        runTest(dispatcher) {
            val goalId = goals.save(draft()).expectOk()
            insertTransaction("t1", minor = -5_000_00)
            links.link(goalId, "t1").expectOk()
            links.unlink(goalId, "t1").expectOk()

            links.link(goalId, "t1").expectOk()

            assertEquals(
                "the unique index forbids a duplicate, so a second row would have thrown",
                1,
                database.goalContributionDao().forProfile(REAL_PROFILE).size,
            )
            assertEquals(Money(15_000_00), goals.observeGoals().first().single().saved)
        }

    @Test
    fun `a future-dated transaction is linkable but does not count until it happens`() =
        runTest(dispatcher) {
            // FR-TXN-010: a scheduled payment has not happened, so it cannot be progress. The bound
            // is the same `booked_on_iso_date <= today` every balance query uses.
            val goalId = goals.save(draft()).expectOk()
            insertTransaction("t1", minor = -5_000_00, bookedOn = "2026-12-01")

            links.link(goalId, "t1").expectOk()

            assertEquals(Money(10_000_00), goals.observeGoals().first().single().saved)
        }

    @Test
    fun `a soft-deleted transaction stops counting without the link being touched`() =
        runTest(dispatcher) {
            val goalId = goals.save(draft()).expectOk()
            insertTransaction("t1", minor = -5_000_00)
            links.link(goalId, "t1").expectOk()

            database.transactionDao().softDelete("t1", clock.nowUtcMillis())

            assertEquals(
                "the movement is gone, so the evidence is gone — without the user unlinking anything",
                Money(10_000_00),
                goals.observeGoals().first().single().saved,
            )
        }

    // --- funding accounts ----------------------------------------------------------------------

    @Test
    fun `a dedicated account counts its net movement, signed, from the day it was linked`() =
        runTest(dispatcher) {
            val goalId = goals.save(draft()).expectOk()
            insertTransaction("old", minor = 3_000_00, bookedOn = "2026-01-01")
            insertTransaction("in", minor = 8_000_00, bookedOn = "2026-08-30")
            insertTransaction("out", minor = -1_000_00, bookedOn = "2026-08-30")

            links.linkAccount(goalId, SAVINGS, countHistory = false).expectOk()

            val linked = goals.observeGoals().first().single()
            assertEquals(
                "only movements from the link day, and the outflow reduces the pot",
                Money(7_000_00),
                linked.savedEvidenced,
            )
            assertEquals(Money(17_000_00), linked.saved)
        }

    @Test
    fun `counting the history reaches back past the link day`() =
        runTest(dispatcher) {
            val goalId = goals.save(draft()).expectOk()
            insertTransaction("old", minor = 3_000_00, bookedOn = "2026-01-01")

            links.linkAccount(goalId, SAVINGS, countHistory = true).expectOk()

            assertEquals(Money(3_000_00), goals.observeGoals().first().single().savedEvidenced)
        }

    @Test
    fun `a movement that is both dedicated and linked by hand counts once`() =
        runTest(dispatcher) {
            // The double-count ADR-0009 exists to prevent, in its 7.4 shape. The explicit link wins
            // because it is the more specific statement.
            val goalId = goals.save(draft()).expectOk()
            insertTransaction("in", minor = 8_000_00, bookedOn = "2026-08-30")

            links.linkAccount(goalId, SAVINGS, countHistory = true).expectOk()
            links.link(goalId, "in").expectOk()

            assertEquals(Money(8_000_00), goals.observeGoals().first().single().savedEvidenced)
        }

    @Test
    fun `releasing an account reverses its whole contribution`() =
        runTest(dispatcher) {
            val goalId = goals.save(draft()).expectOk()
            insertTransaction("in", minor = 8_000_00, bookedOn = "2026-08-30")
            links.linkAccount(goalId, SAVINGS, countHistory = true).expectOk()

            links.unlinkAccount(goalId, SAVINGS).expectOk()

            assertEquals(Money(10_000_00), goals.observeGoals().first().single().saved)
        }

    @Test
    fun `an account that paid out more than was claimed floors progress at zero without inventing a claim`() =
        runTest(dispatcher) {
            // The evidenced half is clamped at `-declared`, not the total at zero: flooring the
            // total would leave `saved - savedEvidenced` reporting a figure the user never typed.
            val goalId = goals.save(draft(saved = Money(1_000_00))).expectOk()
            insertTransaction("out", minor = -5_000_00, bookedOn = "2026-08-30")
            links.linkAccount(goalId, SAVINGS, countHistory = true).expectOk()

            val projected = goals.observeGoals().first().single()

            assertEquals(Money.ZERO, projected.saved)
            assertEquals(Money(-1_000_00), projected.savedEvidenced)
            assertEquals("still exactly what the user typed", Money(1_000_00), projected.savedDeclared)
        }

    // --- scope ---------------------------------------------------------------------------------

    @Test
    fun `evidence does not cross profiles`() =
        runTest(dispatcher) {
            val goalId = goals.save(draft()).expectOk()
            insertAccount("acct:demo", DEMO_PROFILE)
            insertTransaction("demo", minor = -9_000_00, profileId = DEMO_PROFILE, accountId = "acct:demo")
            // The link row itself is written under the demo profile: the sum is scoped by
            // `goal_contribution.profile_id`, so a link forged under another profile must not count.
            database.goalContributionDao().upsert(
                com.aicfo.core.database.entity.GoalContributionEntity(
                    id = "gc:forged",
                    profileId = DEMO_PROFILE,
                    goalId = goalId,
                    transactionId = "demo",
                    createdAtUtcMillis = clock.nowUtcMillis(),
                    updatedAtUtcMillis = clock.nowUtcMillis(),
                ),
            )

            assertEquals(Money(10_000_00), goals.observeGoals().first().single().saved)
        }

    // --- the picker ----------------------------------------------------------------------------

    @Test
    fun `the picker hides what is already linked and offers a transfer only once`() =
        runTest(dispatcher) {
            val goalId = goals.save(draft()).expectOk()
            insertAccount("acct:cash", REAL_PROFILE)
            insertTransaction("plain", minor = -2_000_00)
            insertTransaction("leg-out", minor = -4_000_00, type = "transfer_out", transferId = "tr1")
            insertTransaction(
                "leg-in",
                minor = 4_000_00,
                type = "transfer_in",
                transferId = "tr1",
                accountId = "acct:cash",
            )

            assertEquals(
                "both legs offered would let the user link one sum of money twice (ADR-0008)",
                listOf("leg-in", "plain"),
                links.observeLinkable(goalId).first().map { it.id }.sorted(),
            )

            links.link(goalId, "plain").expectOk()

            assertEquals(listOf("leg-in"), links.observeLinkable(goalId).first().map { it.id })
        }

    @Test
    fun `the contributions list resolves through the ledger rather than a stored copy`() =
        runTest(dispatcher) {
            val goalId = goals.save(draft()).expectOk()
            insertTransaction("t1", minor = -5_000_00)
            links.link(goalId, "t1").expectOk()

            val contribution = links.observeContributions(goalId).first().single()

            assertEquals("t1", contribution.transaction.id)
            assertEquals("the magnitude, not the stored sign", Money(5_000_00), contribution.amount)
            assertEquals(clock.nowUtcMillis(), contribution.linkedAtUtcMillis)
        }

    @Test
    fun `a dedicated account is listed with the day it starts counting`() =
        runTest(dispatcher) {
            val goalId = goals.save(draft()).expectOk()

            links.linkAccount(goalId, SAVINGS, countHistory = false).expectOk()

            val funding = links.observeFundingAccounts(goalId).first().single()
            assertEquals(SAVINGS, funding.account.id)
            assertEquals("TIM-002: the choice is stored as a day", "2026-08-30", funding.linkedFromIsoDate)
            assertTrue("and it is a real day, not the sentinel", !funding.countsWholeHistory)
        }

    @Test
    fun `counting the history is reported as a flag, so no screen has to recognise the sentinel`() =
        runTest(dispatcher) {
            // Found by running it: the detail screen rendered the stored date and read
            // "counting from 0001-01-01" — a true statement about the database and a meaningless
            // one about the user's money. The repository that writes the sentinel reports it.
            val goalId = goals.save(draft()).expectOk()

            links.linkAccount(goalId, SAVINGS, countHistory = true).expectOk()

            assertTrue(links.observeFundingAccounts(goalId).first().single().countsWholeHistory)
        }

    // --- RULE-PAY-FIRST ------------------------------------------------------------------------

    @Test
    fun `the salary day comes from the income rule the profile already has`() =
        runTest(dispatcher) {
            val goalId = goals.save(draft()).expectOk()
            insertIncomeRule(nextDue = "2026-09-07")

            assertEquals(7, goals.observeGoals().first().single().contributionAnchorDay)
            assertTrue("and the goal is still the one under test", goalId.isNotBlank())
        }

    @Test
    fun `a profile with no income rule is told nothing rather than a guessed payday`() =
        runTest(dispatcher) {
            goals.save(draft()).expectOk()

            assertNull(goals.observeGoals().first().single().contributionAnchorDay)
        }

    // --- helpers -------------------------------------------------------------------------------

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

    /** Result: an account row, written straight to the DAO so the test controls its profile. */
    private suspend fun insertAccount(
        id: String,
        profileId: String,
    ) {
        database.accountDao().upsert(
            AccountEntity(
                id = id,
                profileId = profileId,
                name = "Savings $id",
                type = "bank",
                institution = null,
                openingBalanceMinor = 0L,
                currentBalanceMinor = 0L,
                currencyCode = "INR",
                createdAtUtcMillis = clock.nowUtcMillis(),
                updatedAtUtcMillis = clock.nowUtcMillis(),
            ),
        )
    }

    /**
     * Result: a transaction row, written straight to the DAO.
     *
     * Deliberately not through `TransactionRepository.create`: these tests need to place a movement
     * on an exact day, with an exact sign, under an exact profile, and the repository's own
     * validation would refuse some of the shapes the sum has to survive.
     */
    @Suppress("LongParameterList") // Six knobs, each varied by at least one case above.
    private suspend fun insertTransaction(
        id: String,
        minor: Long,
        bookedOn: String = "2026-08-01",
        profileId: String = REAL_PROFILE,
        accountId: String = SAVINGS,
        type: String = "expense",
        transferId: String? = null,
    ) {
        database.transactionDao().upsert(
            TransactionEntity(
                id = id,
                profileId = profileId,
                accountId = accountId,
                amountMinor = minor,
                currencyCode = "INR",
                occurredAtUtcMillis = clock.nowUtcMillis(),
                bookedOnIsoDate = bookedOn,
                source = "manual",
                type = type,
                transferId = transferId,
                createdAtUtcMillis = clock.nowUtcMillis(),
                updatedAtUtcMillis = clock.nowUtcMillis(),
            ),
        )
    }

    /** Result: the quick-setup income seed whose due date carries the salary day (issue 2.3). */
    private suspend fun insertIncomeRule(nextDue: String) {
        database.recurringRuleDao().upsertAll(
            listOf(
                RecurringRuleEntity(
                    id = "rule:income",
                    profileId = REAL_PROFILE,
                    name = "Salary",
                    seedKind = "income",
                    amountMinor = 75_000_00,
                    cadence = "monthly",
                    nextDueIsoDate = nextDue,
                    source = "quick_setup",
                    isConfirmed = true,
                    createdAtUtcMillis = clock.nowUtcMillis(),
                    updatedAtUtcMillis = clock.nowUtcMillis(),
                ),
            ),
        )
    }

    private companion object {
        const val REAL_PROFILE = "profile:real"
        const val DEMO_PROFILE = "profile:demo"
        const val SAVINGS = "acct:savings"
    }
}

/**
 * Unwraps an `Ok`, failing the test with the error otherwise.
 * Result: the value. Input: the receiver. Output: [T].
 *
 * File-private, as in every other repository suite here.
 */
private fun <T> com.aicfo.core.common.Result<T, AppError>.expectOk(): T =
    when (this) {
        is Ok -> value
        is Err -> error("expected Ok, was $error")
    }
