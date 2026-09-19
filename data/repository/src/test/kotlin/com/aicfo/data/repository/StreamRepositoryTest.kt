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
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.AccountEntity
import com.aicfo.core.database.entity.CategoryEntity
import com.aicfo.core.database.entity.ProfileEntity
import com.aicfo.core.database.entity.RecurringRuleEntity
import com.aicfo.core.database.entity.TransactionEntity
import com.aicfo.core.database.entity.TransactionSplitEntity
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.stream.StreamBasis
import com.aicfo.domain.engines.stream.StreamClass
import com.aicfo.domain.engines.stream.StreamEngineFactory
import com.aicfo.domain.engines.stream.StreamProfile
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

/**
 * AI-CLS Stage 2 fed from the real ledger (issue 9.1; §8.2).
 *
 * Why:  the engine is proven on literal inputs; what only this layer can get wrong is **what a
 *       stream is**. Which rows count (expenses only — not income, not the live month, not a
 *       tombstone), how a split payment is attributed (by its lines, ADR-0018), which categories
 *       are known obligations (a confirmed, live, outflow recurring rule), and which prior a
 *       category carries (the key it was seeded from). Each mistake would give the engine a
 *       well-formed input with the wrong meaning, and nothing downstream could tell.
 * What: the six-closed-month window, row filtering, split attribution, the obligation join, the
 *       prior lookup, and the profile scoping.
 * Result: the stream profile the dashboard and 9.2/9.4 read is built from the right rows.
 * Changelog: 2026-09-19 — Created for issue 9.1.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StreamRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var repository: StreamRepository

    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = FakeClock(initialMillis = Instant.parse("2026-09-19T06:00:00Z").toEpochMilli())
    private val activeProfileId = MutableStateFlow(PROFILE)

    /** Input: none. Output: an in-memory database with a profile and a bank account. */
    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CfoDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository =
            RepositoryFactory.streams(
                database = database,
                engine = StreamEngineFactory.create(),
                clock = clock,
                dispatchers = TestDispatchers(dispatcher),
                activeProfileId = activeProfileId,
            )
    }

    /** Input: none. Output: closed. */
    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `six months of rent on the first classifies FIXED from the ledger`() =
        runTest(dispatcher) {
            seedBasics()
            (3..8).forEach { month -> expense("rent-$month", "2026-%02d-01".format(month), -25_000_00L, RENT) }

            val profile = latest()

            val rent = profile.streams.single()
            assertEquals(RENT, rent.streamKey)
            assertEquals(StreamClass.FIXED, rent.streamClass)
            assertEquals(StreamBasis.SCORED, rent.basis)
            assertEquals(Money(25_000_00L), profile.fixedLoad)
            assertEquals("2026-03-01..2026-08-31", profile.provenance.inputWindow)
        }

    @Test
    fun `only closed-month expenses count — not income, not this month, not before the window, not tombstones`() =
        runTest(dispatcher) {
            seedBasics()
            expense("in-window", "2026-08-10", -1_000_00L, RENT)
            expense("live-month", "2026-09-02", -9_999_00L, RENT)
            expense("too-old", "2026-02-27", -9_999_00L, RENT)
            expense("deleted", "2026-08-11", -9_999_00L, RENT, deletedAt = NOW)
            transaction("salary", "2026-08-01", 90_000_00L, RENT, type = "income")

            val stream = latest().streams.single()

            assertEquals(Money(1_000_00L), stream.typicalMonthly)
        }

    @Test
    fun `a split payment counts under each line's category, not the parent's`() =
        runTest(dispatcher) {
            seedBasics()
            expense("mixed", "2026-08-10", -4_000_00L, category = null)
            database.transactionSplitDao().upsertAll(
                listOf(split("line-1", "mixed", -3_000_00L, GROCERIES), split("line-2", "mixed", -1_000_00L, DINING)),
            )

            val streams = latest().streams.associateBy { it.streamKey }

            assertEquals(setOf(GROCERIES, DINING), streams.keys)
            assertEquals(Money(3_000_00L), streams.getValue(GROCERIES).typicalMonthly)
            assertEquals(Money(1_000_00L), streams.getValue(DINING).typicalMonthly)
        }

    @Test
    fun `a confirmed recurring outflow makes its category a known obligation`() =
        runTest(dispatcher) {
            seedBasics()
            expense("gym", "2026-08-10", -2_000_00L, DINING)
            database.recurringRuleDao().upsertAll(listOf(rule("rule-1", DINING, confirmed = true)))

            val stream = latest().streams.single()

            assertEquals(StreamClass.FIXED, stream.streamClass)
            assertEquals(StreamBasis.KNOWN_OBLIGATION, stream.basis)
        }

    @Test
    fun `a confirmed detected rule makes its merchant's payments their own FIXED stream`() =
        runTest(dispatcher) {
            // Exactly what the device produced: the detector keys a rule by merchant and stores no
            // category (issue 3.7), so a category-only join never fires for it. §8.2 says
            // "transactions linked to recurring rules" — the link is the merchant.
            seedBasics()
            expense("rent-7", "2026-07-03", -28_000_00L, RENT, merchant = "Landlord ")
            expense("rent-8", "2026-08-03", -28_000_00L, RENT, merchant = "landlord")
            expense("repairs", "2026-08-20", -1_500_00L, RENT, merchant = "Plumber")
            database.recurringRuleDao().upsertAll(
                listOf(rule("detected", category = null, confirmed = true, name = "Landlord")),
            )

            val streams = latest().streams.associateBy { it.streamKey }

            val rent = streams.getValue("${StreamRepository.RECURRING_PREFIX}landlord")
            assertEquals(StreamClass.FIXED, rent.streamClass)
            assertEquals(StreamBasis.KNOWN_OBLIGATION, rent.basis)
            assertEquals(Money(28_000_00L), rent.typicalMonthly)
            assertEquals(
                "the rest of the category stays its own, scored stream",
                Money(1_500_00L),
                streams.getValue(RENT).typicalMonthly,
            )
            assertEquals(StreamBasis.COLD_START_PRIOR, streams.getValue(RENT).basis)
        }

    @Test
    fun `an unconfirmed, dismissed, deleted or income rule is not an obligation`() =
        runTest(dispatcher) {
            seedBasics()
            expense("gym", "2026-08-10", -2_000_00L, DINING)
            database.recurringRuleDao().upsertAll(
                listOf(
                    rule("unconfirmed", DINING, confirmed = false),
                    rule("dismissed", DINING, confirmed = true, dismissedAt = NOW),
                    rule("deleted", DINING, confirmed = true, deletedAt = NOW),
                    rule("income", DINING, confirmed = true, amountMinor = 50_000_00L),
                ),
            )

            assertEquals(StreamBasis.COLD_START_PRIOR, latest().streams.single().basis)
        }

    @Test
    fun `a seeded category carries its prior and one the user made does not`() =
        runTest(dispatcher) {
            seedBasics()
            expense("food", "2026-08-10", -2_000_00L, DINING)
            expense("hobby", "2026-08-11", -2_000_00L, CUSTOM)

            val streams = latest().streams.associateBy { it.streamKey }

            assertEquals(StreamBasis.COLD_START_PRIOR, streams.getValue(DINING).basis)
            assertEquals(StreamClass.VARIABLE, streams.getValue(DINING).streamClass)
            assertEquals(StreamBasis.COLD_START_NO_PRIOR, streams.getValue(CUSTOM).basis)
        }

    @Test
    fun `an uncategorised expense is its own stream, with no prior`() =
        runTest(dispatcher) {
            seedBasics()
            expense("mystery", "2026-08-10", -500_00L, category = null)

            val stream = latest().streams.single()

            assertEquals(StreamRepository.UNCATEGORISED, stream.streamKey)
            assertEquals(StreamBasis.COLD_START_NO_PRIOR, stream.basis)
        }

    @Test
    fun `another profile's rows are never read`() =
        runTest(dispatcher) {
            seedBasics()
            expense("theirs", "2026-08-10", -500_00L, RENT, profileId = "demo")

            assertTrue(latest().streams.isEmpty())
        }

    // --- fixtures ---------------------------------------------------------------------------------

    private suspend fun latest(): StreamProfile {
        var profile: StreamProfile? = null
        repository.observeStreams().test {
            profile = awaitItem().expectOk()
            cancelAndIgnoreRemainingEvents()
        }
        return profile!!
    }

    private suspend fun seedBasics() {
        listOf(PROFILE, "demo").forEach { id ->
            database.profileDao().upsert(ProfileEntity(id, "Test", "UTC", "INR", NOW, NOW))
            database.accountDao().upsert(
                AccountEntity(
                    id = "account:$id",
                    profileId = id,
                    name = "Bank",
                    type = "bank",
                    openingBalanceMinor = 0L,
                    currentBalanceMinor = 0L,
                    currencyCode = "INR",
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                ),
            )
        }
        database.categoryDao().upsertAll(
            listOf(RENT to "need", GROCERIES to "need", DINING to "want", CUSTOM to "want").map { (id, nature) ->
                CategoryEntity(id, PROFILE, id, null, nature, isSystem = id != CUSTOM, NOW, NOW)
            },
        )
    }

    @Suppress("LongParameterList") // a fixture builder: each argument is one column a test varies
    private suspend fun expense(
        id: String,
        date: String,
        amountMinor: Long,
        category: String?,
        deletedAt: Long? = null,
        profileId: String = PROFILE,
        merchant: String? = null,
    ) = transaction(id, date, amountMinor, category, "expense", deletedAt, profileId, merchant)

    @Suppress("LongParameterList") // a fixture builder: each argument is one column a test varies
    private suspend fun transaction(
        id: String,
        date: String,
        amountMinor: Long,
        category: String?,
        type: String,
        deletedAt: Long? = null,
        profileId: String = PROFILE,
        merchant: String? = null,
    ) = database.transactionDao().upsert(
        TransactionEntity(
            id = id,
            profileId = profileId,
            accountId = "account:$profileId",
            amountMinor = amountMinor,
            currencyCode = "INR",
            occurredAtUtcMillis = NOW,
            bookedOnIsoDate = date,
            categoryId = category,
            merchant = merchant,
            source = "manual",
            type = type,
            createdAtUtcMillis = NOW,
            updatedAtUtcMillis = NOW,
            deletedAtUtcMillis = deletedAt,
        ),
    )

    private fun split(
        id: String,
        parent: String,
        amountMinor: Long,
        category: String,
    ) = TransactionSplitEntity(
        id = id,
        profileId = PROFILE,
        transactionId = parent,
        amountMinor = amountMinor,
        categoryId = category,
        createdAtUtcMillis = NOW,
        updatedAtUtcMillis = NOW,
    )

    @Suppress("LongParameterList") // a fixture builder: each argument is one column a test varies
    private fun rule(
        id: String,
        category: String?,
        confirmed: Boolean,
        dismissedAt: Long? = null,
        deletedAt: Long? = null,
        amountMinor: Long = -2_000_00L,
        name: String = id,
    ) = RecurringRuleEntity(
        id = id,
        profileId = PROFILE,
        accountId = "account:$PROFILE",
        categoryId = category,
        name = name,
        amountMinor = amountMinor,
        cadence = "monthly",
        nextDueIsoDate = "2026-09-10",
        source = "detected",
        isConfirmed = confirmed,
        dismissedAtUtcMillis = dismissedAt,
        createdAtUtcMillis = NOW,
        updatedAtUtcMillis = NOW,
        deletedAtUtcMillis = deletedAt,
    )

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val PROFILE = "local"
        const val NOW = 1_789_800_000_000L

        // Seeded ids follow CategoryRepository's `<profile>:category:<key>` shape, which is how the
        // repository finds a category's prior; CUSTOM is shaped like one the user made.
        const val RENT = "local:category:rent"
        const val GROCERIES = "local:category:groceries"
        const val DINING = "local:category:dining"
        const val CUSTOM = "local:user:pottery"
    }
}
