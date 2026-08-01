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
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [AccountRepository] — the CRUD half of issue 2.5 (FR-ACC-001, FR-ACC-007, DB-002).
 *
 * Why:  four properties decide whether this class is safe to build the rest of the app on, and none
 *       of them is visible from reading it. **Every type round-trips** — FR-ACC-001 is a MUST and a
 *       type that cannot survive a write is a type the app does not really support. **Delete is
 *       soft** — DB-002, and the row must still be there afterwards, which only a raw read can
 *       show. **Archive is not delete** — FR-ACC-007 wants a closed account's history kept while it
 *       leaves active totals, so the two must be independently observable. And **profiles do not
 *       leak**, because the demo lives in a second profile and a leak either way is the worst bug
 *       this feature can have.
 * What: the write paths, the read paths, validation, and the demo/real switch.
 * Result: the persistence half of accounts is proven against a real SQL engine.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 *
 * Unencrypted in-memory Room, deliberately — the same reasoning as `DemoModeRepositoryTest`: what
 * is under test is the SQL and the ordering, not SQLCipher. `EncryptedDatabaseTest` (androidTest)
 * is what proves the file on disk is ciphertext.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AccountRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var repository: AccountRepository
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
            RepositoryFactory.accounts(
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

    // --- create ------------------------------------------------------------------------------------

    @Test
    fun `creating an account returns it with a generated id`() =
        runTest {
            val created = repository.create(draft { copy(name = "HDFC Savings") })

            assertTrue(created is Ok)
            val account = (created as Ok).value
            assertEquals("account:1", account.id)
            assertEquals("HDFC Savings", account.name)
            assertEquals(REAL_PROFILE, account.profileId)
        }

    @Test
    fun `a new account's balance is its opening balance — it has no transactions yet`() =
        runTest {
            val account = repository.create(draft { copy(openingBalance = Money(50_000_00L)) }).expectOk()

            assertEquals(Money(50_000_00L), account.openingBalance)
            assertEquals(Money(50_000_00L), account.balance)
        }

    @Test
    fun `every one of FR-ACC-001's account types round-trips`() =
        runTest {
            // The requirement is a MUST and it names eleven. A type the store cannot return is a
            // type the app does not really support, however well the picker renders it.
            AccountType.entries.forEach { type ->
                repository.create(draft { copy(name = type.storedValue, type = type) }).expectOk()
            }

            val stored = repository.observeAccounts(REAL_PROFILE).first().associate { it.name to it.type }

            assertEquals(AccountType.entries.size, stored.size)
            AccountType.entries.forEach { type ->
                assertEquals("$type must survive the round trip", type, stored[type.storedValue])
            }
        }

    @Test
    fun `two accounts may share a name — that is why ids are generated`() =
        runTest {
            // The reason `budgetId()`-style derivation is wrong here: a derived id would make the
            // second write silently REPLACE the first.
            repository.create(draft { copy(name = "HDFC Savings") }).expectOk()
            repository.create(draft { copy(name = "HDFC Savings") }).expectOk()

            assertEquals(2, repository.observeAccounts(REAL_PROFILE).first().size)
        }

    @Test
    fun `a liability is stored with its sign intact`() =
        runTest {
            val card =
                repository.create(
                    draft { copy(type = AccountType.CREDIT_CARD, openingBalance = Money(-18_000_00L)) },
                ).expectOk()

            assertEquals(Money(-18_000_00L), card.balance)
        }

    @Test
    fun `an account may be created without an institution`() =
        runTest {
            assertNull(repository.create(draft { copy(institution = null) }).expectOk().institution)
        }

    @Test
    fun `names and institutions are trimmed, so two spellings cannot look identical`() =
        runTest {
            val account =
                repository.create(
                    draft { copy(name = "  HDFC Savings  ", institution = "  HDFC  ") },
                ).expectOk()

            assertEquals("HDFC Savings", account.name)
            assertEquals("HDFC", account.institution)
        }

    @Test
    fun `a whitespace-only institution is stored as absent, not as blank`() =
        runTest {
            assertNull(repository.create(draft { copy(institution = "   ") }).expectOk().institution)
        }

    // --- validation --------------------------------------------------------------------------------

    @Test
    fun `a blank name is rejected and nothing is written`() =
        runTest {
            val result = repository.create(draft { copy(name = "   ") })

            assertEquals(AppError.Validation("name"), (result as Err).error)
            assertTrue(repository.observeAccounts(REAL_PROFILE).first().isEmpty())
        }

    @Test
    fun `a rejected draft does not consume an id`() =
        runTest {
            // Validation happens before the generator is touched. An id burned on a rejected write
            // is harmless but it means the failure left a trace, and it makes golden ids drift.
            repository.create(draft { copy(name = "") })

            assertEquals(0, ids.issuedCount)
        }

    // --- read --------------------------------------------------------------------------------------

    @Test
    fun `find returns the account`() =
        runTest {
            val created = repository.create(draft { copy(name = "Cash") }).expectOk()

            assertEquals(created, repository.find(created.id).expectOk())
        }

    @Test
    fun `find on an unknown id is NotFound, not null`() =
        runTest {
            assertEquals(AppError.NotFound, (repository.find("nope") as Err).error)
        }

    @Test
    fun `an account whose stored type this build cannot parse is skipped, not thrown on`() =
        runTest {
            // A row from a newer build. The list shows fewer accounts; it does not crash.
            database.accountDao().upsert(rawAccount(id = "a1", type = "timeshare"))
            repository.create(draft { copy(name = "Cash") }).expectOk()

            assertEquals(listOf("Cash"), repository.observeAccounts(REAL_PROFILE).first().map { it.name })
        }

    @Test
    fun `accounts come back name-ordered`() =
        runTest {
            repository.create(draft { copy(name = "Zerodha") }).expectOk()
            repository.create(draft { copy(name = "Axis") }).expectOk()

            assertEquals(listOf("Axis", "Zerodha"), repository.observeAccounts(REAL_PROFILE).first().map { it.name })
        }

    // --- update ------------------------------------------------------------------------------------

    @Test
    fun `updating changes the fields the user owns`() =
        runTest {
            val created = repository.create(draft { copy(name = "HDFC", type = AccountType.BANK) }).expectOk()

            val updated =
                repository.update(
                    created.id,
                    draft { copy(name = "HDFC Salary", type = AccountType.BANK, institution = "HDFC Bank") },
                ).expectOk()

            assertEquals(created.id, updated.id)
            assertEquals("HDFC Salary", updated.name)
            assertEquals("HDFC Bank", updated.institution)
        }

    @Test
    fun `updating the opening balance re-derives the balance`() =
        runTest {
            val created = repository.create(draft { copy(openingBalance = Money(10_000_00L)) }).expectOk()

            val updated = repository.update(created.id, draft { copy(openingBalance = Money(25_000_00L)) }).expectOk()

            assertEquals(Money(25_000_00L), updated.balance)
        }

    @Test
    fun `updating an unknown id is NotFound`() =
        runTest {
            assertEquals(AppError.NotFound, (repository.update("nope", draft()) as Err).error)
        }

    @Test
    fun `an update with a blank name is rejected and changes nothing`() =
        runTest {
            val created = repository.create(draft { copy(name = "Cash") }).expectOk()

            val result = repository.update(created.id, draft { copy(name = "") })

            assertEquals(AppError.Validation("name"), (result as Err).error)
            assertEquals("Cash", repository.find(created.id).expectOk().name)
        }

    // --- the net-worth opt-out (issue 2.6, FR-ACC-005) ---------------------------------------------

    @Test
    fun `an account counts towards net worth by default`() =
        runTest {
            assertTrue(repository.create(draft()).expectOk().includeInNetWorth)
        }

    @Test
    fun `creating an account opted out persists the choice`() =
        runTest {
            val created = repository.create(draft { copy(includeInNetWorth = false) }).expectOk()

            assertFalse(created.includeInNetWorth)
            assertFalse(repository.find(created.id).expectOk().includeInNetWorth)
        }

    @Test
    fun `updating an account can opt it out of net worth`() =
        runTest {
            // The path the editor takes. Read back through `find`, not from the returned value, so
            // this proves the column was written rather than that the object was constructed.
            val created = repository.create(draft()).expectOk()

            repository.update(created.id, draft { copy(includeInNetWorth = false) }).expectOk()

            assertFalse(repository.find(created.id).expectOk().includeInNetWorth)
        }

    @Test
    fun `updating can opt an account back in`() =
        runTest {
            val created = repository.create(draft { copy(includeInNetWorth = false) }).expectOk()

            repository.update(created.id, draft { copy(includeInNetWorth = true) }).expectOk()

            assertTrue(repository.find(created.id).expectOk().includeInNetWorth)
        }

    // --- archive (FR-ACC-007) ----------------------------------------------------------------------

    @Test
    fun `an archived account leaves the active list but keeps its row`() =
        runTest {
            val created = repository.create(draft { copy(name = "Old Card") }).expectOk()

            assertTrue(repository.setArchived(created.id, archived = true) is Ok)

            assertTrue(repository.observeAccounts(REAL_PROFILE).first().isEmpty())
            assertEquals(
                listOf("Old Card"),
                repository.observeAccounts(REAL_PROFILE, includeArchived = true).first().map { it.name },
            )
        }

    @Test
    fun `an archived account reports itself as archived`() =
        runTest {
            val created = repository.create(draft()).expectOk()
            repository.setArchived(created.id, archived = true)

            assertTrue(repository.find(created.id).expectOk().isArchived)
        }

    @Test
    fun `archiving is reversible`() =
        runTest {
            val created = repository.create(draft { copy(name = "Reopened") }).expectOk()
            repository.setArchived(created.id, archived = true)

            assertTrue(repository.setArchived(created.id, archived = false) is Ok)

            assertEquals(listOf("Reopened"), repository.observeAccounts(REAL_PROFILE).first().map { it.name })
            assertFalse(repository.find(created.id).expectOk().isArchived)
        }

    @Test
    fun `archiving an unknown id is NotFound`() =
        runTest {
            assertEquals(AppError.NotFound, (repository.setArchived("nope", archived = true) as Err).error)
        }

    // --- delete (DB-002) ---------------------------------------------------------------------------

    @Test
    fun `deleting is soft — the row survives`() =
        runTest {
            val created = repository.create(draft { copy(name = "Mistake") }).expectOk()

            assertTrue(repository.delete(created.id) is Ok)

            assertTrue(repository.observeAccounts(REAL_PROFILE, includeArchived = true).first().isEmpty())
            // Read past the repository: DB-002 is about the row still being there, which no
            // repository-level read can show, because every one of them filters tombstones.
            assertNotNull("DB-002: a soft delete must leave the row", rawRow(created.id))
            assertEquals(clock.nowUtcMillis(), rawRow(created.id)?.deletedAtUtcMillis)
        }

    @Test
    fun `deleting twice reports NotFound the second time`() =
        runTest {
            // Not `Ok` twice: a screen that confirms a delete which never happened is lying to the
            // user. This is why the DAO's UPDATE filters on `deleted_at_utc_millis IS NULL`.
            val created = repository.create(draft()).expectOk()
            repository.delete(created.id)

            assertEquals(AppError.NotFound, (repository.delete(created.id) as Err).error)
        }

    @Test
    fun `deleting an unknown id is NotFound`() =
        runTest {
            assertEquals(AppError.NotFound, (repository.delete("nope") as Err).error)
        }

    @Test
    fun `a deleted account is gone from find as well as from the list`() =
        runTest {
            val created = repository.create(draft()).expectOk()
            repository.delete(created.id)

            assertEquals(AppError.NotFound, (repository.find(created.id) as Err).error)
        }

    // --- profile scoping and the demo switch --------------------------------------------------------

    @Test
    fun `an account created under one profile is invisible to another`() =
        runTest {
            repository.create(draft { copy(name = "Real") }).expectOk()

            assertTrue(repository.observeAccounts(DEMO_PROFILE).first().isEmpty())
        }

    @Test
    fun `the no-argument read follows the active profile`() =
        runTest {
            repository.create(draft { copy(name = "Real") }).expectOk()
            activeProfileId.value = DEMO_PROFILE
            repository.create(draft { copy(name = "Sample") }).expectOk()

            assertEquals(listOf("Sample"), repository.observeAccounts().first().map { it.name })

            activeProfileId.value = REAL_PROFILE
            assertEquals(listOf("Real"), repository.observeAccounts().first().map { it.name })
        }

    @Test
    fun `a write lands under whichever profile is active at the time`() =
        runTest {
            // The reason the profile is read per write rather than cached at construction: an
            // account created while exploring the demo must not end up in the user's real data.
            activeProfileId.value = DEMO_PROFILE
            val created = repository.create(draft { copy(name = "Sample") }).expectOk()

            assertEquals(DEMO_PROFILE, created.profileId)
        }

    // --- helpers ------------------------------------------------------------------------------------

    /**
     * Result: a usable draft with every field overridable. Input: the named parameters.
     * Output: [AccountDraft].
     */
    private fun draft(changes: AccountDraft.() -> AccountDraft = { this }): AccountDraft = BASE_DRAFT.changes()

    /**
     * Result: an entity written straight to the DAO, bypassing validation — for rows the repository
     *         could not itself create, such as an unknown type. Input: [id], [type]. Output: an entity.
     */
    private fun rawAccount(
        id: String,
        type: String,
    ) = AccountEntity(
        id = id,
        profileId = REAL_PROFILE,
        name = "From the future",
        type = type,
        openingBalanceMinor = 0L,
        currentBalanceMinor = 0L,
        currencyCode = "INR",
        createdAtUtcMillis = clock.nowUtcMillis(),
        updatedAtUtcMillis = clock.nowUtcMillis(),
    )

    /**
     * Result: the raw row including tombstones, or null. Input: [id]. Output: `AccountEntity?`.
     *
     * Reaches past the repository on purpose: every repository read filters soft-deleted rows, so
     * "the row is still there" is not a claim any of them can make.
     */
    private suspend fun rawRow(id: String): AccountEntity? =
        database.query("SELECT * FROM account WHERE id = ?", arrayOf<Any>(id)).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            AccountEntity(
                id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                profileId = cursor.getString(cursor.getColumnIndexOrThrow("profile_id")),
                name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                openingBalanceMinor = cursor.getLong(cursor.getColumnIndexOrThrow("opening_balance_minor")),
                currentBalanceMinor = cursor.getLong(cursor.getColumnIndexOrThrow("current_balance_minor")),
                currencyCode = cursor.getString(cursor.getColumnIndexOrThrow("currency_code")),
                createdAtUtcMillis = cursor.getLong(cursor.getColumnIndexOrThrow("created_at_utc_millis")),
                updatedAtUtcMillis = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at_utc_millis")),
                deletedAtUtcMillis =
                    cursor.getColumnIndexOrThrow("deleted_at_utc_millis").let { index ->
                        if (cursor.isNull(index)) null else cursor.getLong(index)
                    },
            )
        }

    private companion object {
        /**
         * The default draft every [draft] call starts from.
         *
         * `copy` rather than a parameter per field: an [AccountDraft] has six, and a helper taking
         * them all is the longest parameter list in the module for no gain — the same shape
         * `feature/accounts`'s `account()` fixture settled on for the same reason.
         */
        val BASE_DRAFT =
            AccountDraft(
                name = "HDFC Savings",
                type = AccountType.BANK,
                openingBalance = Money(1_00_000_00L),
                currencyCode = "INR",
                institution = "HDFC Bank",
            )

        const val REAL_PROFILE = "local"
        const val DEMO_PROFILE = "demo"
    }
}

/**
 * Unwraps a result the test expects to have succeeded.
 * Why:    `(result as Ok).value` at thirty call sites buries the assertion that matters. A failure
 *         here names the error rather than throwing a bare `ClassCastException`.
 * Result: the value. Input: the receiver. Output: [T].
 * Changelog: 2026-07-28 — Created for issue 2.5.
 */
private fun <T> com.aicfo.core.common.Result<T, AppError>.expectOk(): T =
    when (this) {
        is Ok -> value
        is Err -> throw AssertionError("expected Ok, got Err(${error.code})")
    }
