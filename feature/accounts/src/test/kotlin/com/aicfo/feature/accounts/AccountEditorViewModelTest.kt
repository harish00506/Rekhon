package com.aicfo.feature.accounts

import androidx.lifecycle.SavedStateHandle
import com.aicfo.core.common.AppError
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
 * Tests for [AccountEditorViewModel] (issue 2.5; ARC-004, FR-ACC-001, MNY-001).
 *
 * Why:  one class serves create and edit, so the assertions that matter are the ones that would
 *       reveal the two paths diverging — an edit that created a duplicate, a create that reported
 *       `NotFound`. Beyond that, this is the only place in the feature where **typed text becomes
 *       money**, so the parsing edges are here: a blank field is zero, a negative is a liability,
 *       and something the parser cannot represent exactly is refused rather than rounded (P-03).
 * What: load, edit, create, validation, and the money parsing.
 * Result: both paths through the editor are proven, including their refusals.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountEditorViewModelTest {
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

    /**
     * Result: an editor for [accountId], or a blank one when it is null.
     * Input: [accountId]. Output: [AccountEditorViewModel].
     */
    private fun editor(accountId: String? = null) =
        AccountEditorViewModel(
            repository,
            SavedStateHandle(
                accountId?.let { mapOf(AccountEditorViewModel.ACCOUNT_ID_KEY to it) } ?: emptyMap(),
            ),
        )

    // --- create ------------------------------------------------------------------------------------

    @Test
    fun `a new editor opens blank and is not editing`() {
        val state = editor().uiState.value

        assertNull(state.id)
        assertFalse(state.isEditing)
        assertEquals("", state.name)
        assertEquals(AccountType.BANK, state.type)
        assertFalse("nothing typed yet, so Save must be off", state.canSave)
    }

    @Test
    fun `typing a name enables Save`() {
        val viewModel = editor()

        viewModel.onEvent(AccountEditorEvent.NameChanged("HDFC Savings"))

        assertTrue(viewModel.uiState.value.canSave)
    }

    @Test
    fun `saving creates the account and signals the screen to leave`() =
        runTest {
            val viewModel = editor()
            viewModel.onEvent(AccountEditorEvent.NameChanged("HDFC Savings"))
            viewModel.onEvent(AccountEditorEvent.TypeChanged(AccountType.BANK))
            viewModel.onEvent(AccountEditorEvent.OpeningBalanceChanged("1,25,000"))

            viewModel.onEvent(AccountEditorEvent.Save)

            assertTrue(viewModel.uiState.value.isSaved)
            val created = repository.observeAccounts(includeArchived = true).first()
            assertEquals(listOf("HDFC Savings"), created.map { it.name })
            assertEquals(Money(1_25_000_00L), created.single().openingBalance)
        }

    @Test
    fun `a blank name is refused by canSave rather than by the store`() {
        // Cheaper and clearer than a round trip: the button is off, so the user is told before they
        // tap. The repository enforces the same rule regardless — this is the screen's half.
        val viewModel = editor()
        viewModel.onEvent(AccountEditorEvent.NameChanged("   "))

        viewModel.onEvent(AccountEditorEvent.Save)

        assertFalse(viewModel.uiState.value.isSaved)
    }

    @Test
    fun `a failed create reports the error and does not leave the screen`() {
        repository.failWith = AppError.Storage("disk")
        val viewModel = editor()
        viewModel.onEvent(AccountEditorEvent.NameChanged("HDFC"))

        viewModel.onEvent(AccountEditorEvent.Save)

        assertFalse("must not navigate away from a save that did not happen", viewModel.uiState.value.isSaved)
        assertEquals(AppError.Storage("disk").code, viewModel.uiState.value.errorCode)
        assertFalse(viewModel.uiState.value.isSaving)
    }

    // --- edit --------------------------------------------------------------------------------------

    @Test
    fun `an editor opened on an id loads that account's values`() {
        // An editor that opened blank and then saved would silently clear the account's name.
        repository.setAccounts(
            account {
                copy(
                    id = "account:1",
                    name = "HDFC Savings",
                    institution = "HDFC Bank",
                    type = AccountType.BANK,
                )
            },
        )

        val state = editor("account:1").uiState.value

        assertTrue(state.isEditing)
        assertEquals("HDFC Savings", state.name)
        assertEquals("HDFC Bank", state.institution)
        assertEquals(AccountType.BANK, state.type)
        assertFalse(state.isLoading)
    }

    @Test
    fun `the loaded opening balance is formatted for editing`() {
        repository.setAccounts(account { copy(id = "account:1", openingBalance = Money(1_25_000_00L)) })

        val text = editor("account:1").uiState.value.openingBalanceText

        // Round-trips through the parser, which is the only property that matters: whatever is
        // shown must come back as the same amount if the user does not touch it (MNY-001).
        assertEquals(Money(1_25_000_00L), MoneyFormatter.parse(text))
    }

    @Test
    fun `saving an edit updates rather than creating a second account`() =
        runTest {
            repository.setAccounts(account { copy(id = "account:1", name = "HDFC") })
            val viewModel = editor("account:1")

            viewModel.onEvent(AccountEditorEvent.NameChanged("HDFC Salary"))
            viewModel.onEvent(AccountEditorEvent.Save)

            val stored = repository.observeAccounts(includeArchived = true).first()
            assertEquals(1, stored.size)
            assertEquals("HDFC Salary", stored.single().name)
            assertTrue(viewModel.uiState.value.isSaved)
        }

    @Test
    fun `an editor opened on a missing id reports it instead of showing a blank form`() {
        val state = editor("nope").uiState.value

        assertEquals(AppError.NotFound.code, state.errorCode)
        assertFalse(state.isLoading)
    }

    @Test
    fun `the balance is never carried into the form — only the opening balance is editable`() {
        // Correcting a balance is FR-ACC-006's reconciliation flow, which posts an adjustment
        // transaction rather than mutating the row (DB-001). The form must not offer it.
        repository.setAccounts(
            account { copy(id = "account:1", openingBalance = Money(1_00_000_00L), balance = Money(75_000_00L)) },
        )

        val text = editor("account:1").uiState.value.openingBalanceText

        assertEquals(Money(1_00_000_00L), MoneyFormatter.parse(text))
    }

    // --- the money ---------------------------------------------------------------------------------

    @Test
    fun `a blank opening balance parses to zero`() {
        val state = AccountEditorUiState(name = "Cash", openingBalanceText = "")

        assertEquals(Money.ZERO, state.parsedOpeningBalance())
    }

    @Test
    fun `a negative opening balance keeps its sign`() {
        val state = AccountEditorUiState(name = "Card", openingBalanceText = "-18000")

        assertEquals(Money(-18_000_00L), state.parsedOpeningBalance())
    }

    @Test
    fun `paise survive`() {
        val state = AccountEditorUiState(name = "HDFC", openingBalanceText = "1234.56")

        assertEquals(Money(1_234_56L), state.parsedOpeningBalance())
    }

    @Test
    fun `an amount the parser cannot represent exactly is refused, not rounded`() {
        val state = AccountEditorUiState(name = "HDFC", openingBalanceText = "12.345")

        assertNull(state.parsedOpeningBalance())
    }

    @Test
    fun `saving an unrepresentable amount reports validation and writes nothing`() =
        runTest {
            val viewModel = editor()
            viewModel.onEvent(AccountEditorEvent.NameChanged("HDFC"))
            viewModel.onEvent(AccountEditorEvent.OpeningBalanceChanged("12.345"))

            viewModel.onEvent(AccountEditorEvent.Save)

            assertFalse(viewModel.uiState.value.isSaved)
            assertEquals(AccountEditorViewModel.VALIDATION_ERROR_CODE, viewModel.uiState.value.errorCode)
            assertTrue(repository.observeAccounts(includeArchived = true).first().isEmpty())
        }

    @Test
    fun `dismissing the error clears it`() {
        repository.failWith = AppError.Storage("disk")
        val viewModel = editor()
        viewModel.onEvent(AccountEditorEvent.NameChanged("HDFC"))
        viewModel.onEvent(AccountEditorEvent.Save)

        viewModel.onEvent(AccountEditorEvent.DismissError)

        assertNull(viewModel.uiState.value.errorCode)
    }

    @Test
    fun `every account type can be selected`() {
        // FR-ACC-001 is a MUST; a type the editor cannot hold is a type the app does not support.
        val viewModel = editor()

        AccountType.entries.forEach { type ->
            viewModel.onEvent(AccountEditorEvent.TypeChanged(type))
            assertEquals(type, viewModel.uiState.value.type)
        }
    }

    @Test
    fun `the institution field is optional`() {
        val viewModel = editor()
        viewModel.onEvent(AccountEditorEvent.NameChanged("Cash"))

        assertTrue(viewModel.uiState.value.canSave)
        assertEquals("", viewModel.uiState.value.institution)
    }

    @Test
    fun `every error code maps to a message`() {
        // An unrecognised code must fall back rather than render the code itself at the user.
        listOf(AppError.Storage("x").code, AppError.NotFound.code, AccountEditorViewModel.VALIDATION_ERROR_CODE, null)
            .forEach { code -> assertNotNull(AccountLabels.errorMessage(code)) }
    }

    @Test
    fun `every account type has a label`() {
        AccountType.entries.forEach { type -> assertNotNull(AccountLabels.typeLabel(type)) }
    }
}
