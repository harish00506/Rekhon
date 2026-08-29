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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Tests for future-dated transactions — issue 3.4 (FR-TXN-010, TIM-001, TIM-002).
 *
 * Why:  FR-TXN-010 is a MUST — future-dated transactions "MUST be supported and excluded from
 *       actuals but included in forecasts" — and every part of it fails silently.
 *
 *       **Excluded from actuals** is the half that touches money. It is asserted here against the
 *       balance the accounts screen actually renders, not against a query, because the claim is
 *       about the figure the user sees: schedule a ₹25,000 rent payment and today's balance must not
 *       move by a paise. The boundary is the risk — a `<` where a `<=` belongs would either hold a
 *       payment made this morning out of the user's figures, or leave tomorrow's in them.
 *
 *       **Rollover with no write** is the property that makes the whole design safe: the same row,
 *       with only the clock moved, must start counting. If that ever stops being true, a balance
 *       would depend on a background job having run, and a device that was switched off would show
 *       its user the wrong number.
 *
 *       **Idempotence** is asserted because `postDue` is a bare `UPDATE` a worker may run twice —
 *       after a retry, after a reboot, or racing an app-start enqueue.
 *
 *       **Zone and DST** matter because "which day is it?" is the question TIM-001 exists for. A
 *       scheduled row's instant is its own local midnight, and there are days in some zones on which
 *       local midnight does not exist at all.
 * What: the write path's date handling, the balance exclusion, the two read windows, posting, and
 *       the refusal of a past date.
 * Result: a scheduled payment is provably invisible to today's money and provably visible to
 *       whatever plans for tomorrow.
 * Changelog: 2026-08-03 — Created for issue 3.4 (FR-TXN-010).
 *
 * Unencrypted in-memory Room, deliberately — the same reasoning as [TransactionRepositoryTest]: what
 * is under test is the SQL and the date arithmetic, not SQLCipher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FutureDatedTransactionTest {
    private lateinit var database: CfoDatabase
    private lateinit var repository: TransactionRepository
    private lateinit var accounts: AccountRepository

    // 2026-08-02T18:00Z is 2026-08-02T23:30 in Asia/Kolkata — the same calendar day in both zones,
    // and an hour and a half before IST's midnight. The `advanceBy(2h)` steps below therefore cross
    // the profile's day boundary while UTC is still on the previous date, which is the case TIM-002
    // exists for and the one a rollover test written against UTC would get wrong.
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
        repository =
            RepositoryFactory.transactions(
                database, clock, ids, dispatchers, activeProfileId, ClassificationEngineFactory.create(),
                NatureEngineFactory.create(),
            )
        // The real AccountRepository, not a stub: the claim under test is about the balance the
        // accounts screen renders, and a stub could not make it.
        accounts = RepositoryFactory.accounts(database, clock, ids, dispatchers, activeProfileId)
    }

    /** Input: none. Output: closes the database between tests. */
    @After
    fun tearDown() {
        database.close()
    }

    // --- excluded from actuals ---------------------------------------------------------------------

    @Test
    fun `a transaction booked tomorrow leaves today's balance untouched`() =
        runTest {
            // FR-TXN-010's first clause, asserted on the figure the accounts screen shows.
            val account = newAccount(opening = Money(1_00_000_00L))

            val scheduled =
                repository.create(
                    TransactionDraft(account.id, Money(-25_000_00L), bookedOn = clock.today().plusDays(1)),
                ).expectOk()

            assertEquals("2026-08-03", scheduled.bookedOn)
            assertEquals(Money(1_00_000_00L), accounts.find(account.id).expectOk().balance)
        }

    @Test
    fun `a transaction booked today counts immediately`() =
        runTest {
            // The other side of the boundary, and the one an off-by-one breaks: today is an actual.
            val account = newAccount(opening = Money(1_00_000_00L))

            repository.create(
                TransactionDraft(account.id, Money(-25_000_00L), bookedOn = clock.today()),
            ).expectOk()

            assertEquals(Money(75_000_00L), accounts.find(account.id).expectOk().balance)
        }

    @Test
    fun `an omitted date still means today, exactly as it did before issue 3-4`() =
        runTest {
            // Every call site written for issues 3.1 to 3.3 passes no date. None of them may change
            // behaviour, which is why `bookedOn` is nullable rather than defaulted to a date.
            val account = newAccount(opening = Money(1_00_000_00L))

            val created = repository.create(TransactionDraft(account.id, Money(-250_00L))).expectOk()

            assertEquals(clock.today().toString(), created.bookedOn)
            assertEquals(Money(99_750_00L), accounts.find(account.id).expectOk().balance)
        }

    @Test
    fun `the balance moves on the day itself, with nothing written in between`() =
        runTest {
            // **The property the whole design rests on.** The only thing that changes between the two
            // assertions is the clock: no worker runs, no row is updated, nothing is re-read from a
            // cache. If this ever needed a write, a user whose device was off would see a wrong
            // balance until the job caught up.
            val account = newAccount(opening = Money(1_00_000_00L))
            repository.create(
                TransactionDraft(account.id, Money(-25_000_00L), bookedOn = clock.today().plusDays(1)),
            ).expectOk()
            assertEquals(Money(1_00_000_00L), accounts.find(account.id).expectOk().balance)

            // 23:30 IST + 2h = 01:30 IST the next day. UTC is still on 2026-08-02.
            clock.advanceBy(Duration.ofHours(2))

            assertEquals(Money(75_000_00L), accounts.find(account.id).expectOk().balance)
        }

    @Test
    fun `both legs of a scheduled transfer share one future day`() =
        runTest {
            // A transfer's legs landing on different days would leave money missing from one account
            // for the days in between — the bug `writeTransferLegs` computing the day twice caused.
            val from = newAccount(name = "Savings", opening = Money(1_00_000_00L))
            val to = newAccount(name = "Cash", opening = Money(5_000_00L))

            val transfer =
                repository.createTransfer(
                    TransferDraft(from.id, to.id, Money(10_000_00L), bookedOn = clock.today().plusDays(3)),
                ).expectOk()

            assertEquals("2026-08-05", transfer.bookedOn)
            val legs = allRows().filter { it.transferId == transfer.id }
            assertEquals(2, legs.size)
            assertEquals(1, legs.map { it.bookedOnIsoDate }.distinct().size)
            assertTrue("neither leg may be posted yet", legs.all { it.postedAtUtcMillis == null })
            // Neither balance moves: a scheduled transfer is not a transfer that has happened.
            assertEquals(Money(1_00_000_00L), accounts.find(from.id).expectOk().balance)
            assertEquals(Money(5_000_00L), accounts.find(to.id).expectOk().balance)
        }

    @Test
    fun `a scheduled split keeps its lines on the parent's day and out of the balance`() =
        runTest {
            val account = newAccount(opening = Money(1_00_000_00L))

            val split =
                repository.createSplit(
                    SplitDraft(
                        accountId = account.id,
                        amount = Money(-1_000_00L),
                        lines = listOf(SplitLineDraft(Money(-600_00L)), SplitLineDraft(Money(-400_00L))),
                        bookedOn = clock.today().plusDays(7),
                    ),
                ).expectOk()

            assertEquals("2026-08-09", split.bookedOn)
            assertEquals(2, split.splits.size)
            assertEquals(Money(1_00_000_00L), accounts.find(account.id).expectOk().balance)
        }

    // --- back-dating (ADR-0012) --------------------------------------------------------------------

    @Test
    fun `a date in the past is accepted and moves the balance straight away`() =
        runTest {
            // Issue 3.4 refused this, because `net_worth_snapshot` holds one frozen row per past day
            // and nothing recomputed them. `NetWorthRepository.repairStaleHistory` corrects exactly
            // those days now, so the refusal went with the reason for it (ADR-0012). The *balance* is
            // right immediately either way — it is derived from the ledger, never stored (ADR-0007).
            val account = newAccount(opening = Money(1_00_000_00L))
            val yesterday = clock.today().minusDays(1)

            val created =
                repository.create(
                    TransactionDraft(account.id, Money(-250_00L), bookedOn = yesterday),
                ).expectOk()

            assertEquals("the day the user chose, not today", yesterday.toString(), created.bookedOn)
            assertEquals(Money(99_750_00L), accounts.find(account.id).expectOk().balance)
        }

    @Test
    fun `a back-dated row is already posted, so the worker cannot post it twice`() =
        runTest {
            // `postedAtUtcMillis` is `postDue`'s idempotence key: the day has arrived and the money
            // has moved, so a null here would leave the rollover job trying to post it every run.
            val account = newAccount()

            repository.create(
                TransactionDraft(account.id, Money(-250_00L), bookedOn = clock.today().minusDays(3)),
            ).expectOk()

            assertEquals(1, allRows().count { it.postedAtUtcMillis != null })
        }

    @Test
    fun `every write path accepts a past date`() =
        runTest {
            val from = newAccount(name = "Savings")
            val to = newAccount(name = "Cash")
            val yesterday = clock.today().minusDays(1)

            val transfer =
                repository.createTransfer(
                    TransferDraft(from.id, to.id, Money(1_000_00L), bookedOn = yesterday),
                ).expectOk()
            val split =
                repository.createSplit(
                    SplitDraft(
                        accountId = from.id,
                        amount = Money(-1_000_00L),
                        lines = listOf(SplitLineDraft(Money(-600_00L)), SplitLineDraft(Money(-400_00L))),
                        bookedOn = yesterday,
                    ),
                ).expectOk()

            assertEquals(
                "both legs of a transfer share one booked day (FR-TXN-003)",
                yesterday.toString(),
                transfer.bookedOn,
            )
            assertEquals(yesterday.toString(), split.bookedOn)
        }

    // --- the two read windows ----------------------------------------------------------------------

    @Test
    fun `the recent list holds actuals and the upcoming list holds the rest`() =
        runTest {
            // The two windows abut exactly: no row may appear in both, and none may fall between them.
            val account = newAccount()
            repository.create(TransactionDraft(account.id, Money(-100_00L))).expectOk()
            repository.create(
                TransactionDraft(account.id, Money(-25_000_00L), bookedOn = clock.today().plusDays(1)),
            ).expectOk()

            val recent = repository.liveTransactions()
            val upcoming = repository.observeUpcoming().first()

            assertEquals(1, recent.size)
            assertEquals(clock.today().toString(), recent.single().bookedOn)
            assertEquals(1, upcoming.size)
            assertEquals("2026-08-03", upcoming.single().bookedOn)
            assertTrue(
                "a row may never be in both lists",
                recent.map { it.id }.intersect(upcoming.map { it.id }.toSet()).isEmpty(),
            )
        }

    @Test
    fun `the upcoming list reads soonest first`() =
        runTest {
            // The opposite order to the recent list, and deliberately so: history reads newest first,
            // a schedule reads by what is due next.
            val account = newAccount()
            repository.create(
                TransactionDraft(account.id, Money(-300_00L), bookedOn = clock.today().plusDays(10)),
            ).expectOk()
            repository.create(
                TransactionDraft(account.id, Money(-100_00L), bookedOn = clock.today().plusDays(2)),
            ).expectOk()

            val upcoming = repository.observeUpcoming().first()

            assertEquals(listOf("2026-08-04", "2026-08-12"), upcoming.map { it.bookedOn })
        }

    @Test
    fun `the upcoming window is bounded at both ends`() =
        runTest {
            val account = newAccount()
            val window = TransactionRepository.UPCOMING_WINDOW_DAYS
            repository.create(
                TransactionDraft(account.id, Money(-100_00L), bookedOn = clock.today().plusDays(window)),
            ).expectOk()
            repository.create(
                TransactionDraft(account.id, Money(-200_00L), bookedOn = clock.today().plusDays(window + 1)),
            ).expectOk()

            val upcoming = repository.observeUpcoming().first()

            // The far row is not lost — it simply arrives in the window as its date approaches.
            assertEquals(1, upcoming.size)
            assertEquals(clock.today().plusDays(window).toString(), upcoming.single().bookedOn)
        }

    @Test
    fun `a scheduled row moves between the lists on its own date, with no write`() =
        runTest {
            val account = newAccount()
            val created =
                repository.create(
                    TransactionDraft(account.id, Money(-25_000_00L), bookedOn = clock.today().plusDays(1)),
                ).expectOk()
            assertEquals(1, repository.observeUpcoming().first().size)

            clock.advanceBy(Duration.ofHours(2))

            assertTrue(repository.observeUpcoming().first().isEmpty())
            assertEquals(created.id, repository.liveTransactions().single().id)
        }

    // --- posting -----------------------------------------------------------------------------------

    @Test
    fun `a scheduled row carries no posted stamp and a same-day one does`() =
        runTest {
            val account = newAccount()
            val today = repository.create(TransactionDraft(account.id, Money(-100_00L))).expectOk()
            val later =
                repository.create(
                    TransactionDraft(account.id, Money(-200_00L), bookedOn = clock.today().plusDays(1)),
                ).expectOk()

            assertNotNull("a row booked today is posted as it is written", rawRow(today.id)?.postedAtUtcMillis)
            assertNull("a scheduled row is not", rawRow(later.id)?.postedAtUtcMillis)
        }

    @Test
    fun `posting stamps the rows whose day has come, and only those`() =
        runTest {
            val account = newAccount()
            val tomorrow =
                repository.create(
                    TransactionDraft(account.id, Money(-100_00L), bookedOn = clock.today().plusDays(1)),
                ).expectOk()
            val nextWeek =
                repository.create(
                    TransactionDraft(account.id, Money(-200_00L), bookedOn = clock.today().plusDays(7)),
                ).expectOk()

            clock.advanceBy(Duration.ofHours(2))
            val stamped = repository.postDueTransactions().expectOk()

            assertEquals(1, stamped)
            assertEquals(clock.nowUtcMillis(), rawRow(tomorrow.id)?.postedAtUtcMillis)
            assertNull("a row still in the future must not be stamped", rawRow(nextWeek.id)?.postedAtUtcMillis)
        }

    @Test
    fun `posting twice on the same day stamps nothing the second time`() =
        runTest {
            // The idempotence FR-TXN-010 asks for, and the reason the statement filters on
            // `posted_at_utc_millis IS NULL`. A worker may run twice — after a retry, after a reboot.
            val account = newAccount()
            val scheduled =
                repository.create(
                    TransactionDraft(account.id, Money(-100_00L), bookedOn = clock.today().plusDays(1)),
                ).expectOk()
            clock.advanceBy(Duration.ofHours(2))

            assertEquals(1, repository.postDueTransactions().expectOk())
            val firstStamp = rawRow(scheduled.id)?.postedAtUtcMillis
            clock.advanceBy(Duration.ofMinutes(30))

            assertEquals(0, repository.postDueTransactions().expectOk())
            assertEquals("the first stamp must not be moved", firstStamp, rawRow(scheduled.id)?.postedAtUtcMillis)
        }

    @Test
    fun `posting catches up every day a switched-off device missed`() =
        runTest {
            // `<= today`, not `= today`: a device off for a week must not leave six days unposted.
            val account = newAccount()
            val days =
                (1..5).map { day ->
                    repository.create(
                        TransactionDraft(
                            account.id,
                            Money(-100_00L),
                            bookedOn = clock.today().plusDays(day.toLong()),
                        ),
                    ).expectOk()
                }

            clock.advanceBy(Duration.ofDays(6))

            assertEquals(5, repository.postDueTransactions().expectOk())
            assertTrue(days.all { rawRow(it.id)?.postedAtUtcMillis != null })
        }

    @Test
    fun `posting never touches a deleted row`() =
        runTest {
            val account = newAccount()
            val scheduled =
                repository.create(
                    TransactionDraft(account.id, Money(-100_00L), bookedOn = clock.today().plusDays(1)),
                ).expectOk()
            repository.delete(scheduled.id).expectOk()
            clock.advanceBy(Duration.ofHours(2))

            assertEquals(0, repository.postDueTransactions().expectOk())
            assertNull(rawRow(scheduled.id)?.postedAtUtcMillis)
        }

    @Test
    fun `posting reports zero rather than failing when nothing is due`() =
        runTest {
            // A worker must be able to tell "nothing to do" from "the write failed", because it
            // retries on the second and not the first.
            newAccount()

            assertEquals(0, repository.postDueTransactions().expectOk())
        }

    @Test
    fun `a stamp is not what decides whether a row counts`() =
        runTest {
            // The window between midnight and the worker's next run: the row is in the balance while
            // still carrying no stamp. Nothing may read a null stamp as "this is scheduled".
            val account = newAccount(opening = Money(1_00_000_00L))
            val scheduled =
                repository.create(
                    TransactionDraft(account.id, Money(-25_000_00L), bookedOn = clock.today().plusDays(1)),
                ).expectOk()

            clock.advanceBy(Duration.ofHours(2))

            assertNull("the worker has not run", rawRow(scheduled.id)?.postedAtUtcMillis)
            val balance = accounts.find(account.id).expectOk().balance
            assertEquals("and the money has moved anyway", Money(75_000_00L), balance)
            assertFalse(repository.liveTransactions().single().isScheduledOn(clock.today().toString()))
        }

    // --- zone and DST ------------------------------------------------------------------------------

    @Test
    fun `a scheduled row's instant is its own local midnight, not now`() =
        runTest {
            // The list orders by `occurred_at_utc_millis`, so stamping "now" would sort a payment
            // scheduled for next month in among today's rows.
            val account = newAccount()
            val scheduled =
                repository.create(
                    TransactionDraft(account.id, Money(-100_00L), bookedOn = clock.today().plusDays(1)),
                ).expectOk()

            // 2026-08-03T00:00 IST is 2026-08-02T18:30Z.
            assertEquals(
                Instant.parse("2026-08-02T18:30:00Z").toEpochMilli(),
                scheduled.occurredAtUtcMillis,
            )
            assertTrue(scheduled.occurredAtUtcMillis > clock.nowUtcMillis())
        }

    @Test
    fun `a day whose local midnight does not exist still books correctly`() =
        runTest {
            // Chile skips 2026-09-06T00:00 entirely — the clocks go straight to 01:00. `startOfDay`
            // resolves through the ZoneId, so java.time moves to the first valid instant; arithmetic
            // on a day count would land an hour into the previous day and book the row a day early.
            clock.setZone(ZoneId.of("America/Santiago"))
            clock.setTo(Instant.parse("2026-09-04T12:00:00Z").toEpochMilli())
            val account = newAccount()

            val scheduled =
                repository.create(
                    TransactionDraft(account.id, Money(-100_00L), bookedOn = clock.today().plusDays(2)),
                ).expectOk()

            assertEquals("2026-09-06", scheduled.bookedOn)
            // 01:00 CLST on the 6th is 04:00Z — the first instant that day actually has.
            assertEquals(
                Instant.parse("2026-09-06T04:00:00Z").toEpochMilli(),
                scheduled.occurredAtUtcMillis,
            )
        }

    @Test
    fun `the rollover happens at the profile's midnight, not UTC's`() =
        runTest {
            // TIM-001's whole point. At 23:30 IST the row booked for tomorrow is scheduled; half an
            // hour later it is an actual — while UTC is still five and a half hours from rolling over.
            val account = newAccount(opening = Money(1_00_000_00L))
            repository.create(
                TransactionDraft(account.id, Money(-25_000_00L), bookedOn = clock.today().plusDays(1)),
            ).expectOk()

            clock.advanceBy(Duration.ofMinutes(29))
            assertEquals(Money(1_00_000_00L), accounts.find(account.id).expectOk().balance)

            clock.advanceBy(Duration.ofMinutes(2))
            assertEquals(Money(75_000_00L), accounts.find(account.id).expectOk().balance)
        }

    // --- time of day (FR-TXN-001's "date-time") ----------------------------------------------------

    @Test
    fun `a chosen time is stamped instead of now`() =
        runTest {
            // FR-TXN-001 says "date-time". Before this the instant was always the app's choice —
            // now for today — so a user recording this morning's coffee this evening got an instant
            // that ordered it after everything they had bought since.
            val account = newAccount()

            val created =
                repository.create(
                    TransactionDraft(account.id, Money(-250_00L), bookedAt = LocalTime.of(8, 30)),
                ).expectOk()

            // 08:30 IST on 2026-08-02 is 03:00Z.
            assertEquals(
                Instant.parse("2026-08-02T03:00:00Z").toEpochMilli(),
                created.occurredAtUtcMillis,
            )
            assertTrue("and it is before 'now', which is 23:30 IST", created.occurredAtUtcMillis < clock.nowUtcMillis())
        }

    @Test
    fun `a chosen time does not change which day the row belongs to`() =
        runTest {
            // **The property that keeps the time harmless.** Balances and budgets bound on
            // `booked_on_iso_date`, so an hour is presentation and ordering; the day is money.
            val account = newAccount(opening = Money(1_00_000_00L))

            val created =
                repository.create(
                    TransactionDraft(account.id, Money(-250_00L), bookedAt = LocalTime.of(0, 5)),
                ).expectOk()

            assertEquals(clock.today().toString(), created.bookedOn)
            assertEquals(Money(99_750_00L), accounts.find(account.id).expectOk().balance)
        }

    @Test
    fun `a time on a future date still leaves the row out of the balance`() =
        runTest {
            val account = newAccount(opening = Money(1_00_000_00L))

            val scheduled =
                repository.create(
                    TransactionDraft(
                        account.id,
                        Money(-25_000_00L),
                        bookedOn = clock.today().plusDays(1),
                        bookedAt = LocalTime.of(9, 0),
                    ),
                ).expectOk()

            assertEquals("2026-08-03", scheduled.bookedOn)
            // 09:00 IST on the 3rd is 03:30Z on the 3rd.
            assertEquals(
                Instant.parse("2026-08-03T03:30:00Z").toEpochMilli(),
                scheduled.occurredAtUtcMillis,
            )
            assertNull("still scheduled: the hour does not post it", rawRow(scheduled.id)?.postedAtUtcMillis)
            assertEquals(Money(1_00_000_00L), accounts.find(account.id).expectOk().balance)
        }

    @Test
    fun `an omitted time keeps the behaviour every caller had before`() =
        runTest {
            // `null` is not "midnight" — it is "the app's choice", which is now for today.
            val account = newAccount()

            val created = repository.create(TransactionDraft(account.id, Money(-250_00L))).expectOk()

            assertEquals(clock.nowUtcMillis(), created.occurredAtUtcMillis)
        }

    @Test
    fun `a time that does not exist on that day resolves forward rather than backward`() =
        runTest {
            // Chile skips 2026-09-06T00:00 to 01:00. Asking for 00:30 on that day asks for an
            // instant the local clock never shows, and arithmetic on a fixed offset would land at
            // 23:30 the *previous* day — a row booked on the wrong date, the one thing the day must
            // never get wrong.
            //
            // **`ZonedDateTime` shifts forward by the length of the gap, not to the first valid
            // instant.** 00:30 + 1h = 01:30 local, which is 04:30Z — *not* the 04:00Z that
            // `startOfDay` produces for the same day, because that asks for 00:00 and 00:00 + 1h is
            // 01:00. Both are "resolve forward"; the results differ because the requested local
            // times do. Worth spelling out: the obvious expectation here is wrong, and this test was
            // written asserting it.
            clock.setZone(ZoneId.of("America/Santiago"))
            clock.setTo(Instant.parse("2026-09-04T12:00:00Z").toEpochMilli())
            val account = newAccount()

            val created =
                repository.create(
                    TransactionDraft(
                        account.id,
                        Money(-250_00L),
                        bookedOn = clock.today().plusDays(2),
                        bookedAt = LocalTime.of(0, 30),
                    ),
                ).expectOk()

            assertEquals("2026-09-06", created.bookedOn)
            assertEquals(
                Instant.parse("2026-09-06T04:30:00Z").toEpochMilli(),
                created.occurredAtUtcMillis,
            )
        }

    @Test
    fun `both legs of a transfer share one instant`() =
        runTest {
            val from = newAccount(name = "Savings")
            val to = newAccount(name = "Cash")

            val transfer =
                repository.createTransfer(
                    TransferDraft(from.id, to.id, Money(1_000_00L), bookedAt = LocalTime.of(14, 15)),
                ).expectOk()

            val instants = allRows().filter { it.transferId == transfer.id }.map { it.occurredAtUtcMillis }
            assertEquals(2, instants.size)
            assertEquals("one movement, one instant", 1, instants.distinct().size)
            assertEquals(Instant.parse("2026-08-02T08:45:00Z").toEpochMilli(), instants.first())
        }

    // --- helpers -----------------------------------------------------------------------------------

    /**
     * Result: a live account under the real profile. Input: [name], [opening]. Output: the [Account].
     */
    private suspend fun newAccount(
        name: String = "HDFC Savings",
        opening: Money = Money(50_000_00L),
    ) = accounts.create(
        AccountDraft(
            name = name,
            type = AccountType.BANK,
            openingBalance = opening,
            currencyCode = "INR",
        ),
    ).expectOk()

    /**
     * Result: every transaction row including tombstones — the repository's reads are windowed and
     *         mapped, so they cannot answer "what is actually stored?".
     * Input:  none. Output: the rows.
     */
    private fun allRows() =
        database.openHelper.readableDatabase
            .query("SELECT * FROM transactions")
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            RawTransactionRow(
                                id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                                bookedOnIsoDate = cursor.getString(cursor.getColumnIndexOrThrow("booked_on_iso_date")),
                                transferId =
                                    cursor.getColumnIndexOrThrow("transfer_id").let {
                                        if (cursor.isNull(it)) null else cursor.getString(it)
                                    },
                                occurredAtUtcMillis =
                                    cursor.getLong(cursor.getColumnIndexOrThrow("occurred_at_utc_millis")),
                                postedAtUtcMillis =
                                    cursor.getColumnIndexOrThrow("posted_at_utc_millis").let {
                                        if (cursor.isNull(it)) null else cursor.getLong(it)
                                    },
                                deletedAtUtcMillis =
                                    cursor.getColumnIndexOrThrow("deleted_at_utc_millis").let {
                                        if (cursor.isNull(it)) null else cursor.getLong(it)
                                    },
                            ),
                        )
                    }
                }
            }

    /** Result: one stored row, tombstones included, or null. Input: [id]. Output: `RawTransactionRow?`. */
    private fun rawRow(id: String) = allRows().firstOrNull { it.id == id }

    private companion object {
        const val REAL_PROFILE = "local"
    }
}

/**
 * The columns this suite asserts on, read straight out of SQLite.
 *
 * Why:  the repository's reads are windowed, mapped and soft-delete-filtered, so none of them can
 *       answer "is the stamp actually null on the stored row?" — which is the question half these
 *       tests ask. Declared per file, as [TransactionRepositoryTest]'s own raw helpers are.
 * Result: an immutable view of one row.
 * Changelog: 2026-08-03 — Created for issue 3.4.
 *
 * Input:  [id]; [bookedOnIsoDate]; [transferId]; [postedAtUtcMillis]; [deletedAtUtcMillis].
 * Output: an immutable value.
 */
private data class RawTransactionRow(
    val id: String,
    val bookedOnIsoDate: String,
    val transferId: String?,
    val occurredAtUtcMillis: Long,
    val postedAtUtcMillis: Long?,
    val deletedAtUtcMillis: Long?,
)

/**
 * Unwraps a result the test expects to have succeeded.
 * Why:    a failure here names the error rather than throwing a bare `ClassCastException`. Declared
 *         per file because the same helper in the sibling suites is private to each of them.
 * Result: the value. Input: the receiver. Output: [T].
 * Changelog: 2026-08-03 — Created for issue 3.4.
 */
private fun <T> com.aicfo.core.common.Result<T, AppError>.expectOk(): T =
    when (this) {
        is Ok -> value
        is Err -> throw AssertionError("expected Ok, got Err(${error.code})")
    }
