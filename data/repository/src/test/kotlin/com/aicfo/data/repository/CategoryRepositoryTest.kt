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
import com.aicfo.core.database.entity.CategoryEntity
import com.aicfo.core.database.entity.TransactionEntity
import com.aicfo.core.database.entity.TransactionSplitEntity
import com.aicfo.core.model.CategoryNature
import com.aicfo.core.model.CategorySeed
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

/**
 * Tests for [CategoryRepository] — the taxonomy half of issue 4.1 (FR-SET-001, AI-CLSN-001, DB-002).
 *
 * Why:  five properties decide whether the rest of Epic 4 can be built on this class, and none is
 *       visible from reading it. **The seed runs once** — it fires at every cold start, so a second
 *       run that wrote anything would duplicate a user's taxonomy daily. **A deleted default stays
 *       deleted** — the seed's guard counts soft-deleted rows precisely so the app does not overrule
 *       a user (P-07), and only a raw read can show that. **The demo keeps its own twelve** — the
 *       seed follows `activeProfileId`, and a leak either way is the worst bug this feature can
 *       have. **Nesting stays one level**, because §8 names a category and a subcategory and nothing
 *       below. And **deleting a category does not delete the money**, which is the surprising half
 *       of soft delete and the reason the editor states a count first.
 * What: the seed, the four writes, validation, the usage count, and the demo/real switch.
 * Result: the persistence half of the categories editor is proven against a real SQL engine.
 * Changelog: 2026-08-08 — Created for issue 4.1.
 *
 * Unencrypted in-memory Room, deliberately — the same reasoning as `AccountRepositoryTest`: what is
 * under test is the SQL and the ordering, not SQLCipher.
 *
 * **Every test here is an airplane-mode test (P-04).** This repository opens no socket and has no
 * network collaborator to stub; the whole path is SQLite on the device. That is asserted once, in
 * `the taxonomy is reachable with no network of any kind`, rather than left implied.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CategoryRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var repository: CategoryRepository
    private val clock = FakeClock()
    private val ids = FakeIdGenerator()
    private val activeProfileId = MutableStateFlow(REAL_PROFILE)

    /** Input: none. Output: a fresh in-memory database and a repository over it. */
    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                CfoDatabase::class.java,
            ).allowMainThreadQueries().build()
        repository =
            RepositoryFactory.categories(
                database = database,
                clock = clock,
                ids = ids,
                dispatchers = TestDispatchers(UnconfinedTestDispatcher()),
                activeProfileId = activeProfileId,
            )
    }

    /** Input: none. Output: closes the database between tests. */
    @After
    fun tearDown() {
        database.close()
    }

    // --- the seed ----------------------------------------------------------------------------------

    @Test
    fun `a fresh profile is seeded with the knowledge base's defaults`() =
        runTest {
            // The gap this issue closes: before it, this assertion read `0` — DemoDataset was the
            // only thing in the codebase that ever wrote a category row.
            assertEquals(0, repository.observeCategories().first().size)

            assertEquals(CategorySeed.rows.size, repository.ensureSeeded().expectOk())

            val seeded = repository.observeCategories().first()
            assertEquals(CategorySeed.rows.size, seeded.size)
            assertEquals(CategorySeed.rows.map { it.name }.toSet(), seeded.map { it.name }.toSet())
            assertTrue("every seeded row should be marked system", seeded.all { it.isSystem })
            assertTrue("seeded rows are top-level", seeded.all { it.parentId == null })
        }

    @Test
    fun `every seeded nature survives the round trip through the column`() =
        runTest {
            // The vocabularies differ for INVEST (`invest` stored, `INVESTMENT` in the knowledge
            // base). If the seed wrote the wrong one, `toCategory` would drop the row and this would
            // come back short rather than wrong — which is why the count is asserted too.
            repository.ensureSeeded().expectOk()

            val byName = repository.observeCategories().first().associateBy { it.name }
            assertEquals(CategorySeed.rows.size, byName.size)
            CategorySeed.rows.forEach { seed ->
                assertEquals("nature for ${seed.name}", seed.nature, byName.getValue(seed.name).nature)
            }
            assertEquals(CategoryNature.INVEST, byName.getValue("Investment").nature)
            assertEquals("invest", byName.getValue("Investment").nature.storedValue)
        }

    @Test
    fun `seeding twice writes nothing the second time`() =
        runTest {
            repository.ensureSeeded().expectOk()

            // The call site is MainViewModel.init, so this runs on every cold start. A second write
            // would grow the taxonomy by fifteen rows a day.
            assertEquals(0, repository.ensureSeeded().expectOk())
            assertEquals(CategorySeed.rows.size, repository.observeCategories().first().size)
        }

    @Test
    fun `a profile that deleted every default is not re-seeded`() =
        runTest {
            repository.ensureSeeded().expectOk()
            repository.observeCategories().first().forEach { repository.delete(it.id).expectOk() }
            assertEquals(0, repository.observeCategories().first().size)

            assertEquals(0, repository.ensureSeeded().expectOk())

            // Nothing live, and the soft-deleted rows still there — the proof that the guard counted
            // them rather than the live ones (P-07: the app does not overrule the user).
            assertEquals(0, repository.observeCategories().first().size)
            assertEquals(CategorySeed.rows.size, database.categoryDao().countForProfile(REAL_PROFILE))
        }

    @Test
    fun `the demo profile keeps its own categories and is never seeded over`() =
        runTest {
            // DemoDataset writes twelve of its own. The seed must not add fifteen on top of them.
            database.categoryDao().upsert(
                row(id = "demo:category:dining", name = "Dining Out", profileId = DEMO_PROFILE),
            )
            activeProfileId.value = DEMO_PROFILE

            assertEquals(0, repository.ensureSeeded().expectOk())

            assertEquals(listOf("Dining Out"), repository.observeCategories().first().map { it.name })
            // ...and the real profile, still unseeded, is untouched by the demo's rows.
            activeProfileId.value = REAL_PROFILE
            assertEquals(0, repository.observeCategories().first().size)
        }

    @Test
    fun `seeded ids are derived, so they are the same on any device`() =
        runTest {
            repository.ensureSeeded().expectOk()

            val rent = repository.observeCategories().first().single { it.name == "Rent" }
            assertEquals("$REAL_PROFILE:category:rent", rent.id)
        }

    // --- create ------------------------------------------------------------------------------------

    @Test
    fun `creating a category returns it and it is not a system row`() =
        runTest {
            val created = repository.create("Chai", CategoryNature.WANT).expectOk()

            assertEquals("Chai", created.name)
            assertEquals(CategoryNature.WANT, created.nature)
            assertNull(created.parentId)
            assertEquals(false, created.isSystem)
            assertEquals(listOf("Chai"), repository.observeCategories().first().map { it.name })
        }

    @Test
    fun `a name is trimmed before it is stored`() =
        runTest {
            val created = repository.create("  Chai  ", CategoryNature.WANT).expectOk()

            assertEquals("Chai", created.name)
        }

    @Test
    fun `a blank or whitespace-only name is rejected`() =
        runTest {
            assertEquals("name", repository.create("", CategoryNature.WANT).expectValidation())
            assertEquals("name", repository.create("   ", CategoryNature.WANT).expectValidation())
            assertEquals(0, repository.observeCategories().first().size)
        }

    @Test
    fun `a duplicate name is rejected regardless of case`() =
        runTest {
            repository.create("Fuel", CategoryNature.NEED).expectOk()

            // `fuel` and `Fuel` are the same category to the person typing them, and two of them
            // would be indistinguishable on every chip and in every budget.
            assertEquals("name", repository.create("fuel", CategoryNature.NEED).expectValidation())
            assertEquals("name", repository.create("  FUEL ", CategoryNature.WANT).expectValidation())
            assertEquals(1, repository.observeCategories().first().size)
        }

    @Test
    fun `a name freed by a delete can be used again`() =
        runTest {
            val fuel = repository.create("Fuel", CategoryNature.NEED).expectOk()
            repository.delete(fuel.id).expectOk()

            val recreated = repository.create("Fuel", CategoryNature.WANT).expectOk()

            // A new row, not the old one resurrected — the deleted row still carries the old nature.
            assertTrue("recreated category reused the deleted id", recreated.id != fuel.id)
            assertEquals(CategoryNature.WANT, recreated.nature)
            assertEquals("need", database.categoryDao().findById(fuel.id)?.nature)
        }

    @Test
    fun `a category can be nested one level under another`() =
        runTest {
            val transport = repository.create("Transport", CategoryNature.NEED).expectOk()

            val cab = repository.create("Cabs", CategoryNature.NEED, parentId = transport.id).expectOk()

            assertEquals(transport.id, cab.parentId)
        }

    @Test
    fun `a second level of nesting is rejected`() =
        runTest {
            val transport = repository.create("Transport", CategoryNature.NEED).expectOk()
            val cab = repository.create("Cabs", CategoryNature.NEED, parentId = transport.id).expectOk()

            // FR-TXN-001 names "category, subcategory" — two levels. A grandchild would render under
            // a parent the editor does not draw.
            assertEquals(
                "parentId",
                repository.create("Ola", CategoryNature.NEED, parentId = cab.id).expectValidation(),
            )
        }

    @Test
    fun `a parent that does not exist is rejected`() =
        runTest {
            assertEquals(
                "parentId",
                repository.create("Cabs", CategoryNature.NEED, parentId = "category:ghost").expectValidation(),
            )
        }

    @Test
    fun `a soft-deleted category cannot be used as a parent`() =
        runTest {
            val transport = repository.create("Transport", CategoryNature.NEED).expectOk()
            repository.delete(transport.id).expectOk()

            // The row is still in the table; only the live view excludes it. A parent lookup that
            // read the table rather than the live rows would accept it and orphan the child.
            assertEquals(
                "parentId",
                repository.create("Cabs", CategoryNature.NEED, parentId = transport.id).expectValidation(),
            )
        }

    // --- update ------------------------------------------------------------------------------------

    @Test
    fun `renaming and re-naturing land in one write`() =
        runTest {
            val created = repository.create("Chai", CategoryNature.WANT).expectOk()

            val updated = repository.update(created.id, "Coffee", CategoryNature.NEED).expectOk()

            assertEquals("Coffee", updated.name)
            assertEquals(CategoryNature.NEED, updated.nature)
            assertEquals(listOf("Coffee"), repository.observeCategories().first().map { it.name })
        }

    @Test
    fun `a seeded category is renamed like any other — is_system grants it nothing`() =
        runTest {
            repository.ensureSeeded().expectOk()
            val dining = repository.observeCategories().first().single { it.name == "Dining" }

            val renamed = repository.update(dining.id, "Eating out", dining.nature).expectOk()

            // It is the user's taxonomy, not the app's (P-07). The flag records only where the row
            // came from — the id is unchanged, so every transaction still points at it.
            assertEquals("Eating out", renamed.name)
            assertEquals(dining.id, renamed.id)
            assertTrue("renaming should not clear the system flag", renamed.isSystem)
        }

    @Test
    fun `a category may keep its own name when edited`() =
        runTest {
            val created = repository.create("Fuel", CategoryNature.NEED).expectOk()

            // The duplicate check has to exclude the row being edited, or changing only the nature
            // would report the category's own name as taken.
            val updated = repository.update(created.id, "Fuel", CategoryNature.WANT).expectOk()

            assertEquals(CategoryNature.WANT, updated.nature)
        }

    @Test
    fun `editing into another category's name is rejected`() =
        runTest {
            repository.create("Fuel", CategoryNature.NEED).expectOk()
            val chai = repository.create("Chai", CategoryNature.WANT).expectOk()

            assertEquals("name", repository.update(chai.id, "FUEL", CategoryNature.WANT).expectValidation())
        }

    @Test
    fun `a category cannot become its own parent`() =
        runTest {
            val created = repository.create("Transport", CategoryNature.NEED).expectOk()

            assertEquals(
                "parentId",
                repository.update(created.id, "Transport", CategoryNature.NEED, parentId = created.id)
                    .expectValidation(),
            )
        }

    @Test
    fun `a category with children cannot be given a parent`() =
        runTest {
            val transport = repository.create("Transport", CategoryNature.NEED).expectOk()
            val travel = repository.create("Travel", CategoryNature.WANT).expectOk()
            repository.create("Cabs", CategoryNature.NEED, parentId = transport.id).expectOk()

            // Otherwise Cabs -> Transport -> Travel is three levels, built one legal edit at a time.
            assertEquals(
                "parentId",
                repository.update(transport.id, "Transport", CategoryNature.NEED, parentId = travel.id)
                    .expectValidation(),
            )
        }

    @Test
    fun `updating a category that does not exist or was deleted reports not found`() =
        runTest {
            val created = repository.create("Chai", CategoryNature.WANT).expectOk()
            repository.delete(created.id).expectOk()

            assertEquals(AppError.NotFound, repository.update("category:ghost", "X", CategoryNature.WANT).expectErr())
            assertEquals(AppError.NotFound, repository.update(created.id, "X", CategoryNature.WANT).expectErr())
        }

    // --- delete ------------------------------------------------------------------------------------

    @Test
    fun `delete is soft — the row survives and only the live view drops it`() =
        runTest {
            val created = repository.create("Chai", CategoryNature.WANT).expectOk()

            repository.delete(created.id).expectOk()

            assertEquals(0, repository.observeCategories().first().size)
            val raw = database.categoryDao().findById(created.id)
            assertNotNull("DB-002: the row must still be there", raw)
            assertNotNull("no deletion stamp was written", raw?.deletedAtUtcMillis)
        }

    @Test
    fun `deleting a category leaves the transaction pointing at it`() =
        runTest {
            val created = repository.create("Chai", CategoryNature.WANT).expectOk()
            givenAccount()
            givenTransaction(id = "txn:1", categoryId = created.id)

            repository.delete(created.id).expectOk()

            // The money is not un-spent and history is not rewritten; the row simply starts reading
            // as Uncategorised, which is why the editor states the count before confirming.
            assertEquals(created.id, database.transactionDao().findById("txn:1")?.categoryId)
        }

    @Test
    fun `a category with live children cannot be deleted`() =
        runTest {
            val transport = repository.create("Transport", CategoryNature.NEED).expectOk()
            val cab = repository.create("Cabs", CategoryNature.NEED, parentId = transport.id).expectOk()

            assertEquals("children", repository.delete(transport.id).expectValidation())

            // Delete the child and the parent is free — the guard is about orphans, not about parents.
            repository.delete(cab.id).expectOk()
            repository.delete(transport.id).expectOk()
            assertEquals(0, repository.observeCategories().first().size)
        }

    @Test
    fun `deleting twice reports not found rather than stamping the row again`() =
        runTest {
            val created = repository.create("Chai", CategoryNature.WANT).expectOk()
            clock.setTo(FIRST_DELETE_MILLIS)
            repository.delete(created.id).expectOk()
            clock.setTo(SECOND_DELETE_MILLIS)

            assertEquals(AppError.NotFound, repository.delete(created.id).expectErr())

            // A second stamp would rewrite when the category was deleted — a false entry about the
            // user's own history, the same reason OnboardingWriter refuses to revoke an ungranted
            // consent.
            assertEquals(FIRST_DELETE_MILLIS, database.categoryDao().findById(created.id)?.deletedAtUtcMillis)
        }

    // --- usage count -------------------------------------------------------------------------------

    @Test
    fun `an unused category reports no usage`() =
        runTest {
            val created = repository.create("Chai", CategoryNature.WANT).expectOk()

            assertEquals(0, repository.countUsage(created.id).expectOk())
        }

    @Test
    fun `usage counts transactions and split lines together`() =
        runTest {
            val created = repository.create("Chai", CategoryNature.WANT).expectOk()
            val other = repository.create("Fuel", CategoryNature.NEED).expectOk()
            givenAccount()
            givenTransaction(id = "txn:1", categoryId = created.id)
            givenTransaction(id = "txn:2", categoryId = other.id)
            givenTransaction(id = "txn:3", categoryId = null)
            givenSplit(id = "split:1", transactionId = "txn:3", categoryId = created.id)

            // A split parent carries no category — the lines do (FR-TXN-004). Counting only
            // `transactions` would report 1 here and promise the user two lines were safe.
            assertEquals(2, repository.countUsage(created.id).expectOk())
            assertEquals(1, repository.countUsage(other.id).expectOk())
        }

    @Test
    fun `usage ignores deleted transactions and deleted split lines`() =
        runTest {
            val created = repository.create("Chai", CategoryNature.WANT).expectOk()
            givenAccount()
            givenTransaction(id = "txn:1", categoryId = created.id, deletedAtUtcMillis = FIRST_DELETE_MILLIS)
            givenTransaction(id = "txn:2", categoryId = null)
            givenSplit(
                id = "split:1",
                transactionId = "txn:2",
                categoryId = created.id,
                deletedAtUtcMillis = FIRST_DELETE_MILLIS,
            )

            assertEquals(0, repository.countUsage(created.id).expectOk())
        }

    // --- offline (P-04) ----------------------------------------------------------------------------

    @Test
    fun `the taxonomy is reachable with no network of any kind`() =
        runTest {
            // P-04: this repository is constructed from a database, a clock, dispatchers and a
            // profile id — there is no network collaborator to stub out, so the whole path below is
            // the airplane-mode path. Seed, read, write, delete, all offline.
            repository.ensureSeeded().expectOk()
            val created = repository.create("Chai", CategoryNature.WANT).expectOk()
            repository.update(created.id, "Coffee", CategoryNature.NEED).expectOk()
            repository.delete(created.id).expectOk()

            assertEquals(CategorySeed.rows.size, repository.observeCategories().first().size)
        }

    // --- fixtures ----------------------------------------------------------------------------------

    /**
     * Writes the account every transaction fixture hangs off.
     * Result: a row in `account`. Input: none. Output: none (suspends).
     * Changelog: 2026-08-08 — Created for issue 4.1.
     */
    private suspend fun givenAccount() {
        database.accountDao().upsert(
            AccountEntity(
                id = ACCOUNT_ID,
                profileId = REAL_PROFILE,
                name = "HDFC Savings",
                type = "bank",
                openingBalanceMinor = 0L,
                currentBalanceMinor = 0L,
                currencyCode = "INR",
                createdAtUtcMillis = clock.nowUtcMillis(),
                updatedAtUtcMillis = clock.nowUtcMillis(),
            ),
        )
    }

    /**
     * Writes one transaction against a category.
     * Result: a row in `transactions`. Input: [id]; [categoryId] — `null` for a split parent;
     *         [deletedAtUtcMillis]. Output: none (suspends).
     * Changelog: 2026-08-08 — Created for issue 4.1.
     */
    private suspend fun givenTransaction(
        id: String,
        categoryId: String?,
        deletedAtUtcMillis: Long? = null,
    ) {
        database.transactionDao().upsert(
            TransactionEntity(
                id = id,
                profileId = REAL_PROFILE,
                accountId = ACCOUNT_ID,
                amountMinor = -100_00L,
                currencyCode = "INR",
                occurredAtUtcMillis = clock.nowUtcMillis(),
                bookedOnIsoDate = clock.today().toString(),
                categoryId = categoryId,
                type = "expense",
                source = "manual",
                createdAtUtcMillis = clock.nowUtcMillis(),
                updatedAtUtcMillis = clock.nowUtcMillis(),
                deletedAtUtcMillis = deletedAtUtcMillis,
            ),
        )
    }

    /**
     * Writes one split line against a category.
     * Result: a row in `transaction_splits`. Input: [id]; [transactionId] — the parent;
     *         [categoryId]; [deletedAtUtcMillis]. Output: none (suspends).
     * Changelog: 2026-08-08 — Created for issue 4.1.
     */
    private suspend fun givenSplit(
        id: String,
        transactionId: String,
        categoryId: String,
        deletedAtUtcMillis: Long? = null,
    ) {
        database.transactionSplitDao().upsertAll(
            listOf(
                TransactionSplitEntity(
                    id = id,
                    profileId = REAL_PROFILE,
                    transactionId = transactionId,
                    amountMinor = -100_00L,
                    categoryId = categoryId,
                    createdAtUtcMillis = clock.nowUtcMillis(),
                    updatedAtUtcMillis = clock.nowUtcMillis(),
                    deletedAtUtcMillis = deletedAtUtcMillis,
                ),
            ),
        )
    }

    /**
     * Builds a category row directly, bypassing the repository.
     * Why:    the demo test needs a row that did *not* come from the seed, which is the only way to
     *         reproduce the state `DemoDataset` leaves behind.
     * Result: a [CategoryEntity]. Input: [id]; [name]; [profileId]. Output: the entity.
     * Changelog: 2026-08-08 — Created for issue 4.1.
     */
    private fun row(
        id: String,
        name: String,
        profileId: String,
    ): CategoryEntity =
        CategoryEntity(
            id = id,
            profileId = profileId,
            name = name,
            nature = "want",
            isSystem = false,
            createdAtUtcMillis = clock.nowUtcMillis(),
            updatedAtUtcMillis = clock.nowUtcMillis(),
        )

    private companion object {
        const val REAL_PROFILE = "local"
        const val DEMO_PROFILE = "demo"
        const val ACCOUNT_ID = "account:1"
        const val FIRST_DELETE_MILLIS = 1_800_000_000_000L
        const val SECOND_DELETE_MILLIS = 1_800_000_600_000L
    }
}

/**
 * Unwraps a result the test expects to have succeeded.
 * Why:    a failure here names the error rather than throwing a bare `ClassCastException`.
 * Result: the value. Input: the receiver. Output: [T].
 * Changelog: 2026-08-08 — Created for issue 4.1.
 */
private fun <T> com.aicfo.core.common.Result<T, AppError>.expectOk(): T =
    when (this) {
        is Ok -> value
        is Err -> throw AssertionError("expected Ok, got Err(${error.code})")
    }

/**
 * Unwraps a result the test expects to have failed.
 * Result: the error. Input: the receiver. Output: [AppError].
 * Changelog: 2026-08-08 — Created for issue 4.1.
 */
private fun <T> com.aicfo.core.common.Result<T, AppError>.expectErr(): AppError =
    when (this) {
        is Ok -> throw AssertionError("expected Err, got Ok($value)")
        is Err -> error
    }

/**
 * Unwraps the field name from a result the test expects to have failed validation.
 * Why:    asserting the *field* rather than merely "it failed" is what stops a rejection landing on
 *         the wrong input — a name error reported as `parentId` would highlight the wrong box.
 * Result: the offending field's name. Input: the receiver. Output: [String].
 * Changelog: 2026-08-08 — Created for issue 4.1.
 */
private fun <T> com.aicfo.core.common.Result<T, AppError>.expectValidation(): String =
    when (val error = expectErr()) {
        is AppError.Validation -> error.field
        else -> throw AssertionError("expected Validation, got $error")
    }
