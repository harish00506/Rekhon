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
import com.aicfo.core.model.CreditCard
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.card.CardAlertKind
import com.aicfo.domain.engines.card.CardEngineFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * The data half of credit cards — issue 6.1 (SRS §5.7, FR-ACC-002, ARC-005).
 *
 * Why:  `CardEngineTest` and the golden file already prove the arithmetic against fixtures, so
 *       repeating it here would assert nothing new. **What is unproven above SQLite is the three
 *       translations this class owns**, and each fails while still returning a plausible number:
 *
 *       - the **sign flip**. A card's balance is stored negative because it is a liability. Miss the
 *         flip and utilisation comes out negative — or, with a `coerceAtLeast` in the wrong place,
 *         comes out as a confident 0% on a maxed-out card.
 *       - the **claim**. `markNotified` must return `true` exactly once per cycle per kind, and it
 *         must be the *database* refusing the second one rather than this class checking first.
 *       - the **type guard**. A `credit_card` row against a savings account would give it a
 *         utilisation bar and a payment reminder computed from a limit it does not have.
 * What: the flip, the claim's uniqueness and its two axes, the type guard, and the absent cases.
 * Result: the first card figures in the app are proven against a real SQL engine.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 *
 * Unencrypted in-memory Room and the **real** engine rather than a stub, the reasoning
 * `BudgetRepositoryTest` gives: the claim is that a card figure reaches the screen, and a stub could
 * not make it. The clock sits inside a reminder window on purpose — see [clock].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CreditCardRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var cards: CreditCardRepository
    private lateinit var accounts: AccountRepository
    private lateinit var accountId: String

    /**
     * 2026-08-23, which is three days before a card due on the 26th — inside the reminder window on
     * purpose, so the claim tests have something to claim without arranging a date each time.
     */
    private val clock = FakeClock(initialMillis = Instant.parse("2026-08-23T06:00:00Z").toEpochMilli())
    private val ids = FakeIdGenerator()
    private val dispatcher = UnconfinedTestDispatcher()
    private val activeProfileId = MutableStateFlow(REAL_PROFILE)

    /** Input: none. Output: a fresh in-memory database, the repositories, and one card account. */
    @Before
    fun setUp() =
        runTest(dispatcher) {
            database =
                Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    CfoDatabase::class.java,
                ).allowMainThreadQueries().build()
            val dispatchers = TestDispatchers(dispatcher)
            accounts = RepositoryFactory.accounts(database, clock, ids, dispatchers, activeProfileId)
            cards =
                RepositoryFactory.creditCards(
                    database, CardEngineFactory.create(), clock, ids, dispatchers, activeProfileId,
                )
            accountId =
                accounts.create(
                    AccountDraft(
                        name = "HDFC Card",
                        type = AccountType.CREDIT_CARD,
                        // Negative: a card's balance is what is owed (AccountType.isLiability).
                        openingBalance = Money(-70_000_00L),
                        currencyCode = "INR",
                    ),
                ).expectOk().id
        }

    /** Input: none. Output: closes the database between tests. */
    @After
    fun tearDown() {
        database.close()
    }

    // --- the sign flip --------------------------------------------------------------------------

    /**
     * Input:  a card owing ₹70,000 against a ₹2,00,000 limit, stored as a negative balance.
     * Output: asserts 35%, positive. Without the flip this is −3 500 bps, or 0% once floored — a
     *         maxed-out card reporting perfect discipline.
     */
    @Test
    fun `a liability balance becomes a positive utilisation`() =
        runTest(dispatcher) {
            cards.save(card()).expectOk()

            val status = cards.observeCardStatuses().first().getValue(accountId)

            assertEquals(3_500, status.live.ratioBps)
            assertEquals(Money(70_000_00L), status.live.used)
        }

    /**
     * Input:  a card in credit — the user overpaid, so the stored balance is positive.
     * Output: asserts nothing owed rather than a negative amount. A refund landing after a payment
     *         is ordinary, and "you owe −₹2,000" is not a sentence the app should be able to form.
     */
    @Test
    fun `a card in credit owes nothing, not a negative amount`() =
        runTest(dispatcher) {
            accounts.reconcile(accountId, Money(2_000_00L)).expectOk()
            cards.save(card()).expectOk()

            val status = cards.observeCardStatuses().first().getValue(accountId)

            assertEquals(Money.ZERO, status.live.used)
            assertEquals(0, status.live.ratioBps)
        }

    // --- the claim ------------------------------------------------------------------------------

    /**
     * Input:  the same alert claimed twice.
     * Output: asserts `true` then `false`. The second refusal comes from the unique index, not from
     *         this class reading first — which is what makes it hold when two workers overlap.
     */
    @Test
    fun `an alert can only be claimed once`() =
        runTest(dispatcher) {
            cards.save(card()).expectOk()
            val alert = cards.pendingAlerts().expectOk().first { it.alert.kind == CardAlertKind.DUE_SOON }.alert

            assertTrue("the first claim must win", cards.markNotified(alert).expectOk())
            assertFalse("the second must lose", cards.markNotified(alert).expectOk())
        }

    /**
     * Input:  a card that is both due and over its utilisation line.
     * Output: asserts both kinds claim independently. `kind` is in the unique key precisely so one
     *         message cannot swallow the other — they go to different channels (§17.1, NTF-006).
     */
    @Test
    fun `the two kinds claim independently in one cycle`() =
        runTest(dispatcher) {
            cards.save(card()).expectOk()
            val alerts = cards.pendingAlerts().expectOk()

            assertEquals(2, alerts.size)
            assertEquals("the card's own name must travel with the alert", "HDFC Card", alerts.first().accountName)
            alerts.forEach { assertTrue("each kind claims on its own", cards.markNotified(it.alert).expectOk()) }
        }

    /**
     * Input:  an alert already claimed.
     * Output: asserts `pendingAlerts` no longer offers it. This is the subtraction that stops the
     *         worker re-notifying, and it is here rather than in the worker so the in-app surface
     *         can still show the unfiltered truth (P-02).
     */
    @Test
    fun `a claimed alert stops being pending`() =
        runTest(dispatcher) {
            cards.save(card()).expectOk()
            val due = cards.pendingAlerts().expectOk().first { it.alert.kind == CardAlertKind.DUE_SOON }.alert
            cards.markNotified(due)

            val remaining = cards.pendingAlerts().expectOk()

            assertEquals(listOf(CardAlertKind.UTILISATION), remaining.map { it.alert.kind })
        }

    /**
     * Input:  a claimed alert, then the next cycle.
     * Output: asserts the reminder returns. A claim keyed by the *statement date* rather than by the
     *         month is what makes this work for a card whose cycle straddles two calendar months.
     */
    @Test
    fun `the next cycle is a new claim`() =
        runTest(dispatcher) {
            cards.save(card()).expectOk()
            cards.pendingAlerts().expectOk().forEach { cards.markNotified(it.alert) }
            assertTrue("nothing should be pending yet", cards.pendingAlerts().expectOk().isEmpty())

            // One month on: a new statement, a new due date, a new claim key.
            clock.advanceBy(java.time.Duration.ofDays(31))

            assertTrue("the next cycle must be able to remind again", cards.pendingAlerts().expectOk().isNotEmpty())
        }

    // --- the type guard and the absent cases ----------------------------------------------------

    /**
     * Input:  card terms saved against a savings account.
     * Output: asserts a validation error. Not a UI concern: the row would give a bank account a
     *         utilisation bar and a payment reminder computed from a limit it has not got.
     */
    @Test
    fun `card terms are refused against a non-card account`() =
        runTest(dispatcher) {
            val savings =
                accounts.create(
                    AccountDraft("HDFC Savings", AccountType.BANK, Money(1_00_000_00L), "INR"),
                ).expectOk().id

            val result = cards.save(card().copy(accountId = savings))

            assertTrue("a savings account must not get card terms", result is Err)
            assertEquals(AppError.Validation("account.notACreditCard"), (result as Err).error)
        }

    /**
     * Input:  a credit-card account with no terms entered.
     * Output: asserts it is **absent** from the map rather than present with zeros. The ordinary
     *         state of a card the user has not filled in, and a false 0% would be a figure no engine
     *         produced (P-03).
     */
    @Test
    fun `a card with no terms is absent, not zero`() =
        runTest(dispatcher) {
            val statuses = cards.observeCardStatuses().first()

            assertTrue("nothing should be reported yet", statuses.isEmpty())
            assertNull(cards.find(accountId).expectOk())
        }

    /**
     * Input:  terms saved, then edited.
     * Output: asserts the edit replaces rather than duplicating, and that `created_at` survives —
     *         "when did this card start?" stays answerable across an edit.
     */
    @Test
    fun `editing the terms replaces them and keeps the created stamp`() =
        runTest(dispatcher) {
            cards.save(card()).expectOk()
            val created = database.creditCardDao().find(accountId)!!.createdAtUtcMillis
            clock.advanceBy(java.time.Duration.ofDays(1))

            cards.save(card().copy(creditLimit = Money(3_00_000_00L))).expectOk()

            val stored = database.creditCardDao().find(accountId)!!
            assertEquals(3_00_000_00L, stored.creditLimitMinor)
            assertEquals("the card did not start again when it was edited", created, stored.createdAtUtcMillis)
            assertEquals(1, database.creditCardDao().forProfile(REAL_PROFILE).size)
        }

    /** Result: a ₹2,00,000 card due on the 26th with a ₹70,000 statement (35%). Input: none. */
    private fun card() =
        CreditCard(
            accountId = accountId,
            creditLimit = Money(2_00_000_00L),
            statementDay = 6,
            dueDay = 26,
            lastStatement = Money(70_000_00L),
            lastStatementIsoDate = "2026-08-06",
            minimumDue = Money(3_500_00L),
            aprBps = 42_00,
        )

    /**
     * Unwraps an `Ok`, failing the test on an `Err`.
     *
     * Matched on the type rather than written as `(this as? Ok)?.value ?: error(...)`, because that
     * shorter form treats a perfectly good `Ok(null)` as a failure — and `find` returning
     * `Ok(null)` for a card with no terms is one of the behaviours under test here.
     */
    private fun <T> com.aicfo.core.common.Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> error("expected Ok, was $this")
        }

    private companion object {
        const val REAL_PROFILE = "profile:real"
    }
}
