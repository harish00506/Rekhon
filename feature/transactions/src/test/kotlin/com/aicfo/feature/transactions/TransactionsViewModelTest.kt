package com.aicfo.feature.transactions

import androidx.paging.testing.asSnapshot
import app.cash.turbine.test
import com.aicfo.core.common.AppError
import com.aicfo.core.model.Money
import com.aicfo.core.model.Tag
import com.aicfo.core.model.Transaction
import com.aicfo.core.model.TransactionSource
import com.aicfo.core.model.TransactionType
import com.aicfo.data.repository.FilteredTransaction
import com.aicfo.data.repository.TransactionFilter
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

/**
 * Tests for [TransactionsViewModel], [groupIntoDays] and [toListItems] (issue 3.1; ARC-004).
 *
 * Why:  the grouping is the logic on this screen, and it has ways to be wrong a rendered test would
 *       not catch. **Days must come newest first** — a list starting three weeks ago is one the user
 *       has to scroll to see what they just saved. **The daily total must be the day's, not the
 *       page's** (issue 3.6), which is the whole reason it is read from the database rather than
 *       folded from loaded rows. The empty/failed distinction is the third: rendering a database
 *       that would not open as "no transactions yet" hides the failure.
 * What: the state sequence, the paged items, the totals, the empty-ish states, and — since issue
 *       3.6 — the filter becoming a query, selection, and undo.
 * Result: FR-TXN-007's grouping half is a property a test holds.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 *            2026-08-04 — Issue 3.6: the rows moved from `uiState.days` onto a `PagingData` stream,
 *            so list assertions run through `asSnapshot` and state assertions stay on Turbine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModelTest {
    private val repository = FakeTransactionRepository()
    private val accounts = FakeAccountRepository()
    private val recurring = FakeRecurringRepository()

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

    // --- the list (issue 3.1; FR-TXN-007's grouping half) ------------------------------------------

    @Test
    fun `an empty store renders the empty state, not a spinner`() =
        runTest {
            val viewModel = TransactionsViewModel(repository, recurring, accounts)

            viewModel.uiState.test {
                assertFalse(awaitItem().isLoading)
                cancelAndIgnoreRemainingEvents()
            }
            assertTrue(viewModel.listItems().isEmpty())
        }

    @Test
    fun `transactions are grouped by their booked day, newest day first`() =
        runTest {
            repository.setTransactions(
                transaction { copy(id = "txn:1", bookedOn = "2026-08-02") },
                transaction { copy(id = "txn:2", bookedOn = "2026-08-01") },
            )

            val headers = TransactionsViewModel(repository, recurring, accounts).dayHeaders()

            assertEquals(listOf("2026-08-02", "2026-08-01"), headers.map { it.isoDate })
        }

    @Test
    fun `one header per day, even when a day holds several rows`() =
        runTest {
            // What `insertSeparators` is for. A header per row — or none after the first page — is
            // the failure paging makes easy and a whole-list grouping made impossible.
            repository.setTransactions(
                transaction { copy(id = "txn:1", bookedOn = "2026-08-02") },
                transaction { copy(id = "txn:2", bookedOn = "2026-08-02") },
                transaction { copy(id = "txn:3", bookedOn = "2026-08-01") },
            )

            val items = TransactionsViewModel(repository, recurring, accounts).listItems()

            assertEquals(
                listOf("day:2026-08-02", "txn:txn:1", "txn:txn:2", "day:2026-08-01", "txn:txn:3"),
                items.map { it.key },
            )
        }

    @Test
    fun `the repository's order within a day is kept`() =
        runTest {
            repository.setTransactions(
                transaction { copy(id = "txn:1", note = "newest") },
                transaction { copy(id = "txn:2", note = "older") },
            )

            val rows = TransactionsViewModel(repository, recurring, accounts).rows()

            assertEquals(listOf("newest", "older"), rows.map { it.noteOrNull() })
        }

    @Test
    fun `a day's total is the store's figure, not a fold over the loaded rows`() =
        runTest {
            // The claim paging makes hardest to keep: a page boundary can fall inside a day, so a
            // header summed from what is in memory would understate its own day until the user
            // scrolled. The header renders whatever `observeDayTotals` says.
            repository.setTransactions(
                transaction { copy(id = "txn:1", amount = Money(-700_00L)) },
                transaction { copy(id = "txn:2", amount = Money(-250_00L)) },
            )

            val headers = TransactionsViewModel(repository, recurring, accounts).dayHeaders()

            assertEquals(Money(-950_00L), headers.single().total)
        }

    @Test
    fun `each day totals only its own rows`() =
        runTest {
            repository.setTransactions(
                transaction { copy(id = "txn:1", bookedOn = "2026-08-02", amount = Money(-250_00L)) },
                transaction { copy(id = "txn:2", bookedOn = "2026-08-01", amount = Money(-700_00L)) },
            )

            val headers = TransactionsViewModel(repository, recurring, accounts).dayHeaders()

            assertEquals(listOf(Money(-250_00L), Money(-700_00L)), headers.map { it.total })
        }

    @Test
    fun `a read that throws reports an error rather than an empty list`() =
        runTest {
            repository.failOnObserve = AppError.Storage("read")

            TransactionsViewModel(repository, recurring, accounts).uiState.test {
                val state = awaitItem()
                assertFalse(state.isLoading)
                // Non-null, not a particular code: the fake signals a broken read by throwing an
                // `IllegalStateException`, which `toAppError` classifies as `unexpected`. What this
                // test is about is that the failure *surfaces* rather than rendering as an empty
                // profile; which code it carries is a property of the fake, not of the ViewModel.
                assertTrue(state.errorCode != null)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `grouping an empty list gives no days`() =
        runTest {
            assertTrue(emptyList<Transaction>().groupIntoDays().isEmpty())
        }

    // --- transfers (issue 3.2; FR-TXN-003) ---------------------------------------------------------

    @Test
    fun `a transfer leg with a counterpart renders as one collapsed row`() {
        // Issue 3.6 moved the collapse into SQL, so the ViewModel's job is now to turn one leg plus
        // its projected counterpart into a pair — see `FilteredTransaction.toRow`.
        val row =
            filtered(
                transaction {
                    copy(
                        id = "tfr:1:out",
                        amount = Money(-5_000_00L),
                        type = TransactionType.TRANSFER_OUT,
                        transferId = "tfr:1",
                    )
                },
                counterpart = "account:2",
            ).toRow()

        val pair = row as TransactionRow.TransferPair
        assertEquals("account:1", pair.outAccountId)
        assertEquals("account:2", pair.inAccountId)
        // Positive: the size of the movement, matching `Transfer`. The stored leg is signed.
        assertEquals(Money(5_000_00L), pair.amount)
    }

    @Test
    fun `an incoming leg names the accounts the same way round`() {
        // Reached when the user filters to the destination account, where the query returns the
        // incoming leg instead. "HDFC → Cash" must not become "Cash → HDFC" because of it.
        val row =
            filtered(
                transaction {
                    copy(
                        id = "tfr:1:in",
                        accountId = "account:2",
                        amount = Money(5_000_00L),
                        type = TransactionType.TRANSFER_IN,
                        transferId = "tfr:1",
                    )
                },
                counterpart = "account:1",
            ).toRow()

        val pair = row as TransactionRow.TransferPair
        assertEquals("account:1", pair.outAccountId)
        assertEquals("account:2", pair.inAccountId)
        assertEquals(Money(5_000_00L), pair.amount)
    }

    @Test
    fun `a leg whose sibling is gone stays an ordinary row rather than vanishing`() {
        // The honest failure mode: hiding it would make money disappear from the list.
        val row = filtered(transaction { copy(id = "tfr:1:out", transferId = "tfr:1") }).toRow()

        assertTrue(row is TransactionRow.Single)
    }

    @Test
    fun `account names are exposed so a transfer row can name them`() =
        runTest {
            accounts.setAccounts(account { copy(id = "account:1", name = "HDFC Savings") })

            TransactionsViewModel(repository, recurring, accounts).uiState.test {
                assertEquals(mapOf("account:1" to "HDFC Savings"), awaitItem().accountNames)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- delete (issue 3.2) ------------------------------------------------------------------------

    @Test
    fun `deleting a row hands the store the transaction id, not a transfer id`() =
        runTest {
            repository.setTransactions(transaction { copy(id = "txn:1") })
            val viewModel = TransactionsViewModel(repository, recurring, accounts)

            viewModel.onEvent(TransactionsEvent.Delete("txn:1"))

            assertEquals(listOf("txn:1"), repository.deleted)
        }

    @Test
    fun `a refused delete reports the code and leaves the row`() =
        runTest {
            repository.setTransactions(transaction { copy(id = "txn:1") })
            repository.failWith = AppError.Storage("delete")
            val viewModel = TransactionsViewModel(repository, recurring, accounts)

            viewModel.onEvent(TransactionsEvent.Delete("txn:1"))

            viewModel.uiState.test {
                assertEquals("storage", awaitItem().errorCode)
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(1, viewModel.rows().size)
        }

    @Test
    fun `dismissing the error clears it`() =
        runTest {
            repository.failWith = AppError.Storage("delete")
            val viewModel = TransactionsViewModel(repository, recurring, accounts)
            viewModel.onEvent(TransactionsEvent.Delete("txn:1"))

            viewModel.onEvent(TransactionsEvent.DismissError)

            viewModel.uiState.test {
                assertNull(awaitItem().errorCode)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- future-dated transactions (issue 3.4; FR-TXN-010) -----------------------------------------

    @Test
    fun `the scheduled group reads soonest first, the opposite of history`() =
        runTest {
            repository.setUpcoming(
                transaction { copy(id = "txn:1", bookedOn = "2026-08-20") },
                transaction { copy(id = "txn:2", bookedOn = "2026-08-10") },
            )

            TransactionsViewModel(repository, recurring, accounts).uiState.test {
                assertEquals(listOf("2026-08-10", "2026-08-20"), awaitItem().upcoming.map { it.isoDate })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a profile holding only scheduled rows still has a scheduled section`() =
        runTest {
            repository.setUpcoming(transaction { copy(id = "txn:1", bookedOn = "2026-08-10") })

            TransactionsViewModel(repository, recurring, accounts).uiState.test {
                assertTrue(awaitItem().hasUpcoming)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a scheduled transfer still collapses to one row`() =
        runTest {
            // The scheduled group runs through `toRows`, so FR-TXN-003's collapsing is not something
            // the section has to re-implement or can forget.
            repository.setUpcoming(*transferLegs(bookedOn = "2026-08-10").toTypedArray())

            TransactionsViewModel(repository, recurring, accounts).uiState.test {
                val rows = awaitItem().upcoming.single().rows
                assertEquals(1, rows.size)
                assertTrue(rows.single() is TransactionRow.TransferPair)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- source chips (issue 3.5; FR-TXN-009) ------------------------------------------------------

    @Test
    fun `the chips come from the whole ledger, in enum order`() =
        runTest {
            repository.setTransactions(
                transaction { copy(id = "txn:1", source = TransactionSource.RECONCILIATION) },
                transaction { copy(id = "txn:2", source = TransactionSource.MANUAL) },
            )

            TransactionsViewModel(repository, recurring, accounts).uiState.test {
                val state = awaitItem()
                assertEquals(
                    listOf(TransactionSource.MANUAL, TransactionSource.RECONCILIATION),
                    state.availableSources,
                )
                assertTrue(state.hasSourceFilter)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `one source is not a choice, so no chip row is offered`() =
        runTest {
            repository.setTransactions(transaction { copy(source = TransactionSource.MANUAL) })

            TransactionsViewModel(repository, recurring, accounts).uiState.test {
                assertFalse(awaitItem().hasSourceFilter)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `the chips survive a selection, because they come from the store and not the rows`() =
        runTest {
            // Derived from what is on screen, choosing a chip would delete every alternative and
            // strand the user with no way back to "All".
            repository.setTransactions(
                transaction { copy(id = "txn:1", source = TransactionSource.MANUAL) },
                transaction { copy(id = "txn:2", source = TransactionSource.RECONCILIATION) },
            )
            val viewModel = TransactionsViewModel(repository, recurring, accounts)

            viewModel.onEvent(TransactionsEvent.SourceFilterSelected(TransactionSource.RECONCILIATION))

            viewModel.uiState.test {
                assertEquals(2, awaitItem().availableSources.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- search and filters (issue 3.6; FR-TXN-007) ------------------------------------------------

    @Test
    fun `typing a search term becomes a query`() =
        runTest {
            val viewModel = TransactionsViewModel(repository, recurring, accounts)

            viewModel.onEvent(TransactionsEvent.SearchChanged("chai"))
            viewModel.listItems()

            assertEquals("chai", repository.filtersQueried.last().query)
        }

    @Test
    fun `a search narrows the rows`() =
        runTest {
            repository.setTransactions(
                transaction { copy(id = "txn:1", merchant = "Chai Point") },
                transaction { copy(id = "txn:2", merchant = "Big Bazaar") },
            )
            val viewModel = TransactionsViewModel(repository, recurring, accounts)

            viewModel.onEvent(TransactionsEvent.SearchChanged("chai"))

            assertEquals(listOf("txn:1"), viewModel.rows().map { it.id })
        }

    @Test
    fun `a chip and a search term compose rather than replacing one another`() =
        runTest {
            val viewModel = TransactionsViewModel(repository, recurring, accounts)

            viewModel.onEvent(TransactionsEvent.SearchChanged("chai"))
            viewModel.onEvent(TransactionsEvent.SourceFilterSelected(TransactionSource.OCR))
            viewModel.listItems()

            val queried = repository.filtersQueried.last()
            assertEquals("chai", queried.query)
            assertEquals(TransactionSource.OCR, queried.source)
        }

    @Test
    fun `a filter from the sheet reaches the query whole`() =
        runTest {
            val viewModel = TransactionsViewModel(repository, recurring, accounts)
            val filter = TransactionFilter(accountId = "account:1", minAmount = Money(100_00L))

            viewModel.onEvent(FilterEvent.Changed(filter))
            viewModel.listItems()

            assertEquals(filter, repository.filtersQueried.last())
        }

    @Test
    fun `clearing the filter keeps the search text the user is still holding`() =
        runTest {
            // The text is in a field the sheet does not own; wiping it from under the cursor would
            // read as the app losing what they said.
            val viewModel = TransactionsViewModel(repository, recurring, accounts)
            viewModel.onEvent(TransactionsEvent.SearchChanged("chai"))
            viewModel.onEvent(TransactionsEvent.SourceFilterSelected(TransactionSource.OCR))

            viewModel.onEvent(FilterEvent.Cleared)

            viewModel.uiState.test {
                val state = awaitItem()
                assertEquals("chai", state.filter.query)
                assertNull(state.filter.source)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `the sheet opens and closes`() =
        runTest {
            val viewModel = TransactionsViewModel(repository, recurring, accounts)

            viewModel.onEvent(FilterEvent.Opened)
            viewModel.uiState.test {
                assertTrue(awaitItem().isFilterSheetOpen)
                cancelAndIgnoreRemainingEvents()
            }

            viewModel.onEvent(FilterEvent.Dismissed)
            viewModel.uiState.test {
                assertFalse(awaitItem().isFilterSheetOpen)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `the tags the profile has reach the filter sheet`() =
        runTest {
            repository.setTags(Tag(id = "tag:1", name = "goa-trip"))

            TransactionsViewModel(repository, recurring, accounts).uiState.test {
                assertEquals(listOf("goa-trip"), awaitItem().availableTags.map { it.name })
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- bulk edit (issue 3.6; FR-TXN-008) ---------------------------------------------------------

    @Test
    fun `selecting and deselecting a row toggles it`() =
        runTest {
            val viewModel = TransactionsViewModel(repository, recurring, accounts)

            viewModel.onEvent(BulkEvent.Toggled("txn:1"))
            viewModel.uiState.test {
                val state = awaitItem()
                assertEquals(setOf("txn:1"), state.selection)
                assertTrue(state.isSelecting)
                cancelAndIgnoreRemainingEvents()
            }

            viewModel.onEvent(BulkEvent.Toggled("txn:1"))
            viewModel.uiState.test {
                assertFalse(awaitItem().isSelecting)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a selected row is marked as such in the list`() =
        runTest {
            repository.setTransactions(transaction { copy(id = "txn:1") })
            val viewModel = TransactionsViewModel(repository, recurring, accounts)

            viewModel.onEvent(BulkEvent.Toggled("txn:1"))

            val rows = viewModel.listItems().filterIsInstance<TransactionListItem.Row>()
            assertTrue(rows.single().isSelected)
        }

    @Test
    fun `recategorising sends the whole selection and then leaves selection mode`() =
        runTest {
            // Leaving twenty rows selected after changing them invites the user to change them again
            // by reflex.
            val viewModel = TransactionsViewModel(repository, recurring, accounts)
            viewModel.onEvent(BulkEvent.Toggled("txn:1"))
            viewModel.onEvent(BulkEvent.Toggled("txn:2"))

            viewModel.onEvent(BulkEvent.Recategorise("cat:food"))

            assertEquals(listOf("recategorise:txn:1,txn:2:cat:food"), repository.bulkCalls)
            viewModel.uiState.test {
                assertFalse(awaitItem().isSelecting)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `retagging sends the labels the user typed`() =
        runTest {
            val viewModel = TransactionsViewModel(repository, recurring, accounts)
            viewModel.onEvent(BulkEvent.Toggled("txn:1"))

            viewModel.onEvent(BulkEvent.Retag(listOf("goa-trip", "work")))

            assertEquals(listOf("retag:txn:1:goa-trip,work"), repository.bulkCalls)
        }

    @Test
    fun `a bulk action on an empty selection asks the store for nothing`() =
        runTest {
            val viewModel = TransactionsViewModel(repository, recurring, accounts)

            viewModel.onEvent(BulkEvent.Recategorise("cat:food"))
            viewModel.onEvent(BulkEvent.Delete)

            assertTrue(repository.bulkCalls.isEmpty())
        }

    @Test
    fun `a refused bulk edit reports the code and keeps the selection`() =
        runTest {
            // The rows did not change, so dropping the selection would leave the user re-picking
            // twenty rows to retry.
            repository.failWith = AppError.Storage("bulk")
            val viewModel = TransactionsViewModel(repository, recurring, accounts)
            viewModel.onEvent(BulkEvent.Toggled("txn:1"))

            viewModel.onEvent(BulkEvent.Recategorise("cat:food"))

            viewModel.uiState.test {
                val state = awaitItem()
                assertEquals("storage", state.errorCode)
                assertTrue(state.isSelecting)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a bulk delete arms undo with what the store actually removed`() =
        runTest {
            // **The property this whole batch exists for.** Deleting one leg of a transfer removes
            // both (FR-TXN-003); an undo that restored only the selection would bring back one leg
            // and leave the money it moved in one account with no counterpart.
            repository.deleteAllReturns = listOf("tfr:1:out", "tfr:1:in")
            val viewModel = TransactionsViewModel(repository, recurring, accounts)
            viewModel.onEvent(BulkEvent.Toggled("tfr:1:out"))

            viewModel.onEvent(BulkEvent.Delete)

            viewModel.uiState.test {
                val undo = awaitItem().undo
                assertEquals(listOf("tfr:1:out", "tfr:1:in"), undo?.ids)
                // The count is what the user picked — "2 deleted" for a one-row selection would be
                // alarming and true; "1" is what they meant.
                assertEquals(1, undo?.selectedCount)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `undo restores exactly the ids the delete reported`() =
        runTest {
            repository.deleteAllReturns = listOf("tfr:1:out", "tfr:1:in")
            val viewModel = TransactionsViewModel(repository, recurring, accounts)
            viewModel.onEvent(BulkEvent.Toggled("tfr:1:out"))
            viewModel.onEvent(BulkEvent.Delete)

            viewModel.onEvent(BulkEvent.Undo)

            assertEquals("restoreAll:tfr:1:out,tfr:1:in", repository.bulkCalls.last())
        }

    @Test
    fun `undo disarms itself, so a second tap cannot restore twice`() =
        runTest {
            // The second call would be a NotFound and would surface as an error banner over an
            // operation that had in fact succeeded.
            val viewModel = TransactionsViewModel(repository, recurring, accounts)
            viewModel.onEvent(BulkEvent.Toggled("txn:1"))
            viewModel.onEvent(BulkEvent.Delete)

            viewModel.onEvent(BulkEvent.Undo)
            viewModel.onEvent(BulkEvent.Undo)

            assertEquals(1, repository.bulkCalls.count { it.startsWith("restoreAll") })
            viewModel.uiState.test {
                assertNull(awaitItem().undo)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `letting the snackbar time out disarms undo without restoring`() =
        runTest {
            val viewModel = TransactionsViewModel(repository, recurring, accounts)
            viewModel.onEvent(BulkEvent.Toggled("txn:1"))
            viewModel.onEvent(BulkEvent.Delete)

            viewModel.onEvent(BulkEvent.UndoDismissed)

            assertTrue(repository.bulkCalls.none { it.startsWith("restoreAll") })
            viewModel.uiState.test {
                assertNull(awaitItem().undo)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- the detail sheet (issue 3.5) --------------------------------------------------------------

    @Test
    fun `tapping a row opens it, carrying the transaction the row was built from`() =
        runTest {
            // Issue 3.6: the event carries the transaction rather than an id. There is no snapshot of
            // the whole list to resolve an id against once the list is paged, and every row already
            // knows what it was built from.
            val tapped = transaction { copy(id = "txn:1", note = "Chai") }
            val viewModel = TransactionsViewModel(repository, recurring, accounts)

            viewModel.onEvent(TransactionsEvent.RowTapped(tapped))

            viewModel.uiState.test {
                assertEquals("Chai", awaitItem().detail?.note)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `dismissing closes the sheet`() =
        runTest {
            val viewModel = TransactionsViewModel(repository, recurring, accounts)
            viewModel.onEvent(TransactionsEvent.RowTapped(transaction()))

            viewModel.onEvent(TransactionsEvent.DetailDismissed)

            viewModel.uiState.test {
                assertNull(awaitItem().detail)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- recurring detection (issue 3.7; FR-TXN-006) ----------------------------------------------

    @Test
    fun `a detected series reaches the state`() =
        runTest {
            recurring.setSuggestions(listOf(series()))

            TransactionsViewModel(repository, recurring, accounts).uiState.test {
                val state = awaitItem()
                assertEquals(listOf("Landlord"), state.suggestions.map { it.merchant })
                assertTrue(state.hasSuggestions)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `confirming hands the whole series to the store and the card goes`() =
        runTest {
            // FR-TXN-006's "user confirms to create a Recurring Rule". The *series* is passed, not a
            // merchant name: the amount and next-due date the engine derived are what get stored.
            val proposal = series()
            recurring.setSuggestions(listOf(proposal))
            val viewModel = TransactionsViewModel(repository, recurring, accounts)

            viewModel.onEvent(RecurringEvent.Confirm(proposal))

            assertEquals(listOf(proposal), recurring.confirmed)
            viewModel.uiState.test {
                assertTrue(awaitItem().suggestions.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `dismissing records the rejection and the card goes`() =
        runTest {
            val proposal = series(merchant = "Netflix", minor = -649_00L)
            recurring.setSuggestions(listOf(proposal))
            val viewModel = TransactionsViewModel(repository, recurring, accounts)

            viewModel.onEvent(RecurringEvent.Dismiss(proposal))

            assertEquals(listOf(proposal), recurring.dismissed)
            assertTrue("a rejection is not a confirmation", recurring.confirmed.isEmpty())
            viewModel.uiState.test {
                assertTrue(awaitItem().suggestions.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a refused write surfaces and leaves the card in place`() =
        runTest {
            // The user's answer was not recorded, so they must be able to give it again — hiding the
            // card here would leave the screen disagreeing with the database.
            val proposal = series()
            recurring.setSuggestions(listOf(proposal))
            recurring.failWith = AppError.Storage("disk")
            val viewModel = TransactionsViewModel(repository, recurring, accounts)

            viewModel.onEvent(RecurringEvent.Confirm(proposal))

            viewModel.uiState.test {
                val state = awaitItem()
                assertEquals("storage", state.errorCode)
                assertEquals(listOf("Landlord"), state.suggestions.map { it.merchant })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `the section is hidden while the user is selecting rows`() =
        runTest {
            // A proposal is not part of the selection a bulk action operates on, and a Confirm button
            // beside an action bar counting rows would invite the user to think the two are related.
            recurring.setSuggestions(listOf(series()))
            val viewModel = TransactionsViewModel(repository, recurring, accounts)

            viewModel.onEvent(BulkEvent.Toggled("t1"))

            viewModel.uiState.test {
                val state = awaitItem()
                assertFalse(state.hasSuggestions)
                assertTrue("hidden, not discarded — leaving selection brings it back", state.suggestions.isNotEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- fixtures ----------------------------------------------------------------------------------

    /**
     * Result: every item the list would render, headers included.
     *
     * `asSnapshot` drives the pager to completion and hands back what it loaded — the list these
     * assertions were always about, which `first()` on a `Flow<PagingData<…>>` cannot give.
     *
     * Input: the receiver. Output: `List<TransactionListItem>`.
     */
    private suspend fun TransactionsViewModel.listItems(): List<TransactionListItem> = items.asSnapshot()

    /** Result: just the rows. Input: the receiver. Output: `List<TransactionRow>`. */
    private suspend fun TransactionsViewModel.rows(): List<TransactionRow> =
        listItems().filterIsInstance<TransactionListItem.Row>().map { it.row }

    /** Result: just the day headers. Input: the receiver. Output: the headers. */
    private suspend fun TransactionsViewModel.dayHeaders(): List<TransactionListItem.DayHeader> =
        listItems().filterIsInstance<TransactionListItem.DayHeader>()

    /**
     * Result: a page row as the repository would hand it over.
     * Input: [transaction]; [counterpart] — the other leg's account for a transfer, else `null`.
     * Output: [FilteredTransaction].
     */
    private fun filtered(
        transaction: Transaction,
        counterpart: String? = null,
    ) = FilteredTransaction(transaction = transaction, counterpartAccountId = counterpart)
}

/**
 * Result: the note of a single row, or `null` for a transfer. Input: the receiver. Output: `String?`.
 *
 * Only used by the ordering assertions, which seed plain transactions — a transfer has two notes and
 * no single one to return.
 */
private fun TransactionRow.noteOrNull(): String? = (this as? TransactionRow.Single)?.transaction?.note
