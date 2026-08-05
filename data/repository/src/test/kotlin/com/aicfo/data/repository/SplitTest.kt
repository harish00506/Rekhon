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
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import com.aicfo.core.model.total
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.random.Random

/**
 * Tests for split transactions — issue 3.3 (FR-TXN-004, DB-004, MNY-001).
 *
 * Why:  FR-TXN-004's demand is "lines MUST sum exactly to the parent amount (validated, no rounding
 *       drift)", and there are two ways to fail it that no other test here would catch.
 *
 *       **The sum can be right in memory and wrong on disk.** `MoneySplitPropertyTest` already
 *       proves `Money.split`/`allocate` never lose a paise, so re-proving that here would assert
 *       nothing new. What is unproven is the round trip: what the repository *stored* versus what
 *       the parent says. The property test below therefore reads both back out of SQLite.
 *
 *       **A split must not move money twice.** The parent holds the amount and the lines only
 *       attribute it (ADR-0009); a balance that moved by the parent *and* its lines would double
 *       every split, and the accounts screen would be wrong in a way the split screen could not show.
 * What: the write, the exact-sum rule, the balance, deletion, and profile isolation.
 * Result: a split is provably exact on disk, and provably weightless in every balance.
 * Changelog: 2026-08-02 — Created for issue 3.3.
 *
 * Unencrypted in-memory Room, deliberately — the same reasoning as [TransferTest]: what is under
 * test is the SQL and the arithmetic, not SQLCipher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SplitTest {
    private lateinit var database: CfoDatabase
    private lateinit var repository: TransactionRepository
    private lateinit var accounts: AccountRepository

    private val clock = FakeClock(initialMillis = Instant.parse("2026-08-02T18:00:00Z").toEpochMilli())
    private val ids = FakeIdGenerator()
    private val activeProfileId = MutableStateFlow(REAL_PROFILE)

    /** Input: none. Output: a fresh in-memory database and both repositories over it. */
    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                CfoDatabase::class.java,
            ).allowMainThreadQueries().build()
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        repository = RepositoryFactory.transactions(database, clock, ids, dispatchers, activeProfileId)
        // The real AccountRepository: the claim that a split moves the balance once, not once per
        // line, is about the figure the accounts screen renders, and a stub could not make it.
        accounts = RepositoryFactory.accounts(database, clock, ids, dispatchers, activeProfileId)
    }

    /** Input: none. Output: closes the database between tests. */
    @After
    fun tearDown() {
        database.close()
    }

    // --- the write ---------------------------------------------------------------------------------

    @Test
    fun `a split writes one parent and one row per line`() =
        runTest {
            val account = newAccount()

            val parent = repository.createSplit(splitDraft(account.id)).expectOk()

            assertEquals(2, parent.splits.size)
            assertEquals(2, linesOf(parent.id).size)
            assertTrue("the parent is a single ordinary transaction", parent.isSplit)
        }

    @Test
    fun `every line points at its parent and shares its profile`() =
        runTest {
            val account = newAccount()

            val parent = repository.createSplit(splitDraft(account.id)).expectOk()

            linesOf(parent.id).forEach { line ->
                assertEquals(parent.id, line.transactionId)
                // Denormalised so the demo wipe, a profile-scoped single-table delete, can reach it.
                assertEquals(REAL_PROFILE, line.profileId)
            }
        }

    @Test
    fun `the parent carries no category — the lines do`() =
        runTest {
            // A category on the parent as well would be a second, contradictory answer to "what was
            // this?", and would double-count against a budget envelope once issue 4.4 lands.
            val account = newAccount()

            val parent =
                repository.createSplit(
                    splitDraft(
                        account.id,
                        lines =
                            listOf(
                                SplitLineDraft(Money(-600_00L), categoryId = "category:groceries"),
                                SplitLineDraft(Money(-400_00L), categoryId = "category:household"),
                            ),
                    ),
                ).expectOk()

            assertNull(parent.categoryId)
            assertEquals(
                listOf("category:groceries", "category:household"),
                parent.splits.map { it.categoryId },
            )
        }

    @Test
    fun `a line may have no category at all`() =
        runTest {
            // Not an edge case: a real profile has no categories until issue 4.1, so this is the
            // only shape a split can take today outside demo mode.
            val account = newAccount()

            val parent = repository.createSplit(splitDraft(account.id)).expectOk()

            assertTrue(parent.splits.all { it.categoryId == null })
        }

    @Test
    fun `an income splits into positive lines`() =
        runTest {
            val account = newAccount()

            val parent =
                repository.createSplit(
                    splitDraft(
                        account.id,
                        amount = Money(60_000_00L),
                        lines = listOf(SplitLineDraft(Money(50_000_00L)), SplitLineDraft(Money(10_000_00L))),
                    ),
                ).expectOk()

            assertTrue(parent.splits.all { it.amount > Money.ZERO })
            assertEquals(Money(60_000_00L), parent.splits.total())
        }

    // --- the exact-sum rule (FR-TXN-004) -----------------------------------------------------------

    @Test
    fun `the persisted lines sum exactly to the persisted parent, for any division`() =
        runTest {
            // The property FR-TXN-004 actually asks for, asserted **on what came back out of
            // SQLite** rather than on Money — `MoneySplitPropertyTest` already owns the in-memory
            // guarantee. Seeded, so a failure is reproducible (P-08). `Money.split` is used to
            // generate the divisions because it is the one function that can always produce an exact
            // one, which is precisely what the app offers the user as "split evenly".
            val random = Random(20_260_802L)

            repeat(60) {
                val account = newAccount()
                val parentMinor = random.nextLong(1L, 10_00_00_000L)
                val parts = random.nextInt(2, 9)
                val lines = Money(-parentMinor).split(parts).map { SplitLineDraft(it) }

                val parent =
                    repository.createSplit(
                        splitDraft(account.id, amount = Money(-parentMinor), lines = lines),
                    ).expectOk()

                val stored = linesOf(parent.id)
                assertEquals("a $parts-way split must store $parts lines", parts, stored.size)
                assertEquals(
                    "the stored lines must sum to the stored parent, exactly",
                    rawAmountOf(parent.id),
                    stored.sumOf { it.amountMinor },
                )
            }
        }

    @Test
    fun `lines that do not sum to the parent are refused, and nothing is written`() =
        runTest {
            // One paise out is out. No tolerance and no silent adjustment: an app that moved a
            // user's figure to make a form balance would be worse than one that refuses.
            val account = newAccount()

            val result =
                repository.createSplit(
                    splitDraft(
                        account.id,
                        lines = listOf(SplitLineDraft(Money(-600_00L)), SplitLineDraft(Money(-399_99L))),
                    ),
                )

            assertEquals("lines", ((result as Err).error as AppError.Validation).field)
            assertTrue(repository.liveTransactions().isEmpty())
            assertTrue(allLines().isEmpty())
        }

    @Test
    fun `a single line is not a split`() =
        runTest {
            val account = newAccount()

            val result =
                repository.createSplit(
                    splitDraft(account.id, lines = listOf(SplitLineDraft(Money(-1_000_00L)))),
                )

            assertEquals("lines", ((result as Err).error as AppError.Validation).field)
            assertTrue(allLines().isEmpty())
        }

    @Test
    fun `a zero line is refused even when the others still sum`() =
        runTest {
            // It contributes nothing and would sit in every category report saying nothing.
            val account = newAccount()

            val result =
                repository.createSplit(
                    splitDraft(
                        account.id,
                        lines =
                            listOf(
                                SplitLineDraft(Money(-600_00L)),
                                SplitLineDraft(Money(-400_00L)),
                                SplitLineDraft(Money.ZERO),
                            ),
                    ),
                )

            assertEquals("lines", ((result as Err).error as AppError.Validation).field)
            assertTrue(allLines().isEmpty())
        }

    @Test
    fun `a line signed against its parent is refused`() =
        runTest {
            // -1200 and +200 do sum to -1000, but they describe a refund inside a purchase that
            // never happened. Without this check the sum rule alone would let it through.
            val account = newAccount()

            val result =
                repository.createSplit(
                    splitDraft(
                        account.id,
                        lines = listOf(SplitLineDraft(Money(-1_200_00L)), SplitLineDraft(Money(200_00L))),
                    ),
                )

            assertEquals("lines", ((result as Err).error as AppError.Validation).field)
            assertTrue(allLines().isEmpty())
        }

    @Test
    fun `a zero parent is refused`() =
        runTest {
            val account = newAccount()

            val result =
                repository.createSplit(
                    splitDraft(
                        account.id,
                        amount = Money.ZERO,
                        lines = listOf(SplitLineDraft(Money.ZERO), SplitLineDraft(Money.ZERO)),
                    ),
                )

            assertEquals("amount", ((result as Err).error as AppError.Validation).field)
        }

    @Test
    fun `an unknown account is NotFound, and no lines are written`() =
        runTest {
            val result = repository.createSplit(splitDraft("account:missing"))

            assertEquals(AppError.NotFound, (result as Err).error)
            assertTrue(allLines().isEmpty())
        }

    // --- the money ---------------------------------------------------------------------------------

    @Test
    fun `a split moves the balance once, not once per line`() =
        runTest {
            // ADR-0009's whole point. If the lines were rows in `transactions` this would be
            // -2,000.00 and every split in the app would double.
            val account = newAccount(opening = Money(50_000_00L))

            repository.createSplit(splitDraft(account.id)).expectOk()

            assertEquals(Money(49_000_00L), accounts.find(account.id).expectOk().balance)
        }

    @Test
    fun `a five-way split still moves the balance by the parent only`() =
        runTest {
            val account = newAccount(opening = Money(50_000_00L))
            val lines = Money(-1_000_00L).split(5).map { SplitLineDraft(it) }

            repository.createSplit(
                splitDraft(account.id, lines = lines),
            ).expectOk()

            assertEquals(Money(49_000_00L), accounts.find(account.id).expectOk().balance)
        }

    // --- reads -------------------------------------------------------------------------------------

    @Test
    fun `the recent list attaches each transaction's lines`() =
        runTest {
            val account = newAccount()
            repository.createSplit(splitDraft(account.id)).expectOk()

            val recent = repository.liveTransactions()

            assertEquals(1, recent.size)
            assertEquals(2, recent.single().splits.size)
            assertEquals(recent.single().amount, recent.single().splits.total())
        }

    @Test
    fun `an ordinary transaction comes back with no lines`() =
        runTest {
            val account = newAccount()
            repository.create(TransactionDraft(account.id, Money(-250_00L))).expectOk()

            val transaction = repository.liveTransactions().single()

            assertTrue(transaction.splits.isEmpty())
            assertTrue(!transaction.isSplit)
        }

    @Test
    fun `lines are attached to their own parent, not to a neighbour`() =
        runTest {
            val account = newAccount()
            repository.createSplit(splitDraft(account.id)).expectOk()
            repository.create(TransactionDraft(account.id, Money(-250_00L))).expectOk()

            val recent = repository.liveTransactions()

            assertEquals(setOf(0, 2), recent.map { it.splits.size }.toSet())
            recent.filter { it.isSplit }.forEach { parent ->
                assertTrue(parent.splits.all { it.transactionId == parent.id })
            }
        }

    // --- deletion ----------------------------------------------------------------------------------

    @Test
    fun `deleting a split takes its lines with it`() =
        runTest {
            // A line whose parent is gone attributes an amount that no longer exists.
            val account = newAccount()
            val parent = repository.createSplit(splitDraft(account.id)).expectOk()

            repository.delete(parent.id).expectOk()

            assertTrue(liveLinesOf(parent.id).isEmpty())
            assertTrue(repository.liveTransactions().isEmpty())
        }

    @Test
    fun `deleting a split reverts the balance`() =
        runTest {
            val account = newAccount(opening = Money(50_000_00L))
            val parent = repository.createSplit(splitDraft(account.id)).expectOk()

            repository.delete(parent.id).expectOk()

            assertEquals(Money(50_000_00L), accounts.find(account.id).expectOk().balance)
        }

    @Test
    fun `a deleted line is soft-deleted, not removed`() =
        runTest {
            // DB-002: the rows survive as tombstones, like every other user data in the app.
            val account = newAccount()
            val parent = repository.createSplit(splitDraft(account.id)).expectOk()

            repository.delete(parent.id).expectOk()

            assertEquals("both lines must still be on disk", 2, linesOf(parent.id).size)
            assertTrue(linesOf(parent.id).all { it.deletedAtUtcMillis != null })
        }

    @Test
    fun `deleting a split twice reports NotFound and does not re-stamp the lines`() =
        runTest {
            val account = newAccount()
            val parent = repository.createSplit(splitDraft(account.id)).expectOk()
            repository.delete(parent.id).expectOk()
            val firstStamps = linesOf(parent.id).map { it.deletedAtUtcMillis }

            clock.advanceBy(java.time.Duration.ofHours(1))
            val second = repository.delete(parent.id)

            assertEquals(AppError.NotFound, (second as Err).error)
            assertEquals(
                "a repeated delete must be a no-op all the way down",
                firstStamps,
                linesOf(parent.id).map {
                    it.deletedAtUtcMillis
                },
            )
        }

    @Test
    fun `deleting an ordinary transaction touches no lines`() =
        runTest {
            val account = newAccount()
            val parent = repository.createSplit(splitDraft(account.id)).expectOk()
            val plain = repository.create(TransactionDraft(account.id, Money(-250_00L))).expectOk()

            repository.delete(plain.id).expectOk()

            assertEquals("the split must be untouched", 2, liveLinesOf(parent.id).size)
        }

    // --- profile isolation -------------------------------------------------------------------------

    @Test
    fun `the demo's split lines are invisible to the real profile`() =
        runTest {
            val account = newAccount()
            repository.createSplit(splitDraft(account.id)).expectOk()

            activeProfileId.value = DEMO_PROFILE

            assertTrue(repository.liveTransactions().isEmpty())
        }

    @Test
    fun `the demo wipe reaches split lines`() =
        runTest {
            // ADR-0006: a profile-scoped table the wipe cannot reach is residue. `countRowsFor` is
            // what the residue test asserts on, and it now counts this table too.
            val account = newAccount()
            repository.createSplit(splitDraft(account.id)).expectOk()
            assertTrue(allLines().isNotEmpty())

            database.demoDao().deleteTransactionSplits(REAL_PROFILE)

            assertTrue(allLines().isEmpty())
            assertEquals(0, database.demoDao().countRowsFor(REAL_PROFILE) - countNonSplitRows())
        }

    // --- fixtures ----------------------------------------------------------------------------------

    /**
     * Result: a ₹1,000 expense split 600/400, the shape most tests here vary from.
     * Input:  [accountId]; [amount]; [lines]. Output: [SplitDraft].
     */
    private fun splitDraft(
        accountId: String,
        amount: Money = Money(-1_000_00L),
        lines: List<SplitLineDraft> =
            listOf(SplitLineDraft(Money(-600_00L)), SplitLineDraft(Money(-400_00L))),
    ) = SplitDraft(accountId = accountId, amount = amount, lines = lines)

    /** Result: one live account. Input: [opening]. Output: the created `Account`. */
    private suspend fun newAccount(opening: Money = Money(50_000_00L)) =
        accounts.create(
            AccountDraft(
                name = "HDFC Savings",
                type = AccountType.BANK,
                openingBalance = opening,
                currencyCode = "INR",
            ),
        ).expectOk()

    /** Result: a parent's lines including tombstones. Input: [transactionId]. Output: the rows. */
    private fun linesOf(transactionId: String) = allLinesIncludingDeleted().filter { it.transactionId == transactionId }

    /** Result: a parent's live lines. Input: [transactionId]. Output: the rows. */
    private fun liveLinesOf(transactionId: String) = linesOf(transactionId).filter { it.deletedAtUtcMillis == null }

    /** Result: every live split line. Input: none. Output: the rows. */
    private fun allLines() = allLinesIncludingDeleted().filter { it.deletedAtUtcMillis == null }

    /**
     * Result: every split row, straight from SQL, tombstones included. Input: none. Output: the rows.
     *
     * Reaches past the repository on purpose: the ledger read filters soft-deleted rows, so "the
     * tombstone is still there" is not a claim it can make.
     */
    private fun allLinesIncludingDeleted(): List<RawLine> =
        database.query(
            "SELECT id, profile_id, transaction_id, amount_minor, category_id, deleted_at_utc_millis " +
                "FROM transaction_splits ORDER BY created_at_utc_millis, id",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        RawLine(
                            id = cursor.getString(0),
                            profileId = cursor.getString(1),
                            transactionId = cursor.getString(2),
                            amountMinor = cursor.getLong(3),
                            categoryId = if (cursor.isNull(4)) null else cursor.getString(4),
                            deletedAtUtcMillis = if (cursor.isNull(5)) null else cursor.getLong(5),
                        ),
                    )
                }
            }
        }

    /** Result: the stored amount of a transaction. Input: [id]. Output: paise. */
    private fun rawAmountOf(id: String): Long =
        database.query("SELECT amount_minor FROM transactions WHERE id = ?", arrayOf<Any>(id)).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    /** Result: how many non-split rows the profile still has, so the wipe assertion isolates lines. */
    private fun countNonSplitRows(): Int =
        database.query(
            "SELECT (SELECT COUNT(*) FROM profile WHERE id = ?) + " +
                "(SELECT COUNT(*) FROM account WHERE profile_id = ?) + " +
                "(SELECT COUNT(*) FROM transactions WHERE profile_id = ?) + " +
                "(SELECT COUNT(*) FROM category WHERE profile_id = ?) + " +
                "(SELECT COUNT(*) FROM budget WHERE profile_id = ?) + " +
                "(SELECT COUNT(*) FROM recurring_rule WHERE profile_id = ?) + " +
                "(SELECT COUNT(*) FROM net_worth_snapshot WHERE profile_id = ?)",
            Array(7) { REAL_PROFILE },
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    /**
     * A split row as stored, with only the columns these tests assert on.
     * Result: a value. Input: the columns. Output: immutable.
     */
    private data class RawLine(
        val id: String,
        val profileId: String,
        val transactionId: String,
        val amountMinor: Long,
        val categoryId: String?,
        val deletedAtUtcMillis: Long?,
    )

    private companion object {
        const val REAL_PROFILE = "local"
        const val DEMO_PROFILE = "demo"
    }
}

/**
 * Unwraps a result the test expects to have succeeded.
 * Result: the value. Input: the receiver. Output: [T].
 * Changelog: 2026-08-02 — Created for issue 3.3.
 */
private fun <T> com.aicfo.core.common.Result<T, AppError>.expectOk(): T =
    when (this) {
        is Ok -> value
        is Err -> throw AssertionError("expected Ok, got Err(${error.code})")
    }
