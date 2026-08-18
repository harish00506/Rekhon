package com.aicfo.feature.accounts

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the reconciliation panel's half of [AccountsViewModel] (issue 2.7; FR-ACC-006).
 *
 * Why:  three of this flow's decisions are the kind that pass review and fail a user. **The store
 *       is handed the statement, never the delta** — the ViewModel previewing a subtraction and
 *       then *sending* it would make the panel authoritative over a figure it read seconds ago.
 *       **A blank field is not zero** — treating it as one would offer, on an untouched form, to
 *       wipe the account's balance to nothing. And **a failed write keeps the panel open with the
 *       typing intact**, because closing it would throw away the amount the user just read off a
 *       statement and make them find it again.
 * What: opening, typing, the previewed delta, confirming, cancelling and the error path.
 * Result: every state the panel can reach is proven without a database.
 * Changelog: 2026-08-02 — Created for issue 2.7.
 *
 * Split from [AccountsViewModelTest] rather than appended to it: that file is issue 2.5's list
 * behaviour and is already the longest in the module. Both drive the same ViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconcileViewModelTest {
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
    fun `no panel is open until the user asks for one`() =
        runTest {
            repository.setAccounts(account())

            assertNull(AccountsViewModel(repository, FakeCreditCardRepository()).uiState.value.reconciling)
        }

    @Test
    fun `opening the panel carries the account and its derived balance`() =
        runTest {
            repository.setAccounts(account { copy(balance = Money(92_500_00L)) })
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository())

            viewModel.onEvent(AccountsEvent.OpenReconcile("account:1"))

            val panel = viewModel.uiState.value.reconciling
            assertNotNull(panel)
            assertEquals(Money(92_500_00L), panel!!.account.balance)
            assertEquals("the field starts empty — the app must not guess a statement", "", panel.statementText)
        }

    @Test
    fun `opening on an id that is not on screen does nothing`() =
        runTest {
            repository.setAccounts(account())
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository())

            viewModel.onEvent(AccountsEvent.OpenReconcile("account:gone"))

            assertNull(viewModel.uiState.value.reconciling)
        }

    @Test
    fun `a blank field is not zero, and cannot be confirmed`() =
        runTest {
            // Zero is a legitimate statement balance the user may genuinely type. An *empty* field
            // means they have not answered, and confirming it would offer to wipe the balance.
            repository.setAccounts(account())
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository())
            viewModel.onEvent(AccountsEvent.OpenReconcile("account:1"))

            val panel = viewModel.uiState.value.reconciling!!
            assertNull(panel.statement)
            assertNull(panel.delta)
            assertFalse(panel.canConfirm)
        }

    @Test
    fun `an amount that cannot be represented exactly is refused, not rounded`() =
        runTest {
            // MNY-001. `MoneyFormatter.parse` returns null rather than guessing at a third decimal;
            // the panel's job is to stay disabled rather than to invent a figure (P-03).
            repository.setAccounts(account())
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository())
            viewModel.onEvent(AccountsEvent.OpenReconcile("account:1"))

            viewModel.onEvent(AccountsEvent.StatementChanged("1,00,500.345"))

            assertFalse(viewModel.uiState.value.reconciling!!.canConfirm)
        }

    @Test
    fun `the previewed adjustment is the statement minus the app balance`() =
        runTest {
            repository.setAccounts(account { copy(balance = Money(92_500_00L)) })
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository())
            viewModel.onEvent(AccountsEvent.OpenReconcile("account:1"))

            viewModel.onEvent(AccountsEvent.StatementChanged("93000"))

            val panel = viewModel.uiState.value.reconciling!!
            assertEquals(Money(500_00L), panel.delta)
            assertTrue(panel.canConfirm)
        }

    @Test
    fun `a statement below the app balance previews a negative adjustment`() =
        runTest {
            // The direction a credit card moves in. Getting the sign backwards here would show the
            // user a correction that reads as the opposite of what is about to be written.
            repository.setAccounts(account { copy(balance = Money(-18_000_00L)) })
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository())
            viewModel.onEvent(AccountsEvent.OpenReconcile("account:1"))

            viewModel.onEvent(AccountsEvent.StatementChanged("-19250"))

            assertEquals(Money(-1_250_00L), viewModel.uiState.value.reconciling!!.delta)
        }

    @Test
    fun `a matching statement is still confirmable — it just adjusts nothing`() =
        runTest {
            // Disabling the button here would leave a user who typed the right figure staring at a
            // dead control with no explanation. The panel says nothing will be added instead.
            repository.setAccounts(account { copy(balance = Money(92_500_00L)) })
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository())
            viewModel.onEvent(AccountsEvent.OpenReconcile("account:1"))

            viewModel.onEvent(AccountsEvent.StatementChanged("92500"))

            val panel = viewModel.uiState.value.reconciling!!
            assertEquals(Money.ZERO, panel.delta)
            assertTrue(panel.canConfirm)
        }

    @Test
    fun `confirming hands the store the statement, not the delta`() =
        runTest {
            // The assertion this whole file exists for. The subtraction belongs to the repository,
            // against a balance it derives inside its own transaction — the ViewModel's figure is a
            // preview of a list that may be seconds old (P-03).
            repository.setAccounts(account { copy(balance = Money(92_500_00L)) })
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository())
            viewModel.onEvent(AccountsEvent.OpenReconcile("account:1"))
            viewModel.onEvent(AccountsEvent.StatementChanged("93000"))

            viewModel.onEvent(AccountsEvent.ConfirmReconcile)

            assertEquals(listOf("account:1" to Money(93_000_00L)), repository.reconciled)
        }

    @Test
    fun `a successful adjustment closes the panel and the list shows the new balance`() =
        runTest {
            repository.setAccounts(account { copy(balance = Money(92_500_00L)) })
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository())
            viewModel.onEvent(AccountsEvent.OpenReconcile("account:1"))
            viewModel.onEvent(AccountsEvent.StatementChanged("93000"))

            viewModel.onEvent(AccountsEvent.ConfirmReconcile)

            assertNull(viewModel.uiState.value.reconciling)
            // No refresh call anywhere: the list is a Flow, so a successful write re-emits on its own.
            assertEquals(Money(93_000_00L), viewModel.uiState.value.accounts.single().balance)
        }

    @Test
    fun `confirming an unparseable amount writes nothing`() =
        runTest {
            repository.setAccounts(account())
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository())
            viewModel.onEvent(AccountsEvent.OpenReconcile("account:1"))
            viewModel.onEvent(AccountsEvent.StatementChanged("not an amount"))

            viewModel.onEvent(AccountsEvent.ConfirmReconcile)

            assertTrue("the store must not be reached at all", repository.reconciled.isEmpty())
            assertNotNull("and the panel stays open", viewModel.uiState.value.reconciling)
        }

    @Test
    fun `a failed write keeps the panel open with the typing intact`() =
        runTest {
            repository.setAccounts(account())
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository())
            viewModel.onEvent(AccountsEvent.OpenReconcile("account:1"))
            viewModel.onEvent(AccountsEvent.StatementChanged("93000"))
            repository.failWith = AppError.Storage("disk")

            viewModel.onEvent(AccountsEvent.ConfirmReconcile)

            val state = viewModel.uiState.value
            assertNotNull("closing it would throw away what the user read off a statement", state.reconciling)
            assertEquals("93000", state.reconciling!!.statementText)
            assertFalse("and the button must come back", state.reconciling.isSaving)
            assertEquals(AppError.Storage("disk").code, state.errorCode)
        }

    @Test
    fun `cancelling closes the panel and writes nothing`() =
        runTest {
            repository.setAccounts(account())
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository())
            viewModel.onEvent(AccountsEvent.OpenReconcile("account:1"))
            viewModel.onEvent(AccountsEvent.StatementChanged("93000"))

            viewModel.onEvent(AccountsEvent.CancelReconcile)

            assertNull(viewModel.uiState.value.reconciling)
            assertTrue(repository.reconciled.isEmpty())
        }

    @Test
    fun `typing when no panel is open is ignored rather than crashing`() =
        runTest {
            val viewModel = AccountsViewModel(repository, FakeCreditCardRepository())

            viewModel.onEvent(AccountsEvent.StatementChanged("93000"))
            viewModel.onEvent(AccountsEvent.ConfirmReconcile)

            assertNull(viewModel.uiState.value.reconciling)
            assertTrue(repository.reconciled.isEmpty())
        }
}
