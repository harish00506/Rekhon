package com.aicfo.feature.transactions

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
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

    // --- transfers and delete (issue 3.2; FR-TXN-003) ----------------------------------------------

    @Test
    fun `a transfer renders as one row naming both accounts`() {
        // FR-TXN-003's "single logical record", as pixels: one line, not two, and the two account
        // names carry the direction because a collapsed pair has no single sign to colour.
        setContent(
            TransactionsUiState(
                isLoading = false,
                days = listOf(TransactionDay("2026-08-02", listOf(transferRow()))),
                accountNames = mapOf("account:1" to "HDFC Savings", "account:2" to "Cash Wallet"),
            ),
        )

        compose.onNodeWithText("HDFC Savings", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cash Wallet", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a transfer's amount carries no plus sign`() {
        // A leading "+" would claim the user gained money they only moved between their own accounts.
        setContent(
            TransactionsUiState(
                isLoading = false,
                days = listOf(TransactionDay("2026-08-02", listOf(transferRow()))),
                accountNames = mapOf("account:1" to "HDFC Savings", "account:2" to "Cash Wallet"),
            ),
        )

        compose.onNodeWithText("₹5,000.00", substring = true).assertIsDisplayed()
        compose.onNodeWithText("+₹5,000.00", substring = true).assertDoesNotExist()
    }

    @Test
    fun `an account id with no name falls back to the id rather than an empty arrow`() {
        setContent(
            TransactionsUiState(
                isLoading = false,
                days = listOf(TransactionDay("2026-08-02", listOf(transferRow()))),
            ),
        )

        compose.onNodeWithText("account:1", substring = true).assertIsDisplayed()
    }

    @Test
    fun `tapping delete on a transfer hands up one of its leg ids`() {
        val events = mutableListOf<TransactionsEvent>()
        setContent(
            state =
                TransactionsUiState(
                    isLoading = false,
                    days = listOf(TransactionDay("2026-08-02", listOf(transferRow()))),
                ),
            onEvent = { events += it },
        )

        compose.onNodeWithContentDescription(text(R.string.transactions_delete)).performClick()

        // A transaction id, not the transfer id: the repository decides whether a sibling goes too.
        assertEquals(listOf(TransactionsEvent.Delete("tfr:1:out")), events)
    }

    @Test
    fun `an ordinary row has a delete action too`() {
        val events = mutableListOf<TransactionsEvent>()
        setContent(
            state = TransactionsUiState(isLoading = false, days = listOf(day(transaction()))),
            onEvent = { events += it },
        )

        compose.onNodeWithContentDescription(text(R.string.transactions_delete)).performClick()

        assertEquals(listOf(TransactionsEvent.Delete("txn:1")), events)
    }

    // --- splits (issue 3.3; FR-TXN-004) ------------------------------------------------------------

    @Test
    fun `a split row says how many lines it has, and still shows one amount`() {
        // The parent holds all the money, so listing the lines here would show it twice. The row
        // says how many categories the amount is spread across instead.
        // A second row so the day total differs from the split's amount — with one row the header
        // would render the same figure and the assertion could not tell them apart.
        setContent(
            TransactionsUiState(
                isLoading = false,
                days = listOf(day(splitTransaction(lines = 2), transaction())),
            ),
        )

        compose.onNodeWithText("2 lines", substring = true).assertIsDisplayed()
        // Once, not twice: splitting does not change what the transaction is worth.
        compose.onNodeWithText("-₹1,000.00", substring = true).assertIsDisplayed()
    }

    @Test
    fun `an unsplit row says nothing about lines`() {
        setContent(TransactionsUiState(isLoading = false, days = listOf(day(transaction()))))

        compose.onNodeWithText("line", substring = true).assertDoesNotExist()
    }

    // --- future-dated transactions (issue 3.4; FR-TXN-010) -----------------------------------------

    @Test
    fun `a scheduled row is labelled, so no user has to guess whether the money has gone`() {
        setContent(
            TransactionsUiState(
                isLoading = false,
                days = listOf(day(transaction { copy(note = "Chai") })),
                upcoming = listOf(scheduledDay()),
            ),
        )

        compose.onNodeWithText(text(R.string.transactions_scheduled)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_posted)).assertIsDisplayed()
        compose.onNodeWithText("Rent", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a scheduled day carries no total`() {
        // A day total is a statement about money that has moved. Printing one over rows that have
        // not moved would invite exactly the reading FR-TXN-010 exists to prevent, and it would
        // reconcile with no balance on any other screen.
        setContent(TransactionsUiState(isLoading = false, upcoming = listOf(scheduledDay())))

        compose.onNodeWithContentDescription(text(R.string.transactions_day_total)).assertDoesNotExist()
        // The row's own amount is still shown — it is the aggregate that is withheld, not the figure.
        compose.onNodeWithText("-₹25,000.00", substring = true).assertIsDisplayed()
    }

    @Test
    fun `neither section heading appears when nothing is scheduled`() {
        // The common case: most users schedule nothing, and a "Posted" heading over the only list on
        // screen would be noise that says nothing.
        setContent(TransactionsUiState(isLoading = false, days = listOf(day(transaction()))))

        compose.onNodeWithText(text(R.string.transactions_scheduled)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.transactions_posted)).assertDoesNotExist()
    }

    @Test
    fun `a scheduled row can still be deleted`() {
        // A payment scheduled by mistake must be removable before it ever counts, without waiting
        // for its date to arrive.
        val events = mutableListOf<TransactionsEvent>()
        setContent(
            state = TransactionsUiState(isLoading = false, upcoming = listOf(scheduledDay())),
            onEvent = { events += it },
        )

        compose.onNodeWithContentDescription(text(R.string.transactions_delete)).performClick()

        assertEquals(listOf(TransactionsEvent.Delete("txn:rent")), events)
    }

    /** Result: one future day holding a ₹25,000 rent payment. Input: none. Output: a day. */
    private fun scheduledDay() =
        TransactionDay(
            isoDate = "2026-08-10",
            rows =
                listOf(
                    TransactionRow.Single(
                        transaction {
                            copy(
                                id = "txn:rent",
                                note = "Rent",
                                amount = Money(-25_000_00L),
                                bookedOn = "2026-08-10",
                            )
                        },
                    ),
                ),
        )

    /** Result: a ₹1,000 expense split across [lines] lines. Input: [lines]. Output: [Transaction]. */
    private fun splitTransaction(lines: Int) =
        transaction {
            copy(
                id = "txn:split",
                amount = Money(-1_000_00L),
                splits =
                    Money(-1_000_00L).split(lines).mapIndexed { index, share ->
                        com.aicfo.core.model.TransactionSplit(
                            id = "spl:$index",
                            transactionId = "txn:split",
                            amount = share,
                        )
                    },
            )
        }

    /** Result: the composition is set. Input: [state], [onEvent]. Output: none. */
    private fun setContent(
        state: TransactionsUiState,
        onEvent: (TransactionsEvent) -> Unit = {},
    ) {
        compose.setContent { CfoTheme { TransactionsContent(uiState = state, onEvent = onEvent) } }
    }

    /** Result: one day holding [transactions] as ordinary rows. Input: [transactions]. Output: a day. */
    private fun day(vararg transactions: com.aicfo.core.model.Transaction) =
        TransactionDay(
            isoDate = "2026-08-02",
            rows = transactions.map { TransactionRow.Single(it) },
        )

    /** Result: a collapsed ₹5,000 transfer row. Input: none. Output: [TransactionRow.TransferPair]. */
    private fun transferRow() =
        TransactionRow.TransferPair(
            transferId = "tfr:1",
            id = "tfr:1:out",
            outAccountId = "account:1",
            inAccountId = "account:2",
            amount = Money(5_000_00L),
        )
}
