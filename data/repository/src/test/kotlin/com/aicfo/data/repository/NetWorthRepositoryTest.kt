package com.aicfo.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.FakeIdGenerator
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.AccountEntity
import com.aicfo.core.database.entity.TransactionEntity
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.networth.NetWorthEngineFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Tests for [NetWorthRepository] — the snapshot and the day it describes (issue 2.6; FR-ACC-005).
 *
 * Why:  the engine's arithmetic is proven in its own module. What is proven here is everything the
 *       engine cannot see: **which accounts count**, **which day's transactions count**, and
 *       **when a row gets written**. Each has a failure that produces a plausible wrong number
 *       rather than an error — an archived account still counted, a future-dated rent already
 *       subtracted, a backfill that silently rewrites history — and none of them would show up
 *       anywhere but in the figure itself.
 * What: the as-of query's four exclusions, its relationship to 2.5's current-balance query, and the
 *       backfill's behaviour on a first run, a gap, a same-day re-run and a clock that went back.
 * Result: the storage half of FR-ACC-005 is proven against a real SQL engine.
 * Changelog: 2026-08-01 — Created for issue 2.6.
 *
 * Unencrypted in-memory Room, deliberately — the same reasoning as `DemoModeRepositoryTest`: what is
 * under test is the SQL and the ordering, not SQLCipher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NetWorthRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var repository: NetWorthRepository
    private lateinit var accounts: AccountRepository
    private val clock = FakeClock(initialMillis = MID_MARCH_MILLIS, initialZone = ZoneId.of("Asia/Kolkata"))
    private val activeProfileId = MutableStateFlow(PROFILE)

    /** Input: none. Output: a fresh in-memory database and the two repositories over it. */
    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                CfoDatabase::class.java,
            ).allowMainThreadQueries().build()
        repository =
            RepositoryFactory.netWorth(
                database = database,
                engine = NetWorthEngineFactory.create(),
                clock = clock,
                dispatchers = TestDispatchers(UnconfinedTestDispatcher()),
                activeProfileId = activeProfileId,
            )
        accounts =
            RepositoryFactory.accounts(
                database = database,
                clock = clock,
                ids = FakeIdGenerator(),
                dispatchers = TestDispatchers(UnconfinedTestDispatcher()),
                activeProfileId = activeProfileId,
            )
    }

    /** Input: none. Output: closes the database between tests. */
    @After
    fun tearDown() {
        database.close()
    }

    // --- the computation ---------------------------------------------------------------------------

    @Test
    fun `net worth is assets minus liabilities over the stored accounts`() =
        runTest {
            insertAccount("a:bank", AccountType.BANK, 1_85_000_00L)
            insertAccount("a:cash", AccountType.CASH, 5_000_00L)
            insertAccount("a:card", AccountType.CREDIT_CARD, -18_000_00L)

            val result = repository.computeAsOf(TODAY).expectOk()

            assertEquals(Money(1_90_000_00L), result.assets)
            assertEquals(Money(18_000_00L), result.liabilities)
            assertEquals(Money(1_72_000_00L), result.netWorth)
        }

    @Test
    fun `a profile with no accounts has a net worth of zero, not an error`() =
        runTest {
            assertEquals(Money.ZERO, repository.computeAsOf(TODAY).expectOk().netWorth)
        }

    @Test
    fun `transactions move net worth`() =
        runTest {
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)
            insertTransaction("t1", "a:bank", -25_000_00L, TODAY)

            assertEquals(Money(75_000_00L), repository.computeAsOf(TODAY).expectOk().netWorth)
        }

    @Test
    fun `an account whose stored type this build cannot parse is skipped, not thrown on`() =
        runTest {
            // A row from a newer build. A figure missing one unknown account is closer to the truth
            // than a crashed dashboard.
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)
            database.accountDao().upsert(rawAccount("a:future", "timeshare", 9_99_999_00L))

            assertEquals(Money(1_00_000_00L), repository.computeAsOf(TODAY).expectOk().netWorth)
        }

    // --- which accounts count ------------------------------------------------------------------------

    @Test
    fun `an archived account is excluded from net worth`() =
        runTest {
            // FR-ACC-007: "excluded from active totals".
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)
            insertAccount("a:old", AccountType.BANK, 50_000_00L)
            accounts.setArchived("a:old", archived = true)

            assertEquals(Money(1_00_000_00L), repository.computeAsOf(TODAY).expectOk().netWorth)
        }

    @Test
    fun `an account opted out of net worth is excluded`() =
        runTest {
            // Still open, still transacting, just not the user's to count.
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)
            insertAccount("a:company", AccountType.BANK, 50_000_00L, includeInNetWorth = false)

            assertEquals(Money(1_00_000_00L), repository.computeAsOf(TODAY).expectOk().netWorth)
        }

    @Test
    fun `a soft-deleted account is excluded`() =
        runTest {
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)
            insertAccount("a:gone", AccountType.BANK, 50_000_00L)
            accounts.delete("a:gone")

            assertEquals(Money(1_00_000_00L), repository.computeAsOf(TODAY).expectOk().netWorth)
        }

    @Test
    fun `another profile's accounts never count`() =
        runTest {
            insertAccount("a:mine", AccountType.BANK, 1_00_000_00L)
            insertAccount("a:theirs", AccountType.BANK, 9_00_000_00L, profileId = "someone-else")

            assertEquals(Money(1_00_000_00L), repository.computeAsOf(TODAY).expectOk().netWorth)
        }

    // --- which day's transactions count ---------------------------------------------------------------

    @Test
    fun `a future-dated transaction is not subtracted from today's net worth`() =
        runTest {
            // The reason this query bounds by date at all. Issue 3.4 lands future-dated transactions;
            // rent scheduled for next week has not been paid, and today's net worth must not say it
            // has. Written now so the behaviour is correct the day those rows first exist.
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)
            insertTransaction("t:future", "a:bank", -30_000_00L, bookedOn = "2026-04-01")

            assertEquals(Money(1_00_000_00L), repository.computeAsOf(TODAY).expectOk().netWorth)
        }

    @Test
    fun `a soft-deleted transaction does not count`() =
        runTest {
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)
            insertTransaction("t1", "a:bank", -30_000_00L, TODAY, deleted = true)

            assertEquals(Money(1_00_000_00L), repository.computeAsOf(TODAY).expectOk().netWorth)
        }

    @Test
    fun `an earlier date sees only the transactions booked by then`() =
        runTest {
            // What makes the backfill honest: the same query with an older date reconstructs that
            // day exactly, rather than applying today's balance to a past label.
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)
            insertTransaction("t:mar10", "a:bank", -10_000_00L, "2026-03-10")
            insertTransaction("t:mar16", "a:bank", -5_000_00L, "2026-03-16")

            assertEquals(Money(1_00_000_00L), repository.computeAsOf("2026-03-09").expectOk().netWorth)
            assertEquals(Money(90_000_00L), repository.computeAsOf("2026-03-10").expectOk().netWorth)
            assertEquals(Money(85_000_00L), repository.computeAsOf(TODAY).expectOk().netWorth)
        }

    @Test
    fun `the as-of and current-balance queries agree when nothing is excluded`() =
        runTest {
            // The two derivations differ deliberately (archived, opted-out and future-dated rows),
            // and this pins the case where they must not: an ordinary account with past
            // transactions. A divergence here would mean the accounts list and the dashboard
            // disagree about the same money.
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)
            insertAccount("a:card", AccountType.CREDIT_CARD, -18_000_00L)
            insertTransaction("t1", "a:bank", -25_000_00L, "2026-03-01")
            insertTransaction("t2", "a:card", -2_000_00L, "2026-03-02")

            val fromAccountsList =
                accounts.observeAccounts(PROFILE).first().fold(Money.ZERO) { running, a -> running + a.balance }

            assertEquals(fromAccountsList, repository.computeAsOf(TODAY).expectOk().netWorth)
        }

    // --- the snapshot and its backfill -----------------------------------------------------------------

    @Test
    fun `a first ever run writes today only`() =
        runTest {
            // No earlier snapshot to walk from, and inventing a history the user never had the app
            // for would be fabricating data (P-03).
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)

            assertEquals(1, repository.snapshotUpToToday().expectOk())

            assertEquals(listOf(TODAY), storedDates())
        }

    @Test
    fun `the stored row carries all three figures and the engine that produced them`() =
        runTest {
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)
            insertAccount("a:card", AccountType.CREDIT_CARD, -18_000_00L)
            repository.snapshotUpToToday()

            val row = database.netWorthSnapshotDao().findForDate(PROFILE, TODAY)

            assertNotNull(row)
            assertEquals(1_00_000_00L, row!!.assetsMinor)
            assertEquals(18_000_00L, row.liabilitiesMinor)
            assertEquals(82_000_00L, row.netWorthMinor)
            assertEquals("AI-ARC-006: the row must stay explainable", "net-worth", row.engineId)
            assertEquals("1.0", row.engineVersion)
        }

    @Test
    fun `running twice in a day writes one row, not two`() =
        runTest {
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)
            repository.snapshotUpToToday()

            assertEquals("nothing left to write", 0, repository.snapshotUpToToday().expectOk())

            assertEquals(listOf(TODAY), storedDates())
        }

    @Test
    fun `a gap is backfilled one row per missing day`() =
        runTest {
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)
            // The phone was off, or the app locked, since the 14th.
            insertSnapshot("2026-03-14", 1_00_000_00L)

            assertEquals("the 15th, 16th and 17th", 3, repository.snapshotUpToToday().expectOk())

            assertEquals(listOf("2026-03-14", "2026-03-15", "2026-03-16", TODAY), storedDates())
        }

    @Test
    fun `a backfilled day is computed from the transactions booked by that day`() =
        runTest {
            // Not today's balance stamped onto an older label — that would make the trend a
            // flat line ending in a step, which is exactly the drift a stored snapshot prevents.
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)
            insertTransaction("t:mar16", "a:bank", -20_000_00L, "2026-03-16")
            insertSnapshot("2026-03-15", 1_00_000_00L)

            repository.snapshotUpToToday()

            assertEquals(1_00_000_00L, snapshotMinor("2026-03-15"))
            assertEquals("the 16th sees its own transaction", 80_000_00L, snapshotMinor("2026-03-16"))
            assertEquals(80_000_00L, snapshotMinor(TODAY))
        }

    @Test
    fun `the day already stored is never recomputed`() =
        runTest {
            // Overwriting it would replace a historical figure with one derived from today's
            // accounts. The backfill starts the day *after* the last stored one for this reason.
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)
            insertSnapshot("2026-03-16", 42L)

            repository.snapshotUpToToday()

            assertEquals("the deliberately odd stored figure must survive", 42L, snapshotMinor("2026-03-16"))
        }

    @Test
    fun `a snapshot dated in the future stops the backfill rather than rewriting it`() =
        runTest {
            // The clock moved backwards — a manual change, or a time-zone correction. Recording
            // nothing is the honest response; replacing a newer figure with an older one is not.
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)
            insertSnapshot("2026-04-01", 7L)

            assertEquals(0, repository.snapshotUpToToday().expectOk())

            assertEquals(7L, snapshotMinor("2026-04-01"))
        }

    @Test
    fun `a long gap is capped so one run cannot write hundreds of days`() =
        runTest {
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)
            insertSnapshot("2020-01-01", 0L)

            val written = repository.snapshotUpToToday().expectOk()

            assertEquals(NetWorthRepository.MAX_BACKFILL_DAYS, written)
            // The next run continues from where this one stopped — nothing is lost, it just takes
            // more than one run to catch up.
            assertTrue(repository.snapshotUpToToday().expectOk() > 0)
        }

    // --- reading it back --------------------------------------------------------------------------------

    @Test
    fun `no snapshot yet reads as null, never as zero`() =
        runTest {
            // A user who onboarded a minute ago has no snapshot. Rendering that as ₹0 would be a
            // figure the app made up (P-03).
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)

            assertNull(repository.observeLatest().first())
        }

    @Test
    fun `the latest snapshot is the one read back`() =
        runTest {
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)
            insertSnapshot("2026-03-15", 11L)
            insertSnapshot("2026-03-16", 22L)

            val latest = repository.observeLatest().first()

            assertEquals("2026-03-16", latest?.asOfIsoDate)
            assertEquals(Money(22L), latest?.netWorth)
        }

    @Test
    fun `the read follows the active profile`() =
        runTest {
            insertSnapshot("2026-03-16", 100L)
            insertSnapshot("2026-03-16", 900L, profileId = DEMO_PROFILE)

            assertEquals(Money(100L), repository.observeLatest().first()?.netWorth)

            activeProfileId.value = DEMO_PROFILE
            assertEquals(Money(900L), repository.observeLatest().first()?.netWorth)
        }

    @Test
    fun `a snapshot lands under whichever profile is active at the time`() =
        runTest {
            activeProfileId.value = DEMO_PROFILE
            insertAccount("a:demo", AccountType.BANK, 5_000_00L, profileId = DEMO_PROFILE)

            repository.snapshotUpToToday()

            assertNull("the real profile must be untouched", database.netWorthSnapshotDao().findForDate(PROFILE, TODAY))
            assertNotNull(database.netWorthSnapshotDao().findForDate(DEMO_PROFILE, TODAY))
        }

    // --- the live figure the dashboard shows (issue 2.6) ------------------------------------------

    @Test
    fun `the live figure moves the moment an account is deleted`() =
        runTest {
            // **The test this issue was missing.** Every assertion above was about the stored
            // snapshot, which is correct and a day stale — so the dashboard sat unchanged after an
            // account was deleted, and only driving the app on a device revealed it.
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)
            insertAccount("a:fund", AccountType.INVESTMENT, 1_20_000_00L)
            assertEquals(Money(2_20_000_00L), repository.observeCurrent().first().netWorth)

            accounts.delete("a:fund")

            assertEquals(Money(1_00_000_00L), repository.observeCurrent().first().netWorth)
        }

    @Test
    fun `the live figure moves when an account is archived`() =
        runTest {
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)
            insertAccount("a:old", AccountType.BANK, 50_000_00L)

            accounts.setArchived("a:old", archived = true)

            assertEquals(Money(1_00_000_00L), repository.observeCurrent().first().netWorth)
        }

    @Test
    fun `the live figure and the stored snapshot agree at the moment it is taken`() =
        runTest {
            // They diverge afterwards, by design — that is what "historical record" means. What must
            // never differ is the set of accounts each counts, which this pins.
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)
            insertAccount("a:card", AccountType.CREDIT_CARD, -18_000_00L)
            insertAccount("a:company", AccountType.BANK, 9_00_000_00L, includeInNetWorth = false)

            repository.snapshotUpToToday()

            assertEquals(
                repository.observeCurrent().first().netWorth,
                repository.observeLatest().first()?.netWorth,
            )
        }

    @Test
    fun `a profile with no accounts has a live net worth of zero, not an absence`() =
        runTest {
            // Distinct from the stored side, where null means "no snapshot yet". Here there is
            // nothing to wait for: the answer is zero.
            assertEquals(Money.ZERO, repository.observeCurrent().first().netWorth)
        }

    // --- fixtures ---------------------------------------------------------------------------------------

    /** Result: an account row. Input: see the parameters. Output: none (suspends). */
    private suspend fun insertAccount(
        id: String,
        type: AccountType,
        openingBalanceMinor: Long,
        profileId: String = PROFILE,
        includeInNetWorth: Boolean = true,
    ) {
        database.accountDao().upsert(
            AccountEntity(
                id = id,
                profileId = profileId,
                name = id,
                type = type.storedValue,
                openingBalanceMinor = openingBalanceMinor,
                // Deliberately a lie: nothing may read this column, so a wrong value here proves the
                // computation is not quietly falling back to it (DB-001, ADR-0007).
                currentBalanceMinor = Long.MIN_VALUE / 2,
                currencyCode = "INR",
                includeInNetWorth = includeInNetWorth,
                createdAtUtcMillis = clock.nowUtcMillis(),
                updatedAtUtcMillis = clock.nowUtcMillis(),
            ),
        )
    }

    /** Result: an account with a type this build does not know. Input: [id], [type], [opening]. */
    private fun rawAccount(
        id: String,
        type: String,
        opening: Long,
    ) = AccountEntity(
        id = id,
        profileId = PROFILE,
        name = id,
        type = type,
        openingBalanceMinor = opening,
        currentBalanceMinor = opening,
        currencyCode = "INR",
        createdAtUtcMillis = clock.nowUtcMillis(),
        updatedAtUtcMillis = clock.nowUtcMillis(),
    )

    // --- repairing back-dated history (ADR-0012) ---------------------------------------------------

    @Test
    fun `a back-dated transaction makes the days it lands in stale`() =
        runTest {
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)
            repository.snapshotUpToToday().expectOk()

            // Booked into a day already snapshotted, and written *after* that day was computed —
            // which is exactly the condition `findEarliestStaleDay` derives.
            clock.advanceBy(ONE_MINUTE)
            insertTransaction("t:backdated", "a:bank", -5_000_00L, bookedOn = TODAY)

            assertEquals(TODAY, database.netWorthSnapshotDao().findEarliestStaleDay(PROFILE))
        }

    @Test
    fun `the repair rewrites the stale day with the figure it should always have had`() =
        runTest {
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)
            repository.snapshotUpToToday().expectOk()
            assertEquals(Money(1_00_000_00L), repository.observeLatest().first()!!.netWorth)

            clock.advanceBy(ONE_MINUTE)
            insertTransaction("t:backdated", "a:bank", -5_000_00L, bookedOn = TODAY)

            assertEquals(1, repository.repairStaleHistory().expectOk())
            assertEquals(
                "the stored history must agree with the ledger it is derived from",
                Money(95_000_00L),
                repository.observeLatest().first()!!.netWorth,
            )
        }

    @Test
    fun `deleting an old transaction also makes its days stale`() =
        runTest {
            // `softDelete` deliberately does not touch `updated_at`, so the detection needs its
            // `deleted_at` term — without it a removed row would silently leave history overstated.
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)
            insertTransaction("t:old", "a:bank", -5_000_00L, bookedOn = TODAY)
            repository.snapshotUpToToday().expectOk()

            clock.advanceBy(ONE_MINUTE)
            database.transactionDao().softDelete("t:old", clock.nowUtcMillis())

            assertEquals(1, repository.repairStaleHistory().expectOk())
            assertEquals(Money(1_00_000_00L), repository.observeLatest().first()!!.netWorth)
        }

    @Test
    fun `a run with nothing stale rewrites nothing`() =
        runTest {
            // The normal case, and the one that matters most: a frozen series must stay frozen
            // (FR-ACC-005). If this ever wrote a row, every trend in the app would move on its own.
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)
            repository.snapshotUpToToday().expectOk()

            assertEquals(0, repository.repairStaleHistory().expectOk())
            assertNull(database.netWorthSnapshotDao().findEarliestStaleDay(PROFILE))
        }

    @Test
    fun `a repaired day stops being reported as stale`() =
        runTest {
            // What makes the cap safe: successive runs converge rather than repeating the same work.
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)
            repository.snapshotUpToToday().expectOk()
            clock.advanceBy(ONE_MINUTE)
            insertTransaction("t:backdated", "a:bank", -5_000_00L, bookedOn = TODAY)
            repository.repairStaleHistory().expectOk()

            assertEquals(0, repository.repairStaleHistory().expectOk())
        }

    @Test
    fun `a transaction booked before any snapshot corrects the stored days and invents no others`() =
        runTest {
            // A 2020 purchase really does change what today is worth — net worth as at a day counts
            // every transaction booked on or before it — so today's stored figure is stale and gets
            // rewritten. What must **not** happen is a snapshot appearing for 2020: reconstructing
            // years the user never had the app for would be inventing data (P-03), the same argument
            // `snapshotUpToToday` makes for its first ever run.
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)
            repository.snapshotUpToToday().expectOk()

            clock.advanceBy(ONE_MINUTE)
            insertTransaction("t:ancient", "a:bank", -5_000_00L, bookedOn = "2020-01-01")

            assertEquals("only the one stored day", 1, repository.repairStaleHistory().expectOk())
            assertEquals(Money(95_000_00L), repository.observeLatest().first()!!.netWorth)
            assertNull(
                "no history may be conjured for a day the app never saw",
                database.netWorthSnapshotDao().findForDate(PROFILE, "2020-01-01"),
            )
        }

    @Test
    fun `another profile's back-dated row never touches this one's history`() =
        runTest {
            insertAccount("a:bank", AccountType.BANK, 1_00_000_00L)
            repository.snapshotUpToToday().expectOk()

            clock.advanceBy(ONE_MINUTE)
            insertTransaction("t:demo", "a:bank", -5_000_00L, bookedOn = TODAY, profileId = DEMO_PROFILE)

            assertEquals(0, repository.repairStaleHistory().expectOk())
        }

    /**
     * Result: a transaction row. Input: see the parameters. Output: none (suspends).
     *
     * `@Suppress("LongParameterList")`: a row fixture — the count is the table's, not a design choice.
     */
    @Suppress("LongParameterList")
    private suspend fun insertTransaction(
        id: String,
        accountId: String,
        amountMinor: Long,
        bookedOn: String,
        deleted: Boolean = false,
        profileId: String = PROFILE,
    ) {
        database.transactionDao().upsert(
            TransactionEntity(
                id = id,
                profileId = profileId,
                accountId = accountId,
                amountMinor = amountMinor,
                currencyCode = "INR",
                occurredAtUtcMillis = clock.nowUtcMillis(),
                bookedOnIsoDate = bookedOn,
                source = "manual",
                // Issue 3.2: the fixture writes plain outflows, so they are expenses.
                type = "expense",
                createdAtUtcMillis = clock.nowUtcMillis(),
                updatedAtUtcMillis = clock.nowUtcMillis(),
                deletedAtUtcMillis = if (deleted) clock.nowUtcMillis() else null,
            ),
        )
    }

    /**
     * Result: a snapshot row placed directly, so a test can stage a gap or a stale figure.
     * Input: [asOfIsoDate], [netWorthMinor], [profileId]. Output: none (suspends).
     */
    private suspend fun insertSnapshot(
        asOfIsoDate: String,
        netWorthMinor: Long,
        profileId: String = PROFILE,
    ) {
        database.netWorthSnapshotDao().upsertAll(
            listOf(
                com.aicfo.core.database.entity.NetWorthSnapshotEntity(
                    id = netWorthSnapshotId(profileId, asOfIsoDate),
                    profileId = profileId,
                    asOfIsoDate = asOfIsoDate,
                    assetsMinor = netWorthMinor,
                    liabilitiesMinor = 0L,
                    netWorthMinor = netWorthMinor,
                    engineId = "net-worth",
                    engineVersion = "1.0",
                    computedAtUtcMillis = clock.nowUtcMillis(),
                    createdAtUtcMillis = clock.nowUtcMillis(),
                    updatedAtUtcMillis = clock.nowUtcMillis(),
                ),
            ),
        )
    }

    /** Result: every stored day for the active profile, oldest first. Output: `List<String>`. */
    private fun storedDates(): List<String> =
        database.query(
            "SELECT as_of_iso_date FROM net_worth_snapshot WHERE profile_id = ? ORDER BY as_of_iso_date",
            arrayOf<Any>(PROFILE),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    /** Result: one day's stored net worth in paise. Input: [asOfIsoDate]. Output: `Long`. */
    private suspend fun snapshotMinor(asOfIsoDate: String): Long =
        database.netWorthSnapshotDao().findForDate(PROFILE, asOfIsoDate)?.netWorthMinor
            ?: error("no snapshot for $asOfIsoDate")

    private companion object {
        const val PROFILE = "local"
        const val DEMO_PROFILE = "demo"

        /** 2026-03-17, 11:30 IST — mid-month, so a backfill has room on both sides. */
        val MID_MARCH_MILLIS: Long = Instant.parse("2026-03-17T06:00:00Z").toEpochMilli()

        /** The profile-zone day [MID_MARCH_MILLIS] falls on. */
        const val TODAY = "2026-03-17"

        /**
         * Enough to put a write strictly after a snapshot's `computed_at`, without crossing midnight.
         *
         * The repair's whole detection is a `>` on two timestamps, so a fixture that wrote a
         * transaction at the same millisecond as the snapshot would report nothing stale and the
         * test would pass for the wrong reason.
         */
        val ONE_MINUTE: Duration = Duration.ofMinutes(1)
    }
}

/**
 * Unwraps a result the test expects to have succeeded.
 * Result: the value. Input: the receiver. Output: [T].
 * Changelog: 2026-08-01 — Created for issue 2.6.
 */
private fun <T> Result<T, AppError>.expectOk(): T =
    when (this) {
        is Ok -> value
        is Err -> throw AssertionError("expected Ok, got Err(${error.code})")
    }
