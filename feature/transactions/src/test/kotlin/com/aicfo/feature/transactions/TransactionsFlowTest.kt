package com.aicfo.feature.transactions

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.Money
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the recent-transactions list (issue 3.1; §21.5).
 *
 * Why:  the ViewModel tests prove the grouping and the totals; these prove the screen renders them.
 *       Two things only a rendered test catches: **the amount reaching the screen formatted for
 *       India** — ₹1,23,456.78, not ₹123,456.78 (P-06) — and **the day header being a readable date
 *       rather than the ISO string it is stored as** (TIM-002). Both would pass every state test.
 * What: a populated list, the three empty-ish states, and the row's title fallback.
 * Result: the screen that makes a save observable is exercised on every `test` run.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h1200dp")
class TransactionsFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Result: a string from this module's resources. Input: [id]. Output: the resolved text. */
    private fun text(id: Int): String = compose.activity.getString(id)

    @Test
    fun `a transaction renders with its note and an Indian-grouped amount`() {
        // Two rows, so the day's total differs from either amount — with one row the header would
        // render the same figure and the assertion below could not tell them apart.
        setContent(
            TransactionsUiState(
                isLoading = false,
                days =
                    listOf(
                        day(
                            transaction { copy(id = "txn:1", note = "Chai", amount = Money(-1_23_456_78L)) },
                            transaction { copy(id = "txn:2", note = "Salary", amount = Money(60_000_00L)) },
                        ),
                    ),
            ),
        )

        compose.onNodeWithText("Chai").assertIsDisplayed()
        // P-06: 2,2,3 grouping, which `NumberFormat` does not give reliably — hence MoneyFormatter.
        compose.onNodeWithText("₹1,23,456.78", substring = true).assertIsDisplayed()
        // Positive amounts carry a leading + so the direction is readable without reading the colour.
        compose.onNodeWithText("+₹60,000.00", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the day header is a readable date, not the stored ISO string`() {
        setContent(TransactionsUiState(isLoading = false, days = listOf(day(transaction()))))

        compose.onNodeWithText("2026-08-02").assertDoesNotExist()
        compose.onNodeWithText(TransactionLabels.dayHeader("2026-08-02")).assertIsDisplayed()
    }

    @Test
    fun `a row with neither note nor merchant still says something`() {
        // Every field but the amount is optional (FR-TXN-001), and a row a user cannot identify is
        // a row they cannot decide to delete.
        setContent(TransactionsUiState(isLoading = false, days = listOf(day(transaction()))))

        compose.onNodeWithText(text(R.string.transactions_uncategorised)).assertIsDisplayed()
    }

    @Test
    fun `an empty store invites the user to the FAB`() {
        setContent(TransactionsUiState(isLoading = false))

        compose.onNodeWithText(text(R.string.transactions_empty)).assertIsDisplayed()
    }

    @Test
    fun `a failed read shows an error rather than the empty invitation`() {
        // The distinction: a database that would not open must not render as "no transactions yet".
        setContent(TransactionsUiState(isLoading = false, errorCode = "storage"))

        compose.onNodeWithText(text(R.string.add_txn_error_storage)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_empty)).assertDoesNotExist()
    }

    @Test
    fun `nothing is rendered as empty while it is still loading`() {
        setContent(TransactionsUiState(isLoading = true))

        compose.onNodeWithText(text(R.string.transactions_empty)).assertDoesNotExist()
    }

    /** Result: the composition is set. Input: [state]. Output: none. */
    private fun setContent(state: TransactionsUiState) {
        compose.setContent { CfoTheme { TransactionsContent(uiState = state) } }
    }

    /** Result: one day holding [transactions]. Input: [transactions]. Output: [TransactionDay]. */
    private fun day(vararg transactions: com.aicfo.core.model.Transaction) =
        TransactionDay(isoDate = "2026-08-02", transactions = transactions.toList())
}
