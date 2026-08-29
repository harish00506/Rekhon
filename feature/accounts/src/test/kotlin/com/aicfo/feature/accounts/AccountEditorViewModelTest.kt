package com.aicfo.feature.accounts

import androidx.lifecycle.SavedStateHandle
import com.aicfo.core.common.AppError
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Loan
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
    private val cards = FakeCreditCardRepository()
    private val loans = FakeLoanRepository()

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
            cards,
            loans,
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

    // --- issue 6.2: loan terms (FR-ACC-003) --------------------------------------------------

    /**
     * Input:  a complete loan section saved on a new LOAN account.
     * Output: asserts the terms reached the store with the rate in **basis points**. The user typed
     *         `8.5` and the engine is defined in `850`; a conversion that silently dropped the
     *         decimal would give a schedule for a 0.085% loan that still balances perfectly.
     */
    @Test
    fun `a loan's terms are saved with the rate converted to basis points`() =
        runTest {
            val viewModel = editor()
            viewModel.enterLoan()

            viewModel.onEvent(AccountEditorEvent.Save)

            val loan = loans.saved.values.single()
            assertEquals(850, loan.annualRateBps)
            assertEquals(Money(300_000_000L), loan.principal)
            assertEquals(240, loan.tenureMonths)
            assertEquals("2026-09-05", loan.firstEmiIsoDate)
            assertNull("a blank override means derive it, never zero", loan.emiOverride)
            assertTrue(viewModel.uiState.value.isSaved)
        }

    /**
     * Input:  a loan account whose terms are already stored, opened for editing.
     * Output: asserts the rate comes back out in **percent** — the unit it went in as. Storing 850
     *         and redisplaying it as `850` would let a user re-save an 850% loan without touching
     *         the field, which is the round trip this asserts cannot happen.
     */
    @Test
    fun `stored loan terms open in the form, rate back in percent`() =
        runTest {
            repository.setAccounts(account { copy(id = "account:1", type = AccountType.LOAN) })
            loans.saved["account:1"] =
                Loan(
                    accountId = "account:1",
                    principal = Money(300_000_000L),
                    annualRateBps = 850,
                    tenureMonths = 240,
                    firstEmiIsoDate = "2026-09-05",
                )

            val state = editor("account:1").uiState.value

            assertEquals("8.50", state.annualRateText)
            assertEquals("240", state.tenureMonthsText)
            assertEquals("2026-09-05", state.firstEmiDateText)
            assertEquals("", state.emiOverrideText)
        }

    /**
     * Input:  a lender EMI far below the first month's interest.
     * Output: asserts the save is refused and reported, and that **nothing was written**. Terms
     *         that never amortise would otherwise leave the user on a loan row that shows nothing
     *         and explains nothing — the repository's refusal has to reach the form.
     */
    @Test
    fun `loan terms that cannot amortise are refused and reported`() =
        runTest {
            val viewModel = editor()
            viewModel.enterLoan()
            viewModel.onEvent(AccountEditorEvent.LoanFieldChanged(LoanField.EMI_OVERRIDE, "1000"))

            viewModel.onEvent(AccountEditorEvent.Save)

            assertFalse(viewModel.uiState.value.isSaved)
            assertEquals(AppError.Validation("emiOverride").code, viewModel.uiState.value.errorCode)
            assertTrue(loans.saved.isEmpty())
        }

    /**
     * Input:  a tenure that is not a number, then both ends of the model's 1..600 bound.
     * Output: asserts each is one honest validation error rather than a crash. `Loan`'s `require`
     *         throws on the out-of-range ones, and a throw inside a ViewModel is a crash — `toLoan`
     *         catches it, and this is the test that says so.
     */
    @Test
    fun `a tenure that is not a number, or out of range, is a validation error`() =
        runTest {
            listOf("24x", "0", "601").forEach { tenure ->
                val viewModel = editor()
                viewModel.enterLoan()
                viewModel.onEvent(AccountEditorEvent.LoanFieldChanged(LoanField.TENURE_MONTHS, tenure))

                viewModel.onEvent(AccountEditorEvent.Save)

                assertFalse("tenure $tenure must not save", viewModel.uiState.value.isSaved)
                assertEquals(AccountEditorViewModel.VALIDATION_ERROR_CODE, viewModel.uiState.value.errorCode)
                assertTrue(loans.saved.isEmpty())
            }
        }

    /**
     * Input:  a first EMI date written the way an Indian bank statement writes it.
     * Output: asserts a validation error rather than a saved loan whose schedule cannot be dated.
     *         TIM-002 says ISO, and a parser that guessed between 05-09 and 09-05 would be picking
     *         a month for the user.
     */
    @Test
    fun `a first EMI date that is not ISO is refused`() =
        runTest {
            val viewModel = editor()
            viewModel.enterLoan()
            viewModel.onEvent(AccountEditorEvent.LoanFieldChanged(LoanField.FIRST_EMI_DATE, "05-09-2026"))

            viewModel.onEvent(AccountEditorEvent.Save)

            assertFalse(viewModel.uiState.value.isSaved)
            assertTrue(loans.saved.isEmpty())
        }

    /**
     * Input:  a LOAN account saved with the section left completely blank.
     * Output: asserts the account is created and no loan row is written — a loan account whose
     *         terms have not been filled in yet is a supported state, and refusing it would make
     *         four fields feel mandatory the moment the type is picked.
     */
    @Test
    fun `a loan account with no terms saves the account and no loan`() =
        runTest {
            val viewModel = editor()
            viewModel.onEvent(AccountEditorEvent.NameChanged("SBI Home Loan"))
            viewModel.onEvent(AccountEditorEvent.TypeChanged(AccountType.LOAN))

            viewModel.onEvent(AccountEditorEvent.Save)

            assertTrue(viewModel.uiState.value.isSaved)
            assertTrue(loans.saved.isEmpty())
            assertEquals(1, repository.observeAccounts(includeArchived = true).first().size)
        }

    /**
     * Input:  a full loan section typed, then the type changed to BANK before saving.
     * Output: asserts nothing is written. The section is off screen for a bank account, so text
     *         left in state from a type the user changed away from must not reach the store — the
     *         `showsLoanFields` guard in `saveLoanTerms`, which nothing else covers.
     */
    @Test
    fun `loan text left over from a type change is not saved`() =
        runTest {
            val viewModel = editor()
            viewModel.enterLoan()
            viewModel.onEvent(AccountEditorEvent.TypeChanged(AccountType.BANK))

            viewModel.onEvent(AccountEditorEvent.Save)

            assertTrue(viewModel.uiState.value.isSaved)
            assertTrue(loans.saved.isEmpty())
        }

    /**
     * Input:  rates at both ends of what the field accepts, and beyond.
     * Output: asserts the percent-to-bps conversion refuses what it cannot hold exactly. `8.555` is
     *         more precise than a basis point and is declined rather than rounded (P-03), and a
     *         negative rate is not a rate.
     */
    @Test
    fun `the rate conversion accepts exact percentages and refuses the rest`() {
        assertEquals(850, parseRateBps("8.5"))
        assertEquals(0, parseRateBps("0"))
        assertEquals(1_000, parseRateBps("10"))
        assertEquals(1_006, parseRateBps("10.06"))
        assertNull("more precise than a basis point", parseRateBps("8.555"))
        assertNull("a rate is never negative", parseRateBps("-1"))
        assertNull("above the sane ceiling", parseRateBps("10001"))
        assertNull("not a number", parseRateBps("eight"))
        assertNull("blank", parseRateBps(""))
    }

    /** Result: the inverse — a stored rate reads back as a plain percentage, with no rupee sign. */
    @Test
    fun `a stored rate formats back as percent`() {
        assertEquals("8.50", formatRatePercent(850))
        assertEquals("0.00", formatRatePercent(0))
        assertEquals("100.00", formatRatePercent(10_000))
    }

    /**
     * Fills the form with the canonical loan: 30,00,000 at 8.5% over 240 months.
     * Result: the editor holding a complete, valid loan section. Input: none. Output: none.
     */
    private fun AccountEditorViewModel.enterLoan() {
        onEvent(AccountEditorEvent.NameChanged("SBI Home Loan"))
        onEvent(AccountEditorEvent.TypeChanged(AccountType.LOAN))
        onEvent(AccountEditorEvent.LoanFieldChanged(LoanField.PRINCIPAL, "3000000"))
        onEvent(AccountEditorEvent.LoanFieldChanged(LoanField.ANNUAL_RATE, "8.5"))
        onEvent(AccountEditorEvent.LoanFieldChanged(LoanField.TENURE_MONTHS, "240"))
        onEvent(AccountEditorEvent.LoanFieldChanged(LoanField.FIRST_EMI_DATE, "2026-09-05"))
    }
}
