package com.aicfo.feature.transactions

import app.cash.turbine.test
import com.aicfo.core.common.AppError
import com.aicfo.core.model.Money
import com.aicfo.core.model.Transaction
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
 * Tests for [TransactionsViewModel] and [groupIntoDays] (issue 3.1; ARC-004).
 *
 * Why:  the grouping is the only logic on this screen, and it has two ways to be wrong that a
 *       rendered test would not catch. **Days must come newest first** — a list that started three
 *       weeks ago is one the user has to scroll to see what they just saved. **The daily total must
 *       be the sum of the rows under it**, computed with `Money`'s checked arithmetic (MNY-001), or
 *       the header contradicts the list beneath it. The empty/failed distinction is the third:
 *       rendering a database that would not open as "no transactions yet" hides the failure.
 * What: the state sequence, the grouping, the totals, and the three empty-ish states.
 * Result: FR-TXN-007's grouping half is a property a test holds.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModelTest {
    private val repository = FakeTransactionRepository()
    private val accounts = FakeAccountRepository()

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
    fun `an empty store renders the empty state, not a spinner`() =
        runTest {
            TransactionsViewModel(repository, accounts).uiState.test {
                val state = awaitItem()
                assertFalse(state.isLoading)
                assertTrue(state.isEmpty)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `transactions are grouped by their booked day, newest day first`() =
        runTest {
            repository.setTransactions(
                transaction { copy(id = "txn:3", bookedOn = "2026-08-02") },
                transaction { copy(id = "txn:2", bookedOn = "2026-08-01") },
                transaction { copy(id = "txn:1", bookedOn = "2026-07-31") },
            )

            TransactionsViewModel(repository, accounts).uiState.test {
                assertEquals(
                    listOf("2026-08-02", "2026-08-01", "2026-07-31"),
                    awaitItem().days.map { it.isoDate },
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `the repository's order within a day is kept`() =
        runTest {
            repository.setTransactions(
                transaction { copy(id = "txn:2", note = "later") },
                transaction { copy(id = "txn:1", note = "earlier") },
            )

            TransactionsViewModel(repository, accounts).uiState.test {
                assertEquals(
                    listOf("later", "earlier"),
                    awaitItem().days.single().rows.map { it.noteOrNull() },
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a day's total is the signed sum of its rows`() =
        runTest {
            // Signed, not a spend figure: an income booked the same day offsets the outflows, which
            // is what keeps this consistent with the account balance the user checks it against.
            repository.setTransactions(
                transaction { copy(id = "txn:1", amount = Money(-250_00L)) },
                transaction { copy(id = "txn:2", amount = Money(-1_200_00L)) },
                transaction { copy(id = "txn:3", amount = Money(500_00L)) },
            )

            TransactionsViewModel(repository, accounts).uiState.test {
                assertEquals(Money(-950_00L), awaitItem().days.single().total)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `each day totals only its own rows`() =
        runTest {
            repository.setTransactions(
                transaction { copy(id = "txn:2", bookedOn = "2026-08-02", amount = Money(-250_00L)) },
                transaction { copy(id = "txn:1", bookedOn = "2026-08-01", amount = Money(-1_200_00L)) },
            )

            TransactionsViewModel(repository, accounts).uiState.test {
                assertEquals(
                    listOf(Money(-250_00L), Money(-1_200_00L)),
                    awaitItem().days.map { it.total },
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a new transaction appears without the screen asking again`() =
        runTest {
            val viewModel = TransactionsViewModel(repository, accounts)

            viewModel.uiState.test {
                assertTrue(awaitItem().isEmpty)

                repository.setTransactions(transaction())

                assertEquals(1, awaitItem().days.single().rows.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a read that throws reports an error rather than an empty list`() =
        runTest {
            // The distinction this exists for: a database that would not open must not render as a
            // cheerful "no transactions yet", which would hide the failure from the user who most
            // needs to see it.
            repository.failOnObserve = AppError.Storage("boom")

            TransactionsViewModel(repository, accounts).uiState.test {
                val state = awaitItem()
                assertFalse(state.isLoading)
                assertFalse(state.isEmpty)
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
    fun `a transfer's two legs collapse into one row`() =
        runTest {
            // FR-TXN-003: "not two unlinked transactions". Rendered as stored, a ₹5,000 transfer
            // would appear twice and read as ₹10,000 of activity.
            repository.setTransactions(*transferLegs().toTypedArray())

            TransactionsViewModel(repository, accounts).uiState.test {
                val rows = awaitItem().days.single().rows
                assertEquals(1, rows.size)
                assertTrue(rows.single() is TransactionRow.TransferPair)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `the collapsed row names both accounts and a positive amount`() =
        runTest {
            repository.setTransactions(
                *transferLegs(amount = Money(5_000_00L), from = "account:1", to = "account:2").toTypedArray(),
            )

            TransactionsViewModel(repository, accounts).uiState.test {
                val row = awaitItem().days.single().rows.single() as TransactionRow.TransferPair
                assertEquals("account:1", row.outAccountId)
                assertEquals("account:2", row.inAccountId)
                // Positive: the size of the movement. A sign here would be asking which leg it is.
                assertEquals(Money(5_000_00L), row.amount)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a transfer contributes nothing to its day's total`() =
        runTest {
            // Arithmetically true — the legs are -X and +X — which is why collapsing them into one
            // row does not change the figure the day header shows.
            repository.setTransactions(
                transaction { copy(id = "txn:1", amount = Money(-250_00L)) },
                *transferLegs().toTypedArray(),
            )

            TransactionsViewModel(repository, accounts).uiState.test {
                assertEquals(Money(-250_00L), awaitItem().days.single().total)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `two separate transfers stay two rows`() =
        runTest {
            // The pairing key is `transferId`, never a match on amount and date. Two transfers of the
            // same size on one day must not merge into one.
            repository.setTransactions(
                *transferLegs(transferId = "tfr:1").toTypedArray(),
                *transferLegs(transferId = "tfr:2").toTypedArray(),
            )

            TransactionsViewModel(repository, accounts).uiState.test {
                assertEquals(2, awaitItem().days.single().rows.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `two same-day transactions of equal size are not mistaken for a transfer`() =
        runTest {
            repository.setTransactions(
                transaction { copy(id = "txn:1", amount = Money(-5_000_00L)) },
                transaction { copy(id = "txn:2", amount = Money(5_000_00L)) },
            )

            TransactionsViewModel(repository, accounts).uiState.test {
                val rows = awaitItem().days.single().rows
                assertEquals(2, rows.size)
                assertTrue(rows.all { it is TransactionRow.Single })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a leg whose sibling fell outside the window still renders`() =
        runTest {
            // The 30-day window can cut a pair in half. Hiding the survivor would make money vanish
            // from the list; showing it as an ordinary row is the honest failure mode.
            repository.setTransactions(transferLegs().first())

            TransactionsViewModel(repository, accounts).uiState.test {
                val rows = awaitItem().days.single().rows
                assertEquals(1, rows.size)
                assertTrue(rows.single() is TransactionRow.Single)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `account names are exposed so a transfer row can name them`() =
        runTest {
            accounts.setAccounts(
                account { copy(id = "account:1", name = "HDFC Savings") },
                account { copy(id = "account:2", name = "Cash Wallet") },
            )

            TransactionsViewModel(repository, accounts).uiState.test {
                assertEquals(
                    mapOf("account:1" to "HDFC Savings", "account:2" to "Cash Wallet"),
                    awaitItem().accountNames,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- delete (issue 3.2; FR-TXN-003) ------------------------------------------------------------

    @Test
    fun `deleting a row hands the store the transaction id, not a transfer id`() =
        runTest {
            // The screen must not branch on the row's kind: the atomicity guarantee belongs in the
            // repository, and a UI that had to know which case it held is a UI that can get it wrong.
            repository.setTransactions(*transferLegs().toTypedArray())
            val viewModel = TransactionsViewModel(repository, accounts)
            val row = viewModel.uiState.value.days.single().rows.single()

            viewModel.onEvent(TransactionsEvent.Delete(row.id))

            assertEquals(listOf("tfr:1:out"), repository.deleted)
        }

    @Test
    fun `deleting a transfer removes the whole row`() =
        runTest {
            repository.setTransactions(*transferLegs().toTypedArray())
            val viewModel = TransactionsViewModel(repository, accounts)

            viewModel.uiState.test {
                val row = awaitItem().days.single().rows.single()
                viewModel.onEvent(TransactionsEvent.Delete(row.id))
                assertTrue("both legs must go together", awaitItem().isEmpty)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a refused delete reports the code and leaves the row`() =
        runTest {
            repository.setTransactions(transaction())
            repository.failWith = AppError.NotFound
            val viewModel = TransactionsViewModel(repository, accounts)

            viewModel.onEvent(TransactionsEvent.Delete("txn:1"))

            viewModel.uiState.test {
                val state = awaitItem()
                assertEquals(AppError.NotFound.code, state.errorCode)
                assertEquals(1, state.days.single().rows.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `dismissing the error clears it`() =
        runTest {
            repository.setTransactions(transaction())
            repository.failWith = AppError.NotFound
            val viewModel = TransactionsViewModel(repository, accounts)
            viewModel.onEvent(TransactionsEvent.Delete("txn:1"))

            viewModel.onEvent(TransactionsEvent.DismissError)

            viewModel.uiState.test {
                assertNull(awaitItem().errorCode)
                cancelAndIgnoreRemainingEvents()
            }
        }
}

/**
 * Result: the note of a single row, or `null` for a transfer. Input: the receiver. Output: `String?`.
 *
 * Only used by the ordering assertions, which seed plain transactions — a transfer has two notes and
 * no single one to return.
 */
private fun TransactionRow.noteOrNull(): String? = (this as? TransactionRow.Single)?.transaction?.note
