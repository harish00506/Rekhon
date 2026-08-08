package com.aicfo.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.Ok
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.quicksetup.QuickSetupEngineFactory
import com.aicfo.domain.engines.quicksetup.QuickSetupInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [DemoModeRepository] — loading and erasing the sample dataset (issue 2.4; FR-ONB-004).
 *
 * Why:  demo mode makes one promise that is easy to state and easy to break: *you can look at this
 *       app on fake money and leave without a trace*. Two failures would break it, and neither is
 *       visible from reading the class. **Leakage** — a demo row scoped to the real profile, or a
 *       real row reached by the wipe — which would either pollute the user's data or delete it.
 *       And **residue** — anything at all left behind after exit, including a soft-delete tombstone,
 *       which is why this repository is the one place in the app that hard-deletes (ADR-0006).
 * What: the write, the isolation from a pre-existing real profile, the wipe, idempotence, the
 *       active-profile switch, and both halves of the flag-ordering contract.
 * Result: the persistence half of FR-ONB-004 is proven against a real SQL engine.
 * Changelog: 2026-07-28 — Created for issue 2.4.
 *
 * **Unencrypted in-memory Room, deliberately** — the same reasoning as `QuickSetupRepositoryTest`:
 * SQLCipher needs a device, and what is under test here is the SQL and the ordering, not the
 * encryption. `EncryptedDatabaseTest` (androidTest, issue 1.6) is what proves the file is ciphertext.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DemoModeRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var repository: DemoModeRepository
    private lateinit var settings: FakeSettingsStore
    private val clock = FakeClock()

    /** Input: none. Output: a fresh in-memory database and a repository over it. */
    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                CfoDatabase::class.java,
            )
                // For the assertions that reach past the repository to read raw SQL and check what
                // actually landed. The repository still goes through its injected dispatcher.
                .allowMainThreadQueries()
                .build()
        settings = FakeSettingsStore()
        repository =
            RepositoryFactory.demoMode(
                database = database,
                settingsStore = settings,
                clock = clock,
                dispatchers = TestDispatchers(UnconfinedTestDispatcher()),
            )
    }

    /** Input: none. Output: closes the database between tests. */
    @After
    fun tearDown() {
        database.close()
    }

    // --- entering ---------------------------------------------------------------------------------

    /**
     * Input:  a fresh database, then `enter()`.
     * Output: asserts every table the dataset covers actually received rows. Asserting each table
     *         rather than a total is what catches a write silently dropped from the transaction —
     *         the failure mode where the demo looks fine on the one screen that exists today and is
     *         empty on the screens issues 2.5 and 3.6 add.
     */
    @Test
    fun `entering writes the sample data`() =
        runTest {
            assertTrue(repository.enter() is Ok)

            val demo = DemoModeRepository.DEMO_PROFILE_ID
            assertNotNull(database.profileDao().findById(demo))
            assertEquals(4, database.accountDao().observeForProfile(demo).first().size)
            assertEquals(12, database.categoryDao().observeForProfile(demo).first().size)
            assertEquals(3, database.budgetDao().observeLatestPeriod(demo).first().size)
            assertEquals(3, database.recurringRuleDao().observeForProfile(demo).first().size)
            assertTrue(demoTransactions().isNotEmpty())
        }

    /**
     * Input:  `enter()`.
     * Output: asserts the flag is set and the active profile switches to the demo. This is what makes
     *         the dashboard show sample figures and the banner appear; without it the rows would be
     *         written and nothing would ever read them.
     */
    @Test
    fun `entering turns the demo on and switches the active profile`() =
        runTest {
            assertEquals(QuickSetupRepository.DEFAULT_PROFILE_ID, repository.activeProfileId.first())
            assertFalse(repository.isActive.first())

            assertTrue(repository.enter() is Ok)

            assertTrue(repository.isActive.first())
            assertEquals(DemoModeRepository.DEMO_PROFILE_ID, repository.activeProfileId.first())
        }

    /**
     * Input:  `enter()` twice.
     * Output: asserts the second run **updates** the same rows rather than adding a second copy of
     *         the dataset. Ids are derived, so REPLACE lands on the same rows — the same property
     *         quick setup relies on. Without it, a user who tapped the demo button twice would see
     *         every figure doubled and no error anywhere.
     */
    @Test
    fun `entering twice is idempotent`() =
        runTest {
            assertTrue(repository.enter() is Ok)
            val afterFirst = database.demoDao().countRowsFor(DemoModeRepository.DEMO_PROFILE_ID)

            assertTrue(repository.enter() is Ok)

            assertEquals(afterFirst, database.demoDao().countRowsFor(DemoModeRepository.DEMO_PROFILE_ID))
        }

    // --- isolation --------------------------------------------------------------------------------

    /**
     * Input:  a real profile with its own budget, then a full demo round trip.
     * Output: asserts the real rows are **byte-identical** before and after. This is the acceptance
     *         criterion "no residue in the real profile" from the other direction: not only must the
     *         demo leave nothing behind, it must not have touched anything on the way through.
     */
    @Test
    fun `a real profile survives a demo round trip untouched`() =
        runTest {
            seedRealProfile()
            val profileBefore = database.profileDao().findById(QuickSetupRepository.DEFAULT_PROFILE_ID)
            val budgetsBefore = realBudgets()
            assertTrue(budgetsBefore.isNotEmpty())

            assertTrue(repository.enter() is Ok)
            assertTrue(repository.exit() is Ok)

            assertEquals(profileBefore, database.profileDao().findById(QuickSetupRepository.DEFAULT_PROFILE_ID))
            assertEquals(budgetsBefore, realBudgets())
        }

    /**
     * Input:  a real profile, then `enter()`.
     * Output: asserts the demo rows never carry the real profile id. The two datasets coexist in the
     *         same tables, so this is the assertion that the scoping — the entire isolation
     *         mechanism (ADR-0006) — actually holds once the rows are in SQL rather than in a
     *         generated list.
     */
    @Test
    fun `demo rows never land under the real profile`() =
        runTest {
            seedRealProfile()
            val realBudgetCount = realBudgets().size

            assertTrue(repository.enter() is Ok)

            val real = QuickSetupRepository.DEFAULT_PROFILE_ID
            assertEquals(realBudgetCount, realBudgets().size)
            assertEquals(0, database.accountDao().observeForProfile(real).first().size)
            assertEquals(0, database.categoryDao().observeForProfile(real).first().size)
        }

    // --- leaving ----------------------------------------------------------------------------------

    /**
     * Input:  a full demo, then `exit()`.
     * Output: asserts **zero** rows remain for the demo profile across every table — the acceptance
     *         criterion stated as one number. `countRowsFor` deliberately does not filter
     *         `deleted_at_utc_millis`, so a soft delete would leave the count non-zero and red this
     *         test, which is exactly why the wipe is a hard delete.
     */
    @Test
    fun `leaving erases every demo row with no residue`() =
        runTest {
            assertTrue(repository.enter() is Ok)
            assertTrue(database.demoDao().countRowsFor(DemoModeRepository.DEMO_PROFILE_ID) > 0)

            assertTrue(repository.exit() is Ok)

            assertEquals(0, database.demoDao().countRowsFor(DemoModeRepository.DEMO_PROFILE_ID))
            assertNull(database.profileDao().findById(DemoModeRepository.DEMO_PROFILE_ID))
            assertTrue(demoTransactions().isEmpty())
        }

    /**
     * Input:  a demo session during which the SMS scanner drafted a transaction, then `exit()`.
     * Output: asserts the draft goes too.
     *
     * A separate test for the reason the snapshot one below states, and it is the same failure
     * repeating: `enter()` does not write `sms_draft`, the daily scan does — against whichever
     * profile is active. So a demo session on a phone whose owner opted in accumulates inferences
     * drawn from their **real inbox** under the demo profile, and a wipe that could not reach them
     * would leave behind a record of the user's spending belonging to a profile that no longer
     * exists (P-01, ADR-0006).
     *
     * **This was a real gap, found by the issue 3.9 security review rather than by a test.** The
     * "no residue" assertion above uses `countRowsFor`, which enumerates the same table list
     * `DemoDao` deletes — so a table missing from the DAO is also missing from the count, and the
     * assertion passes vacuously. Adding a profile-scoped table means adding it to **both**.
     * Changelog: 2026-08-07 — Added for issue 3.9 (`sms_draft`).
     */
    @Test
    fun `a draft parsed during the demo is erased on the way out`() =
        runTest {
            assertTrue(repository.enter() is Ok)
            database.smsDraftDao().insertIfNew(
                com.aicfo.core.database.entity.SmsDraftEntity(
                    id = "d1",
                    profileId = DemoModeRepository.DEMO_PROFILE_ID,
                    smsId = 71L,
                    sender = "VM-HDFCBK",
                    amountMinor = 125_000L,
                    direction = "debit",
                    bookedOn = "2026-08-07",
                    confidenceBps = 9_000,
                    engineVersion = "1.0",
                    ruleVersion = "1.0",
                    status = "pending",
                    createdAtUtcMillis = 1_786_082_400_000L,
                    updatedAtUtcMillis = 1_786_082_400_000L,
                ),
            )

            assertTrue(repository.exit() is Ok)

            assertEquals(0, database.demoDao().countRowsFor(DemoModeRepository.DEMO_PROFILE_ID))
        }

    /**
     * Input:  a demo session that produced a net-worth snapshot, then `exit()`.
     * Output: asserts the snapshot goes too.
     *
     * Why this is a separate test rather than covered by the one above: the demo dataset does not
     * create snapshots — the daily job does, while the user is browsing — so a demo profile that has
     * been open for a day has rows in a table `enter()` never wrote. **A profile-scoped table the
     * wipe does not reach is exactly the residue ADR-0006 forbids**, and issue 2.6 added the first
     * such table since that ADR was written.
     * Changelog: 2026-08-01 — Added for issue 2.6 (`net_worth_snapshot`).
     */
    @Test
    fun `a snapshot taken during the demo is erased on the way out`() =
        runTest {
            assertTrue(repository.enter() is Ok)
            database.netWorthSnapshotDao().upsertAll(
                listOf(
                    com.aicfo.core.database.entity.NetWorthSnapshotEntity(
                        id = "${DemoModeRepository.DEMO_PROFILE_ID}:networth:2026-03-17",
                        profileId = DemoModeRepository.DEMO_PROFILE_ID,
                        asOfIsoDate = "2026-03-17",
                        assetsMinor = 3_10_000_00L,
                        liabilitiesMinor = 18_000_00L,
                        netWorthMinor = 2_92_000_00L,
                        engineId = "net-worth",
                        engineVersion = "1.0",
                        computedAtUtcMillis = clock.nowUtcMillis(),
                        createdAtUtcMillis = clock.nowUtcMillis(),
                        updatedAtUtcMillis = clock.nowUtcMillis(),
                    ),
                ),
            )

            assertTrue(repository.exit() is Ok)

            assertEquals(0, database.demoDao().countRowsFor(DemoModeRepository.DEMO_PROFILE_ID))
            assertNull(
                database.netWorthSnapshotDao().findForDate(DemoModeRepository.DEMO_PROFILE_ID, "2026-03-17"),
            )
        }

    /**
     * Input:  `exit()`.
     * Output: asserts the flag is cleared and the active profile goes back to the real one — which
     *         is what removes the banner and returns the dashboard to the user's own budget.
     */
    @Test
    fun `leaving turns the demo off and restores the active profile`() =
        runTest {
            assertTrue(repository.enter() is Ok)

            assertTrue(repository.exit() is Ok)

            assertFalse(repository.isActive.first())
            assertEquals(QuickSetupRepository.DEFAULT_PROFILE_ID, repository.activeProfileId.first())
        }

    /**
     * Input:  `exit()` on a database that never held a demo.
     * Output: asserts it succeeds and changes nothing. The app clears the flag defensively at points
     *         where the demo may or may not be running, and an exit that failed — or worse, threw —
     *         when there was nothing to erase would turn a no-op into an error banner.
     */
    @Test
    fun `leaving a demo that was never entered is a no-op`() =
        runTest {
            seedRealProfile()

            assertTrue(repository.exit() is Ok)

            assertEquals(0, database.demoDao().countRowsFor(DemoModeRepository.DEMO_PROFILE_ID))
            assertNotNull(database.profileDao().findById(QuickSetupRepository.DEFAULT_PROFILE_ID))
        }

    /**
     * Input:  a real profile with its own budget, read through the **no-argument** envelope query,
     *         then a demo entered and left underneath it.
     * Output: asserts the read follows the active profile in both directions. This is the whole
     *         point of routing the switch through `activeProfileId`: the dashboard calls one
     *         function, never learns demo mode exists, and still shows the right budget. Without it
     *         a demo user would sit in front of their own empty dashboard wondering where the sample
     *         data went.
     */
    @Test
    fun `the active-profile read follows the demo in and out`() =
        runTest {
            seedRealProfile()
            val quickSetup = quickSetupRepository()
            val realEnvelopes = quickSetup.observeLatestEnvelopes().first()
            assertTrue(realEnvelopes.isNotEmpty())

            assertTrue(repository.enter() is Ok)
            val demoEnvelopes = quickSetup.observeLatestEnvelopes().first()

            assertTrue(demoEnvelopes.isNotEmpty())
            assertNotEquals("the demo budget must not be the user's own", realEnvelopes, demoEnvelopes)
            assertEquals(demoEnvelopes, quickSetup.observeLatestEnvelopes(DemoModeRepository.DEMO_PROFILE_ID).first())

            assertTrue(repository.exit() is Ok)

            assertEquals(realEnvelopes, quickSetup.observeLatestEnvelopes().first())
        }

    // --- the ordering contract --------------------------------------------------------------------

    /**
     * Input:  `enter()` with the settings write failing.
     * Output: asserts the call reports the failure and the demo is **not** marked active. The rows
     *         are written by then and are deliberately left orphaned — the next `enter()` replaces
     *         them and the next `exit()` erases them. The alternative, setting the flag first, would
     *         put the demo banner over the user's own data if the seed then failed, and that is the
     *         one outcome this feature must never produce.
     */
    @Test
    fun `a failed flag write leaves the demo off`() =
        runTest {
            settings.failDemoWrites = true

            assertTrue(repository.enter() is Err)

            assertFalse(repository.isActive.first())
            assertEquals(QuickSetupRepository.DEFAULT_PROFILE_ID, repository.activeProfileId.first())
        }

    /**
     * Input:  `exit()` with the settings write failing, after a successful demo.
     * Output: asserts the wipe **did not run**. Erasing the data while the app still believes it is
     *         in demo mode would leave the user looking at a banner over an empty dashboard, with
     *         nothing to explain where the sample data went. Keeping the rows means the next exit
     *         attempt can still do the whole job.
     */
    @Test
    fun `a failed flag write on the way out leaves the data in place`() =
        runTest {
            assertTrue(repository.enter() is Ok)
            val rowsBefore = database.demoDao().countRowsFor(DemoModeRepository.DEMO_PROFILE_ID)
            settings.failDemoWrites = true

            assertTrue(repository.exit() is Err)

            assertEquals(rowsBefore, database.demoDao().countRowsFor(DemoModeRepository.DEMO_PROFILE_ID))
        }

    // --- helpers ----------------------------------------------------------------------------------

    /**
     * Reads the real profile's budget envelopes.
     * Why:    named because four assertions ask the same question — "is the user's own budget still
     *         exactly what it was?" — and spelling the query out at each one buried the assertion.
     * Result: the `local` profile's latest-period budget rows. Input: none. Output: the list.
     */
    private suspend fun realBudgets() =
        database.budgetDao().observeLatestPeriod(QuickSetupRepository.DEFAULT_PROFILE_ID).first()

    /**
     * Reads every demo transaction, whatever it is dated.
     * Why:    the only transaction query is a date range, and the demo's dates depend on the clock —
     *         so the range is deliberately absurd rather than computed, which keeps the assertion
     *         about "are there any?" rather than about "did I get the window right?".
     * Result: the demo's transaction rows. Input: none. Output: the list.
     */
    private suspend fun demoTransactions() =
        database.transactionDao()
            .observeBookedBetween(DemoModeRepository.DEMO_PROFILE_ID, "0000-01-01", "9999-12-31")
            .first()

    /**
     * Writes a real user's profile and budget, so isolation has something to be isolated from.
     * Why:    uses the actual quick-setup path rather than raw DAO inserts — the rows a real user
     *         would have are the rows this test needs to prove survive, and hand-built ones could
     *         drift from what onboarding really writes.
     * Result: a `local` profile with three budget envelopes. Input: none. Output: none.
     */
    private suspend fun seedRealProfile() {
        val plan =
            QuickSetupEngineFactory.create()
                .plan(
                    QuickSetupInput(
                        // Deliberately different from the demo household's figures, so a test can
                        // tell the two budgets apart by their amounts alone.
                        monthlyIncome = Money(60_000_00),
                        rentOrEmi = Money(18_000_00),
                        typicalSavings = Money(8_000_00),
                        periodStartIsoDate = clock.today().withDayOfMonth(1).toString(),
                        nowUtcMillis = clock.nowUtcMillis(),
                    ),
                )
        quickSetupRepository().applySeeds(
            plan = (plan as Ok).value,
            profile = ProfileSeed(displayName = "Real user", timeZoneId = "Asia/Kolkata", currencyCode = "INR"),
        )
    }

    /**
     * Builds a quick-setup repository wired to this test's active-profile switch.
     * Why:    the demo/real switch only means anything when a *reader* is attached to it, and this
     *         is the reader the dashboard actually uses.
     * Result: a [QuickSetupRepository] over the same database. Input: none. Output: the repository.
     */
    private fun quickSetupRepository(): QuickSetupRepository =
        RepositoryFactory.quickSetup(
            database = database,
            clock = clock,
            dispatchers = TestDispatchers(UnconfinedTestDispatcher()),
            activeProfileId = repository.activeProfileId,
        )
}
