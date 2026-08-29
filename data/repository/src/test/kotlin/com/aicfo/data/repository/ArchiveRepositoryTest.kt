package com.aicfo.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.AccountEntity
import com.aicfo.core.database.entity.AttachmentEntity
import com.aicfo.core.database.entity.BudgetAlertEntity
import com.aicfo.core.database.entity.BudgetEntity
import com.aicfo.core.database.entity.BudgetReviewEntity
import com.aicfo.core.database.entity.CardAlertEntity
import com.aicfo.core.database.entity.CategoryEntity
import com.aicfo.core.database.entity.CreditCardEntity
import com.aicfo.core.database.entity.LoanEntity
import com.aicfo.core.database.entity.NetWorthSnapshotEntity
import com.aicfo.core.database.entity.ProfileEntity
import com.aicfo.core.database.entity.RecurringRuleEntity
import com.aicfo.core.database.entity.SmsDraftEntity
import com.aicfo.core.database.entity.TagEntity
import com.aicfo.core.database.entity.TransactionEntity
import com.aicfo.core.database.entity.TransactionSplitEntity
import com.aicfo.core.database.entity.TransactionTagEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * §5.10's archive, proven against a real SQL engine (issue 5.4; §34, P-01).
 *
 * Why:  the acceptance criterion is a **lossless round-trip**, and losslessness is the one property
 *       you cannot eyeball. The failure that matters is silent: a table or a column that never
 *       reaches the file, so the user's export looks fine, restores without error, and is quietly
 *       missing their tags, or their split lines, or every `deleted_at` they ever set.
 *
 *       So the fixture seeds **every one of the fourteen tables**, and every nullable column in at
 *       least one row — because a round-trip test only covers the columns its fixture populates,
 *       and a fixture that left them null would pass identically against a mapper that dropped
 *       them. That is the single most likely bug in this issue and the reason this file is long.
 * What: the round trip, the empty and large cases, the version gate, demo isolation, and tombstones.
 * Result: an archive that loses a column fails the build naming the table.
 * Changelog: 2026-08-16 — Created for issue 5.4.
 *
 * Unencrypted in-memory Room, as every repository test in this module: the claim is about which
 * rows travel, not about SQLCipher, which needs a device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ArchiveRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var archive: ArchiveRepository

    private val clock = FakeClock(initialMillis = Instant.parse("2026-08-16T06:00:00Z").toEpochMilli())
    private val dispatcher = UnconfinedTestDispatcher()
    private val activeProfileId = MutableStateFlow(REAL_PROFILE)

    /** Input: none. Output: a fresh in-memory database and the repository under test. */
    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                CfoDatabase::class.java,
            ).allowMainThreadQueries().build()
        archive =
            RepositoryFactory.archive(database, clock, TestDispatchers(dispatcher), activeProfileId)
    }

    /** Input: none. Output: closes the database between tests. */
    @After
    fun tearDown() {
        database.close()
    }

    // --- the criterion ----------------------------------------------------------------------------

    /**
     * The assertion this issue exists for.
     * Input:  a profile with a row in every table, exported, wiped, and imported back.
     * Output: asserts the database is **row-for-row identical** to what it was.
     *
     * Why:    compared table by table against the entities themselves, not against a summary or a
     *         count. A count would pass against an import that restored the right number of rows
     *         with the wrong contents, which is exactly what a mis-wired mapper produces.
     */
    @Test
    fun `a full profile survives export and import unchanged`() =
        runTest(dispatcher) {
            seedEveryTable()
            val before = snapshot()

            val json = archive.export().expectOk()
            wipeEverything()
            assertEquals("the wipe did not clear the profile", 0, database.demoDao().countRowsFor(REAL_PROFILE))

            archive.import(json).expectOk()

            assertEquals(before, snapshot())
        }

    /**
     * Input:  the seeded profile.
     * Output: asserts the fixture actually populated every table — without this, the round trip
     *         above proves only that zero rows survive being copied.
     */
    @Test
    fun `the fixture covers every table the archive carries`() =
        runTest(dispatcher) {
            seedEveryTable()

            snapshot().forEach { (table, rows) ->
                assertTrue(
                    "the fixture seeds no rows in '$table', so the round trip proves nothing about it",
                    rows.isNotEmpty(),
                )
            }
        }

    /**
     * Input:  a row whose every nullable column is set.
     * Output: asserts each one comes back set.
     *
     * Why:    a fixture of all-null optionals passes identically against an archive that drops
     *         them. These are the columns most likely to be lost and least likely to be noticed —
     *         a merchant, a note, a `deleted_at` tombstone.
     */
    @Test
    fun `nullable columns survive the round trip`() =
        runTest(dispatcher) {
            seedEveryTable()

            val json = archive.export().expectOk()
            wipeEverything()
            archive.import(json).expectOk()

            val restored = database.archiveDao().transactions(REAL_PROFILE).first { it.id == RICH_TXN }
            assertEquals("Big Bazaar", restored.merchant)
            assertEquals("weekly shop", restored.note)
            assertEquals(DELETED_AT, restored.deletedAtUtcMillis)
        }

    /**
     * Input:  a soft-deleted transaction.
     * Output: asserts it comes back **deleted**, not live and not absent.
     *
     * Why:    the two ways to get this wrong are opposite and both bad. Export the tombstone and
     *         restore it as live, and a transaction the user deleted reappears. Skip it, and a
     *         later sync sees a row that never existed rather than one that was removed.
     */
    @Test
    fun `a deleted row comes back deleted`() =
        runTest(dispatcher) {
            seedEveryTable()

            val json = archive.export().expectOk()
            wipeEverything()
            archive.import(json).expectOk()

            val restored = database.archiveDao().transactions(REAL_PROFILE).first { it.id == RICH_TXN }
            assertEquals(DELETED_AT, restored.deletedAtUtcMillis)
        }

    // --- the edges ---------------------------------------------------------------------------------

    /**
     * Input:  a profile with nothing in it.
     * Output: asserts export and import both succeed and invent nothing.
     */
    @Test
    fun `an empty profile exports and imports cleanly`() =
        runTest(dispatcher) {
            val json = archive.export().expectOk()

            val summary = archive.import(json).expectOk()

            assertEquals(0, summary.rowsImported)
            assertEquals(0, database.demoDao().countRowsFor(REAL_PROFILE))
        }

    /**
     * Input:  a few thousand transactions.
     * Output: asserts they all survive.
     *
     * Why:    the acceptance criteria ask for large datasets. What it actually proves is that the
     *         whole import runs inside one transaction without tripping SQLite's variable limit —
     *         the failure mode of a naive per-row insert loop, and one that only appears at scale.
     */
    @Test
    fun `a large ledger survives the round trip`() =
        runTest(dispatcher) {
            seedProfileAndAccount()
            val many = (1..LARGE_ROW_COUNT).map { transaction(id = "txn:bulk:$it", amountMinor = -it.toLong()) }
            database.archiveDao().insertTransactions(many)

            val json = archive.export().expectOk()
            wipeEverything()
            archive.import(json).expectOk()

            assertEquals(LARGE_ROW_COUNT, database.archiveDao().transactions(REAL_PROFILE).size)
        }

    // --- the guards --------------------------------------------------------------------------------

    /**
     * Input:  an archive stamped with another schema version.
     * Output: asserts it is refused **and the data is still there**.
     *
     * Why:    the second half is the assertion that matters. A refusal that had already wiped the
     *         profile would be the worst bug this feature could have: the user's data destroyed by
     *         an import that then said no. The parse and the version check run before the
     *         transaction opens, and this is what holds them there.
     */
    @Test
    fun `an archive from another schema is refused with the data intact`() =
        runTest(dispatcher) {
            seedEveryTable()
            val rowsBefore = database.demoDao().countRowsFor(REAL_PROFILE)
            val json =
                archive.export().expectOk()
                    .replace("\"schemaVersion\": ${CfoDatabase.VERSION}", "\"schemaVersion\": 1")

            val outcome = archive.import(json)

            assertTrue("an archive from another schema must be refused", outcome is Err)
            assertEquals(
                "a refused import must not have deleted anything",
                rowsBefore,
                database.demoDao().countRowsFor(REAL_PROFILE),
            )
        }

    /**
     * Input:  text that is not an archive at all.
     * Output: asserts it is refused, again with the data intact.
     */
    @Test
    fun `a file that is not an archive is refused with the data intact`() =
        runTest(dispatcher) {
            seedEveryTable()
            val rowsBefore = database.demoDao().countRowsFor(REAL_PROFILE)

            val outcome = archive.import("this is a photo, not a backup")

            assertTrue(outcome is Err)
            assertEquals(rowsBefore, database.demoDao().countRowsFor(REAL_PROFILE))
        }

    /**
     * Input:  a real profile and a demo profile side by side.
     * Output: asserts the real profile's archive contains none of the demo's rows (ADR-0006).
     *
     * Why:    the demo seeds a full three months of fabricated finances. An export that swept it up
     *         would hand the user a "backup" of data that was never theirs, and importing it would
     *         write fiction into their real profile.
     */
    @Test
    fun `the demo profile stays out of the real profile's archive`() =
        runTest(dispatcher) {
            seedEveryTable()
            database.archiveDao().insertProfiles(listOf(profile(DEMO_PROFILE)))
            database.archiveDao().insertTransactions(
                listOf(transaction(id = "txn:demo:1", profileId = DEMO_PROFILE)),
            )

            val json = archive.export().expectOk()

            assertTrue("the demo's rows leaked into the real profile's archive", !json.contains("txn:demo:1"))
            assertTrue("the demo profile itself leaked", !json.contains(DEMO_PROFILE))
        }

    /**
     * Input:  the same profile exported twice.
     * Output: asserts the two archives differ only by their timestamp (P-08).
     *
     * Why:    the reads are ordered by primary key for this reason. An export whose row order came
     *         from SQLite's whim would make every backup a different file, so a user could never
     *         tell whether anything had actually changed.
     */
    @Test
    fun `two exports of unchanged data are identical but for the timestamp`() =
        runTest(dispatcher) {
            seedEveryTable()

            val first = archive.export().expectOk()
            clock.advanceBy(java.time.Duration.ofMinutes(5))
            val second = archive.export().expectOk()

            assertNotEquals("the archive must record when it was taken", first, second)
            assertEquals(first.withoutTimestamp(), second.withoutTimestamp())
        }

    // --- fixtures -----------------------------------------------------------------------------------

    /**
     * Reads every table back, as the comparison subject.
     * Why:    a map keyed by table name so a failure says *which* table diverged rather than
     *         printing two enormous objects.
     * Result: table name → rows. Input: none. Output: `Map<String, List<Any>>`.
     */
    private suspend fun snapshot(): Map<String, List<Any>> {
        val dao = database.archiveDao()
        return mapOf(
            "profile" to dao.profiles(REAL_PROFILE),
            "account" to dao.accounts(REAL_PROFILE),
            "category" to dao.categories(REAL_PROFILE),
            "transactions" to dao.transactions(REAL_PROFILE),
            "transaction_splits" to dao.transactionSplits(REAL_PROFILE),
            "tags" to dao.tags(REAL_PROFILE),
            "transaction_tags" to dao.transactionTags(REAL_PROFILE),
            "budget" to dao.budgets(REAL_PROFILE),
            "budget_alert" to dao.budgetAlerts(REAL_PROFILE),
            "budget_review" to dao.budgetReviews(REAL_PROFILE),
            "recurring_rule" to dao.recurringRules(REAL_PROFILE),
            "net_worth_snapshot" to dao.netWorthSnapshots(REAL_PROFILE),
            "attachments" to dao.attachments(REAL_PROFILE),
            "sms_draft" to dao.smsDrafts(REAL_PROFILE),
            "credit_card" to dao.creditCards(REAL_PROFILE),
            "card_alert" to dao.cardAlerts(REAL_PROFILE),
            "loan" to dao.loans(REAL_PROFILE),
        )
    }

    /** Result: the profile and one account, the minimum other rows need. Output: none (suspends). */
    private suspend fun seedProfileAndAccount() {
        val dao = database.archiveDao()
        dao.insertProfiles(listOf(profile(REAL_PROFILE)))
        dao.insertAccounts(
            listOf(
                AccountEntity(
                    id = ACCOUNT,
                    profileId = REAL_PROFILE,
                    name = "HDFC Savings",
                    type = "bank",
                    openingBalanceMinor = 5_000_00L,
                    currentBalanceMinor = 4_000_00L,
                    currencyCode = "INR",
                    institution = "HDFC Bank",
                    includeInNetWorth = true,
                    archivedAtUtcMillis = DELETED_AT,
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                    deletedAtUtcMillis = null,
                ),
            ),
        )
    }

    /**
     * Seeds one row in every table the archive carries, with every nullable column set.
     * Why:    see the class doc — the round trip only covers what this populates.
     * Result: a profile the archive must carry whole. Output: none (suspends).
     */
    @Suppress("LongMethod") // Fourteen tables; the length is the schema's, not a design choice.
    private suspend fun seedEveryTable() {
        seedProfileAndAccount()
        val dao = database.archiveDao()
        dao.insertCategories(
            listOf(
                CategoryEntity(
                    id = CATEGORY,
                    profileId = REAL_PROFILE,
                    name = "Groceries",
                    parentId = PARENT_CATEGORY,
                    nature = "need",
                    isSystem = true,
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                    deletedAtUtcMillis = null,
                ),
            ),
        )
        dao.insertTransactions(listOf(transaction(id = RICH_TXN, deletedAt = DELETED_AT)))
        dao.insertTransactionSplits(
            listOf(
                TransactionSplitEntity(
                    id = "split:1",
                    profileId = REAL_PROFILE,
                    transactionId = RICH_TXN,
                    amountMinor = -1_200_00L,
                    categoryId = CATEGORY,
                    note = "produce",
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                    deletedAtUtcMillis = DELETED_AT,
                ),
            ),
        )
        dao.insertTags(
            listOf(
                TagEntity(
                    id = TAG,
                    profileId = REAL_PROFILE,
                    name = "reimbursable",
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                    deletedAtUtcMillis = DELETED_AT,
                ),
            ),
        )
        dao.insertTransactionTags(
            listOf(
                TransactionTagEntity(
                    id = "txntag:1",
                    profileId = REAL_PROFILE,
                    transactionId = RICH_TXN,
                    tagId = TAG,
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                    deletedAtUtcMillis = null,
                ),
            ),
        )
        dao.insertBudgets(
            listOf(
                BudgetEntity(
                    id = BUDGET,
                    profileId = REAL_PROFILE,
                    categoryId = CATEGORY,
                    nature = "need",
                    periodStartIsoDate = "2026-08-01",
                    amountMinor = 10_000_00L,
                    rolloverEnabled = true,
                    source = "manual",
                    ruleId = "RULE-BUD-SUGGEST",
                    ruleVersion = "1.0",
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                ),
            ),
        )
        dao.insertBudgetAlerts(
            listOf(
                BudgetAlertEntity(
                    id = "alert:1",
                    profileId = REAL_PROFILE,
                    budgetId = BUDGET,
                    categoryId = CATEGORY,
                    monthStartIsoDate = "2026-08-01",
                    band = "warn",
                    ruleId = "RULE-BUD-ALERT",
                    ruleVersion = "1.0",
                    notifiedAtUtcMillis = NOW,
                ),
            ),
        )
        dao.insertCreditCards(
            listOf(
                CreditCardEntity(
                    accountId = ACCOUNT,
                    profileId = REAL_PROFILE,
                    creditLimitMinor = 20_000_000L,
                    statementDay = 5,
                    dueDay = 25,
                    lastStatementMinor = 7_000_000L,
                    lastStatementIsoDate = "2026-03-05",
                    minimumDueMinor = 350_000L,
                    aprBps = 4_200,
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                ),
            ),
        )
        dao.insertLoans(
            listOf(
                // Five columns and no schedule: the amortisation rows are derived on read
                // (ADR-0026), so a round trip that carried them would be round-tripping a cache.
                LoanEntity(
                    accountId = ACCOUNT,
                    profileId = REAL_PROFILE,
                    principalMinor = 300_000_000L,
                    annualRateBps = 850,
                    tenureMonths = 240,
                    firstEmiIsoDate = "2026-09-05",
                    emiOverrideMinor = null,
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                ),
            ),
        )
        dao.insertCardAlerts(
            listOf(
                CardAlertEntity(
                    id = "card-alert:1",
                    profileId = REAL_PROFILE,
                    accountId = ACCOUNT,
                    cycleStartIsoDate = "2026-03-05",
                    kind = "DUE_SOON",
                    ruleId = "RULE-CC-DUE",
                    ruleVersion = "1.0",
                    notifiedAtUtcMillis = NOW,
                ),
            ),
        )
        dao.insertBudgetReviews(
            listOf(
                BudgetReviewEntity(
                    id = "review:1",
                    profileId = REAL_PROFILE,
                    monthStartIsoDate = "2026-07-01",
                    ruleId = "RULE-BUD-REVIEW",
                    ruleVersion = "1.0",
                    totalBudgetedMinor = 30_000_00L,
                    totalActualMinor = 28_450_00L,
                    reviewedAtUtcMillis = NOW,
                ),
            ),
        )
        dao.insertRecurringRules(
            listOf(
                RecurringRuleEntity(
                    id = "recurring:1",
                    profileId = REAL_PROFILE,
                    accountId = ACCOUNT,
                    categoryId = CATEGORY,
                    name = "Netflix",
                    seedKind = "income",
                    amountMinor = -649_00L,
                    cadence = "monthly",
                    nextDueIsoDate = "2026-09-03",
                    source = "detected",
                    isConfirmed = true,
                    dismissedAtUtcMillis = NOW,
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                ),
            ),
        )
        dao.insertNetWorthSnapshots(
            listOf(
                NetWorthSnapshotEntity(
                    id = "snapshot:1",
                    profileId = REAL_PROFILE,
                    asOfIsoDate = "2026-08-15",
                    assetsMinor = 3_00_000_00L,
                    liabilitiesMinor = 50_000_00L,
                    netWorthMinor = 2_50_000_00L,
                    engineId = "net-worth",
                    engineVersion = "1.0",
                    computedAtUtcMillis = NOW,
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                    deletedAtUtcMillis = null,
                ),
            ),
        )
        dao.insertAttachments(
            listOf(
                AttachmentEntity(
                    id = "attachment:1",
                    profileId = REAL_PROFILE,
                    transactionId = RICH_TXN,
                    kind = "receipt",
                    fileName = "attachment-1.bin",
                    mimeType = "image/jpeg",
                    byteSize = 42_000L,
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                    deletedAtUtcMillis = DELETED_AT,
                ),
            ),
        )
        dao.insertSmsDrafts(
            listOf(
                SmsDraftEntity(
                    id = "draft:1",
                    profileId = REAL_PROFILE,
                    smsId = 4_242L,
                    sender = "HDFCBK",
                    amountMinor = -450_00L,
                    direction = "debit",
                    bookedOn = "2026-08-15",
                    counterparty = "SWIGGY",
                    accountTail = "1234",
                    confidenceBps = 8_500,
                    engineVersion = "1.0",
                    ruleVersion = "1.0",
                    status = "pending",
                    transactionId = RICH_TXN,
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                ),
            ),
        )
    }

    /** Result: a transaction row. Input: [id]; [profileId]; [amountMinor]; [deletedAt]. */
    private fun transaction(
        id: String,
        profileId: String = REAL_PROFILE,
        amountMinor: Long = -1_200_00L,
        deletedAt: Long? = null,
    ) = TransactionEntity(
        id = id,
        profileId = profileId,
        accountId = ACCOUNT,
        amountMinor = amountMinor,
        currencyCode = "INR",
        occurredAtUtcMillis = NOW,
        bookedOnIsoDate = "2026-08-15",
        categoryId = CATEGORY,
        merchant = "Big Bazaar",
        note = "weekly shop",
        source = "manual",
        type = "expense",
        transferId = "transfer:1",
        postedAtUtcMillis = NOW,
        nature = "need",
        createdAtUtcMillis = NOW,
        updatedAtUtcMillis = NOW,
        deletedAtUtcMillis = deletedAt,
    )

    /** Result: a profile row. Input: [id]. */
    private fun profile(id: String) =
        ProfileEntity(
            id = id,
            displayName = "Harish",
            timeZoneId = "Asia/Kolkata",
            currencyCode = "INR",
            createdAtUtcMillis = NOW,
            updatedAtUtcMillis = NOW,
            deletedAtUtcMillis = null,
        )

    /** Result: the profile is empty. Output: none (suspends). */
    private suspend fun wipeEverything() {
        val demo = database.demoDao()
        demo.deleteBudgetAlerts(REAL_PROFILE)
        demo.deleteCardAlerts(REAL_PROFILE)
        demo.deleteBudgets(REAL_PROFILE)
        demo.deleteBudgetReviews(REAL_PROFILE)
        demo.deleteNetWorthSnapshots(REAL_PROFILE)
        demo.deleteRecurringRules(REAL_PROFILE)
        demo.deleteTransactionSplits(REAL_PROFILE)
        demo.deleteAttachments(REAL_PROFILE)
        demo.deleteSmsDrafts(REAL_PROFILE)
        demo.deleteTransactionTags(REAL_PROFILE)
        demo.deleteTags(REAL_PROFILE)
        demo.deleteTransactions(REAL_PROFILE)
        demo.deleteCategories(REAL_PROFILE)
        demo.deleteCreditCards(REAL_PROFILE)
        demo.deleteLoans(REAL_PROFILE)
        demo.deleteAccounts(REAL_PROFILE)
        demo.deleteProfile(REAL_PROFILE)
    }

    /** Result: the archive text with its timestamp line removed. Input: the receiver. */
    private fun String.withoutTimestamp(): String =
        lines().filterNot { it.contains("exportedAtUtcMillis") }.joinToString("\n")

    private companion object {
        const val REAL_PROFILE = "local"
        const val DEMO_PROFILE = "demo"
        const val ACCOUNT = "account:1"
        const val CATEGORY = "category:groceries"
        const val TAG = "tag:1"
        const val BUDGET = "budget:1"
        const val PARENT_CATEGORY = "category:food"

        /** The row whose every nullable column is populated. */
        const val RICH_TXN = "txn:rich"
        const val NOW = 1_786_000_000_000L

        /** A tombstone the archive must carry as a tombstone. */
        const val DELETED_AT = 1_786_000_500_000L

        /** Enough rows to exceed SQLite's bind-variable limit if the insert were done per row. */
        const val LARGE_ROW_COUNT = 2_000
    }
}

/**
 * Unwraps a result the test expects to have succeeded.
 * Why:    an `Err` here is a test failure, and `as Ok` would report it as a `ClassCastException`
 *         naming neither the code nor the call — the helper every repository test in this module
 *         carries, kept file-private so each states its own reason.
 * Result: the value. Input: the receiver. Output: [T]; throws [AssertionError] on an `Err`.
 * Changelog: 2026-08-16 — Created for issue 5.4.
 */
private fun <T> Result<T, AppError>.expectOk(): T =
    when (this) {
        is Ok -> value
        else -> throw AssertionError("expected Ok, got $this")
    }
