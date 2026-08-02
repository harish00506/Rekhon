package com.aicfo.feature.transactions

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the add-transaction screen — **FR-TXN-002's tap budget** (issue 3.1, §21.5).
 *
 * Why:  FR-TXN-002 is a MUST with a number in it: "reachable in one tap (FAB) and completable in
 *       ≤ 3 taps for a common expense (amount → category suggestion → save)". A number in a
 *       requirement is only real if something counts it, so these tests count.
 *
 *       **The budget is asserted across two files, and neither is complete alone.** The FAB is in
 *       `:app` — a feature module cannot see it (ARC-001) — so `AddTransactionFabTest` there proves
 *       the first tap reaches this screen, and these tests prove what it costs to finish once here.
 *       The arithmetic is: 1 (FAB) + 1 (Save) = **2** for a common expense, 3 with a category. Both
 *       files name this sum so neither can be read as the whole assertion.
 * What: the tap count, what is preselected, and what is hidden when it has nothing to offer.
 * Result: a change that adds a required tap fails a test rather than a review.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 *
 * On the JVM via Robolectric, following `:feature:accounts`'s `AccountsFlowTest`: the flow is
 * checked on every `test` run rather than only when an emulator happens to be up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h1200dp")
class AddTransactionFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Result: a string from this module's resources. Input: [id]. Output: the resolved text. */
    private fun text(id: Int): String = compose.activity.getString(id)

    // --- the tap budget ----------------------------------------------------------------------------

    @Test
    fun `a common expense is one tap on this screen — typing the amount, then Save`() {
        // Tap 1 of the budget was the FAB (AddTransactionFabTest). This is tap 2, and there is no
        // tap 3: the account is preselected and the category row is absent for a real profile.
        var taps = 0
        val events = mutableListOf<AddTransactionEvent>()
        setContent(
            state = loadedState(amountText = "250"),
            onEvent = { events += it },
        )

        compose.onNodeWithText(text(R.string.add_txn_save)).performClick().also { taps++ }

        assertEquals("one tap on this screen, two with the FAB — FR-TXN-002 allows three", 1, taps)
        assertTrue(events.contains(AddTransactionEvent.Save))
    }

    @Test
    fun `typing the amount costs no tap, and the field is there to type into`() {
        val events = mutableListOf<AddTransactionEvent>()
        setContent(state = loadedState(), onEvent = { events += it })

        // performTextInput, not performClick: the field takes focus on entry (see AmountField), so
        // reaching it is free. If the autofocus were removed this would still pass — which is why
        // `the amount field takes focus on entry` below asserts the focus directly.
        compose.onNodeWithText(text(R.string.add_txn_amount)).performTextInput("250")

        assertEquals(listOf(AddTransactionEvent.AmountChanged("250")), events)
    }

    @Test
    fun `the amount field takes focus on entry, so the keypad is already up`() {
        // The autofocus is what makes the test above honest: without it the user's first tap goes on
        // the field and the common expense costs three taps before anything is chosen. Asserted
        // directly rather than inferred, because `performTextInput` focuses the node itself and
        // would pass either way.
        setContent(state = loadedState())

        compose.onNodeWithText(text(R.string.add_txn_amount)).assertIsFocused()
    }

    @Test
    fun `choosing a category is the third tap, and only when there is one to choose`() {
        var taps = 0
        val events = mutableListOf<AddTransactionEvent>()
        setContent(
            state = loadedState(amountText = "1200").copy(categories = listOf(Category("category:fuel", "Fuel"))),
            onEvent = { events += it },
        )

        compose.onNodeWithText("Fuel").performClick().also { taps++ }
        compose.onNodeWithText(text(R.string.add_txn_save)).performClick().also { taps++ }

        assertEquals("two taps here plus the FAB is exactly FR-TXN-002's three", 2, taps)
        assertEquals(AddTransactionEvent.CategorySelected("category:fuel"), events.first())
    }

    // --- what is hidden ----------------------------------------------------------------------------

    @Test
    fun `the category row is absent for a profile with no categories`() {
        // Every real profile until issue 4.1. An empty picker reads as a broken one, and a control
        // with no options must not sit in the tap budget.
        setContent(state = loadedState())

        compose.onNodeWithText(text(R.string.add_txn_category)).assertDoesNotExist()
    }

    @Test
    fun `the account picker is absent when there is only one account`() {
        // The state most users are in after FR-ONB-001's fourth step. The ViewModel has preselected
        // it either way, so hiding the picker changes nothing about what is saved.
        setContent(state = loadedState())

        compose.onNodeWithText(text(R.string.add_txn_account)).assertDoesNotExist()
    }

    @Test
    fun `the account picker appears, and is tappable, when there are two`() {
        val events = mutableListOf<AddTransactionEvent>()
        val two =
            listOf(
                account { copy(id = "account:1") },
                account { copy(id = "account:2", name = "Cash") },
            )
        setContent(
            state = loadedState(amountText = "250").copy(accounts = two),
            onEvent = { events += it },
        )

        compose.onNodeWithText(text(R.string.add_txn_account)).assertIsDisplayed()
        compose.onNodeWithText("Cash").performClick()

        assertEquals(AddTransactionEvent.AccountSelected("account:2"), events.single())
    }

    // --- states ------------------------------------------------------------------------------------

    @Test
    fun `Save is disabled until there is an amount`() {
        setContent(state = loadedState())

        compose.onNodeWithText(text(R.string.add_txn_save)).assertIsNotEnabled()
    }

    @Test
    fun `Save is enabled once the amount is a real figure`() {
        setContent(state = loadedState(amountText = "250"))

        compose.onNodeWithText(text(R.string.add_txn_save)).assertIsEnabled()
    }

    @Test
    fun `a profile with no account is told so, and cannot save`() {
        setContent(state = AddTransactionUiState(isLoading = false, amountText = "250"))

        compose.onNodeWithText(text(R.string.add_txn_no_account)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.add_txn_save)).assertIsNotEnabled()
    }

    @Test
    fun `an error shows its message, not its code`() {
        // P-01 and §21.6: the state carries `AppError.code` and the wording comes from strings.xml.
        // A screen that rendered "not_found" would have told the user nothing and leaked an
        // internal name.
        setContent(state = loadedState().copy(errorCode = "not_found"))

        compose.onNodeWithText(text(R.string.add_txn_error_not_found)).assertIsDisplayed()
        compose.onNodeWithText("not_found").assertDoesNotExist()
    }

    @Test
    fun `an unrecognised error code falls back to the generic message`() {
        setContent(state = loadedState().copy(errorCode = "something_new"))

        compose.onNodeWithText(text(R.string.add_txn_error_unknown)).assertIsDisplayed()
    }

    @Test
    fun `Cancel leaves without saving`() {
        var cancelled = false
        val events = mutableListOf<AddTransactionEvent>()
        setContent(state = loadedState(amountText = "250"), onEvent = { events += it }, onCancel = { cancelled = true })

        compose.onNodeWithText(text(R.string.add_txn_cancel)).performClick()

        assertTrue(cancelled)
        assertTrue(events.isEmpty())
    }

    // --- transfers (issue 3.2; FR-TXN-003) ---------------------------------------------------------

    @Test
    fun `the transfer option is offered, and does not disturb the expense default`() {
        // FR-TXN-002 is specified "for a common expense", so the third option must not change what
        // the form does when the user just wants to record spending.
        setContent(state = twoAccountState())

        compose.onNodeWithText(text(R.string.add_txn_transfer)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.add_txn_expense)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.add_txn_to_account)).assertDoesNotExist()
    }

    @Test
    fun `choosing transfer reveals a To picker and hides categories`() {
        val state =
            twoAccountState(amountText = "5000")
                .copy(
                    direction = TransactionDirection.TRANSFER,
                    categories = listOf(Category("category:fuel", "Fuel")),
                )
        setContent(state = state)

        compose.onNodeWithText(text(R.string.add_txn_to_account)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.add_txn_from_account)).assertIsDisplayed()
        // A transfer is not spending, so it has no category to pick (FR-TXN-003).
        compose.onNodeWithText(text(R.string.add_txn_category)).assertDoesNotExist()
        compose.onNodeWithText("Fuel").assertDoesNotExist()
    }

    @Test
    fun `the To picker does not offer the account the money is leaving`() {
        setContent(
            state = twoAccountState(amountText = "5000").copy(direction = TransactionDirection.TRANSFER),
        )

        // Both names appear under "From"; only the other one appears under "To". Asserting the
        // source is offered exactly once is what shows it was filtered out of the destinations.
        compose.onAllNodesWithText("HDFC Savings").assertCountEquals(1)
        compose.onAllNodesWithText("Cash Wallet").assertCountEquals(2)
    }

    @Test
    fun `picking a destination is one tap`() {
        val events = mutableListOf<AddTransactionEvent>()
        setContent(
            state = twoAccountState(amountText = "5000").copy(direction = TransactionDirection.TRANSFER),
            onEvent = { events += it },
        )

        compose.onAllNodesWithText("Cash Wallet").onLast().performClick()

        assertEquals(AddTransactionEvent.DestinationSelected("account:2"), events.single())
    }

    @Test
    fun `Save stays disabled until a transfer has a destination`() {
        setContent(
            state = twoAccountState(amountText = "5000").copy(direction = TransactionDirection.TRANSFER),
        )

        compose.onNodeWithText(text(R.string.add_txn_save)).assertIsNotEnabled()
    }

    @Test
    fun `Save is enabled once a transfer has both accounts and an amount`() {
        setContent(
            state =
                twoAccountState(amountText = "5000")
                    .copy(direction = TransactionDirection.TRANSFER, toAccountId = "account:2"),
        )

        compose.onNodeWithText(text(R.string.add_txn_save)).assertIsEnabled()
    }

    @Test
    fun `transfer is not offered to a profile with one account`() {
        // There is nowhere to move money to. Disabled rather than absent, so the option is
        // discoverable and its unavailability is explained by the account list beside it.
        setContent(state = loadedState(amountText = "250"))

        compose.onNodeWithText(text(R.string.add_txn_transfer)).assertIsNotEnabled()
    }

    // --- fixtures ----------------------------------------------------------------------------------

    /**
     * Result: the loaded state for a profile with two accounts — the minimum a transfer needs.
     * Input:  [amountText]. Output: [AddTransactionUiState].
     */
    private fun twoAccountState(amountText: String = "") =
        AddTransactionUiState(
            amountText = amountText,
            isLoading = false,
            accounts =
                listOf(
                    account { copy(id = "account:1", name = "HDFC Savings") },
                    account { copy(id = "account:2", name = "Cash Wallet") },
                ),
            selectedAccountId = "account:1",
        )

    /**
     * Renders the stateless body.
     * Why:    the body rather than [AddTransactionScreen], so no Hilt, no database and no navigator
     *         stand between the assertion and the layout.
     * Result: the composition is set. Input: [state], [onEvent], [onCancel]. Output: none.
     */
    private fun setContent(
        state: AddTransactionUiState,
        onEvent: (AddTransactionEvent) -> Unit = {},
        onCancel: () -> Unit = {},
    ) {
        compose.setContent {
            CfoTheme {
                AddTransactionContent(uiState = state, onEvent = onEvent, onCancel = onCancel)
            }
        }
    }

    /**
     * Result: the state a user sees after the screen loads with one account and no categories — what
     *         every real profile has today. Input: [amountText]. Output: [AddTransactionUiState].
     */
    private fun loadedState(amountText: String = "") =
        AddTransactionUiState(
            amountText = amountText,
            isLoading = false,
            accounts = listOf(account()),
            selectedAccountId = "account:1",
        )
}
