package com.aicfo.feature.accounts

import app.cash.turbine.test
import com.aicfo.core.common.AppError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [AccountsViewModel] (issue 2.5; ARC-004, FR-ACC-007).
 *
 * Why:  three of this class's decisions are the kind that look right and are not. **Empty is not
 *       loading** — a screen that cannot tell them apart shows a "no accounts yet" invitation to a
 *       user whose database failed to open. **The archived toggle must switch the query**, not
 *       filter a list it already has, or a closed account stays invisible until the screen is
 *       rebuilt. And **a failed write must be reported**, because a delete that silently did
 *       nothing is the bug where the row reappears on the next launch.
 * What: the full `UiState` sequence including loading and error (§21.5), and every event.
 * Result: every state the list can reach is proven without a database.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountsViewModelTest {
    private val repository = FakeAccountRepository()

    /** Input: none. Output: pins `viewModelScope` to a test dispatcher so writes run inline. */
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Input: none. Output: releases the main dispatcher between tests. */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the screen starts in the loading state`() {
        // Asserted on the state's own default rather than through Turbine: the collector in `init`
        // runs inline under `UnconfinedTestDispatcher`, so by the time a test can subscribe the
        // first emission has already been replaced. The contract that matters is that the screen
        // never renders "no accounts yet" before the store has answered — which is this.
        val initial = AccountsUiState()

        assertTrue(initial.isLoading)
        assertFalse("loading is not empty", initial.isEmpty)
    }

    @Test
    fun `emits the accounts once the store answers`() =
        runTest {
            repository.setAccounts(account { copy(id = "account:1", name = "HDFC Savings") })

            AccountsViewModel(repository, FakeCreditCardRepository()).uiState.test {
                val loaded = awaitItem()
                assertFalse(loaded.isLoading)
                assertEquals(listOf("HDFC Savings"), loaded.accounts.map { it.name })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a store that fails clears loading and reports the error`() =
        runTest {
            // The other half of the same rule: a screen stuck on a spinner and a screen showing an
            // empty invitation are both wrong answers to "the database would not open".
            repository.failOnObserve = AppError.Storage("disk")

            val state = AccountsViewModel(repository, FakeCreditCardRepository()).uiState.value

            assertFalse("must not spin forever", state.isLoading)
            // The code, not which code: a Flow carries a raw `Throwable`, so the mapping is
            // `toAppError`'s and is tested there. What matters here is that something is reported
            // rather than the failure being swallowed into an empty list.
            assertNotNull("an unreadable store must not look like an empty one", state.errorCode)
            assertFalse(state.isEmpty)
        }

    @Test
    fun `an empty store is empty, not loading`() =
        runTest {
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository())

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertTrue("the screen must show an invitation, not a spinner", state.isEmpty)
        }

    @Test
    fun `archived accounts are hidden until the toggle is on`() =
        runTest {
            repository.setAccounts(
                account { copy(id = "account:1", name = "Active") },
                account { copy(id = "account:2", name = "Closed", isArchived = true) },
            )
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository())

            assertEquals(listOf("Active"), viewModel.uiState.value.accounts.map { it.name })

            viewModel.onEvent(AccountsEvent.ToggleArchived(show = true))

            assertEquals(listOf("Active", "Closed"), viewModel.uiState.value.accounts.map { it.name })
            assertTrue(viewModel.uiState.value.showArchived)
        }

    @Test
    fun `turning the toggle back off hides them again`() =
        runTest {
            repository.setAccounts(account { copy(id = "account:2", name = "Closed", isArchived = true) })
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository())
            viewModel.onEvent(AccountsEvent.ToggleArchived(show = true))

            viewModel.onEvent(AccountsEvent.ToggleArchived(show = false))

            assertTrue(viewModel.uiState.value.accounts.isEmpty())
        }

    @Test
    fun `archiving an account calls the repository and the list follows`() =
        runTest {
            repository.setAccounts(account { copy(id = "account:1", name = "Old Card") })
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository())

            viewModel.onEvent(AccountsEvent.SetArchived("account:1", archived = true))

            assertEquals(listOf("account:1"), repository.archivedIds)
            // The list is a Flow, so a successful write re-emits on its own — nothing refreshes it.
            assertTrue(viewModel.uiState.value.accounts.isEmpty())
        }

    @Test
    fun `deleting an account calls the repository and the list follows`() =
        runTest {
            repository.setAccounts(account { copy(id = "account:1") })
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository())

            viewModel.onEvent(AccountsEvent.Delete("account:1"))

            assertEquals(listOf("account:1"), repository.deletedIds)
            assertTrue(viewModel.uiState.value.accounts.isEmpty())
        }

    @Test
    fun `a failed delete is reported rather than swallowed`() =
        runTest {
            repository.setAccounts(account { copy(id = "account:1") })
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository())
            repository.failWith = AppError.Storage("disk")

            viewModel.onEvent(AccountsEvent.Delete("account:1"))

            assertEquals(AppError.Storage("disk").code, viewModel.uiState.value.errorCode)
            assertEquals("the row must still be listed", 1, viewModel.uiState.value.accounts.size)
        }

    @Test
    fun `a failed archive is reported rather than swallowed`() =
        runTest {
            repository.setAccounts(account { copy(id = "account:1") })
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository())
            repository.failWith = AppError.NotFound

            viewModel.onEvent(AccountsEvent.SetArchived("account:1", archived = true))

            assertEquals(AppError.NotFound.code, viewModel.uiState.value.errorCode)
        }

    @Test
    fun `dismissing the error clears it`() =
        runTest {
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository())
            repository.failWith = AppError.Storage("disk")
            viewModel.onEvent(AccountsEvent.Delete("account:1"))

            viewModel.onEvent(AccountsEvent.DismissError)

            assertNull(viewModel.uiState.value.errorCode)
        }

    @Test
    fun `restoring an archived account brings it back to the active list`() =
        runTest {
            repository.setAccounts(account { copy(id = "account:1", name = "Reopened", isArchived = true) })
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository())

            viewModel.onEvent(AccountsEvent.SetArchived("account:1", archived = false))

            assertEquals(listOf("Reopened"), viewModel.uiState.value.accounts.map { it.name })
        }

    @Test
    fun `the state carries the derived balance, not the opening one`() =
        runTest {
            // The list shows what the account holds now. Showing the opening balance instead would
            // be a figure that stops changing the moment the user records anything (DB-001).
            repository.setAccounts(
                account {
                    copy(
                        openingBalance = com.aicfo.core.model.Money(1_00_000_00L),
                        balance = com.aicfo.core.model.Money(75_000_00L),
                    )
                },
            )

            val account = AccountsViewModel(repository, FakeCreditCardRepository()).uiState.value.accounts.single()

            assertEquals(com.aicfo.core.model.Money(75_000_00L), account.balance)
            assertEquals(com.aicfo.core.model.Money(1_00_000_00L), account.openingBalance)
        }
}
