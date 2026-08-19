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
import com.aicfo.core.model.Loan
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.loan.LoanEngineFactory
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
 * The data half of loans — issue 6.2 (SRS §5.8, FR-ACC-003, ARC-005).
 *
 * Why:  `LoanEngineTest`, the property test and the golden file already prove the arithmetic
 *       against fixtures, so repeating it here would assert nothing new. **What is unproven above
 *       SQLite is what this class owns**, and each part fails while still returning something
 *       plausible:
 *
 *       - the **next-instalment decision**, the one place on this path that reads a clock. Off by
 *         one and the accounts list shows last month's split for ever, which looks entirely
 *         correct until somebody checks it against a statement.
 *       - the **round trip**. Five columns and one nullable; storing the EMI override as `0`
 *         instead of `NULL` would turn "derive it" into "the lender charges nothing".
 *       - the **type guard**, and the **amortises guard**: terms saved that produce no schedule
 *         would leave an empty table with nothing on screen explaining why.
 * What: the round trip, the clock-dependent instalment choice at four points in a loan's life, both
 *       guards, and the absent cases.
 * Result: the first loan figures in the app are proven against a real SQL engine.
 * Changelog: 2026-08-19 — Created for issue 6.2.
 *
 * Unencrypted in-memory Room and the **real** engine rather than a stub, the reasoning
 * `CreditCardRepositoryTest` gives: the claim is that a loan figure reaches the screen, and a stub
 * could not make it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LoanRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var loans: LoanRepository
    private lateinit var accounts: AccountRepository
    private lateinit var accountId: String

    /** 2026-10-20 — after the first instalment of the fixture loan (2026-09-05) and before its second. */
    private val clock = FakeClock(initialMillis = Instant.parse("2026-10-20T06:00:00Z").toEpochMilli())
    private val ids = FakeIdGenerator()
    private val dispatcher = UnconfinedTestDispatcher()
    private val activeProfileId = MutableStateFlow(REAL_PROFILE)

    /** Input: none. Output: a fresh in-memory database, the repositories, and one loan account. */
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
            loans =
                RepositoryFactory.loans(
                    database, LoanEngineFactory.create(), clock, dispatchers, activeProfileId,
                )
            accountId =
                accounts.create(
                    AccountDraft(
                        name = "SBI Home Loan",
                        type = AccountType.LOAN,
                        // Negative: a loan's balance is what is owed (AccountType.isLiability).
                        openingBalance = Money(-300_000_000L),
                        currencyCode = "INR",
                    ),
                ).expectOk().id
        }

    /** Input: none. Output: closes the database between tests. */
    @After
    fun tearDown() {
        database.close()
    }

    // --- the round trip -------------------------------------------------------------------------

    /**
     * Input:  a loan saved and read back.
     * Output: asserts every term survives — the paise, the basis points, the ISO date, and the
     *         absent override as `null` rather than `Money.ZERO`.
     */
    @Test
    fun `a loan's terms round-trip through SQL`() =
        runTest(dispatcher) {
            loans.save(loan()).expectOk()

            val stored = loans.find(accountId).expectOk()

            assertEquals(loan(), stored)
            assertNull("an absent override must stay absent, never become zero", stored?.emiOverride)
        }

    /**
     * Input:  a loan saved twice, the second time with the lender's own EMI.
     * Output: asserts the second write is an edit rather than a duplicate, and that the override
     *         survives. A loan is keyed by its account, so there can only ever be one row.
     */
    @Test
    fun `saving twice edits the one row`() =
        runTest(dispatcher) {
            loans.save(loan()).expectOk()
            loans.save(loan().copy(emiOverride = Money(2_603_600))).expectOk()

            assertEquals(Money(2_603_600), loans.find(accountId).expectOk()?.emiOverride)
            assertEquals(1, database.loanDao().forProfile(REAL_PROFILE).size)
        }

    /**
     * Input:  an account with no loan detail.
     * Output: asserts `Ok(null)`, not an error — the ordinary state of a loan account the user has
     *         just created, so the editor opens on an empty form.
     */
    @Test
    fun `a loan account with no terms is absent, not an error`() =
        runTest(dispatcher) {
            assertNull(loans.find(accountId).expectOk())
            assertTrue(loans.observeNextInstalments().first().isEmpty())
        }

    // --- the next-instalment decision (the clock read) ------------------------------------------

    /**
     * Input:  the fixture loan, read on 2026-10-20 — after instalment 1 (2026-09-05) and before
     *         instalment 2 (2026-10-05)… which has also passed.
     * Output: asserts instalment **3** is next, dated 2026-11-05. Both earlier ones have fallen, and
     *         an off-by-one here would show the list a split the borrower already paid.
     */
    @Test
    fun `the next instalment is the first one not yet due`() =
        runTest(dispatcher) {
            loans.save(loan()).expectOk()

            val row = loans.observeNextInstalments().first().getValue(accountId)

            assertEquals(3, row.number)
            assertEquals("2026-11-05", row.dueIsoDate)
            assertEquals(row.amount, row.principal + row.interest)
        }

    /**
     * Input:  the same loan read on its own first EMI date.
     * Output: asserts instalment 1 is still the next one. An instalment due **today** has not been
     *         paid yet, and `isBefore` rather than `isAfter` is what keeps that true — the exact
     *         boundary a `>` would get wrong once a month.
     */
    @Test
    fun `an instalment due today is still the next one due`() =
        runTest(dispatcher) {
            clock.setTo(Instant.parse("2026-09-05T06:00:00Z").toEpochMilli())
            loans.save(loan()).expectOk()

            assertEquals(1, loans.observeNextInstalments().first().getValue(accountId).number)
        }

    /**
     * Input:  the same loan read before it has even started.
     * Output: asserts instalment 1 — a loan the user entered ahead of its first EMI shows the
     *         instalment that is coming, not nothing.
     */
    @Test
    fun `a loan that has not started yet shows its first instalment`() =
        runTest(dispatcher) {
            clock.setTo(Instant.parse("2026-08-01T06:00:00Z").toEpochMilli())
            loans.save(loan()).expectOk()

            assertEquals(1, loans.observeNextInstalments().first().getValue(accountId).number)
        }

    /**
     * Input:  a repaid loan — every instalment of a 3-month loan is in the past.
     * Output: asserts it is **absent** from the map rather than showing a final instalment for ever.
     *         There is no next EMI on a loan that is closed, and claiming one would be a bill.
     */
    @Test
    fun `a repaid loan has no next instalment`() =
        runTest(dispatcher) {
            loans.save(loan().copy(tenureMonths = 3)).expectOk()
            clock.setTo(Instant.parse("2027-06-01T06:00:00Z").toEpochMilli())

            assertTrue(loans.observeNextInstalments().first().isEmpty())
        }

    // --- the guards -----------------------------------------------------------------------------

    /**
     * Input:  loan terms against a savings account.
     * Output: asserts `Err(Validation)`. A `loan` row on a bank account would give it an EMI and a
     *         schedule computed from a principal it has not got — a data-integrity rule, so it is
     *         enforced here and not in the form.
     */
    @Test
    fun `loan terms against a non-loan account are refused`() =
        runTest(dispatcher) {
            val savings =
                accounts.create(
                    AccountDraft(
                        name = "HDFC Savings",
                        type = AccountType.BANK,
                        openingBalance = Money(100_000_00L),
                        currencyCode = "INR",
                    ),
                ).expectOk().id

            val result = loans.save(loan().copy(accountId = savings))

            assertEquals(AppError.Validation("account.notALoan"), (result as Err).error)
            assertNull(loans.find(savings).expectOk())
        }

    /**
     * Input:  a lender EMI far below the first month's interest.
     * Output: asserts `Err(Validation("emiOverride"))` **and that nothing was written** — terms that
     *         produce no schedule must not reach the database, or the user gets an empty table with
     *         nothing on screen explaining it.
     */
    @Test
    fun `terms that cannot amortise are refused before they are written`() =
        runTest(dispatcher) {
            val result = loans.save(loan().copy(emiOverride = Money(100_000)))

            assertEquals(AppError.Validation("emiOverride"), (result as Err).error)
            assertNull(loans.find(accountId).expectOk())
        }

    /**
     * Input:  terms against an account that does not exist.
     * Output: asserts `Err(NotFound)` rather than an orphan row pointing at nothing.
     */
    @Test
    fun `loan terms for a missing account are refused`() =
        runTest(dispatcher) {
            val result = loans.save(loan().copy(accountId = "account:nowhere"))

            assertEquals(AppError.NotFound, (result as Err).error)
        }

    // --- the derived schedule -------------------------------------------------------------------

    /**
     * Input:  a saved loan, asked for its whole schedule.
     * Output: asserts it is derived on demand and balances — 240 rows, principals summing to the
     *         loan, ending at zero. Nothing was stored to produce it (ADR-0026), which is the point.
     */
    @Test
    fun `the schedule is derived from the stored terms`() =
        runTest(dispatcher) {
            loans.save(loan()).expectOk()

            val schedule = loans.schedule(accountId).expectOk()

            assertEquals(240, schedule.rows.size)
            assertEquals(Money(2_603_470), schedule.emi.amount)
            assertEquals(Money.ZERO, schedule.rows.last().closingBalance)
        }

    /**
     * Input:  an account with no loan detail, asked for a schedule.
     * Output: asserts `Err(NotFound)` — there are no terms to amortise, which is different from a
     *         loan whose terms do not work.
     */
    @Test
    fun `a schedule for an account with no loan is not found`() =
        runTest(dispatcher) {
            assertEquals(AppError.NotFound, (loans.schedule(accountId) as Err).error)
        }

    /**
     * The fixture loan: ₹30,00,000 at 8.5% over 20 years, first instalment 2026-09-05.
     * Result: a [Loan]. Input: none. Output: [Loan].
     */
    private fun loan() =
        Loan(
            accountId = accountId,
            principal = Money(300_000_000L),
            annualRateBps = 850,
            tenureMonths = 240,
            firstEmiIsoDate = "2026-09-05",
        )

    /**
     * Unwraps an `Ok`, failing the test on an `Err`.
     *
     * Matched on the type rather than `(this as? Ok)?.value ?: error(...)`, because that shorter
     * form treats a perfectly good `Ok(null)` as a failure — and `find` returning `Ok(null)` for an
     * account with no loan terms is one of the behaviours under test here.
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
