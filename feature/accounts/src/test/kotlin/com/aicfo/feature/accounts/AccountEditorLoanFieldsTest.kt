package com.aicfo.feature.accounts

import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.AccountType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the editor's loan branch (issue 6.2; FR-ACC-003).
 *
 * Why:  the module's **second** type branch, and the first time two of them exist at once — which
 *       is the thing only a rendered test catches. A branch written as `isLiability` rather than an
 *       exact type match would hand a credit card a tenure in months and a loan a statement day,
 *       and both forms would still look plausible to anyone reading the state class. So the
 *       assertions here are as much about what is **absent** as about what is shown: the loan
 *       section must not appear for the other ten types, and the two sections must never be on
 *       screen together.
 *
 *       The third case is the one a state test cannot see — **switching type away must not lose
 *       what was already typed elsewhere on the form.** Conditional rendering is where a `remember`
 *       in the wrong scope silently resets a name.
 * What: the branch in both directions, its exclusivity with the card section, and the name
 *       surviving a type change.
 * Result: the second type-specific form in the app is exercised on every run.
 * Changelog: 2026-08-20 — Created for issue 6.2.
 *
 * On the JVM via Robolectric, following `AccountEditorCardFieldsTest` beside it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h1200dp")
class AccountEditorLoanFieldsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Result: a string from this module's resources. Input: [id]. Output: the resolved text. */
    private fun text(id: Int): String = compose.activity.getString(id)

    @Test
    fun `a loan shows the loan section`() {
        renderEditor(AccountEditorUiState(name = "SBI Home Loan", type = AccountType.LOAN))

        compose.onNodeWithText(text(R.string.account_editor_loan_section)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.account_editor_loan_principal)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.account_editor_loan_rate)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.account_editor_loan_tenure)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.account_editor_loan_first_emi)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `every other type still shows only the shared fields`() {
        // Asserted over the whole enum rather than one example, the argument the card test makes:
        // the branch is `== LOAN`, and a later refactor to `isLiability` would quietly hand a
        // credit card a tenure in months.
        val state = mutableStateOf(AccountEditorUiState(name = "Something", type = AccountType.BANK))
        renderEditor(state)

        AccountType.entries.filter { it != AccountType.LOAN }.forEach { type ->
            state.value = state.value.copy(type = type)
            compose.waitForIdle()

            compose.onNodeWithText(text(R.string.account_editor_loan_section)).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.account_editor_loan_tenure)).assertDoesNotExist()
        }
    }

    @Test
    fun `the card and loan sections are never on screen together`() {
        // The one assertion neither type's own test can make. Two independent `if`s over the same
        // state could both be true if either branch were ever widened, and the result would be a
        // form asking for a statement day *and* a tenure — plausible-looking nonsense.
        val state = mutableStateOf(AccountEditorUiState(name = "Something", type = AccountType.CREDIT_CARD))
        renderEditor(state)

        compose.onNodeWithText(text(R.string.account_editor_card_section)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.account_editor_loan_section)).assertDoesNotExist()

        state.value = state.value.copy(type = AccountType.LOAN)
        compose.waitForIdle()

        compose.onNodeWithText(text(R.string.account_editor_loan_section)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.account_editor_card_section)).assertDoesNotExist()
    }

    @Test
    fun `the name survives switching the type into and out of the loan branch`() {
        val state = mutableStateOf(AccountEditorUiState(name = "SBI Home Loan", type = AccountType.BANK))
        renderEditor(state)
        compose.onNodeWithText("SBI Home Loan").assertIsDisplayed()

        state.value = state.value.copy(type = AccountType.LOAN)
        compose.waitForIdle()
        compose.onNodeWithText("SBI Home Loan").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.account_editor_loan_section)).performScrollTo().assertIsDisplayed()

        state.value = state.value.copy(type = AccountType.BANK)
        compose.waitForIdle()
        compose.onNodeWithText("SBI Home Loan").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.account_editor_loan_section)).assertDoesNotExist()
    }

    @Test
    fun `stored loan terms open in the form rather than blank`() {
        // An editor that opens empty and saves would silently clear the loan's principal — the
        // argument `toEditorState` makes for the account itself. The rate is shown in **percent**,
        // which is what makes this more than a rendering check.
        renderEditor(
            AccountEditorUiState(
                name = "SBI Home Loan",
                type = AccountType.LOAN,
                principalText = "₹30,00,000.00",
                annualRateText = "8.50",
                tenureMonthsText = "240",
                firstEmiDateText = "2026-09-05",
            ),
        )

        compose.onNodeWithText("₹30,00,000.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("8.50").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("240").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2026-09-05").performScrollTo().assertIsDisplayed()
    }

    /** Result: the editor rendered with a fixed [state]. Input: [state]. Output: none. */
    private fun renderEditor(state: AccountEditorUiState) = renderEditor(mutableStateOf(state))

    /**
     * Result: the editor rendered, recomposing whenever [state] changes.
     * Input:  [state] — the driver. Output: none.
     */
    private fun renderEditor(state: MutableState<AccountEditorUiState>) {
        compose.setContent {
            CfoTheme {
                AccountEditorContent(uiState = state.value, onEvent = {}, onCancel = {})
            }
        }
    }
}
