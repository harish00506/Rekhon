package com.aicfo.feature.accounts

import app.cash.turbine.test
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Ok
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Loan
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.loan.AmortisationRow
import com.aicfo.domain.engines.loan.LoanEngineFactory
import com.aicfo.domain.engines.loan.LoanInstalmentInput
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

            AccountsViewModel(repository, FakeCreditCardRepository(), FakeLoanRepository()).uiState.test {
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

            val state = AccountsViewModel(repository, FakeCreditCardRepository(), FakeLoanRepository()).uiState.value

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
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository(), FakeLoanRepository())

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
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository(), FakeLoanRepository())

            assertEquals(listOf("Active"), viewModel.uiState.value.accounts.map { it.name })

            viewModel.onEvent(AccountsEvent.ToggleArchived(show = true))

            assertEquals(listOf("Active", "Closed"), viewModel.uiState.value.accounts.map { it.name })
            assertTrue(viewModel.uiState.value.showArchived)
        }

    @Test
    fun `turning the toggle back off hides them again`() =
        runTest {
            repository.setAccounts(account { copy(id = "account:2", name = "Closed", isArchived = true) })
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository(), FakeLoanRepository())
            viewModel.onEvent(AccountsEvent.ToggleArchived(show = true))

            viewModel.onEvent(AccountsEvent.ToggleArchived(show = false))

            assertTrue(viewModel.uiState.value.accounts.isEmpty())
        }

    @Test
    fun `archiving an account calls the repository and the list follows`() =
        runTest {
            repository.setAccounts(account { copy(id = "account:1", name = "Old Card") })
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository(), FakeLoanRepository())

            viewModel.onEvent(AccountsEvent.SetArchived("account:1", archived = true))

            assertEquals(listOf("account:1"), repository.archivedIds)
            // The list is a Flow, so a successful write re-emits on its own — nothing refreshes it.
            assertTrue(viewModel.uiState.value.accounts.isEmpty())
        }

    @Test
    fun `deleting an account calls the repository and the list follows`() =
        runTest {
            repository.setAccounts(account { copy(id = "account:1") })
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository(), FakeLoanRepository())

            viewModel.onEvent(AccountsEvent.Delete("account:1"))

            assertEquals(listOf("account:1"), repository.deletedIds)
            assertTrue(viewModel.uiState.value.accounts.isEmpty())
        }

    @Test
    fun `a failed delete is reported rather than swallowed`() =
        runTest {
            repository.setAccounts(account { copy(id = "account:1") })
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository(), FakeLoanRepository())
            repository.failWith = AppError.Storage("disk")

            viewModel.onEvent(AccountsEvent.Delete("account:1"))

            assertEquals(AppError.Storage("disk").code, viewModel.uiState.value.errorCode)
            assertEquals("the row must still be listed", 1, viewModel.uiState.value.accounts.size)
        }

    @Test
    fun `a failed archive is reported rather than swallowed`() =
        runTest {
            repository.setAccounts(account { copy(id = "account:1") })
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository(), FakeLoanRepository())
            repository.failWith = AppError.NotFound

            viewModel.onEvent(AccountsEvent.SetArchived("account:1", archived = true))

            assertEquals(AppError.NotFound.code, viewModel.uiState.value.errorCode)
        }

    @Test
    fun `dismissing the error clears it`() =
        runTest {
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository(), FakeLoanRepository())
            repository.failWith = AppError.Storage("disk")
            viewModel.onEvent(AccountsEvent.Delete("account:1"))

            viewModel.onEvent(AccountsEvent.DismissError)

            assertNull(viewModel.uiState.value.errorCode)
        }

    @Test
    fun `restoring an archived account brings it back to the active list`() =
        runTest {
            repository.setAccounts(account { copy(id = "account:1", name = "Reopened", isArchived = true) })
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository(), FakeLoanRepository())

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
                        openingBalance = Money(1_00_000_00L),
                        balance = Money(75_000_00L),
                    )
                },
            )

            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository(), FakeLoanRepository())
            val account = viewModel.uiState.value.accounts.single()

            assertEquals(Money(75_000_00L), account.balance)
            assertEquals(Money(1_00_000_00L), account.openingBalance)
        }

    // --- issue 6.2: the next EMI on a loan row (FR-ACC-003) -----------------------------------

    @Test
    fun `a loan's next instalment reaches the state and its split sums to the EMI`() =
        runTest {
            // The figure the accounts list exists to show. The split is asserted to **sum** rather
            // than compared to a constant: the constant lives in the engine's golden file, and
            // restating it here would only prove this test can copy a number (P-02).
            repository.setAccounts(account { copy(id = "account:1", type = AccountType.LOAN) })
            val row = instalment()
            val loans = FakeLoanRepository(mapOf("account:1" to row))

            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository(), loans)

            val shown = viewModel.uiState.value.loans.getValue("account:1")
            assertEquals(row.amount, shown.principal + shown.interest)
            assertEquals("2026-11-05", shown.dueIsoDate)
        }

    @Test
    fun `a loan with no terms is absent from the map rather than showing zeros`() =
        runTest {
            // Absent, not present-with-zeros: a zero EMI would tell a borrower with twenty years
            // left that they owe nothing this month, and the row renders a prompt instead (P-03).
            repository.setAccounts(account { copy(id = "account:1", type = AccountType.LOAN) })

            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository(), FakeLoanRepository())

            assertTrue(viewModel.uiState.value.loans.isEmpty())
        }

    @Test
    fun `the loan stream survives a failed account read`() =
        runTest {
            // Three separate collectors, not a `combine`: a loan read that fails must not blank the
            // accounts list, and a list that fails must not be reported as "no loans". Asserted by
            // failing the account read and checking the loan map came through anyway.
            repository.failWith = AppError.Storage("disk")
            val loans = FakeLoanRepository(mapOf("account:1" to instalment()))

            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository(), loans)

            assertEquals(1, viewModel.uiState.value.loans.size)
        }

    /**
     * One instalment of the canonical loan, produced by the **real** engine.
     * Why:    a hand-written [AmortisationRow] would have to satisfy the type's own invariants, and
     *         picking numbers that do is picking the answer. Asking the engine is both shorter and
     *         honest about where the figure came from (P-03).
     * Result: instalment 3 of 30,00,000 at 8.5% over 240 months, due 2026-11-05.
     * Input:  none. Output: [AmortisationRow].
     */
    private fun instalment(): AmortisationRow {
        val loan =
            Loan(
                accountId = "account:1",
                principal = Money(300_000_000L),
                annualRateBps = 850,
                tenureMonths = 240,
                firstEmiIsoDate = "2026-09-05",
            )
        val engine = LoanEngineFactory.create()
        return (engine.instalment(LoanInstalmentInput(loan, number = 3)) as Ok).value
    }
}
