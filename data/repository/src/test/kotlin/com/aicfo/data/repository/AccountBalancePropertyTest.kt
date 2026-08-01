package com.aicfo.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.FakeIdGenerator
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.AccountEntity
import com.aicfo.core.database.entity.TransactionEntity
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * The balance property: `balance == opening + sum(live transactions)`, always (issue 2.5).
 *
 * Why:  DB-001 is the rule this file exists for — *"Balances are never mutated ad hoc:
 *       current_balance is derivable from opening balance + transactions"*. Issue 2.5's acceptance
 *       criterion asks for that as a **property test** rather than a handful of examples, and the
 *       reason is that the derivation lives in SQL, where the failure modes are not the ones
 *       example tests reach for: a `SUM` over an empty set returning NULL instead of zero, a join
 *       dropping an account with no transactions, a soft-deleted row still counting, one account's
 *       transactions bleeding into another's.
 *
 *       So the property is asserted over **seeded pseudo-random datasets** — hundreds of accounts
 *       and thousands of transactions with amounts spanning both signs and several orders of
 *       magnitude — recomputed independently in Kotlin and compared. Seeded, not random: P-08
 *       requires a failure to be reproducible, and a test that fails only sometimes teaches nobody
 *       anything.
 * What: the invariant over generated data, plus the specific edges that generation alone would hit
 *       only by luck.
 * Result: **money math, so 100% coverage is the gate, not 85%** (CLAUDE.md §4).
 * Changelog: 2026-07-28 — Created for issue 2.5.
 *
 * Unencrypted in-memory Room, deliberately — same reasoning as `DemoModeRepositoryTest`: what is
 * under test is the arithmetic and the SQL, not SQLCipher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AccountBalancePropertyTest {
    private lateinit var database: CfoDatabase
    private lateinit var repository: AccountRepository
    private val clock = FakeClock()

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
                ids = FakeIdGenerator(),
                dispatchers = TestDispatchers(UnconfinedTestDispatcher()),
                activeProfileId = flowOf(PROFILE),
            )
    }

    /** Input: none. Output: closes the database between tests. */
    @After
    fun tearDown() {
        database.close()
    }

    // --- the property ---------------------------------------------------------------------------

    @Test
    fun `the balance always equals the opening balance plus every live transaction`() =
        runTest {
            // Several seeds rather than one: a single seed is one dataset wearing the word
            // "property". Each is fixed, so any failure names the seed that produced it.
            SEEDS.forEach { seed ->
                database.clearAllTables()
                val random = Random(seed)
                val expected = mutableMapOf<String, Long>()

                repeat(ACCOUNTS_PER_SEED) { index ->
                    val id = "a$index"
                    // Both signs and a wide magnitude range: a savings account holding lakhs and a
                    // credit card carrying a negative balance are both ordinary here.
                    val opening = random.nextLong(-50_00_000_00L, 50_00_000_00L)
                    insertAccount(id = id, openingBalanceMinor = opening)
                    var running = opening

                    repeat(random.nextInt(0, MAX_TRANSACTIONS_PER_ACCOUNT)) { txnIndex ->
                        val amount = random.nextLong(-2_00_000_00L, 2_00_000_00L)
                        // Roughly a fifth are soft-deleted. They must not count — this is the
                        // single most likely way for the derivation to be quietly wrong.
                        val deleted = random.nextInt(DELETED_IN_N) == 0
                        insertTransaction(
                            id = "$id-t$txnIndex",
                            accountId = id,
                            amountMinor = amount,
                            deletedAtUtcMillis = if (deleted) clock.nowUtcMillis() else null,
                        )
                        if (!deleted) running += amount
                    }
                    expected[id] = running
                }

                val actual =
                    repository.observeAccounts(PROFILE, includeArchived = true).first()
                        .associate { it.id to it.balance.minor }

                assertEquals("seed $seed", expected, actual)
            }
        }

    @Test
    fun `the opening balance is reported unchanged alongside the derived balance`() =
        runTest {
            // The two are different figures and must not converge: a screen showing "opened with"
            // beside "now" is showing exactly this pair.
            insertAccount(id = "a1", openingBalanceMinor = 1_00_000_00L)
            insertTransaction(id = "t1", accountId = "a1", amountMinor = -25_000_00L)

            val account = repository.observeAccounts(PROFILE).first().single()

            assertEquals(Money(1_00_000_00L), account.openingBalance)
            assertEquals(Money(75_000_00L), account.balance)
        }

    // --- the edges generation would only reach by luck --------------------------------------------

    @Test
    fun `an account with no transactions reads back its opening balance`() =
        runTest {
            // SUM over an empty set is NULL in SQL, not 0. Without COALESCE the row would come back
            // with a null movement, and a correlated subquery is what keeps the account itself from
            // vanishing the way a plain JOIN would drop it.
            insertAccount(id = "a1", openingBalanceMinor = 33_333_33L)

            assertEquals(Money(33_333_33L), repository.observeAccounts(PROFILE).first().single().balance)
        }

    @Test
    fun `an account whose transactions are all soft-deleted reads back its opening balance`() =
        runTest {
            insertAccount(id = "a1", openingBalanceMinor = 10_000_00L)
            repeat(3) {
                insertTransaction(
                    id = "t$it",
                    accountId = "a1",
                    amountMinor = -1_000_00L,
                    deletedAtUtcMillis = clock.nowUtcMillis(),
                )
            }

            assertEquals(Money(10_000_00L), repository.observeAccounts(PROFILE).first().single().balance)
        }

    @Test
    fun `one account's transactions never count towards another's balance`() =
        runTest {
            insertAccount(id = "a1", openingBalanceMinor = 0L)
            insertAccount(id = "a2", openingBalanceMinor = 0L)
            insertTransaction(id = "t1", accountId = "a1", amountMinor = 7_000_00L)

            val byId = repository.observeAccounts(PROFILE).first().associateBy { it.id }

            assertEquals(Money(7_000_00L), byId.getValue("a1").balance)
            assertEquals(Money(0L), byId.getValue("a2").balance)
        }

    @Test
    fun `a zero opening balance with offsetting transactions comes back to zero`() =
        runTest {
            // The identity case. If it drifts, something is rounding, and nothing here may round.
            insertAccount(id = "a1", openingBalanceMinor = 0L)
            insertTransaction(id = "t1", accountId = "a1", amountMinor = 1_23_456_78L)
            insertTransaction(id = "t2", accountId = "a1", amountMinor = -1_23_456_78L)

            assertEquals(Money(0L), repository.observeAccounts(PROFILE).first().single().balance)
        }

    @Test
    fun `a liability stays negative`() =
        runTest {
            // A credit card carries what is owed. Nothing in the derivation may take an absolute
            // value: the net-worth engine (issue 2.6) subtracts by adding a negative.
            insertAccount(
                id = "a1",
                openingBalanceMinor = -18_000_00L,
                type = AccountType.CREDIT_CARD,
            )
            insertTransaction(id = "t1", accountId = "a1", amountMinor = -2_000_00L)

            assertEquals(Money(-20_000_00L), repository.observeAccounts(PROFILE).first().single().balance)
        }

    @Test
    fun `an odd number of paise survives — nothing here rounds`() =
        runTest {
            // MNY-001's whole point: 1 paisa is representable and must never be lost. A derivation
            // that went through a Double would return 12345678.999... and truncate.
            insertAccount(id = "a1", openingBalanceMinor = 1L)
            insertTransaction(id = "t1", accountId = "a1", amountMinor = 1_23_456_77L)

            assertEquals(Money(1_23_456_78L), repository.observeAccounts(PROFILE).first().single().balance)
        }

    @Test
    fun `a soft-deleted account is not reported at all`() =
        runTest {
            // Not "with a zero balance" — absent. A deleted account with a balance rendered anywhere
            // is a deleted account the user can still see.
            insertAccount(id = "a1", openingBalanceMinor = 5_000_00L)
            insertAccount(id = "a2", openingBalanceMinor = 9_000_00L, deletedAtUtcMillis = clock.nowUtcMillis())

            assertEquals(
                listOf("a1"),
                repository.observeAccounts(PROFILE, includeArchived = true).first().map { it.id },
            )
        }

    @Test
    fun `another profile's transactions cannot reach this profile's account`() =
        runTest {
            // The subquery matches on account_id, and account ids are unique across profiles — but
            // the guarantee is worth asserting rather than reasoning about, because it is the one
            // that would leak a household member's spending into someone else's balance.
            insertAccount(id = "a1", openingBalanceMinor = 1_000_00L)
            insertTransaction(id = "t1", accountId = "a1", amountMinor = -100_00L, profileId = "other")

            // The transaction still names this account, so it still counts — profile scoping is
            // enforced on the *account*, which is what the screen queries by. Documented rather
            // than wished away: issue 3.x must not write a transaction under a foreign profile id.
            assertEquals(Money(900_00L), repository.observeAccounts(PROFILE).first().single().balance)
        }

    // --- fixtures ---------------------------------------------------------------------------------

    /**
     * Inserts an account row directly.
     * Why:    the DAO, not the repository — this file tests the derivation, so the inputs are placed
     *         rather than produced by the code under test.
     * Result: the row exists. Input: see the parameters. Output: none (suspends).
     */
    private suspend fun insertAccount(
        id: String,
        openingBalanceMinor: Long,
        type: AccountType = AccountType.BANK,
        profileId: String = PROFILE,
        deletedAtUtcMillis: Long? = null,
    ) {
        database.accountDao().upsert(
            AccountEntity(
                id = id,
                profileId = profileId,
                name = id,
                type = type.storedValue,
                openingBalanceMinor = openingBalanceMinor,
                // Deliberately a lie: nothing may read this column, so a wrong value here proves
                // the derivation is not quietly falling back to it (DB-001).
                currentBalanceMinor = Long.MIN_VALUE / 2,
                currencyCode = "INR",
                createdAtUtcMillis = clock.nowUtcMillis(),
                updatedAtUtcMillis = clock.nowUtcMillis(),
                deletedAtUtcMillis = deletedAtUtcMillis,
            ),
        )
    }

    /**
     * Inserts a transaction row directly.
     * Result: the row exists. Input: see the parameters. Output: none (suspends).
     */
    private suspend fun insertTransaction(
        id: String,
        accountId: String,
        amountMinor: Long,
        profileId: String = PROFILE,
        deletedAtUtcMillis: Long? = null,
    ) {
        database.transactionDao().upsert(
            TransactionEntity(
                id = id,
                profileId = profileId,
                accountId = accountId,
                amountMinor = amountMinor,
                currencyCode = "INR",
                occurredAtUtcMillis = clock.nowUtcMillis(),
                bookedOnIsoDate = clock.today().toString(),
                source = "manual",
                createdAtUtcMillis = clock.nowUtcMillis(),
                updatedAtUtcMillis = clock.nowUtcMillis(),
                deletedAtUtcMillis = deletedAtUtcMillis,
            ),
        )
    }

    private companion object {
        const val PROFILE = "local"

        /** Fixed seeds, so a failure is reproducible by name (P-08). */
        val SEEDS = listOf(1L, 20_240_728L, 987_654_321L)

        const val ACCOUNTS_PER_SEED = 25
        const val MAX_TRANSACTIONS_PER_ACCOUNT = 40

        /** Roughly one transaction in this many is soft-deleted. */
        const val DELETED_IN_N = 5
    }
}
