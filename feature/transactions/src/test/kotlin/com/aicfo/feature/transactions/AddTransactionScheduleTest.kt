package com.aicfo.feature.transactions

import app.cash.turbine.test
import com.aicfo.core.common.FakeClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Tests for the add screen's booked-date handling (issue 3.4; FR-TXN-010).
 *
 * Why:  a file of its own rather than more cases in [AddTransactionViewModelTest], which detekt's
 *       600-line class limit stopped the moment these were added — a fair complaint, because future
 *       dating is a distinct claim about the screen from the tap budget and the sign convention that
 *       file exists to pin.
 *
 *       Two properties matter most. **The default path must be byte-for-byte what it was before
 *       issue 3.4** — a draft carrying `null` — or FR-TXN-002's tap budget has silently changed.
 *       And **the picker's lower bound must come from the injected clock**, because at 23:30 IST the
 *       profile's today is a day ahead of UTC's, so a bound read from the wall clock would offer a
 *       day the repository then refuses.
 * What: the default, the bound, the three drafts, and the picker's open/close state.
 * Result: future dating is reachable without costing the common path a tap.
 * Changelog: 2026-08-03 — Created for issue 3.4.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddTransactionScheduleTest {
    private val transactions = FakeTransactionRepository()
    private val accounts = FakeAccountRepository()

    // 2026-08-02T18:00Z is 23:30 in Asia/Kolkata — after UTC's midnight but before the profile's,
    // which is the hour at which a bound read in the wrong zone gives the wrong day.
    private val clock = FakeClock(initialMillis = Instant.parse("2026-08-02T18:00:00Z").toEpochMilli())

    /** Input: none. Output: `viewModelScope` runs on a test dispatcher. */
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Input: none. Output: restores the real main dispatcher. */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the date defaults to today and is sent as null, exactly as before issue 3-4`() =
        runTest {
            // **The tap budget's guard.** `null` is what every path before 3.4 sent, so the common
            // expense is byte-for-byte the same draft it always was and FR-TXN-002 is untouched.
            accounts.setAccounts(account())
            val viewModel = viewModel()
            viewModel.onEvent(AddTransactionEvent.AmountChanged("250"))

            viewModel.onEvent(AddTransactionEvent.Save)

            assertNull(transactions.created.single().bookedOn)
        }

    @Test
    fun `the picker is bounded at today, from the injected clock`() =
        runTest {
            // Not `LocalDate.now()`: the bound is the profile zone's today, and at 23:30 IST that is
            // a day ahead of UTC's. A picker bounded in UTC would offer a day the store refuses.
            accounts.setAccounts(account())

            viewModel().uiState.test {
                assertEquals(TODAY, awaitItem().earliestBookableDate)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `picking a future day sends it on the draft`() =
        runTest {
            accounts.setAccounts(account())
            val viewModel = viewModel()
            viewModel.onEvent(AddTransactionEvent.AmountChanged("25000"))

            viewModel.onEvent(ScheduleEvent.DateSelected(TODAY.plusDays(1)))
            viewModel.onEvent(AddTransactionEvent.Save)

            assertEquals(TODAY.plusDays(1), transactions.created.single().bookedOn)
        }

    @Test
    fun `picking today collapses back to null rather than storing a date`() =
        runTest {
            // So the button reads "Today" again if the user opens the picker and changes their mind,
            // and so the draft is identical to one where the picker was never opened.
            accounts.setAccounts(account())
            val viewModel = viewModel()
            viewModel.onEvent(AddTransactionEvent.AmountChanged("250"))
            viewModel.onEvent(ScheduleEvent.DateSelected(TODAY.plusDays(3)))

            viewModel.onEvent(ScheduleEvent.DateSelected(TODAY))
            viewModel.onEvent(AddTransactionEvent.Save)

            assertNull(transactions.created.single().bookedOn)
        }

    @Test
    fun `the picker opens and closes`() =
        runTest {
            accounts.setAccounts(account())
            val viewModel = viewModel()

            viewModel.onEvent(ScheduleEvent.DatePickerOpened)
            viewModel.uiState.test {
                assertTrue(awaitItem().isDatePickerOpen)
                cancelAndIgnoreRemainingEvents()
            }

            viewModel.onEvent(ScheduleEvent.DatePickerDismissed)
            viewModel.uiState.test {
                assertFalse(awaitItem().isDatePickerOpen)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a scheduled transfer carries the date on its draft`() =
        runTest {
            val viewModel = transferViewModel(amount = "10000")

            viewModel.onEvent(ScheduleEvent.DateSelected(TODAY.plusDays(5)))
            viewModel.onEvent(AddTransactionEvent.Save)

            assertEquals(TODAY.plusDays(5), transactions.transfersCreated.single().bookedOn)
        }

    @Test
    fun `a scheduled split carries the date on its parent`() =
        runTest {
            val viewModel = balancedSplitViewModel()

            viewModel.onEvent(ScheduleEvent.DateSelected(TODAY.plusDays(7)))
            viewModel.onEvent(AddTransactionEvent.Save)

            assertEquals(TODAY.plusDays(7), transactions.splitsCreated.single().bookedOn)
        }

    @Test
    fun `the chosen date survives a change of direction`() =
        runTest {
            // `withDirection` clears what no longer applies — a category, a destination. The date
            // applies to all three kinds of capture, so clearing it would be a silent surprise.
            accounts.setAccounts(account { copy(id = "account:1") }, account { copy(id = "account:2") })
            val viewModel = viewModel()
            viewModel.onEvent(AddTransactionEvent.AmountChanged("250"))
            viewModel.onEvent(ScheduleEvent.DateSelected(TODAY.plusDays(2)))

            viewModel.onEvent(AddTransactionEvent.DirectionChanged(TransactionDirection.INCOME))

            viewModel.uiState.test {
                assertEquals(TODAY.plusDays(2), awaitItem().bookedOn)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- fixtures ----------------------------------------------------------------------------------

    /** Result: a ViewModel over the two fakes and the frozen clock. Output: [AddTransactionViewModel]. */
    private fun viewModel() = AddTransactionViewModel(transactions, accounts, clock)

    /**
     * Result: a ViewModel with two accounts, [amount] typed, Transfer chosen and a destination
     *         picked. Input: [amount]. Output: [AddTransactionViewModel].
     */
    private fun transferViewModel(amount: String): AddTransactionViewModel {
        accounts.setAccounts(account { copy(id = "account:1") }, account { copy(id = "account:2") })
        return viewModel().apply {
            onEvent(AddTransactionEvent.AmountChanged(amount))
            onEvent(AddTransactionEvent.DirectionChanged(TransactionDirection.TRANSFER))
            onEvent(AddTransactionEvent.DestinationSelected("account:2"))
        }
    }

    /**
     * Result: a ViewModel with one account, ₹1,000 typed, splitting on and the lines filled to
     *         600/400 — a balanced, savable split. Input: none. Output: [AddTransactionViewModel].
     */
    private fun balancedSplitViewModel(): AddTransactionViewModel {
        accounts.setAccounts(account())
        return viewModel().apply {
            onEvent(AddTransactionEvent.AmountChanged("1000"))
            onEvent(SplitEvent.SplitToggled(true))
            onEvent(SplitEvent.SplitLineAmountChanged(0, "600"))
            onEvent(SplitEvent.SplitLineAmountChanged(1, "400"))
        }
    }

    private companion object {
        /**
         * The day [clock] reports, in the profile zone.
         *
         * Named rather than repeated so every assertion is relative to the frozen clock — a literal
         * would drift from the fixture the day someone moved it (P-08).
         */
        val TODAY: LocalDate = LocalDate.parse("2026-08-02")
    }
}
