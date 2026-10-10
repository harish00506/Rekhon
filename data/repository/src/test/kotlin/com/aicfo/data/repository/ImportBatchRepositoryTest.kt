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
import com.aicfo.core.model.ImportBatch
import com.aicfo.core.model.Money
import com.aicfo.core.model.TransactionSource
import com.aicfo.domain.engines.classification.ClassificationEngineFactory
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
 * Tests for [ImportBatchRepository] — §33's statement-grade provenance (ADR-0078).
 *
 * Why:  §33 promised `import_batches` from v1 and ADR-0074 found it had never been built, so there
 *       was nowhere to record **which fetch a row came from**. Four properties decide whether this
 *       actually keeps the promise rather than merely adding a table:
 *
 *       - a transaction can be **traced back** to the run that brought it in — the question §33 is
 *         about, and the only one the table exists to answer;
 *       - a batch id that names nothing is **refused**, because the schema has no foreign keys and
 *         this refusal is therefore the entire constraint;
 *       - a batch belonging to **another profile** is refused too — an id-only check would let one
 *         profile's transaction claim another's import;
 *       - an uninmported row answers `null`, and `null` means *not imported*, never "imported,
 *         origin unknown".
 * What: the write, the trace-back, the two refusals, and what the batch records about itself.
 * Result: the promise is kept and provable, with no ingest path on it yet.
 * Changelog: 2026-10-10 — Created (ADR-0078).
 *
 * Unencrypted in-memory Room, like every repository test here: what is under test is the SQL and
 * the integrity rule, not SQLCipher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ImportBatchRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var repository: ImportBatchRepository
    private lateinit var transactions: TransactionRepository
    private lateinit var accounts: AccountRepository

    private val clock = FakeClock(initialMillis = Instant.parse("2026-10-03T06:30:00Z").toEpochMilli())
    private val ids = FakeIdGenerator()
    private val activeProfileId = MutableStateFlow(REAL_PROFILE)

    /** Input: none. Output: a fresh in-memory database and the three repositories over it. */
    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                CfoDatabase::class.java,
            ).allowMainThreadQueries().build()
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        repository = RepositoryFactory.importBatches(database, clock, ids, dispatchers, activeProfileId)
        transactions =
            RepositoryFactory.transactions(
                database, clock, ids, dispatchers, activeProfileId, ClassificationEngineFactory.create(),
                NatureEngineFactory.create(),
            )
        accounts = RepositoryFactory.accounts(database, clock, ids, dispatchers, activeProfileId)
    }

    /** Input: none. Output: closes the database between tests. */
    @After
    fun tearDown() {
        database.close()
    }

    /**
     * Input:  a recorded batch and a transaction written against it.
     * Output: asserts the transaction resolves back to that batch, with its window intact.
     *
     * **This is the promise.** Everything else here guards it. Before this existed, the answer to
     * "which fetch brought this row in" was unavailable at any price — the row said `source = 'aa'`
     * and nothing more, so two imports of the same statement were indistinguishable from one.
     */
    @Test
    fun `a transaction traces back to the run that imported it`() =
        runTest {
            val account = newAccount()
            val batch = recordBatch()

            val created =
                transactions.create(
                    TransactionDraft(account.id, Money(-2_500_00L), importBatchId = batch.id),
                )

            val traced = repository.batchFor((created as Ok).value.id)
            assertEquals(batch.id, traced?.id)
            assertEquals("2026-09-01", traced?.windowStartIsoDate)
            assertEquals("2026-09-30", traced?.windowEndIsoDate)
            assertEquals(TransactionSource.ACCOUNT_AGGREGATOR, traced?.source)
        }

    /**
     * Input:  a transaction the user typed in.
     * Output: asserts the trace is `null`.
     *
     * `null` means **not imported**, which is almost every row in the ledger. It never means
     * "imported, origin unknown" — that state is unreachable, because the write below refuses it.
     */
    @Test
    fun `a hand-typed transaction has no batch, and that is not an unknown origin`() =
        runTest {
            val account = newAccount()

            val created = transactions.create(TransactionDraft(account.id, Money(-250_00L)))

            assertNull(repository.batchFor((created as Ok).value.id))
        }

    /**
     * Input:  a draft naming a batch id that was never recorded.
     * Output: asserts the write is **refused, naming the field**.
     *
     * The schema declares no foreign keys (issue 1.6 chose application-level integrity), so SQLite
     * would happily store a transaction pointing at nothing. This refusal is the whole constraint:
     * without it the column could hold provenance that resolves to no run, which is worse than the
     * honest absence ADR-0074 chose over a half-built substitute.
     */
    @Test
    fun `a transaction naming a batch that does not exist is refused`() =
        runTest {
            val account = newAccount()

            val created =
                transactions.create(
                    TransactionDraft(account.id, Money(-100_00L), importBatchId = "imp_nothing"),
                )

            assertEquals(Err(AppError.Validation("importBatchId")), created)
        }

    /**
     * Input:  a batch recorded under the demo profile, named by a transaction in the real one.
     * Output: asserts the write is refused.
     *
     * The lookup takes the profile **and** the id, never the id alone. An id-only check would let a
     * row in one profile claim provenance from another's import — which on a shared device means
     * one person's transaction citing another person's bank fetch.
     */
    @Test
    fun `a batch belonging to another profile cannot be claimed`() =
        runTest {
            val account = newAccount()
            activeProfileId.value = DemoModeRepository.DEMO_PROFILE_ID
            val theirs = recordBatch()
            activeProfileId.value = REAL_PROFILE

            val created =
                transactions.create(
                    TransactionDraft(account.id, Money(-100_00L), importBatchId = theirs.id),
                )

            assertEquals(Err(AppError.Validation("importBatchId")), created)
        }

    /**
     * Input:  a batch that was offered more lines than it kept.
     * Output: asserts the counts and the derived gap survive the round trip.
     *
     * The difference is the answer to "why does my import look short", and it is **stored** rather
     * than derived from `transactions`: a row the user deletes next month must not retroactively
     * change what this run reports it did.
     */
    @Test
    fun `a batch records what it was offered and what it kept`() =
        runTest {
            val batch = recordBatch(lineCount = 47, acceptedCount = 42)

            val listed = repository.observeBatches().first()

            assertEquals(1, listed.size)
            assertEquals(47, listed.single().lineCount)
            assertEquals(42, listed.single().acceptedCount)
            assertEquals(5, listed.single().skippedCount)
            assertEquals(batch.id, listed.single().id)
        }

    /**
     * Input:  a truncated fetch.
     * Output: asserts `complete = false` survives, and that the fetch instant is kept apart from
     *         the moment the app ran the import.
     *
     * Both matter for P-04's staleness label: a screen has to say "as at" the time the *data* was
     * true, not the time the app happened to ask, and has to say "there may be more" when the
     * window was cut short rather than showing a partial history as a whole one.
     */
    @Test
    fun `a truncated fetch keeps its incompleteness and its own as-at instant`() =
        runTest {
            val fetchedAt = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli()

            repository.record(
                source = TransactionSource.ACCOUNT_AGGREGATOR,
                fetchedAtUtcMillis = fetchedAt,
                windowStartIsoDate = "2026-09-01",
                windowEndIsoDate = "2026-09-30",
                complete = false,
                lineCount = 10,
                acceptedCount = 10,
            )

            val listed = repository.observeBatches().first().single()
            assertTrue("a truncated window must not read as whole", !listed.complete)
            assertEquals(fetchedAt, listed.fetchedAtUtcMillis)
            assertEquals(clock.nowUtcMillis(), listed.startedAtUtcMillis)
        }

    /**
     * Input:  a batch claiming it kept more lines than it was offered.
     * Output: asserts it is refused.
     *
     * An impossible count is a caller bug, and storing it would put a negative "skipped" figure in
     * front of a user. Refused at the repository rather than left to the model's `require`, so the
     * failure is a `Result` a caller can handle and not a crash (§21.6).
     */
    @Test
    fun `a batch that kept more than it was offered is refused`() =
        runTest {
            val recorded = repository.record(source = TransactionSource.IMPORT, lineCount = 3, acceptedCount = 4)

            assertEquals(Err(AppError.Validation("acceptedCount")), recorded)
        }

    // --- helpers -----------------------------------------------------------------------------------

    /** Input: the counts. Output: a recorded batch, unwrapped. */
    private suspend fun recordBatch(
        lineCount: Int = 5,
        acceptedCount: Int = 5,
    ): ImportBatch =
        (
            repository.record(
                source = TransactionSource.ACCOUNT_AGGREGATOR,
                fetchedAtUtcMillis = clock.nowUtcMillis(),
                windowStartIsoDate = "2026-09-01",
                windowEndIsoDate = "2026-09-30",
                complete = true,
                lineCount = lineCount,
                acceptedCount = acceptedCount,
            ) as Ok
        ).value

    /** Input: none. Output: a live account to hang transactions off. */
    private suspend fun newAccount() =
        (
            accounts.create(
                AccountDraft(
                    name = "HDFC Savings",
                    type = AccountType.BANK,
                    openingBalance = Money(1_00_000_00L),
                    currencyCode = "INR",
                ),
            ) as Ok
        ).value

    private companion object {
        const val REAL_PROFILE = "local"
    }
}
