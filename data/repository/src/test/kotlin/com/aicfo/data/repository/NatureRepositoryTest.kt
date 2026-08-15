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
import com.aicfo.core.model.CategoryNature
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.classification.ClassificationEngineFactory
import com.aicfo.domain.engines.nature.NatureEngineFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * The data half of nature classification — issue 4.3 (SRS §8.3, ARC-005).
 *
 * Why:  `NatureEngineTest` and the golden file already prove the decision order against fixtures, so
 *       repeating it here would assert nothing new. **What is unproven above SQLite is the joins**,
 *       and §8.3.1 branches on five of them:
 *
 *       - the account's type and the *counterpart* account's type, which live one `transfer_id`
 *         apart in the same table (issue 3.2's two-row transfer) — the query that finds the second
 *         leg is the only reason steps 2 and 3 can fire at all;
 *       - the category's nature, one join away;
 *       - the user's past overrides, which are rows in the column this issue added;
 *       - the category's median, which SQLite has no function for.
 *
 *       Each is a `WHERE` clause that can be wrong while still returning rows, and a wrong nature is
 *       **invisible**: it moves a number inside the 50/30/20 ring and the savings rate, both of
 *       which look perfectly ordinary while being built on the wrong answer.
 * What: the override round trip, each join that feeds a step, and the monthly fold end to end.
 * Result: the taxonomy the advice layer will be built on is proven against a real SQL engine.
 * Changelog: 2026-08-10 — Created for issue 4.3.
 *
 * Unencrypted in-memory Room, deliberately — the same reasoning as [TransactionRepositoryTest]. The
 * **real** engines rather than stubs, for the reason those tests take the real `AccountRepository`:
 * the claim is that a nature reaches the screen, and a stub could not make it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NatureRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var repository: TransactionRepository
    private lateinit var accounts: AccountRepository
    private lateinit var categories: CategoryRepository

    // Mid-month on purpose: observeNatureBreakdown windows the *calendar* month, and a clock on the
    // 1st or the 31st would let an off-by-one bound pass.
    private val clock = FakeClock(initialMillis = Instant.parse("2026-08-14T06:00:00Z").toEpochMilli())
    private val ids = FakeIdGenerator()
    private val activeProfileId = MutableStateFlow(REAL_PROFILE)

    /** Input: none. Output: a fresh in-memory database, the repositories, and a seeded taxonomy. */
    @Before
    fun setUp() =
        runTest {
            database =
                Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    CfoDatabase::class.java,
                ).allowMainThreadQueries().build()
            val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
            repository =
                RepositoryFactory.transactions(
                    database, clock, ids, dispatchers, activeProfileId, ClassificationEngineFactory.create(),
                    NatureEngineFactory.create(),
                )
            accounts = RepositoryFactory.accounts(database, clock, ids, dispatchers, activeProfileId)
            categories = RepositoryFactory.categories(database, clock, ids, dispatchers, activeProfileId)
            categories.ensureSeeded()
        }

    /** Input: none. Output: closes the database between tests. */
    @After
    fun tearDown() {
        database.close()
    }

    // --- the joins that feed the decision order ----------------------------------------------------

    /**
     * Input:  an ordinary grocery expense on a bank account.
     * Output: NEED by the category, citing `CLS-NAT-005`. The simplest path, and the one that proves
     *         the category join reaches the engine at all.
     */
    @Test
    fun `a categorised expense takes its category's nature`() =
        runTest {
            val bank = newAccount(AccountType.BANK)
            val txn = expense(bank.id, Money(-4_500_00L), categoryId = idOf("Groceries"))

            val verdict = repository.natureOf(txn).expectOk()

            assertEquals(CategoryNature.NEED, verdict.nature)
            assertEquals(listOf("CLS-NAT-005"), verdict.provenance.evidence.map { it.ruleId })
        }

    /**
     * The join this issue exists for.
     * Input:  a transfer from the bank into an investment account, **with no category at all**.
     * Output: INVESTMENT, citing `CLS-NAT-002`. Nothing but the counterpart account's type can say
     *         so, and finding it means matching the sibling row by `transfer_id` — the query that
     *         makes steps 2 and 3 possible. Without it this reads as a flagged Want.
     */
    @Test
    fun `a transfer into an investment account is investing, with no category involved`() =
        runTest {
            val bank = newAccount(AccountType.BANK)
            val sip = newAccount(AccountType.INVESTMENT, name = "Groww SIP")
            repository.createTransfer(
                TransferDraft(fromAccountId = bank.id, toAccountId = sip.id, amount = Money(10_000_00L)),
            ).expectOk()

            val outgoing = repository.natureOf(legIn(bank.id)).expectOk()
            val incoming = repository.natureOf(legIn(sip.id)).expectOk()

            assertEquals(CategoryNature.INVEST, outgoing.nature)
            assertEquals(listOf("CLS-NAT-002"), outgoing.provenance.evidence.map { it.ruleId })
            // Both legs, because both describe the same becoming — and the breakdown below is what
            // proves that does not double-count.
            assertEquals(CategoryNature.INVEST, incoming.nature)
        }

    /**
     * Input:  a transfer into a gold account, categorised Shopping — a WANT.
     * Output: ASSET. The account beats the category, and only the sibling-row join can tell.
     */
    @Test
    fun `a gold purchase is an asset even when it is tagged shopping`() =
        runTest {
            val bank = newAccount(AccountType.BANK)
            val gold = newAccount(AccountType.GOLD, name = "Sovereign gold")
            repository.createTransfer(
                TransferDraft(fromAccountId = bank.id, toAccountId = gold.id, amount = Money(80_000_00L)),
            ).expectOk()

            assertEquals(CategoryNature.ASSET, repository.natureOf(legIn(bank.id)).expectOk().nature)
        }

    /**
     * Input:  a transfer between two bank accounts.
     * Output: the flagged fallback — no account step claims it. Asserted so the *flag* is proven to
     *         reach the screen, and because the breakdown test below relies on this being the shape
     *         a self-transfer takes.
     */
    @Test
    fun `a self-transfer has no account signal and says so`() =
        runTest {
            val one = newAccount(AccountType.BANK)
            val two = newAccount(AccountType.BANK, name = "ICICI Savings")
            repository.createTransfer(
                TransferDraft(fromAccountId = one.id, toAccountId = two.id, amount = Money(10_000_00L)),
            ).expectOk()

            val verdict = repository.natureOf(legIn(one.id)).expectOk()

            assertTrue("a movement nothing can classify must reach the review flow", verdict.isFlagged)
        }

    // --- the override -------------------------------------------------------------------------------

    /**
     * §8.3's "user-correctable", as a round trip.
     * Input:  a NEED the user overrides to WANT, then withdraws.
     * Output: WANT while the override stands — cited as the user's, not as a rule's — and back to
     *         NEED when it is withdrawn. **`null` is a real operation**: it returns the transaction
     *         to whatever the rules currently decide, which is the whole reason only the correction
     *         is stored.
     */
    @Test
    fun `an override replaces the derived nature, and withdrawing it restores the rules`() =
        runTest {
            val bank = newAccount(AccountType.BANK)
            val txn = expense(bank.id, Money(-4_500_00L), categoryId = idOf("Groceries"))

            repository.setNature(txn, CategoryNature.WANT).expectOk()
            val overridden = repository.natureOf(txn).expectOk()
            assertEquals(CategoryNature.WANT, overridden.nature)
            assertFalse("a decision the user made is not a guess", overridden.isFlagged)

            repository.setNature(txn, null).expectOk()
            assertEquals(CategoryNature.NEED, repository.natureOf(txn).expectOk().nature)
        }

    /** Input: an override on an id that names nothing. Output: `NotFound`, not a silent success. */
    @Test
    fun `overriding a transaction that does not exist is refused`() =
        runTest {
            val outcome = repository.setNature("txn:nope", CategoryNature.WANT)

            assertTrue(outcome is Err && outcome.error is AppError.NotFound)
        }

    /**
     * §8.3.1 step 4, through the database.
     * Input:  two past transactions at one merchant, both overridden to WANT, then a third at the
     *         same merchant whose category says NEED.
     * Output: WANT, citing `CLS-NAT-004`. The learned tier reads the override column, which is why
     *         only corrections live there — a stored derived value could not be told apart from one
     *         the user chose.
     */
    @Test
    fun `past overrides at a merchant teach the next transaction`() =
        runTest {
            val bank = newAccount(AccountType.BANK)
            repeat(2) {
                val past =
                    expense(bank.id, Money(-95_000L), categoryId = idOf("Groceries"), merchant = "NATURE'S BASKET")
                repository.setNature(past, CategoryNature.WANT).expectOk()
            }

            val fresh = expense(bank.id, Money(-95_000L), categoryId = idOf("Groceries"), merchant = "nature's basket")
            val verdict = repository.natureOf(fresh).expectOk()

            assertEquals(CategoryNature.WANT, verdict.nature)
            assertEquals(listOf("CLS-NAT-004"), verdict.provenance.evidence.map { it.ruleId })
        }

    /**
     * Input:  a merchant whose overrides the user has since withdrawn.
     * Output: back to the category. A withdrawn correction must stop teaching as well as stop
     *         applying — otherwise "undo" would only undo half of what it appears to.
     */
    @Test
    fun `a withdrawn override stops teaching`() =
        runTest {
            val bank = newAccount(AccountType.BANK)
            val past = expense(bank.id, Money(-95_000L), categoryId = idOf("Groceries"), merchant = "KIRANA")
            repository.setNature(past, CategoryNature.WANT).expectOk()
            repository.setNature(past, null).expectOk()

            val fresh = expense(bank.id, Money(-95_000L), categoryId = idOf("Groceries"), merchant = "KIRANA")

            assertEquals(CategoryNature.NEED, repository.natureOf(fresh).expectOk().nature)
        }

    // --- step 6: the median -------------------------------------------------------------------------

    /**
     * Input:  four ordinary grocery runs and then one five times their size.
     * Output: still NEED, but flagged — §8.3.1's own example, computed by a query SQLite has no
     *         median function for.
     */
    @Test
    fun `an unusually large need is flagged against the category's median`() =
        runTest {
            val bank = newAccount(AccountType.BANK)
            listOf(1_800_00L, 2_000_00L, 2_200_00L, 2_400_00L).forEach {
                expense(bank.id, Money(-it), categoryId = idOf("Groceries"))
            }

            val big = expense(bank.id, Money(-9_400_00L), categoryId = idOf("Groceries"))
            val verdict = repository.natureOf(big).expectOk()

            assertEquals(CategoryNature.NEED, verdict.nature)
            assertEquals(listOf("CLS-NAT-005", "CLS-NAT-006"), verdict.provenance.evidence.map { it.ruleId })
        }

    /**
     * Input:  the same large amount, with only one prior transaction in the category.
     * Output: unflagged. A median over a sample of two is not a typical amount, it is the two
     *         amounts — the rules' minimum is what stops the second grocery run a user records from
     *         being called unusual.
     */
    @Test
    fun `too little history means no median and no flag`() =
        runTest {
            val bank = newAccount(AccountType.BANK)
            expense(bank.id, Money(-2_000_00L), categoryId = idOf("Groceries"))

            val big = expense(bank.id, Money(-9_400_00L), categoryId = idOf("Groceries"))

            assertFalse(repository.natureOf(big).expectOk().isFlagged)
        }

    // --- the monthly fold ---------------------------------------------------------------------------

    /**
     * The whole issue, end to end.
     * Input:  a month with a grocery run, a dinner, an SIP and a self-transfer.
     * Output: needs and wants in true spend, the SIP counted **once** despite being two rows, and the
     *         self-transfer counted not at all. This is the assertion standing between the dashboard
     *         and a savings rate twice the real one.
     */
    @Test
    fun `the month splits into true spend and conversions, counting each movement once`() =
        runTest {
            val bank = newAccount(AccountType.BANK)
            val sip = newAccount(AccountType.INVESTMENT, name = "Groww SIP")
            val second = newAccount(AccountType.BANK, name = "ICICI Savings")
            expense(bank.id, Money(-4_500_00L), categoryId = idOf("Groceries"))
            expense(bank.id, Money(-1_200_00L), categoryId = idOf("Dining"))
            repository.createTransfer(
                TransferDraft(fromAccountId = bank.id, toAccountId = sip.id, amount = Money(10_000_00L)),
            ).expectOk()
            repository.createTransfer(
                TransferDraft(fromAccountId = bank.id, toAccountId = second.id, amount = Money(5_000_00L)),
            ).expectOk()

            val breakdown = repository.observeNatureBreakdown().first()

            assertEquals(Money(4_500_00L), breakdown.needs)
            assertEquals(Money(1_200_00L), breakdown.wants)
            assertEquals("the SIP's two legs are one movement", Money(10_000_00L), breakdown.invested)
            assertEquals(Money(5_700_00L), breakdown.trueSpend)
        }

    /**
     * Input:  a month in which the user has overridden one transaction.
     * Output: the totals follow the override. The dashboard and the detail sheet must not disagree
     *         about a transaction, and they resolve nature by two different code paths — this is the
     *         test that holds them together.
     */
    @Test
    fun `the month's totals follow the user's overrides`() =
        runTest {
            val bank = newAccount(AccountType.BANK)
            val txn = expense(bank.id, Money(-4_500_00L), categoryId = idOf("Groceries"))
            repository.setNature(txn, CategoryNature.WANT).expectOk()

            val breakdown = repository.observeNatureBreakdown().first()

            assertEquals(Money.ZERO, breakdown.needs)
            assertEquals(Money(4_500_00L), breakdown.wants)
            assertEquals(
                "an override moves money between bands, never in or out",
                Money(4_500_00L),
                breakdown.trueSpend,
            )
        }

    /**
     * The behaviour issue 4.4 changed, pinned here so it cannot drift back (ADR-0018).
     *
     * Input:  one ₹4,000 payment split ₹3,000 groceries (NEED) / ₹1,000 dining (WANT).
     * Output: ₹3,000 needs and ₹1,000 wants — the breakdown classifies the **lines**.
     *
     *         **Before 4.4 this month read as ₹4,000 of wants.** `observeNatureCandidates` projected
     *         the parent's `category_id`, which a split transaction does not have, so the payment
     *         fell past §8.3.1 step 5 to the fallback — and the fallback is WANT. Two-thirds of this
     *         fixture was NEED and all of it was counted as WANT, which is the direction that
     *         inflates true spend and understates the essentials the emergency-fund target is sized
     *         from.
     *
     *         The total is asserted as well as the split, because the `UNION ALL` that fixed this
     *         has a second failure mode — counting the parent *and* its lines, for ₹8,000 out of a
     *         ₹4,000 payment.
     */
    @Test
    fun `a split payment is classified by its lines, not by its empty parent`() =
        runTest {
            val bank = newAccount(AccountType.BANK)
            repository.createSplit(
                SplitDraft(
                    accountId = bank.id,
                    amount = Money(-4_000_00L),
                    lines =
                        listOf(
                            SplitLineDraft(amount = Money(-3_000_00L), categoryId = idOf("Groceries")),
                            SplitLineDraft(amount = Money(-1_000_00L), categoryId = idOf("Dining")),
                        ),
                ),
            ).expectOk()

            val breakdown = repository.observeNatureBreakdown().first()

            assertEquals(Money(3_000_00L), breakdown.needs)
            assertEquals(Money(1_000_00L), breakdown.wants)
            assertEquals("the payment is counted once, by its lines", Money(4_000_00L), breakdown.trueSpend)
        }

    /**
     * Input:  the same split payment, read through the **detail sheet's** query.
     * Output: one verdict for the whole transaction, not two. `natureCandidate` is deliberately not
     *         split-aware (ADR-0018): a breakdown sums lines because a total must, but a label names
     *         the thing it is attached to, and a card showing two natures is a screen nobody has
     *         designed. Asserted so a later change that "makes the two queries consistent" has to
     *         confront the decision rather than discover it.
     */
    @Test
    fun `the detail sheet still labels a split payment as one transaction`() =
        runTest {
            val bank = newAccount(AccountType.BANK)
            val id =
                repository.createSplit(
                    SplitDraft(
                        accountId = bank.id,
                        amount = Money(-4_000_00L),
                        lines =
                            listOf(
                                SplitLineDraft(amount = Money(-3_000_00L), categoryId = idOf("Groceries")),
                                SplitLineDraft(amount = Money(-1_000_00L), categoryId = idOf("Dining")),
                            ),
                    ),
                ).expectOk().id

            // Resolves at all, and to a single verdict — the parent carries no category, so this is
            // the flagged fallback, which is the honest answer for a payment with two natures.
            assertTrue(repository.natureOf(id).expectOk().isFlagged)
        }

    /** Input: a profile with no transactions. Output: an empty breakdown, not a zeroed one. */
    @Test
    fun `a month with no transactions is empty`() =
        runTest {
            assertTrue(repository.observeNatureBreakdown().first().isEmpty)
        }

    /**
     * Input:  the same spend under the demo profile, read from the real one.
     * Output: nothing. Exploring the sample data must not move the user's own figures (ADR-0006),
     *         and profile scoping is a clause that is easy to omit and impossible to notice.
     */
    @Test
    fun `the demo profile's spending stays out of the real breakdown`() =
        runTest {
            activeProfileId.value = DEMO_PROFILE
            categories.ensureSeeded()
            val demoBank = newAccount(AccountType.BANK)
            expense(demoBank.id, Money(-4_500_00L), categoryId = idOf("Groceries"))
            activeProfileId.value = REAL_PROFILE

            assertTrue(repository.observeNatureBreakdown().first().isEmpty)
        }

    // --- helpers -----------------------------------------------------------------------------------

    /** Result: a live account of [type]. Input: [type]; [name]. Output: the created account. */
    private suspend fun newAccount(
        type: AccountType,
        name: String = "HDFC Savings",
    ) = accounts.create(
        AccountDraft(name = name, type = type, openingBalance = Money(200_000_00L), currencyCode = "INR"),
    ).expectOk()

    /**
     * Result: a saved expense's id. Input: [accountId]; [amount]; [categoryId]; [merchant].
     * Output: [String].
     */
    private suspend fun expense(
        accountId: String,
        amount: Money,
        categoryId: String? = null,
        merchant: String? = null,
    ): String =
        repository.create(
            TransactionDraft(accountId = accountId, amount = amount, categoryId = categoryId, merchant = merchant),
        ).expectOk().id

    /**
     * Result: the id of the transaction booked in [accountId].
     * Why:    `Transfer` carries the two account ids but not the two row ids (issue 3.2 models a
     *         transfer as two `transactions` rows), and it is the *rows* that get classified — one
     *         per leg, each seeing the other's account type. **Read straight from SQLite rather than
     *         through `observeFiltered`, because the ledger deliberately collapses a transfer's two
     *         rows into one** (issue 3.2) — the leg this test wants is exactly the one the list
     *         hides.
     * Input:  [accountId]. Output: [String]; fails the test when the leg is missing.
     */
    private fun legIn(accountId: String): String =
        database.query(
            "SELECT id FROM transactions WHERE account_id = ? AND deleted_at_utc_millis IS NULL",
            arrayOf<Any>(accountId),
        ).use { cursor ->
            assertTrue("no transaction is booked in $accountId", cursor.moveToFirst())
            cursor.getString(0)
        }

    /** Result: the live category with this display name. Input: [name]. Output: its id. */
    private suspend fun idOf(name: String): String = repository.observeCategories().first().first { it.name == name }.id

    private companion object {
        const val REAL_PROFILE = "local"
        const val DEMO_PROFILE = "demo"
    }
}

/**
 * Unwraps a result the test expects to have succeeded.
 * Why:    an `Err` here is a test failure, and `as Ok` would report it as a `ClassCastException`
 *         naming neither the code nor the call — the helper every repository test in this module
 *         carries, kept file-private so each states its own reason.
 * Result: the value. Input: the receiver. Output: [T]; throws [AssertionError] on an `Err`.
 * Changelog: 2026-08-10 — Created for issue 4.3.
 */
private fun <T> com.aicfo.core.common.Result<T, AppError>.expectOk(): T =
    when (this) {
        is Ok -> value
        is Err -> throw AssertionError("expected Ok, got Err(${error.code})")
    }
