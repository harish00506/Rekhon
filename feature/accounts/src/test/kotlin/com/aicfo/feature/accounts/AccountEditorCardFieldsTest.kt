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
 * Compose tests for the editor's credit-card branch (issue 6.1; FR-ACC-002).
 *
 * Why:  this is the module's **first** type branch — for eleven types and three issues, every
 *       account has seen identical fields. The two things only a rendered test catches are that the
 *       section appears for the right type, and that it stays *out* of the way for the other ten:
 *       a savings account offering a "statement day" would invite a user to fill in a field the app
 *       ignores, and they would have no way to know it was ignored.
 *
 *       The third case is the one a state test cannot see at all — **switching type away must not
 *       lose what was already typed elsewhere on the form**. The card section is conditional
 *       rendering, and conditional rendering is where a `remember` in the wrong scope silently
 *       resets a name.
 * What: the branch in both directions, and the name surviving a type change.
 * Result: the first type-specific form in the app is exercised on every run.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 *
 * On the JVM via Robolectric, following `AccountsFlowTest` beside it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h1200dp")
class AccountEditorCardFieldsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Result: a string from this module's resources. Input: [id]. Output: the resolved text. */
    private fun text(id: Int): String = compose.activity.getString(id)

    @Test
    fun `a credit card shows the card section`() {
        renderEditor(AccountEditorUiState(name = "HDFC Card", type = AccountType.CREDIT_CARD))

        compose.onNodeWithText(text(R.string.account_editor_card_section)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.account_editor_card_limit)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.account_editor_card_statement_day)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.account_editor_card_due_day)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `every other type still shows only the shared fields`() {
        // Asserted over the whole enum rather than one example: the branch is `== CREDIT_CARD`, and
        // a later refactor to `isLiability` would quietly hand a loan a statement day.
        //
        // Driven through one composition rather than re-rendering per type — Compose allows one
        // `setContent` per test, and recomposing is also closer to what the user does.
        val state = mutableStateOf(AccountEditorUiState(name = "Something", type = AccountType.BANK))
        renderEditor(state)

        AccountType.entries.filter { it != AccountType.CREDIT_CARD }.forEach { type ->
            state.value = state.value.copy(type = type)
            compose.waitForIdle()

            compose.onNodeWithText(text(R.string.account_editor_card_section)).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.account_editor_card_limit)).assertDoesNotExist()
        }
    }

    @Test
    fun `the name survives switching the type into and out of the card branch`() {
        // Conditional rendering is where a `remember` in the wrong scope silently resets a field —
        // and this one is above the branch, so it must not move. Recomposition, not re-rendering,
        // for exactly that reason: a fresh `setContent` would rebuild the tree and hide the bug.
        val state = mutableStateOf(AccountEditorUiState(name = "HDFC Card", type = AccountType.BANK))
        renderEditor(state)
        compose.onNodeWithText("HDFC Card").assertIsDisplayed()

        state.value = state.value.copy(type = AccountType.CREDIT_CARD)
        compose.waitForIdle()
        compose.onNodeWithText("HDFC Card").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.account_editor_card_section)).performScrollTo().assertIsDisplayed()

        state.value = state.value.copy(type = AccountType.BANK)
        compose.waitForIdle()
        compose.onNodeWithText("HDFC Card").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.account_editor_card_section)).assertDoesNotExist()
    }

    @Test
    fun `stored card terms open in the form rather than blank`() {
        // An editor that opens empty and saves would silently clear the card's limit — the argument
        // `toEditorState` makes for the account itself, one section down.
        renderEditor(
            AccountEditorUiState(
                name = "HDFC Card",
                type = AccountType.CREDIT_CARD,
                creditLimitText = "₹2,00,000.00",
                statementDayText = "5",
                dueDayText = "25",
            ),
        )

        compose.onNodeWithText("₹2,00,000.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("25").performScrollTo().assertIsDisplayed()
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
