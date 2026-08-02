package com.aicfo.feature.transactions

import app.cash.turbine.test
import com.aicfo.core.common.AppError
import com.aicfo.core.model.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            TransactionsViewModel(repository).uiState.test {
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

            TransactionsViewModel(repository).uiState.test {
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

            TransactionsViewModel(repository).uiState.test {
                assertEquals(
                    listOf("later", "earlier"),
                    awaitItem().days.single().transactions.map { it.note },
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

            TransactionsViewModel(repository).uiState.test {
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

            TransactionsViewModel(repository).uiState.test {
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
            val viewModel = TransactionsViewModel(repository)

            viewModel.uiState.test {
                assertTrue(awaitItem().isEmpty)

                repository.setTransactions(transaction())

                assertEquals(1, awaitItem().days.single().transactions.size)
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

            TransactionsViewModel(repository).uiState.test {
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
            assertTrue(emptyList<com.aicfo.core.model.Transaction>().groupIntoDays().isEmpty())
        }
}
